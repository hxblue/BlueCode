package com.bluecode.prompt;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class PromptBuilder {
    private PromptBuilder() {
    }

    public static List<Module> fixedModules() {
        return Modules.fixedModules();
    }

    public static List<Module> optionalModules() {
        return Modules.optionalModules();
    }

    public static List<Module> optionalModules(String instructions, String memory) {
        return Modules.optionalModules(instructions, memory);
    }

    public static List<Module> optionalModules(String instructions, String memory, String skillsCatalog) {
        return Modules.optionalModules(instructions, memory, skillsCatalog);
    }

    public static String assembleSystem(List<Module> modules) {
        if (modules == null || modules.isEmpty()) {
            return "";
        }
        return modules.stream()
                .filter(module -> module != null && !module.content().isBlank())
                .sorted(Comparator.comparingInt(Module::priority))
                .map(Module::content)
                .reduce((left, right) -> left + "\n\n" + right)
                .orElse("");
    }

    public static String buildSystemPrompt() {
        return assembleSystem(Stream.concat(fixedModules().stream(), optionalModules().stream()).toList());
    }

    public static String buildSystemPrompt(String instructions, String memory) {
        return assembleSystem(Stream.concat(fixedModules().stream(), optionalModules(instructions, memory).stream()).toList());
    }

    public static String buildSystemPrompt(String instructions, String memory, String skillsCatalog) {
        return assembleSystem(Stream.concat(
                fixedModules().stream(),
                optionalModules(instructions, memory, skillsCatalog).stream()).toList());
    }

    public static String buildSystemPrompt(String workingDirectory) {
        return buildSystemPrompt();
    }

    public static String currentWorkingDirectory() {
        return Path.of("").toAbsolutePath().normalize().toString();
    }
}
