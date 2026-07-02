# BlueCode

BlueCode 是一个使用 Java 21 构建的终端 AI 编程助手, 目标是把 Claude Code / Codex 类体验拆成可学习、可扩展的本地工程系统。

在线展示页: [https://hxblue.github.io/BlueCode/](https://hxblue.github.io/BlueCode/)

展示页是静态前端回放, 不连接真实 LLM, 不执行 shell, 不读取访问者文件, 不消耗任何 API key。

## 能力概览

- 终端 TUI: 基于 JLine 和自研 TEA 事件循环。
- LLM Provider: 支持 Anthropic、OpenAI、OpenAI-compatible endpoint。
- 工具系统: 内置读写、搜索、Bash、MCP 工具适配和统一 Registry。
- 权限控制: 支持 default、acceptEdits、plan、bypassPermissions 等模式。
- 会话持久化: 对话和工具事件写入 `.bluecode/sessions/`。
- 记忆与指令: 支持项目级和用户级记忆、AGENTS/MEWCODE 类指令加载。
- SubAgent / Team: 支持后台 Agent、任务列表、消息、Team 协作和 worktree 隔离。

## 快速运行

需要 JDK 21。

```bash
git clone https://github.com/hxblue/BlueCode.git
cd BlueCode
./gradlew shadowJar
java -jar build/libs/bluecode.jar
```

一次性 prompt:

```bash
./gradlew run --args='-p "帮我解释这个项目的启动流程"'
```

## 配置

复制示例配置后填入自己的模型 provider:

```bash
cp .bluecode/config.yaml.example .bluecode/config.yaml
```

`.bluecode/config.yaml` 会被 git 忽略, 不要提交真实 API key。

MCP 示例入口:

```yaml
mcp_servers:
  context7:
    type: http
    url: "https://mcp.context7.com/mcp"
```

## 构建与测试

```bash
./gradlew test
./gradlew shadowJar
```

GitHub Pages 展示站位于 `site/`, 推送到 `main` 后由 `.github/workflows/pages.yml` 自动测试并部署。

## 项目结构

```text
src/main/java/com/bluecode/
  agent/        Agent 循环、事件、SubAgent 工具入口
  command/      slash command 与本地命令
  config/       provider 与应用配置
  llm/          Anthropic / OpenAI / OpenAI-compatible 客户端
  mcp/          MCP server 加载、生命周期与工具适配
  permission/   权限规则与执行决策
  session/      会话写入、恢复与清理
  team/ teams/  Team 协作、邮箱、任务与后端
  tui/          终端界面与 TEA 事件循环
  worktree/     git worktree 隔离
site/           GitHub Pages 静态展示站
docs/ch*/       按章节维护的 spec / plan / task / checklist
```

## 安全边界

BlueCode 是本地编程助手, 可能读取文件、修改代码和执行命令。公开展示站只播放预录制 demo, 不提供公网执行能力。

本地使用时请:

- 不要提交 `.bluecode/config.yaml`、`.env`、key 文件或会话日志。
- 在不信任项目中使用更严格的权限模式。
- 在运行 Bash 或编辑工具前检查审批内容。

## 许可证

本项目使用 MIT License。详见 [LICENSE](LICENSE)。
