package com.bluecode.coordinator;

import com.bluecode.config.AppConfig;
import com.bluecode.team.Feature;

import java.util.List;

public final class Coordinator {
    private Coordinator() {
    }

    public static boolean isEnabled(AppConfig config) {
        return config != null
                && config.getFeatures().coordinatorMode()
                && Feature.envTruthy(System.getenv("MEWCODE_COORDINATOR_MODE"));
    }

    public static List<String> allowedTools() {
        return List.of(
                "Agent",
                "TeamCreate",
                "TeamDelete",
                "TaskCreate",
                "TaskGet",
                "TaskList",
                "TaskUpdate",
                "SendMessage",
                "ReadFile",
                "Glob",
                "Grep",
                "Bash");
    }

    public static String systemPromptSuffix() {
        return """

                <coordinator-mode>
                你是 Team Lead，只负责调度、汇总、验证和收敛。阶段顺序为 Research -> Synthesis -> Implementation -> Verification。
                派出队员或发送消息后，停止自行探索，等待队员汇报；不要立刻调用 read_file/glob/grep/bash 代替队员工作。
                读类工具仅在首次定位、读取队员产出、最终验证时使用。禁止用 sleep 或反复 TaskList 凑时间。
                收敛阶段可以用 Bash 执行 git status、git diff、git merge；冲突无法可靠解决时保留现场并向用户汇报。
                </coordinator-mode>
                """;
    }
}
