package com.bluecode.agent;

import com.bluecode.conversation.ConversationManager;
import com.bluecode.tool.ToolContext;

public record TeamSpawnRequest(
        String teamName,
        String memberName,
        String description,
        String prompt,
        String subagentType,
        String model,
        boolean runInBackground,
        Agent parentAgent,
        ConversationManager parentConversation,
        ToolContext toolContext) {
    public TeamSpawnRequest {
        teamName = teamName == null ? "" : teamName;
        memberName = memberName == null ? "" : memberName;
        description = description == null ? "" : description;
        prompt = prompt == null ? "" : prompt;
        subagentType = subagentType == null ? "" : subagentType;
        model = model == null ? "" : model;
        toolContext = toolContext == null ? ToolContext.root() : toolContext;
    }
}
