package com.bluecode.skill;

import com.bluecode.conversation.Message;

import java.util.List;

public interface SkillForkHost extends SkillHost {
    String runSubAgent(String body, List<Message> seed, List<String> allowedTools, String model);

    List<Message> snapshotParentMessages();
}
