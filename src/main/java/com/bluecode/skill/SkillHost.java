package com.bluecode.skill;

import com.bluecode.tool.Registry;

import java.util.function.Predicate;

public interface SkillHost {
    void activateSkill(String name, String body);

    void setToolFilter(Predicate<String> filter);

    Registry toolRegistry();
}
