package com.bluecode.compact;

public final class CompactConstants {
    // 单条工具结果超过该字节数时落盘。
    public static final int SINGLE_RESULT_LIMIT = 50000;
    // 单轮工具结果聚合超过该字节数时按大到小落盘。
    public static final int MESSAGE_AGGREGATE_LIMIT = 200000;
    // 摘要输出预留的 token 空间。
    public static final int SUMMARY_RESERVE = 20000;
    // 自动压缩额外安全余量。
    public static final int AUTO_SAFETY_MARGIN = 13000;
    // 手动和紧急压缩的请求安全余量。
    public static final int MANUAL_SAFETY_MARGIN = 3000;
    // 恢复段最多展示的最近文件数量。
    public static final int RECOVERY_FILE_LIMIT = 5;
    // 单个文件快照在恢复段里的 token 上限。
    public static final int RECOVERY_TOKENS_PER_FILE = 5000;
    // 摘要后保留近期原文的 token 下界。
    public static final int RECENT_KEEP_TOKENS = 10000;
    // 摘要后保留近期原文的消息条数下界。
    public static final int RECENT_KEEP_MESSAGES = 5;
    // 自动摘要连续失败后的熔断阈值。
    public static final int MAX_CONSECUTIVE_AUTO_COMPACT_FAILURES = 3;
    // 摘要请求自身上下文过长时的直接重试次数。
    public static final int PTL_RETRY_LIMIT = 3;
    // 直接重试后按比例丢弃旧消息组的步长。
    public static final double PTL_DROP_PERCENTAGE = 0.2;
    // 字节到 token 的估算比例。
    public static final double ESTIMATE_CHARS_PER_TOKEN = 3.5;
    // 工具结果替换体头部预览的字节上限。
    public static final int PREVIEW_HEAD_BYTES = 2048;
    // 工具结果替换体头部预览的行数上限。
    public static final int PREVIEW_HEAD_LINES = 20;

    private CompactConstants() {
    }
}
