```Markdown
# Skill 系统 Spec

## 1. 背景

Slash Command 让用户绕过 LLM 直接触发本地动作，但所有 handler 都硬编码在源码里：想加一个 `/commit` 让 Agent 自动分析 diff、生成 message、提交，就得改 Java 再重编。Slash Command 是确定性的快车道，Skill 系统则把可扩展性补上——用户在 `.bluecode/skills//` 或 `~/.bluecode/skills//` 放一个 `SKILL.md`（可选 frontmatter）或 `skill.yaml + prompt.md`，启动时被发现并注册成提示型命令，运行时按 inline 或 fork 模式注入 SOP，让 Agent 借助 LLM 能力完成更复杂的工作流。

## 2. 目标

交付一套进程内的技能编目与执行链路：`SkillCatalog` 两层扫描（用户全局 `~/.bluecode/skills/` + 项目 `.bluecode/skills/`）发现技能；phase-1 仅读 frontmatter 加快启动，`getFull` 触发 phase-2 重读 body 实现热更新；`SkillExecutor` 提供 `executeInline` 与 `executeFork` 两种执行模式，前者把 SOP 注入主 Agent 并按 `allowed_tools` 过滤工具，后者跑隔离的子 Agent，按 `fork_context`（none / recent / full）决定父消息种子；`SkillHost` / `SkillForkHost` 通过接口而非具体类把 Agent 状态切片暴露给 executor，避免 `com.bluecode.skill` 反向依赖 agent 包。`BlueCodeModel` 在 provider 就绪后调用 `loadFromDirectory` 加载项目目录，再把每个技能注册为 PROMPT 类型的 Slash Command，输入 `/` 时把 promptBody 当作 user message 发给 LLM，UI 上紧跟 `Successfully loaded skill` 系统消息。

## 3. 功能需求

- F1: `SkillCatalog` 暴露 `register / get / getFull / list / source / reload / loadCatalog / loadFromDirectory / buildActiveContext` 方法，内部 `skills` 与 `sources` 用 `LinkedHashMap` 保序。
- F2: 两层目录加载 `loadCatalog(workDir)`：tier 1 用户 `~/.bluecode/skills/`、tier 2 项目 `/.bluecode/skills/`，按名字后者覆盖前者。
- F3: 单技能加载策略两选一：优先 `skill.yaml + prompt.md`（`loadFromYamlAndPrompt`），否则 `SKILL.md`（`parseSkillMD`，可选 YAML frontmatter，缺描述时回退到 body 第一行非标题）。
- F4: `getFull(name)` 触发热重载：对 `sourceDir != null` 的技能每次重读 body，读失败时保留旧缓存，避免编辑过程中读到半成品。
- F5: `SkillMeta` 字段包含 `name / description / whenToUse / tags / allowedTools / mode / model / forkContext`；name 缺省时取目录名小写化并把空格换 `-`；mode 缺省 `inline`，向后兼容 `context: fork`；`fork_context` 缺省 `none`。
- F6: `SkillExecutor.executeInline(skill, args, host)`：先 `assertAllowedToolsExist` 校验白名单工具均在 `ToolRegistry`；再 `substituteArguments` 渲染 prompt；最后通过 `host.activateSkill` 注入 SOP 并按 `allowed_tools` 调 `host.setToolFilter`，返回渲染后的 body。
- F7: `SkillExecutor.executeFork(skill, args, host)`：构造 prompt + `buildForkSeed` 种子消息，调 `host.runSubAgent` 起隔离子 Agent，把最终 assistant 文本回传。
- F8: `substituteArguments(body, args)`：args 为空原样返回；body 含 `$ARGUMENTS` 时占位符替换；否则追加 `## User Request` 段。
- F9: `buildForkSeed(mode, parent)`：`full` 全量拷贝；`recent` 取尾部最多 5 条；其他（含 `none`）返回空。
- F10: `SkillHost` / `SkillForkHost` 接口：`activateSkill / setToolFilter / toolRegistry` 由 TUI/Agent 层实现；fork 主机额外提供 `runSubAgent / snapshotParentMessages`。
- F11: `BlueCodeModel.wireSkillsToAgent` 把 catalog 内每个技能注册为 PROMPT 命令，description 后缀 `[skill]` 用作分支判断；handler 返回 `promptBody`，executeCommand 在 PROMPT 分支把它当 user message。
- F12: PROMPT 分发命中 `[skill]` 后缀时，在 UI 上追加 `skill() Successfully loaded skill` 系统消息，提示用户技能已激活。

### 远程安装
- F13: `InstallSkillTool` 让用户把 URL 发给 bluecode、由 Agent 自动安装到 `~/.bluecode/skills//`
  - 支持三种 URL：`skills.sh` / `github.com tree` / `raw.githubusercontent.com`
  - 走 GitHub Contents API 递归拉取目录树（无需本地 git），单文件 ≤1 MiB、总大小 ≤8 MiB、文件数 ≤64、深度 ≤4
  - 暂存到兄弟 tempdir，验证含 SKILL.md 后 atomic rename 到位
  - 安装后自动 reload catalog + 重新注册斜杠命令，无需重启即可使用

### 远程安装
- F13: `InstallSkillTool` 让用户把 URL 发给 bluecode、由 Agent 自动安装到 `~/.bluecode/skills//`
  - 支持三种 URL：`skills.sh` / `github.com tree` / `raw.githubusercontent.com`
  - 走 GitHub Contents API 递归拉取目录树（无需本地 git），单文件 ≤1 MiB、总大小 ≤8 MiB、文件数 ≤64、深度 ≤4
  - 暂存到兄弟 tempdir，验证含 SKILL.md 后 atomic rename 到位
  - 安装后自动 reload catalog + 重新注册斜杠命令，无需重启即可使用

## 4. 非功能需求

- N1: `loadTier` 必须容错：目录缺失、不可读、单个技能解析失败都不中断其他技能。
- N2: phase-1 加载不能读 body：仅 frontmatter / yaml meta，避免大文件拖慢启动；body 由 `getFull` 按需加载。
- N3: `parseSkillMD` 的 YAML 解析失败要降级到「无 frontmatter」分支而不是抛异常。
- N4: `com.bluecode.skill` 不允许 import `com.bluecode.agent` / `com.bluecode.tui`——通过 `SkillHost` / `SkillForkHost` 接口反向解耦。
- N5: `assertAllowedToolsExist` 在工具未注册时抛 `IllegalStateException`，让上层在执行前暴露配置错误，而不是运行到一半才失败。
- N6: `register(skill)` 允许同名覆盖，调用方按 tier 顺序决定优先级（后注册者胜出）。
- N7: 注册成 PROMPT 命令时 `description` 必须以 `[skill]` 结尾，作为 UI 分支识别 marker。

## 5. 设计概要

- 核心数据结构:
 - `SkillCatalog.Skill`：record(`meta`, `promptBody`, `sourceDir`, `bodyLoaded`)，`withBody` 返回带新 body 的副本
 - `SkillCatalog.SkillMeta`：record(name, description, whenToUse, tags, allowedTools, mode, model, forkContext)
 - `SkillCatalog` 内部 `Map skills` + `Map sources` 全部 `LinkedHashMap`
 - `SkillHost`：`activateSkill(name, body)` + `setToolFilter(Predicate)` + `toolRegistry()`
 - `SkillForkHost extends SkillHost`：追加 `runSubAgent(body, seed, allowedTools, model)` + `snapshotParentMessages()`
- 主流程（启动期）:
 1. `BlueCode.main` 装好配置 → 构造 `BlueCodeModel`
 2. provider 就绪后（`BlueCodeModel` line 494-498）`new SkillCatalog()` + `loadFromDirectory(/.bluecode/skills)`
 3. `wireSkillsToAgent`（line 511-516）遍历 `list()`，对每个 meta 调 `registerSkillCommand`
 4. `registerSkillCommand`（line 518-533）跳过已有命令、把技能注册为 PROMPT 类型的 `Command`，handler 在执行时从 catalog 取 `promptBody`
- 主流程（运行期 inline 模式）:
 1. 用户输入 `/ ` → `executeCommand` → PROMPT 分支
 2. `cmdRegistry.execute` 返回 promptBody → `conversation.addUserMessage(promptBody)` → 若有 args 追加 `conversation.addUserMessage(args)`
 3. `agent.run` 启动新一轮 → UI 推送 `skill() Successfully loaded skill` 系统消息
 4. 后续 turn 与普通 Agent loop 一致
- 主流程（运行期 fork 模式 / Executor 直调）:
 1. 调用方持 `SkillForkHost` 实例，调用 `SkillExecutor.executeFork(skill, args, host)`
 2. `assertAllowedToolsExist` 校验工具白名单 → `substituteArguments` 渲染 prompt
 3. `buildForkSeed(skill.forkContext, host.snapshotParentMessages())` 决定种子消息
 4. `host.runSubAgent` 跑隔离 Agent，回最终文本
- 调用链:
 - 启动: `BlueCode.main` → `BlueCodeModel` 构造 → provider 就绪回调 → `new SkillCatalog().loadFromDirectory` → `wireSkillsToAgent` → `cmdRegistry.register`
 - 执行 inline: TUI `executeCommand`(PROMPT) → `cmdRegistry.execute` → catalog handler → 返回 promptBody → conversation → agent
 - 执行 fork（programmatic）: 外部调用 `SkillExecutor.executeFork` → `host.runSubAgent`
- 与其他模块的交互:
 - 上行: `com.bluecode.tui.BlueCodeModel`（注册 / 分发 / UI 提示）、`com.bluecode.command.CommandRegistry`（命令注册）
 - 下行: `com.bluecode.conversation.Message`（fork 种子）、`com.bluecode.tool.ToolRegistry`（白名单校验）
 - 接口反转: `SkillHost` / `SkillForkHost` 由 TUI / agent 层实现，避免循环依赖

## 7. 完成定义

见 [checklist.md](checklist.md)，所有条目勾上即完成。

```
- **F29**：笔记分两级存放——项目级 `.bluecode/memory/`，用户级 `~/.bluecode/memory/`。项目级笔记记录与当前项目相关的信息（项目知识、参考资料），用户级笔记记录跨项目通用的信息（用户偏好、纠正反馈）。具体分级由 LLM 判断。
- **F30**：每级有一个索引文件 `MEMORY.md`，每行一条笔记摘要。格式：`- [<type>] <title> — <一句话描述>`。索引文件不超过 200 行 / 25KB。超出时由 LLM 在更新时决定合并或淘汰旧条目。
- **F31**：文件名由 LLM 生成，格式为 `<type>_<short_slug>.md`（如 `user_preference_terse_replies.md`、`project_knowledge_api_conventions.md`）。slug 全小写、下划线分隔。

#### 记忆注入

- **F32**：启动时和每次笔记更新后，读取两级索引文件的内容拼接（项目级在前、用户级在后），注入系统提示的 `long-term-memory` 模块槽位（priority 100）。
- **F33**：注入内容为索引文件的纯文本，不是笔记全文。模型通过索引了解"记住了什么"，需要详情时可用文件读取工具读取完整笔记文件。
- **F34**：注入前检查拼接后的总大小：超过 25KB 时截断到 25KB 并追加 `(index truncated)` 标注。

#### 记忆更新

- **F35**：触发时机：`Agent.run` 完整执行结束后（模型最终回复无工具调用，事件流发出 `Done`），满足以下任一条件时异步发起记忆更新：① 每 5 轮自动触发（`SessionRuntime.turnCount % 5 == 0`）；② 本轮用户消息包含显式记忆请求关键词（"记住""记忆""别忘""remember""memo"）。两个条件为"或"关系。
- **F36**：异步执行：更新在独立 virtual thread 中运行，不阻塞用户的下一次输入。更新过程中用户可以继续对话。
- **F37**：更新输入：将本轮对话的最近消息（从最后一条 user 消息到最终 assistant 回复）和两级现有索引内容打包成一个记忆更新请求，发送给当前会话的同一个 provider。
- **F38**：更新请求不传工具定义（与摘要请求类似），模型不允许调用工具。
- **F39**：LLM 返回结构化 JSON 数组，每个元素描述一个操作：
  ```json
  [
    {"action":"create","level":"project","type":"project_knowledge","title":"...","slug":"...","content":"..."},
    {"action":"update","level":"user","filename":"user_preference_terse_replies.md","title":"...","content":"..."},
    {"action":"delete","level":"project","filename":"project_knowledge_old_api.md"}
  ]
  ```
  返回空数组 `[]` 表示无需更新。
- **F40**：执行操作：create 时创建新文件并在索引中追加一行；update 时重写文件内容和 frontmatter 并更新索引中对应行；delete 时删除文件并移除索引中对应行。所有文件操作发生在对应级别的 memory 目录下。
- **F41**：去重完全交给 LLM 判断：更新请求中包含完整索引，LLM 自行判断是否已有相似笔记需要合并或跳过。
- **F42**：更新失败（LLM 错误、JSON 解析失败、文件写入失败）静默记录日志，不影响主会话。不做重试。

### 集成与生命周期

- **F43**：`buildSystemPrompt(String instructions, String memory)` 接受两个新参数：非空时分别填入 `custom-instructions`（priority 80）和 `long-term-memory`（priority 100）模块的 content。
- **F44**：`Conversation` 构造时接受可选的 `onAppend` 和 `onReplace` 回调。`onAppend(Message msg)` 在每次追加消息后调用；`onReplace(List<Message> msgs)` 在整体替换后调用。回调由 session Writer 实现。未设置回调时行为与现有完全一致。
- **F45**：`Main` 启动流程新增步骤（在现有步骤之间插入）：① 加载项目指令 → ② 初始化记忆管理器并加载索引 → ③ 后台启动会话清理 → ④ 将指令文本和记忆文本传入 TUI 用于系统提示组装。
- **F46**：`/resume` 与 Agent 主循环互斥：`SessionState.RESUMING` 期间不允许发起新的 `Agent.run`；`Agent.run` 期间不响应 `/resume`（返回提示"请等待当前任务完成"）。
- **F47**：记忆更新与 `/compact` 可并发：记忆更新只读 conversation 快照、只写 memory 目录，不修改 conversation 本身，与压缩操作无冲突。

## 非功能需求

- **N1（性能）**：项目指令加载（含 @include 展开）必须在 200ms 内完成。JSONL 单次 append（序列化 + 写入 + sync）不超过 10ms。会话列表扫描（读首行提取标题）50 个会话不超过 500ms。
- **N2（并发安全）**：session Writer 的 append 在主循环和 TUI 路径并发调用时无竞态。记忆更新的文件写操作（memory 目录）用 `ReentrantLock` 保护，防止两次连续更新的读-写冲突。
- **N3（向后兼容）**：没有 MEWCODE.md 的项目、没有 memory 目录的项目、旧格式 session ID 的会话目录，都不影响启动和运行。旧 session ID 格式的目录在 `/resume` 列表中不展示（无法解析时间戳），也不被自动清理（避免误删）。
- **N4（可测性）**：@include 展开、JSONL 解析与恢复、记忆索引拼接、会话列表构建等核心逻辑可脱离真实 provider 单元测试。记忆更新的 LLM 调用通过 `Provider` 接口可 mock。
- **N5（错误隔离）**：指令文件加载失败（权限、格式）降级为空指令，不阻塞启动。JSONL 写入失败记录日志但不中断对话。记忆更新失败静默跳过。会话恢复中的任何单点错误（坏行、孤立调用、压缩失败）都有对应降级策略，不让一个错误拖垮整个恢复流程。

## 不做的事

- **不做向量数据库或 RAG 检索**：记忆索引直接注入上下文，约 2-3K tokens，不需要语义检索。
- **不做团队记忆同步**：笔记只在本机存储和读取，不做多人协作同步。
- **不做启动时自动恢复最近会话**：启动永远开新会话，只能通过 `/resume` 手动恢复。
- **不做会话合并**：每个会话独立存档，不支持合并两个会话的历史。
- **不做记忆质量反馈优化**：记忆更新 prompt 固定，不做 A/B 测试或用户评分回流。
- **不做指令文件热更新**：进程启动时加载一次，运行期间不监听文件变化。
- **不做笔记全文搜索**：模型通过索引感知记忆概况，需要详情时用文件读取工具按路径读取。
- **不清理旧格式 session ID 的目录**：只清理能解析出时间戳的新格式目录，避免误删 ch08 遗留数据。

## 验收标准

### 项目指令

- **AC1（三层加载）**：在三个路径各放一份 MEWCODE.md → 系统提示的 custom-instructions 模块中包含三份内容，项目根的在最前面。
- **AC2（缺失文件静默）**：只在项目根放 MEWCODE.md，其余两个路径无文件 → 加载成功，只包含项目根的内容。
- **AC3（@include 展开）**：MEWCODE.md 中写 `@include rules/style.md` → 对应文件内容替换该行。
- **AC4（嵌套深度）**：构造 6 层嵌套的 @include 链 → 第 6 层不展开，出现深度警告注释。
- **AC5（环路检测）**：A include B、B include A → 第二次 include 不展开，出现环路警告注释。
- **AC6（路径逃逸）**：项目级 MEWCODE.md 中 `@include ../../etc/passwd` → 不加载，出现范围警告注释。

### 会话存档

- **AC7（Session ID 格式）**：启动进程 → session ID 形如 `20260601-143022-a1b2`，`.bluecode/sessions/` 下能找到对应目录。
- **AC8（JSONL 写入）**：发送一条消息、得到回复 → `conversation.jsonl` 包含至少两行（user + assistant），每行可解析为合法 JSON，包含 role、content、ts 字段。第一行额外包含 model 字段。
- **AC9（压缩标记）**：触发一次压缩 → JSONL 中出现 `{"type":"compact","ts":...}` 标记行，其后跟压缩后的消息。
- **AC10（崩溃安全）**：模拟 Writer 写入中途被 kill → 重新打开 JSONL，除最后一行可能不完整外，之前的行全部可正常解析。

### 会话恢复

- **AC11（/resume 路由）**：在 TUI 输入 `/resume` → 不发送给 LLM，进入会话选择列表；输入 Esc → 返回空闲态。
- **AC12（列表展示）**：存在 3 个有效会话 → 列表展示 3 项，每项有标题、相对时间、模型标签、文件大小。
- **AC13（搜索过滤）**：在列表中输入搜索关键词 → 列表只展示标题匹配的会话。
- **AC14（坏行跳过）**：在 JSONL 中手动插入一行无效 JSON → 恢复时该行被跳过，其余消息正常加载。
- **AC15（孤立工具调用截断）**：JSONL 最后是一条带 tool_calls 的 assistant 消息、没有后续 tool 消息 → 恢复时该条被截断，conversation 以上一条完整消息结尾。
- **AC16（Token 超限压缩）**：构造一个 JSONL 使加载后估算 token 超过阈值 → 恢复过程中自动执行一次压缩后再进入空闲态。
- **AC17（时间跨度提醒）**：恢复一个最后消息 ts 距当前超过 6 小时的会话 → conversation 末尾追加时间跨度提醒消息。
- **AC18（追加写入）**：恢复后发送新消息 → 新消息追加到同一个 JSONL 文件，行号递增。

### 会话清理

- **AC19（过期清理）**：手动创建一个 31 天前时间戳的 session 目录 → 启动进程后该目录被删除。
- **AC20（新格式保护）**：手动创建一个旧格式 session ID（如 `1717000000-abc12345`）的目录 → 启动后不被删除也不在 /resume 列表中出现。

### 自动笔记

- **AC21（笔记创建）**：在对话中明确表达一个偏好（如"回复简洁点"）→ Agent 回复后，`.bluecode/memory/` 或 `~/.bluecode/memory/` 下出现对应类型的 .md 文件，frontmatter 包含 type、title、created。
- **AC22（索引更新）**：创建一条笔记后 → 对应级别的 `MEMORY.md` 中出现该笔记的摘要行。
- **AC23（记忆注入）**：MEMORY.md 有内容时启动新会话 → 系统提示的 long-term-memory 模块包含索引内容。
- **AC24（异步不阻塞）**：记忆更新正在执行时用户发送下一条消息 → 消息立即被处理，不等待记忆更新完成。
- **AC25（更新失败静默）**：mock provider 对记忆更新请求返回错误 → 主会话不受影响，日志记录错误。
- **AC26（索引大小限制）**：构造一个超过 25KB 的索引文件 → 注入系统提示时被截断到 25KB 并出现 truncated 标注。

### 集成

- **AC27（buildSystemPrompt 参数化）**：传入非空 instructions 和 memory → 系统提示中 custom-instructions 和 long-term-memory 模块都有内容且按正确优先级排列。传入空字符串 → 对应模块被跳过，与 ch08 行为一致。
- **AC28（Conversation 回调）**：设置 onAppend 和 onReplace 回调后，addUser/addAssistant/addToolResults/replaceMessages 各调用一次 → 回调被触发的次数和参数正确。未设置回调 → 行为与 ch08 完全一致。
- **AC29（互斥）**：`Agent.run` 执行期间输入 `/resume` → 返回提示信息，不进入列表。`SessionState.RESUMING` 期间不允许发起新的 `run`。

```