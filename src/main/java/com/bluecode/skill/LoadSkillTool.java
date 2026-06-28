package com.bluecode.skill;

import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LoadSkillTool implements Tool {
    private final SkillCatalog catalog;
    private final ActiveSkills activeSkills;

    public LoadSkillTool(SkillCatalog catalog, ActiveSkills activeSkills) {
        this.catalog = catalog;
        this.activeSkills = activeSkills;
    }

    @Override
    public String name() {
        return "LoadSkill";
    }

    @Override
    public String description() {
        return "按名称加载 BlueCode skill，将完整 SOP 固定到下一轮环境上下文。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> name = new LinkedHashMap<>();
        name.put("type", "string");
        name.put("description", "要加载的 skill 名称。");
        name.put("example", "review");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("name", name);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("name"));
        return schema;
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public boolean isSystem() {
        return true;
    }

    @Override
    public Result execute(Map<String, Object> args) {
        Object value = args == null ? null : args.get("name");
        if (!(value instanceof String name) || name.isBlank()) {
            return Result.error("LoadSkill 需要 name");
        }
        if (catalog == null || activeSkills == null) {
            return Result.error("Skill catalog 尚未初始化");
        }
        return catalog.getFull(name)
                .map(skill -> {
                    activeSkills.activate(skill.name(), skill.promptBody());
                    return Result.ok("Skill " + skill.name() + " activated. SOP pinned to env context.");
                })
                .orElseGet(() -> Result.error("unknown skill: " + name));
    }
}
