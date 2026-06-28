package com.bluecode.command;

import com.bluecode.permission.Mode;
import com.bluecode.hook.HookRule;

import java.util.List;

public final class NopUi implements Ui {
    public static final NopUi INSTANCE = new NopUi();

    private NopUi() {
    }

    @Override
    public void println(String msg) {
    }

    @Override
    public void error(String msg) {
    }

    @Override
    public Mode mode() {
        return Mode.DEFAULT;
    }

    @Override
    public void setMode(Mode mode) {
    }

    @Override
    public void injectAndSend(String displayLabel, String presetPrompt) {
    }

    @Override
    public long usageIn() {
        return 0;
    }

    @Override
    public long usageOut() {
        return 0;
    }

    @Override
    public String modelName() {
        return "";
    }

    @Override
    public String cwd() {
        return "";
    }

    @Override
    public int toolCount() {
        return 0;
    }

    @Override
    public List<String> memoryFiles() {
        return List.of();
    }

    @Override
    public List<HookRule> hookRules() {
        return List.of();
    }

    @Override
    public List<String> hookSources() {
        return List.of();
    }

    @Override
    public String sessionPath() {
        return "";
    }

    @Override
    public String sessionId() {
        return "";
    }

    @Override
    public void quit() {
    }

    @Override
    public void forceCompact() {
    }

    @Override
    public void openResumeMenu() {
    }

    @Override
    public void clearAndNewSession() {
    }

    @Override
    public boolean idle() {
        return true;
    }
}
