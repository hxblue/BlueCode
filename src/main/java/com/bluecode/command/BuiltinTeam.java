package com.bluecode.command;

import com.bluecode.team.Team;
import com.bluecode.team.TeamManager;
import com.bluecode.team.TeammateInfo;
import com.bluecode.teams.BackendFactory;

import java.util.List;
import java.util.Optional;

final class BuiltinTeam {
    private BuiltinTeam() {
    }

    static Command command(TeamManager manager) {
        return new Command("team", List.of(), "管理 Agent Team: list/info/delete/kill", Kind.LOCAL, false,
                (cancelled, ui) -> handle(manager, ui));
    }

    private static void handle(TeamManager manager, Ui ui) throws Exception {
        if (manager == null) {
            ui.error("TeamManager 尚未初始化");
            return;
        }
        String[] parts = ui.commandArguments().strip().split("\\s+");
        String sub = parts.length == 0 || parts[0].isBlank() ? "list" : parts[0];
        switch (sub) {
            case "list" -> list(manager, ui);
            case "info" -> info(manager, ui, arg(parts, 1));
            case "delete" -> manager.delete(arg(parts, 1), contains(parts, "--force"));
            case "kill" -> kill(manager, ui, arg(parts, 1));
            default -> ui.println("用法: /team list | /team info <name> | /team delete <name> [--force] | /team kill <member>");
        }
    }

    private static void list(TeamManager manager, Ui ui) {
        List<Team> teams = manager.list();
        if (teams.isEmpty()) {
            ui.println("暂无 Team");
            return;
        }
        ui.println(String.join("\n", teams.stream()
                .map(team -> "%s  %s  members:%d  %s".formatted(
                        team.sanitizedName(),
                        team.backend().wireValue(),
                        team.members().size(),
                        team.configDir()))
                .toList()));
    }

    private static void info(TeamManager manager, Ui ui, String name) {
        Team team = manager.get(name).orElseThrow(() -> new IllegalArgumentException("找不到 Team: " + name));
        StringBuilder builder = new StringBuilder();
        builder.append("Team: ").append(team.name()).append(" (").append(team.sanitizedName()).append(")\n");
        builder.append("Backend: ").append(team.backend().wireValue()).append('\n');
        builder.append("Config: ").append(team.configPath()).append('\n');
        for (TeammateInfo member : team.members()) {
            builder.append("- ")
                    .append(member.name())
                    .append(" id=").append(member.agentId())
                    .append(" active=").append(member.isActive())
                    .append(" backend=").append(member.backendType().wireValue())
                    .append(" pane=").append(member.paneId())
                    .append(" worktree=").append(member.worktreePath())
                    .append('\n');
        }
        ui.println(builder.toString().stripTrailing());
    }

    private static void kill(TeamManager manager, Ui ui, String memberName) throws Exception {
        Optional<Team> owner = manager.list().stream()
                .filter(team -> team.memberByName(memberName).isPresent() || team.memberByAgentId(memberName).isPresent())
                .findFirst();
        if (owner.isEmpty()) {
            throw new IllegalArgumentException("找不到成员: " + memberName);
        }
        Team team = owner.get();
        TeammateInfo member = team.memberByName(memberName).or(() -> team.memberByAgentId(memberName)).orElseThrow();
        BackendFactory.create(member.backendType(), manager.backendDeps()).kill(member.paneId(), member.agentId());
        team.setMemberActive(member.name(), false);
        ui.println("已终止 " + member.name());
    }

    private static String arg(String[] parts, int index) {
        if (parts.length <= index || parts[index].isBlank()) {
            throw new IllegalArgumentException("缺少参数");
        }
        return parts[index];
    }

    private static boolean contains(String[] parts, String value) {
        for (String part : parts) {
            if (value.equals(part)) {
                return true;
            }
        }
        return false;
    }
}
