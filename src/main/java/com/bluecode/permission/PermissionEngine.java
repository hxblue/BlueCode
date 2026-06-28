package com.bluecode.permission;

import com.bluecode.llm.ToolCall;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class PermissionEngine {
    private final Path root;
    private final RuleSet user;
    private final RuleSet project;
    private final RuleSet local;
    private final Path localPath;
    private final Mode startMode;

    private PermissionEngine(Path root, RuleSet user, RuleSet project, RuleSet local, Path localPath, Mode startMode) {
        this.root = root;
        this.user = user;
        this.project = project;
        this.local = local;
        this.localPath = localPath;
        this.startMode = startMode;
    }

    public static PermissionEngine create(Path root) {
        Path resolvedRoot;
        try {
            resolvedRoot = Sandbox.resolveRoot(root == null ? Path.of(".") : root);
        } catch (IOException e) {
            resolvedRoot = (root == null ? Path.of(".") : root).toAbsolutePath().normalize();
            System.err.println("权限引擎降级：无法解析项目根目录，使用空规则继续运行: " + e.getMessage());
        }

        Path userPath = Path.of(System.getProperty("user.home", ".")).resolve(".bluecode/settings.yaml");
        Path projectPath = resolvedRoot.resolve(".bluecode/settings.yaml");
        Path localPath = resolvedRoot.resolve(".bluecode/settings.local.yaml");

        Settings userSettings = Settings.loadSettings(userPath);
        Settings projectSettings = Settings.loadSettings(projectPath);
        Settings localSettings = Settings.loadSettings(localPath);
        Mode startMode = List.of(localSettings, projectSettings, userSettings).stream()
                .map(Settings::defaultMode)
                .map(Mode::parse)
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(Mode.DEFAULT);

        return new PermissionEngine(
                resolvedRoot,
                Settings.toRuleSet(userSettings),
                Settings.toRuleSet(projectSettings),
                Settings.toRuleSet(localSettings),
                localPath,
                startMode);
    }

    public CheckResult check(Mode mode, ToolCall call, boolean readOnly) {
        Mode effectiveMode = mode == null ? Mode.DEFAULT : mode;
        Category category = Settings.categorize(call == null ? "" : call.name(), readOnly);
        String friendly = Settings.friendlyName(call == null ? "" : call.name());
        Settings.TargetInfo targetInfo = Settings.extractTarget(call);
        String target = targetInfo.target();

        if (category == Category.EXEC && target != null && !target.isBlank() && Blacklist.hitsBlacklist(target)) {
            return new CheckResult(Decision.DENY, "命中危险命令黑名单：" + target);
        }

        if (targetInfo.isFile()) {
            if (!targetInfo.ok()) {
                return new CheckResult(Decision.DENY, "无法解析文件路径参数，安全拒绝");
            }
            if (!Sandbox.sandboxOK(root, target)) {
                return new CheckResult(Decision.DENY, "路径在项目目录之外：" + target);
            }
            target = relativeTarget(target);
        }

        for (RuleLayer layer : List.of(
                new RuleLayer("本地", local),
                new RuleLayer("项目", project),
                new RuleLayer("用户", user))) {
            Optional<Decision> matched = layer.rules().match(friendly, target);
            if (matched.isPresent()) {
                Decision decision = matched.get();
                String reason = decision == Decision.DENY
                        ? "匹配 deny 规则：" + layer.name() + "层 " + friendly
                        : "";
                return new CheckResult(decision, reason);
            }
        }

        Decision fallback = modeFallback(effectiveMode, category);
        return fallback == Decision.ALLOW
                ? new CheckResult(Decision.ALLOW, "")
                : new CheckResult(Decision.ASK,
                effectiveMode.displayName() + " 模式中 " + categoryName(category) + " 类操作需要确认");
    }

    public void persistLocalAllow(ToolCall call) throws IOException {
        Persister.persistLocalAllow(root, localPath, local, call);
    }

    public Mode startMode() {
        return startMode;
    }

    static Decision modeFallback(Mode mode, Category category) {
        if (category == Category.READ || mode == Mode.BYPASS) {
            return Decision.ALLOW;
        }
        if (mode == Mode.ACCEPT_EDITS && category == Category.WRITE) {
            return Decision.ALLOW;
        }
        return Decision.ASK;
    }

    private String relativeTarget(String target) {
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

    private String categoryName(Category category) {
        return switch (category) {
            case READ -> "只读";
            case WRITE -> "文件写入";
            case EXEC -> "命令执行";
        };
    }

    public record CheckResult(Decision decision, String reason) {
    }

    private record RuleLayer(String name, RuleSet rules) {
    }
}
