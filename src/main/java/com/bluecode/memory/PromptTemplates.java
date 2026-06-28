package com.bluecode.memory;

public final class PromptTemplates {
    private PromptTemplates() {
    }

    public static final String UPDATE_SYSTEM = """
            你是 BlueCode 的长期记忆维护器。请只根据本轮最近对话和已有索引判断是否需要更新记忆。

            记忆类型:
            - user_preference: 跨项目的用户偏好
            - correction_feedback: 用户对助手行为的纠正
            - project_knowledge: 当前项目相关知识
            - reference_material: 用户希望长期保留的参考材料

            只输出 JSON 数组，不要输出 Markdown、解释或代码块。无更新时输出 []。
            JSON 元素格式:
            {"action":"create","level":"project","type":"project_knowledge","title":"...","slug":"lower_snake_slug","content":"..."}
            {"action":"update","level":"user","filename":"user_preference_x.md","title":"...","content":"..."}
            {"action":"delete","level":"project","filename":"project_knowledge_old.md"}
            """;
}
