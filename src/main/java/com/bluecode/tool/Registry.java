package com.bluecode.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class Registry {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> ARG_TYPE = new TypeReference<>() {
    };

    private final List<String> order = new ArrayList<>();
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("tool 不能为空");
        }
        if (tool.name() == null || tool.name().isBlank()) {
            throw new IllegalArgumentException("tool name 不能为空");
        }
        if (tools.containsKey(tool.name())) {
            throw new IllegalArgumentException("重复注册工具: " + tool.name());
        }
        order.add(tool.name());
        tools.put(tool.name(), tool);
    }

    public Optional<Tool> get(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<String> names() {
        return List.copyOf(order);
    }

    public Registry filtered(List<String> allowedNames) {
        Registry filtered = new Registry();
        if (allowedNames == null || allowedNames.isEmpty()) {
            return filtered;
        }
        Set<String> allowSet = new HashSet<>(allowedNames);
        for (String name : order) {
            Tool tool = tools.get(name);
            if (tool != null && allowSet.contains(name)) {
                filtered.register(tool);
            }
        }
        return filtered;
    }

    public List<Map<String, Object>> definitions() {
        return definitions(false);
    }

    public List<Map<String, Object>> readOnlyDefinitions() {
        return definitions(true);
    }

    public List<Map<String, Object>> systemDefinitions() {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (String name : order) {
            Tool tool = tools.get(name);
            if (tool != null && tool.isSystem()) {
                definitions.add(definition(tool));
            }
        }
        return List.copyOf(definitions);
    }

    public List<Map<String, Object>> definitionsFiltered(List<String> allowed) {
        if (allowed == null || allowed.isEmpty()) {
            return definitions();
        }
        Set<String> allowSet = new HashSet<>(allowed);
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (String name : order) {
            Tool tool = tools.get(name);
            if (tool != null && (tool.isSystem() || allowSet.contains(name))) {
                definitions.add(definition(tool));
            }
        }
        return List.copyOf(definitions);
    }

    public List<Map<String, Object>> getAllSchemas(String protocol) {
        return definitions();
    }

    public boolean isReadOnly(String name) {
        Tool tool = tools.get(name);
        return tool != null && tool.readOnly();
    }

    public int count() {
        return tools.size();
    }

    public Result execute(String name, String arguments) {
        return execute(ToolContext.root(), name, arguments);
    }

    public Result execute(ToolContext ctx, String name, String arguments) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return Result.error("未知工具: " + name);
        }
        try {
            Map<String, Object> args = parseArguments(arguments);
            return tool.execute(ctx == null ? ToolContext.root() : ctx, args);
        } catch (Exception e) {
            return Result.error("工具 " + name + " 执行失败: " + e.getMessage());
        }
    }

    public static Registry createDefault() {
        Registry registry = new Registry();
        registry.register(new ReadFileTool());
        registry.register(new WriteFileTool());
        registry.register(new EditFileTool());
        registry.register(new BashTool());
        registry.register(new GlobTool());
        registry.register(new GrepTool());
        return registry;
    }

    private List<Map<String, Object>> definitions(boolean readOnlyOnly) {
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (String name : order) {
            Tool tool = tools.get(name);
            if (readOnlyOnly && !tool.readOnly()) {
                continue;
            }
            definitions.add(definition(tool));
        }
        return List.copyOf(definitions);
    }

    private Map<String, Object> definition(Tool tool) {
        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("name", tool.name());
        definition.put("description", tool.description());
        definition.put("input_schema", tool.schema());
        return definition;
    }

    private Map<String, Object> parseArguments(String arguments) throws Exception {
        String json = arguments == null || arguments.isBlank() ? "{}" : arguments;
        return MAPPER.readValue(json, ARG_TYPE);
    }
}
