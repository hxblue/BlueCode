package com.bluecode.command;

import com.bluecode.permission.Mode;
import com.bluecode.hook.HookRule;

import java.util.List;

public interface Ui {
    void println(String msg);

    void error(String msg);

    Mode mode();

    void setMode(Mode mode);

    void injectAndSend(String displayLabel, String presetPrompt);

    long usageIn();

    long usageOut();

    String modelName();

    String cwd();

    int toolCount();

    List<String> memoryFiles();

    List<HookRule> hookRules();

    List<String> hookSources();

    String sessionPath();

    String sessionId();

    void quit();

    void forceCompact();

    void openResumeMenu();

    void clearAndNewSession();

    boolean idle();

    default String commandArguments() {
        return "";
    }

    default WorktreeAccessor worktreeAccessor() {
        return null;
    }

    default List<String> listCatalogSkills() {
        return List.of();
    }

    default List<String> listActiveSkills() {
        return List.of();
    }

    default void clearActiveSkills() {
    }

    default void appendAssistantMessage(String text) {
    }
}
