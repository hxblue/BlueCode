package com.bluecode.tui;

import com.bluecode.agent.CompactEvent;
import com.bluecode.agent.CompactPhase;

public final class Commands {
    private Commands() {
    }

    public static String formatCompactNotice(CompactEvent event) {
        return switch (event.phase()) {
            case BEFORE_AUTO -> "正在压缩上下文...";
            case BEFORE_EMERGENCY -> "上下文撞墙,自动压缩中...";
            case AFTER_AUTO, AFTER_EMERGENCY -> event.error() == null
                    ? "已压缩,token 从 %d 降至 %d".formatted(event.before(), event.after())
                    : "压缩失败:" + event.error().getMessage();
        };
    }

    static CompactEvent manualResultEvent(long before, long after, Throwable error) {
        return new CompactEvent(CompactPhase.AFTER_AUTO, before, after, error);
    }
}
