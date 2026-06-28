package com.bluecode.subagent;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BuiltinLoader {
    private static final List<String> BUILTIN_FILES = List.of(
            "general-purpose.md",
            "explore.md",
            "plan.md");

    private BuiltinLoader() {
    }

    public static List<Definition> builtinDefinitions() {
        List<Definition> result = new ArrayList<>();
        for (String file : BUILTIN_FILES) {
            String resource = "/subagent/builtin/" + file;
            try (InputStream in = BuiltinLoader.class.getResourceAsStream(resource)) {
                if (in == null) {
                    throw new RuntimeException("builtin agent missing: " + file);
                }
                byte[] bytes = in.readAllBytes();
                result.add(Parser.parseDefinition(bytes, "classpath:subagent/builtin/" + file,
                        Definition.Source.BUILTIN));
            } catch (Exception e) {
                throw new RuntimeException("builtin agent parse failed: " + file + ": " + e.getMessage(), e);
            }
        }
        result.sort(Comparator.comparing(Definition::name));
        return List.copyOf(result);
    }
}
