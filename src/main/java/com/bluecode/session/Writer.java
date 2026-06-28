package com.bluecode.session;

import com.bluecode.conversation.Message;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public final class Writer implements Closeable {
    private static final Logger LOGGER = Logger.getLogger(Writer.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path file;
    private final FileOutputStream stream;
    private final BufferedWriter out;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean first = new AtomicBoolean(true);

    private volatile String model;
    private volatile boolean closed;

    private Writer(Path file, FileOutputStream stream, String model) {
        this.file = file;
        this.stream = stream;
        this.out = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8));
        this.model = model == null ? "" : model;
    }

    public static Writer create(Path sessionDir) throws IOException {
        Files.createDirectories(sessionDir);
        return openFile(sessionDir, null);
    }

    public static Writer open(Path sessionDir) throws IOException {
        if (!Files.isDirectory(sessionDir)) {
            throw new IOException("会话目录不存在: " + sessionDir);
        }
        return openFile(sessionDir, null);
    }

    public Path file() {
        return file;
    }

    public Path path() {
        return file;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model;
    }

    public void onAppend(Message message) {
        try {
            append(message, model, first.getAndSet(false));
        } catch (IOException e) {
            LOGGER.warning("写入会话 JSONL 失败: " + e.getMessage());
        }
    }

    public void onReplace(List<Message> messages) {
        lock.lock();
        try {
            writeEntryNoLock(Entry.compact(now()));
            for (Message message : messages == null ? List.<Message>of() : messages) {
                writeEntryNoLock(Entry.fromMessage(message, now(), null));
            }
            first.set(false);
        } catch (IOException e) {
            LOGGER.warning("写入压缩后的会话 JSONL 失败: " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public void append(Message message, String model, boolean isFirst) throws IOException {
        String firstModel = isFirst ? model : null;
        lock.lock();
        try {
            writeEntryNoLock(Entry.fromMessage(message, now(), firstModel));
        } finally {
            lock.unlock();
        }
    }

    public void writeCompactMarker() throws IOException {
        lock.lock();
        try {
            writeEntryNoLock(Entry.compact(now()));
        } finally {
            lock.unlock();
        }
    }

    public void appendAll(List<Message> messages) throws IOException {
        lock.lock();
        try {
            for (Message message : messages == null ? List.<Message>of() : messages) {
                writeEntryNoLock(Entry.fromMessage(message, now(), null));
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() throws IOException {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            out.close();
        } finally {
            lock.unlock();
        }
    }

    private static Writer openFile(Path sessionDir, String model) throws IOException {
        Path file = sessionDir.resolve("conversation.jsonl");
        boolean hasContent = Files.exists(file) && Files.size(file) > 0;
        FileOutputStream stream = new FileOutputStream(file.toFile(), true);
        Writer writer = new Writer(file, stream, model);
        writer.first.set(!hasContent);
        return writer;
    }

    private void writeEntryNoLock(Entry entry) throws IOException {
        if (closed) {
            throw new IOException("会话写入器已关闭");
        }
        out.write(MAPPER.writeValueAsString(entry));
        out.newLine();
        out.flush();
        stream.getFD().sync();
    }

    private static long now() {
        return Instant.now().getEpochSecond();
    }
}
