# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

BlueCode 是一个从零构建的终端 AI 编程助手（类似 Claude Code），使用 **Java 21** 实现。项目采用 Gradle（Kotlin DSL）构建，JDK 21 工具链。

### 构建与测试

```bash
# 完整构建（含 shadow jar）
./gradlew build

# 运行所有测试
./gradlew test

# 运行单个测试类
./gradlew test --tests "com.bluecode.team.TeamManagerTest"

# 运行单个测试方法
./gradlew test --tests "com.bluecode.team.TeamManagerTest.testCreate"

# 构建可执行 fat jar
./gradlew shadowJar

# 运行应用
./gradlew run

# 快速一次性对话（非交互模式）
./gradlew run --args='-p "你的问题"'

# 清理构建
./gradlew clean
```

### 端到端测试（smoke test）

```bash
# 编译并运行 smoke test（两轮对话验证）
./gradlew compileJava
java --enable-preview src/main/java/com/bluecode/smoke/SmokeBlueCode.java
```

### 配置

- `.bluecode/config.yaml` — LLM provider 配置（Anthropic / OpenAI / OpenAI-compatible）
- `.bluecode/settings.yaml` — 权限设置（defaultMode、allow/deny 列表）
- `.bluecode/settings.local.yaml` — 本地永久允许记录（已 ignore）

---

## 架构概览

BlueCode 采用**分层、模块化**架构，以增量方式逐章开发（docs/ch02~ch15），每章在前一章基础上增加核心能力。

### 启动流程（`com.bluecode.BlueCode.main`）

1. 加载配置（`ConfigLoader`）
2. 初始化 MCP runtime（`McpManager` + `Registry`）
3. 加载指令文件、记忆系统、权限引擎、Hook 引擎、SubAgent 目录
4. 初始化 WorktreeManager、TaskManager、TeamManager
5. 注册工具（读/写/搜索/MCP + SubAgent Agent 工具 + 团队工具）
6. 创建 `SessionRuntime`（持久化会话容器）
7. 构造 `bluecodeModel`（TUI Model）并启动 `Program`（TUI 事件循环）

### 技术栈

| 组件 | 技术 |
|---|---|
| 语言/运行时 | Java 21 (virtual threads, records, sealed interfaces, pattern matching) |
| 构建 | Gradle (Kotlin DSL), Shadow plugin (fat jar) |
| TUI | JLine (`org.jline:jline`) + 自研 `tui.tea` TEA 架构 |
| LLM SDK | `com.anthropic:anthropic-java`, `com.openai:openai-java` |
| MCP | `io.modelcontextprotocol.sdk:mcp` |
| Markdown 渲染 | `com.github.ajalt.mordant:mordant-markdown` |
| 配置解析 | SnakeYAML |
| JSON | Jackson Databind |
| 测试 | JUnit Jupiter 5 |

---

### 核心子系统

#### 1. TUI 层（`com.bluecode.tui`）

基于自研 `tui.tea` 框架（TEA = The Elm Architecture）：

- **tea/Model** — TEA 架构的 Model 基类，泛型 `Model<M extends Model<M>>`
- **tea/Program** — TUI 事件循环主控
- **tea/Command** — 副作用封装（异步命令模式）
- **bluecodeModel** — 应用主 Model，管理状态机（IDLE / STREAMING / APPROVING / …）
- `AppState` — 应用状态（对话历史、流式内容、provider、spinner 等）
- `MarkdownRenderer` — Mordant-based markdown 实时渲染
- `Commands` — `/` slash 命令处理
- `CompletionMenu` — Tab 补全菜单
- `SpinnerVerbs` — 等待与流式期间的动画文字
- `LeadMailWatcher` / `LeadMailWaiter` — Team 模式下 Lead 的邮箱轮询

#### 2. LLM 客户端（`com.bluecode.llm`）

- `LlmClient` — 统一接口 + 工厂方法
- `AnthropicClient` — Anthropic API 实现（streaming + thinking）
- `OpenAiClient` — OpenAI API 实现
- `OpenAiCompatClient` — OpenAI 兼容端点（DeepSeek 等）
- `Request` — 请求构建器（system prompts, reminders, 多模态内容）
- `SystemPrompt` — 系统提示构建（环境、模块、memory 等）
- `ToolCall` / `ToolResult` — 工具调用数据模型
- `JsonHttpStreamer` — 通用 SSE/JSON streaming 客户端
- `Usage` — token 用量追踪
- `StreamEvent` — 流式事件模型

#### 3. Agent 层（`com.bluecode.agent`）

- **Agent** — 核心 ReAct 循环，`run(conversation, mode, cancel)` 返回 `BlockingQueue<Event>`
- `Agent.runToCompletion` — 子 Agent 专用：注入任务 → ReAct → 返回最后文本
- `Event` — 事件模型（Text / Tool / Approval / Done / Failed / …）
- `Phase` — 工具调用阶段（START / END / …）
- `CancelToken` — 取消令牌（ESC 中断、超时切换后台）
- `ToolEvent` — 工具调用开始/结果事件（含 phase、args、result、truncated）
- `SessionRuntime` — 会话运行时（session 元数据 + provider + reminders）
- **AgentTool** — 注册为工具的 SubAgent 入口（`prompt`, `subagent_type`, `model`, `run_in_background`, `teamName`）
- `TeamHook` 接口 + `TeammateContext` — SubAgent 团队协作扩展

会话事件流：Agent → BlockingQueue → TUI 消费 → 渲染到屏幕

#### 4. 工具系统（`com.bluecode.tool`）

- `Tool` 接口 — `name`, `schema`, `execute(args, context) → Result`
- `Registry` — 工具注册表（名称去重 + 冲突处理 + MCP 工具合并）
- `ToolArgs` — 参数提取（从 JSON Schema 定位参数路径）
- `Result` — 统一返回值封装
- `Truncate` — 结果截断（上下文窗口保护）

内置工具通过 `Registry.createDefault()` 注册，MCP 工具通过 `McpManager` 动态注入。

#### 5. 权限引擎（`com.bluecode.permission`）

- `PermissionEngine` — 规则决策引擎（黑名单 → 白名单 → 沙箱 → mode fallback）
- `Mode` — 权限模式（DEFAULT / ACCEPT_EDITS / PLAN / BYPASS / DONT_ASK）
- `Decision` / `Outcome` — 决策结果与用户响应
- `Blacklist` — 全局黑名单（敏感文件、危险命令）
- `Sandbox` — 沙箱机制（仅允许白名单内的操作）
- `Persister` — 本地永久允许记录（`.bluecode/settings.local.yaml`）

#### 6. MCP 客户端（`com.bluecode.mcp`）

- `ConfigLoader` — 加载 `.bluecode/config.yaml` 中的 `mcp_servers` 配置（HTTP/SSE 模式）
- `McpConfig` / `ServerConfig` — MCP 服务定义
- `McpManager` — MCP 连接生命周期管理（start/close + 工具发现）
- `McpTool` — 将 MCP server tool 适配为 BlueCode `Tool` 接口

`.bluecode.yaml` 示例中的 MCP 配置：
```yaml
mcp_servers:
  context7:
    type: http
    url: "https://mcp.context7.com/mcp"
```

#### 7. SubAgent 系统（`com.bluecode.subagent` + `com.bluecode.task`）

- **SubAgent 定义文件** — Markdown + YAML frontmatter（`src/main/resources/subagent/builtin/`）
  - `general-purpose` — 全工具通用子 Agent
  - `Explore` — 只读探索（haiku 模型）
  - `Plan` — 只读规划（plan 模式）
- **Catalog** — 定义文件加载器（项目级 > 用户级 > 内置级）
- **TaskManager** — 后台任务管理（launch / list / get / stop / sendMessage / onTaskDone）
- **工具过滤**：子 Agent 不可见 `Agent` 工具（防嵌套），后台 Agent 受限工具白名单

#### 8. Team 系统（`com.bluecode.teams` + `com.bluecode.coordinator`）

ch15 新引入，将 SubAgent 扩展为团队协作模式：

- `Team` / `TeamManager` — 团队生命周期管理（create / delete / addMember / persist）
- `Backend` 接口 — 三种执行后端隔离（tmux / iterm2 / in-process）
- `Mailbox` — 进程间邮箱通信（文件锁 + 原子写、读后标 read）
- `AgentNameRegistry` — name ↔ agentId 双向映射
- `Store` (tasks) — 共享团队任务列表（依赖图 + 原子持久化）
- 5 个协作工具：`TaskCreate` / `TaskGet` / `TaskList` / `TaskUpdate` / `SendMessage`
- `Coordinator` — Coordinator Mode（双锁机制 + Lead 工具收窄 + 四阶段提示词）
- `TeamMemberRunner` — Pane 后端子进程自治循环

#### 9. Worktree 系统（`com.bluecode.worktree`）

- `WorktreeManager` — git worktree 创建/删除/清理
- worktree 路径：`.bluecode/worktrees/<branch>/`，Team 模式下嵌套 `team-<team>/<member>/`
- 自动清理 24 小时前的 stale worktree

#### 10. 会话管理（`com.bluecode.session`）

- `Writer` — 实时流式写入 conversation.jsonl（每轮对话落盘）
- `SessionLoader` — 会话恢复（从磁盘反序列化 Conversation）
- `SessionList` — 会话列表查询
- `SessionCleaner` — 自动清理 30 天前的过期 session
- `Entry` — 会话条目（消息/事件/工具调用的统一记录空间）

#### 11. 上下文压缩（`com.bluecode.compact`）

- `ContextCompactor` — 超长对话自动压缩（token 阈值触发）
- `SummaryPrompt` — 压缩时的总结提示
- `Recovery` — 压缩后关键信息的恢复
- `compact.state` — 压缩状态追踪（`SessionContext`, `ContentReplacementState`, `AutoCompactTrackingState`）

#### 12. 记忆系统（`com.bluecode.memory`）

- `Manager` — 记忆管理（项目级 + 用户级双层存储）
- `Note` / `NoteType` — 记忆条目（user / feedback / project / reference）
- `Store` — 持久化存储
- `PromptTemplates` — 记忆提示模板

#### 13. 命令系统（`com.bluecode.command`）

- `Command` — 命令定义（kind, name, description, handler）
- `Kind` — 命令类型枚举
- `BuiltinPrompt` — 内置提示命令
- 扩展点：`/team` 系列、`/worktree` 等集成

#### 14. 技能系统（`com.bluecode.skill`）

- `ActiveSkills` — 当前可用技能管理
- `SkillHost` / `SkillForkHost` — 技能执行上下文
- `InstallSkillTool` / `LoadSkillTool` — 技能安装/加载工具
- `InstallReport` — 安装报告

#### 15. Hook 引擎（`com.bluecode.hook`）

- `HookEngine` — 事件驱动的 hook 调度（SESSION_START / SESSION_END 等）
- `HookLoader` — 从 `.bluecode/hooks/` 加载 hook 脚本
- `Payload` — hook 事件载荷

### 工具注册流程

```
McpManager.start(config)  →  发现 MCP 工具列表
        ↓
Registry.createDefault()  →  内置工具（Read/Write/Edit/Bash/Grep/Glob/…）
        ↓
registerSubAgentTools()   →  Agent、TaskList/Get/Stop、SendMessage、TeamCreate/Delete 等
        ↓
MCP 工具合并到 Registry   →  名称冲突时 MCP 工具覆盖
```

### 开发模式

- **增量章节开发**：docs/ch02~ch15，每章有 spec / plan / task / checklist
- **文档驱动**：先写 spec → plan → task → checklist → 实现 → 验收
- **测试**：JUnit 5 (Jupiter)，测试类按包镜像源码结构
- **权限配置**：开发调试可用 `defaultMode: bypass` 跳过审批
- **Session 清理**：自动清理 30 天前会话

### SubAgent 定义扩展

新增角色在 `src/main/resources/subagent/builtin/` 创建 `.md` 文件，frontmatter 支持：
- `name`, `description` — 必填
- `tools`, `disallowedTools` — 工具白名单/黑名单
- `model` — `haiku` / `sonnet` / `opus` / `inherit`
- `maxTurns` — 最大迭代轮数（默认 25）
- `permissionMode` — `default` / `acceptEdits` / `plan` / `bypassPermissions` / `dontAsk`
