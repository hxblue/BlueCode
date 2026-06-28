package com.bluecode.permission;

import com.bluecode.llm.ToolCall;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class Persister {
    private Persister() {
    }

    static void persistLocalAllow(Path root, Path localPath, RuleSet local, ToolCall call) throws IOException {
        Optional<String> ruleText = ruleFor(root, call);
        if (ruleText.isEmpty()) {
            return;
        }

        Settings existing = Settings.loadSettings(localPath);
        List<String> allow = new ArrayList<>(existing.allow());
        if (!allow.contains(ruleText.get())) {
            allow.add(ruleText.get());
        }
        writeSettings(localPath, existing.defaultMode(), allow, existing.deny());
        Rule.parse(ruleText.get(), true).ifPresent(local::addAllow);
    }

    static Optional<String> ruleFor(Path root, ToolCall call) {
        Settings.TargetInfo targetInfo = Settings.extractTarget(call);
        if (!targetInfo.ok()) {
            return Optional.empty();
        }
        String friendly = Settings.friendlyName(call.name());
        String target = targetInfo.isFile() ? relative(root, targetInfo.target()) : targetInfo.target();
        if (target == null || target.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(friendly + "(" + escapeGlob(target) + ")");
    }

    private static String relative(Path root, String target) {
        try {
            Path raw = Path.of(target);
            Path absolute = raw.isAbsolute() ? raw : root.resolve(raw);
            Path resolved = Sandbox.evalSymlinksOrAncestor(absolute);
            Path relative = root.relativize(resolved.toAbsolutePath().normalize());
            String text = relative.toString().replace('\\', '/');
            return text.isBlank() ? "." : text;
        } catch (Exception e) {
            return target.replace('\\', '/');
        }
    }

    private static String escapeGlob(String text) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if ("\\*?[]".indexOf(ch) >= 0) {
                escaped.append('\\');
            }
            escaped.append(ch);
        }
        return escaped.toString();
    }

    private static void writeSettings(Path localPath, String defaultMode, List<String> allow, List<String> deny)
            throws IOException {
        Files.createDirectories(localPath.getParent());
        Map<String, Object> root = new LinkedHashMap<>();
        if (defaultMode != null && !defaultMode.isBlank()) {
            root.put("defaultMode", defaultMode);
        }
        Map<String, Object> permissions = new LinkedHashMap<>();
        permissions.put("allow", allow);
        permissions.put("deny", deny);
        root.put("permissions", permissions);

        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        String yaml = new Yaml(options).dump(root);
        Files.writeString(localPath, yaml);
    }
}
