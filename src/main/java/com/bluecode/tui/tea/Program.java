package com.bluecode.tui.tea;

import org.jline.terminal.Size;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.terminal.Attributes;
import org.jline.utils.InfoCmp;
import org.jline.utils.NonBlockingReader;
import org.jline.utils.WCWidth;

import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class Program {
    private final BlockingQueue<Message> inbox = new LinkedBlockingQueue<>();
    private Model model;
    private Terminal terminal;
    private volatile boolean running;
    private int linesRendered;
    private int cursorUpFromAfterView;
    private int width = 100;
    private int height = 30;

    public Program(Model model) {
        this.model = model;
    }

    private Attributes originalAttributes;

    public void run() {
        boolean restored = false;
        try (Terminal created = TerminalBuilder.builder().system(true).build()) {
            terminal = created;
            try {
            if (System.console() == null && "dumb".equalsIgnoreCase(terminal.getType())) {
                throw new IllegalStateException("BlueCode TUI 需要交互式终端；自动化调用请使用 -p \"你的问题\"");
            }
            width = Math.max(40, terminal.getWidth());
            height = Math.max(12, terminal.getHeight());
            originalAttributes = terminal.getAttributes();
            terminal.enterRawMode();
            disableEcho();
            running = true;
            execute(model.init());
            send(new WindowSizeMessage(width, height));
            startInputReader();
            renderView();

            while (running) {
                Message message = inbox.poll(80, TimeUnit.MILLISECONDS);
                if (message == null) {
                    continue;
                }
                if (message instanceof QuitMessage) {
                    running = false;
                    break;
                }
                UpdateResult<? extends Model> result = model.update(message);
                model = result.model();
                execute(result.command());
                renderView();
            }
            } finally {
                running = false;
                restoreTerminal();
                restored = true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            throw new IllegalStateException("启动终端失败：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("启动失败: " + e.getMessage(), e);
        } finally {
            running = false;
            if (!restored) {
                restoreTerminal();
            }
            if (model != null) {
                try {
                    String dump = model.dumpHistory();
                    if (dump != null && !dump.isBlank()) {
                        System.out.println(dump);
                    }
                } catch (Exception ignored) {
                    // dump 失败不影响退出。
                }
            }
        }
    }

    private void restoreTerminal() {
        if (terminal == null) {
            return;
        }
        if (originalAttributes == null && linesRendered <= 0) {
            return;
        }
        if (originalAttributes != null) {
            try {
                terminal.setAttributes(originalAttributes);
            } catch (Exception ignored) {
                // 恢复失败时不影响退出。
            }
        }
        try {
            terminal.puts(InfoCmp.Capability.exit_ca_mode);
        } catch (Exception ignored) {
        }
        try {
            terminal.writer().print("[0m[2J[H");
            terminal.writer().flush();
        } catch (Exception ignored) {
        }
    }

    public void send(Message msg) {
        if (msg != null) {
            inbox.offer(msg);
        }
    }

    public int getAvailableHeight() {
        return Math.max(6, height - 8);
    }

    private void startInputReader() {
        Thread.startVirtualThread(() -> {
            NonBlockingReader reader = terminal.reader();
            while (running) {
                try {
                    int ch = reader.read(100);
                    if (ch < 0) {
                        continue;
                    }
                    send(parseKey(ch, reader));
                } catch (IOException e) {
                    send(new QuitMessage());
                    return;
                }
            }
        });
    }

    private Message parseKey(int ch, NonBlockingReader reader) throws IOException {
        if (ch == 3) {
            return new KeyPressMessage("ctrl+c", new char[0]);
        }
        if (ch == 13) {
            return new KeyPressMessage("enter", new char[]{'\n'});
        }
        if (ch == 10) {
            return new KeyPressMessage("alt+enter", new char[]{'\n'});
        }
        if (ch == 127 || ch == 8) {
            return new KeyPressMessage("backspace", new char[0]);
        }
        if (ch == 9) {
            return new KeyPressMessage("tab", new char[0]);
        }
        if (ch == 27) {
            int next = reader.read(20);
            if (next == '[') {
                int code = reader.read(20);
                return switch (code) {
                    case 'A' -> new KeyPressMessage("up", new char[0]);
                    case 'B' -> new KeyPressMessage("down", new char[0]);
                    case 'C' -> new KeyPressMessage("right", new char[0]);
                    case 'D' -> new KeyPressMessage("left", new char[0]);
                    case 'Z' -> new KeyPressMessage("shift+tab", new char[0]);
                    default -> new KeyPressMessage("escape", new char[0]);
                };
            }
            if (next == 13 || next == 10) {
                return new KeyPressMessage("alt+enter", new char[]{'\n'});
            }
            return new KeyPressMessage("escape", new char[0]);
        }
        if (ch >= 32) {
            return new KeyPressMessage("text", Character.toChars(ch));
        }
        return new KeyPressMessage("unknown", new char[0]);
    }

    private void execute(Command command) {
        if (command == null || command instanceof Command.None) {
            return;
        }
        switch (command) {
            case Command.Batch batch -> batch.commands().forEach(this::execute);
            case Command.CheckWindowSize ignored -> {
                if (terminal != null) {
                    Size size = terminal.getSize();
                    width = Math.max(40, size.getColumns());
                    height = Math.max(12, size.getRows());
                }
                send(new WindowSizeMessage(width, height));
            }
            case Command.Tick tick -> Thread.startVirtualThread(() -> {
                try {
                    Thread.sleep(tick.delay());
                    send(tick.fn().apply(Instant.now()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            case Command.PrintLine printLine -> {
                clearView();
                writer().println(printLine.text());
                writer().flush();
                linesRendered = 0;
            }
            case Command.Quit ignored -> send(new QuitMessage());
            case Command.None ignored -> {
            }
        }
    }

    private void renderView() {
        if (terminal == null || model == null) {
            return;
        }
        clearView();
        String view = model.view();
        writer().print(view);
        if (!view.endsWith("\n")) {
            writer().println();
        }
        writer().flush();
        linesRendered = countRenderedLines(view);
        placeCursor();
    }

    private void clearView() {
        if (terminal == null || linesRendered <= 0) {
            return;
        }
        PrintWriter writer = writer();
        if (cursorUpFromAfterView > 0) {
            writer.print("\u001B[" + cursorUpFromAfterView + "B");
        }
        writer.print("\r\u001B[" + linesRendered + "A");
        writer.print("\u001B[J");
        writer.flush();
        linesRendered = 0;
        cursorUpFromAfterView = 0;
    }

    private void placeCursor() {
        CursorPlacement placement = model.cursorPlacement();
        cursorUpFromAfterView = Math.max(0, placement.upFromAfterView());
        int column = Math.max(1, placement.column());
        PrintWriter writer = writer();
        if (cursorUpFromAfterView > 0) {
            writer.print("\u001B[" + cursorUpFromAfterView + "A");
        }
        writer.print("\r");
        if (column > 1) {
            writer.print("\u001B[" + (column - 1) + "C");
        }
        writer.flush();
    }

    private void disableEcho() {
        Attributes attributes = terminal.getAttributes();
        attributes.setLocalFlag(Attributes.LocalFlag.ECHO, false);
        attributes.setLocalFlag(Attributes.LocalFlag.ICANON, false);
        terminal.setAttributes(attributes);
    }

    private int countRenderedLines(String text) {
        if (text == null || text.isEmpty()) {
            return 1;
        }
        String normalized = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        String[] logicalLines = normalized.split("\n", -1);
        int terminalWidth = Math.max(1, width);
        int rendered = 0;
        for (String line : logicalLines) {
            int visibleLength = displayWidth(stripAnsi(line));
            rendered += Math.max(1, (visibleLength + terminalWidth - 1) / terminalWidth);
        }
        return Math.max(1, rendered);
    }

    private String stripAnsi(String text) {
        return text.replaceAll("\u001B\\[[;\\d]*m", "");
    }

    private int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            int charWidth = WCWidth.wcwidth(codePoint);
            width += Math.max(0, charWidth);
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private PrintWriter writer() {
        return terminal == null ? new PrintWriter(System.out, true) : terminal.writer();
    }
}
