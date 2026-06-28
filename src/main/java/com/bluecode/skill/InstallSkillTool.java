package com.bluecode.skill;

import com.bluecode.tool.Result;
import com.bluecode.tool.Tool;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InstallSkillTool implements Tool {
    private final SkillCatalog catalog;
    private final Path workDir;
    private final Path installRoot;
    private final Runnable onInstalled;

    public InstallSkillTool(SkillCatalog catalog, Path workDir, Runnable onInstalled) {
        this(catalog, workDir, Path.of(System.getProperty("user.home", ".")).resolve(".bluecode").resolve("skills"),
                onInstalled);
    }

    public InstallSkillTool(SkillCatalog catalog, Path workDir, Path installRoot, Runnable onInstalled) {
        this.catalog = catalog;
        this.workDir = workDir == null ? Path.of("").toAbsolutePath().normalize() : workDir.toAbsolutePath().normalize();
        this.installRoot = installRoot == null
                ? Path.of(System.getProperty("user.home", ".")).resolve(".bluecode").resolve("skills")
                : installRoot.toAbsolutePath().normalize();
        this.onInstalled = onInstalled;
    }

    @Override
    public String name() {
        return "InstallSkill";
    }

    @Override
    public String description() {
        return "从 GitHub/raw/skills.sh URL 安装 BlueCode skill 到用户级 ~/.bluecode/skills。";
    }

    @Override
    public Map<String, Object> schema() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "string");
        source.put("description", "skill 来源 URL，支持 github.com tree、raw.githubusercontent.com 或 skills.sh。");
        source.put("example", "https://github.com/user/repo/tree/main/skills/demo");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("source", source);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("source"));
        return schema;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Result execute(Map<String, Object> args) {
        Object value = args == null ? null : args.getOrDefault("source", args.get("url"));
        if (!(value instanceof String source) || source.isBlank()) {
            return Result.error("InstallSkill 需要 source URL");
        }
        try {
            InstallReport report = SkillInstaller.install(SkillInstaller.parseSkillURL(source), installRoot);
            if (catalog != null) {
                catalog.reload(workDir);
            }
            if (onInstalled != null) {
                onInstalled.run();
            }
            return Result.ok("Installed skill " + report.skillName() + " to " + report.destination());
        } catch (Exception e) {
            return Result.error("InstallSkill failed: " + e.getMessage());
        }
    }
}
