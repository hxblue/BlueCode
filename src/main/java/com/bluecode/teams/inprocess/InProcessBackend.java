package com.bluecode.teams.inprocess;

import com.bluecode.agent.Agent;
import com.bluecode.conversation.ConversationManager;
import com.bluecode.team.BackendType;
import com.bluecode.teams.Backend;
import com.bluecode.teams.BackendFactory;
import com.bluecode.teams.SpawnRequest;
import com.bluecode.teams.SpawnResult;

import java.io.IOException;

public final class InProcessBackend implements Backend {
    private final BackendFactory.Deps deps;

    public InProcessBackend(BackendFactory.Deps deps) {
        this.deps = deps == null ? BackendFactory.Deps.empty() : deps;
    }

    @Override
    public BackendType type() {
        return BackendType.IN_PROCESS;
    }

    @Override
    public SpawnResult spawn(SpawnRequest request) throws IOException {
        com.bluecode.task.Manager manager = manager(request);
        if (request.subAgent() instanceof Agent agent && request.conv() instanceof ConversationManager conv && manager != null) {
            String id = manager.launchWithId(request.agentId(), agent, conv, request.memberName(), request.initialPrompt());
            return new SpawnResult("", id);
        }
        return new SpawnResult("", request.agentId());
    }

    @Override
    public void wake(String paneId, String agentId) {
        // 同进程后端由后台任务自然轮转，无需外部唤醒。
    }

    @Override
    public void kill(String paneId, String agentId) {
        com.bluecode.task.Manager manager = deps.taskManager();
        if (manager != null && agentId != null && !agentId.isBlank()) {
            manager.stop(agentId);
        }
    }

    private com.bluecode.task.Manager manager(SpawnRequest request) {
        if (request.taskManager() instanceof com.bluecode.task.Manager manager) {
            return manager;
        }
        return deps.taskManager();
    }
}
