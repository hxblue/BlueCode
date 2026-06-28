```Markdown
# SubAgent 机制 Spec

## 背景

BlueCode 目前是单 Agent 架构：所有任务在同一个对话上下文里执行。这导致两个问题：

1. **上下文污染**：长任务后再做无关任务,前序中间结果(读过的文件、diff、错误回放)成为后续任务的噪声,token 飙升、响应质量下降
2. **无法并行**：没有把独立子任务分发出去并行执行的机制,主对话被长任务阻塞

bluecode 已经有「子 Agent 雏形」：

- ch11 Skill fork 模式通过 `agent.WithAllowedTools` 创建受限子 Agent(tui/SkillFork.java `runSubAgent`),走 `subAgent.run(...)` 跑完一轮
- `Conversation.fromMessages` / `replaceMessages` 已支持深拷贝消息列表

但还缺：
- 没有统一的、可被主 Agent 主动调用的 **Agent 工具**——子 Agent 只能由 Skill fork 触发
- 没有 **角色定义文件** 加载机制(Agent 角色全部写死在 fork 闭包里)
- 没有 **后台任务管理**——所有子 Agent 当前都是阻塞前台模式
- 没有 **工具过滤多层防线**——子 Agent 理论上可以无限嵌套
- Skill fork 与未来 SubAgent 工具两套代码并存

本章把上述能力补齐,让 bluecode 从单 Agent 进化到可分发任务的主从架构。

## 目标

- **G1**:提供统一的 Agent 工具,主 Agent 通过 `subagent_type` 参数选择预定义角色或留空走 Fork 路径;工具列表对模型始终稳定(不因角色定义增减而变化)
- **G2**:子 Agent 拥有独立的运行时状态——**消息**、**权限账本**(独立 Engine 决策状态)、**文件读缓存**、**token 计数**;共享基础设施——LLM 客户端、Hook 引擎、文件系统、`ToolRegistry`
- **G3**:支持两种创建模式:
  - **定义式**:指定 `subagent_type`,从空白对话 + 预定义角色 prompt 启动
  - **Fork 式**:不指定 `subagent_type`,克隆父对话历史并注入 Fork Boilerplate,借 prompt cache 降首次请求成本
- **G4**:角色定义为 Markdown + YAML frontmatter 文件;支持多来源加载,优先级:项目级 > 用户级 > 内置 > 插件;同名定义按 source 优先级覆盖,前者覆盖后者
- **G5**:子 Agent 以 **RunToCompletion** 模式执行——任务直接注入对话,模型不再调工具即结束,返回最后一条 assistant 文本作为结果
- **G6**:子 Agent 在工具调用时遇到权限判定,按 **三层升级链** 处理:① 父对话已批准账本 → ② 角色 frontmatter 的 `permissionMode` 兜底 → ③ 仍无法决定时升级到主 TUI 询问用户(子 Agent 暂停、用户响应、子继续)
- **G7**:支持后台任务:三种进入方式——① 显式 `run_in_background:true`、② 前台超时 120 秒自动切后台、③ ESC 手动切后台;Fork 路径无条件后台;Fork Boilerplate 注入到子 Agent 首条消息约束其行为
- **G8**:后台任务跑完通过 `<task-notification>` 自动注入主对话(主 Agent 下次 turn 即看到);主 Agent 可通过 `TaskList`/`TaskGet`/`TaskStop` 工具主动查询和操控,可通过 `SendMessage` 给已跑完的、仍存活的后台 Agent 续派任务
- **G9**:工具过滤多层防线阻断子 Agent 无限嵌套——全局禁止列表(子 Agent 永远不能用 Agent 工具)、后台白名单(后台 Agent 只能用基础读写网络工具)、定义层 `tools`/`disallowedTools` 业务约束
- **G10**:复用 SubAgent 底座统一 Skill fork 路径——`tui/SkillFork.java` 的 `runSubAgent` 改为调用 SubAgent 公共启动函数,两条路径走同一段 agent 构造逻辑
- **G11**:内置 3 个角色——`general-purpose`(全工具)、`Explore`(只读探索,haiku)、`Plan`(只读规划);插件级保留接口占位但本期不实现真插件加载,加载顺序里插件来源恒为空

## 功能需求

### Agent 工具

- **F1**:新建 `Agent` 工具,参数(JSON Schema):
  - `prompt`(string,必填):交给子 Agent 的任务指令
  - `description`(string,必填):一句话描述任务,供 UI 展示
  - `subagent_type`(string,可选):指定预定义角色名,留空时走 Fork 路径
  - `model`(string,可选):模型覆盖,取值 `haiku` / `sonnet` / `opus` / `inherit`;留空沿用 Agent 定义的 model
  - `run_in_background`(bool,可选):true 时强制后台启动;Fork 路径忽略此字段(无条件后台)
  - `name`(string,可选):给本次启动的子 Agent 命名,供 SendMessage 用;同名后启动的覆盖前面的弱引用
- **F2**:Agent 工具的 `execute`:
  - subagent_type 非空:`catalog.resolve(name)` 取定义;不存在则返回结构化错误「未知 subagent_type: X」
  - subagent_type 为空:走 Fork 路径,从 `catalog` 取「fork 默认基础定义」(prompt body=Fork Boilerplate)
  - 按 `run_in_background` 与 Fork 强制规则,选择 inline 跑(阻塞返回 finalText)或 background 跑(返回 `{task_id, status:"async_launched"}`)
- **F3**:Agent 工具被全局禁止列表 `ALL_AGENT_DISALLOWED_TOOLS` 标记——任何子 Agent 都看不到 Agent 工具,从根源上断绝嵌套

### Agent 定义文件

- **F4**:Agent 定义文件是 Markdown,以 `---` frontmatter 块开头、紧跟正文(子 Agent 系统提示);frontmatter YAML 字段:
  - `name`(必填):角色名,小写字母 / 数字 / 连字符,长度 1-32
  - `description`(必填):一句话描述,用于 Agent 工具的 `subagent_type` 文档与 UI 列表
  - `tools`(可选,string array):工具白名单
  - `disallowedTools`(可选,string array):工具黑名单
  - `model`(可选):`haiku` / `sonnet` / `opus` / `inherit`,缺省 `inherit`
  - `maxTurns`(可选,int):最大迭代轮数,缺省继承全局 `maxIterations=25`
  - `permissionMode`(可选):`default` / `acceptEdits` / `plan` / `bypassPermissions` / `dontAsk`,缺省 `default`;`dontAsk` 是子 Agent 专属——自动批准所有规则未命中的工具
  - `background`(可选,bool):缺省 false;true 时 Agent 工具忽略 `run_in_background` 参数、强制后台
- **F5**:Catalog 三层加载(本期插件级恒为空),顺序:
  1. 项目级:`<root>/.bluecode/agents/*.md`
  2. 用户级:`~/.bluecode/agents/*.md`
  3. 内置级:Jar 内 classpath resource `subagent/builtin/*.md`(`Class.getResourceAsStream`)
- **F6**:同名定义按 source 优先级覆盖——项目级 > 用户级 > 内置级;`resolve(name)` 返回优先级最高的版本
- **F7**:Catalog 启动期加载,加载失败的单个文件(frontmatter 不合法、name 重名以外的字段错)走 stderr 警告并跳过,不阻断启动
- **F8**:本章不引入插件加载器——`SourcePlugin` 常量保留供未来扩展;加载顺序里第四层恒为空 List

### 子 Agent 运行时

- **F9**:扩展 `agent.Agent` 增加 `runToCompletion(ctx, conv, task) -> String` 方法:
  - 把 `task` 作为 user 消息追加到 conv
  - 进入 ReAct 循环,maxTurns 由 `Agent.maxTurns` 决定(子 Agent 用 frontmatter,主 Agent 不变=25)
  - 模型不再调工具时结束循环,取末尾 assistant 文本返回
  - 触达 maxTurns 时返回最后一条 assistant 文本 + 「达到最大轮数」异常
  - 同一段循环代码与主对话 `run` 共用,不重复实现
- **F10**:新增 Agent Builder 选项:
  - `systemPrompt(text)`:子 Agent 启动时把 text 作为 system prompt 注入(覆盖默认 bluecode 主 Agent 系统提示)
  - `provider(p)`:让子 Agent 用与父不同的 provider(model 覆盖时切换)
  - `maxTurns(n)`:限制本 Agent 的最大迭代轮数
  - `permissionMode(m)`:子 Agent 启动模式
  - `parentEngine(eng)`:子用父 Engine 做权限决策一级查找(本期所有 Agent 共享同一 Engine,但增加显式参数预留隔离扩展)
- **F11**:子 Agent 的运行时状态隔离——独立 `SessionRuntime`、独立 `Conversation`、独立 token 计数;但共享 `Provider`(除非 `provider()` 覆盖)、`ToolRegistry`、`PermissionEngine`、`HookEngine`

### 权限决策

- **F12**:子 Agent 工具调用权限决策三层链(在 `runGuarded` 内分支):
  1. 父对话已批准账本——父 Engine 已经 `persistLocalAllow` 过的精确规则匹配 → Allow
  2. 子角色 `permissionMode` 兜底——`dontAsk` 模式直接放行所有 Allow/Ask 类规则未命中的;`acceptEdits` 放行写;`bypassPermissions` 全 Allow(黑名单/沙箱仍拦);其他模式仍走原 `modeFallback`
  3. 三层之外仍是 Ask——升级到主 TUI:子 Agent 暂停,主 TUI 弹审批框(标注 `[来自 SubAgent X]`),用户响应后子 Agent 继续;Outcome 沿用现有三选一(DenyOnce/AllowOnce/AllowForever)
- **F13**:升级到主 TUI 的通信机制——子 Agent 把 `ApprovalRequest` emit 到自己的 `Flow.Publisher` 事件流,事件流被 `TaskManager` / `SkillFork` host 转发到主 TUI 的 Approval 弹窗;主 TUI 响应后 Outcome 通过 `CompletableFuture<Outcome>` 回传

### 后台任务管理

- **F14**:新建 `task.Manager`,持有 `Map<String, BackgroundTask>`,提供 `launch(ctx, agent, taskText) -> taskID`、`get(id)`、`list()`、`stop(id)`、`adoptRunning(...)`、`subscribeDone() -> Flow.Publisher<String>`
- **F15**:`BackgroundTask` 字段:
  - `id`(String,manager 生成)
  - `name`(String,可选,F1 的 `name` 字段)
  - `subAgent`(Agent)
  - `conv`(Conversation,子对话)
  - `task`(String,初始任务)
  - `status`(`RUNNING` / `COMPLETED` / `FAILED` / `CANCELLED`)
  - `result`(String,跑完后填)
  - `err`(Throwable)
  - `startTime` / `endTime`(Instant)
  - `cancelFlag`(`AtomicBoolean` 或 `volatile boolean`,虚线程 cancel 钩子)
  - `usage`(`TokenUsage`,token 计数)
  - `toolCount`(int,工具调用次数计数器)
  - `lastActivity`(String,最近一次工具名)
- **F16**:`launch` 内部 virtual thread:`subAgent.runToCompletion(ctx, conv, task)` → status 终态 → 推 `taskID` 到 `Flow.Publisher<String>` → TUI 消费后注入 `<task-notification>`
- **F17**:三种进入后台的方式:
  1. **显式**:Agent 工具 `run_in_background:true` → 直接调 `launch`,工具 result 立刻返回 `{task_id, status:"async_launched"}`
  2. **超时自动**:Agent 工具默认前台 inline 跑,但前台 run 启动后开计时器(120 秒,常量 `AUTO_BACKGROUND_MS`),超时则:
     - 取消前台 publisher 订阅
     - 调 `manager.adoptRunning(agent, conv, ctx, cancelFlag, eventPublisher, partial)` 接管事件流继续后台跑
     - Agent 工具 result 改返回 `{task_id, status:"timed_out_to_background"}`
  3. **ESC 手动切**:用户在前台子 Agent 跑动期间按 ESC → TUI 调 `manager.adoptRunning(...)`,与超时路径走同一逻辑
- **F18**:Fork 路径 `run_in_background` 字段被强制视为 true(代码内 override)
- **F19**:后台任务完成时,Manager 把 `taskID` push 到 `Flow.Publisher<String>` done sink;TUI 在主事件循环消费,把如下文本作为 system reminder 拼到主对话下一次 reminder 区(不打断当前对话):
  ```
  <task-notification>
  Task X (name="Y"): completed
  Result: <最终文本>
  </task-notification>
  ```

### 后台任务工具

- **F20**:新增 4 个内置工具:
  - `TaskList`:无参,返回当前 manager 中所有非 Terminated 任务的简要列表(id、name、status、tool_count、last_activity)
  - `TaskGet`:`{task_id}`,返回指定任务的完整状态(含 result / err)
  - `TaskStop`:`{task_id}`,调 `manager.stop` 触发取消;返回 `{status:"cancellation_requested"}`
  - `SendMessage`:`{name, message}`,按 name 找到仍存活的后台 Agent(status=COMPLETED,conv 仍在内存),把 message 作为新 user 消息追加到 conv 并重新 `launch` 一轮跑动;找不到 / 已 CANCELLED 返回错误
- **F21**:本期不实现 `TaskCreate`(主要给 Hook 用,Hook 暂未需要 SubAgent action);保留 manager API,Hook subagent stub 也可暂未对接

### Fork 路径

- **F22**:`buildForkedMessages(parentConv)` 做三件事:
  1. 深拷贝 parentConv 的全部消息
  2. 把末尾 assistant 中未完成的 `tool_use`(无对应 ToolResult)包装为 placeholder ToolResult,使消息格式合法
  3. 在末尾追加 user 消息,内容 = Fork Boilerplate + 任务文本
- **F23**:Fork Boilerplate 是一段 `<fork_boilerplate>` 包裹的指令,核心约束:
  - 不能再 Fork(再 Fork 会被 QuerySource 拦截 / Boilerplate 标记扫描兜底)
  - 不要对话 / 提问 / 请求确认
  - 直接使用工具
  - 严格限制在分配的任务范围内
  - 最终报告以 `Scope:` 开头,500 字以内
- **F24**:Fork 子 Agent 嵌套阻断三道闸:
  1. **工具列表层**:Fork 子 Agent 的工具列表保留 Agent 工具(继承自父),但调用 Agent 工具时
  2. **QuerySource 检测**:Agent 工具入口检测 caller 来源(检查父链),若 caller 是 Fork 路径产生,直接 `isError=true` 返回「Fork 子 Agent 不能再启动 Agent」
  3. **Boilerplate 标记扫描**:对话历史里如果含 `<fork_boilerplate>` 标记(QuerySource 失效兜底),也认定是 Fork 嵌套
- **F25**:定义式子 Agent 不走 Boilerplate(从空白启动);嵌套阻断靠 `ALL_AGENT_DISALLOWED_TOOLS` 全局禁止 Agent 工具

### 工具过滤多层防线

- **F26**:全局禁止列表 `ALL_AGENT_DISALLOWED_TOOLS = ["Agent"]`(本期范围最小,后续可加 AskUserQuestion / TaskStop);所有子 Agent 启动时从工具列表中剔除这些
- **F27**:自定义 Agent 额外限制 `CUSTOM_AGENT_DISALLOWED_TOOLS`:本期为空,接口预留(用于将来用户自定义 Agent 一律不可访问某些核心工具)
- **F28**:后台 Agent 白名单 `ASYNC_AGENT_ALLOWED_TOOLS`,只列基础工具:
  `read_file, write_file, edit_file, glob, grep, bash, load_skill, install_skill`
  以及所有 MCP / Skill 工具。Fork/run_in_background 任意一种成立的子 Agent 工具集再叠加此白名单交集。
- **F29**:Agent 定义层 `tools`(白名单)与 `disallowedTools`(黑名单)组合应用——白名单先确定范围,黑名单再排除
- **F30**:工具过滤合并执行顺序(在 Agent 工具的 `execute` 内,子 Agent 构造时):
  1. 起点 = registry 的全部工具
  2. 去掉 `ALL_AGENT_DISALLOWED_TOOLS`
  3. 如果是后台 → 取交集 `ASYNC_AGENT_ALLOWED_TOOLS`
  4. 应用定义的 `disallowedTools` 黑名单
  5. 应用定义的 `tools` 白名单(空白名单 = 不再收窄)
  6. 注入到子 Agent 的 `Agent.builder().allowedTools(allowed)`
- **F31**:工具列表对模型稳定——以上过滤只发生在子 Agent 构造时,主 Agent 看到的工具列表不变

### 内置角色与 Skill fork 改造

- **F32**:内置 3 个角色文件,作为 classpath resource 打入 jar:
  - `general-purpose.md`:无 disallowedTools,用 `inherit` 模型,maxTurns=30,permissionMode=default
  - `explore.md`:disallowedTools=[write_file, edit_file],model=haiku,maxTurns=30,permissionMode=default
  - `plan.md`:disallowedTools=[Agent, write_file, edit_file],maxTurns=15,permissionMode=plan(plan 是已有的权限模式)
- **F33**:Skill fork 改造——`tui/SkillFork.java` 的 `runSubAgent` 改为:
  1. 构造一个临时 `subagent.Definition`(name="skill-fork-<skillname>",disallowedTools=skill.allowedTools 反推 / 等同 skill 自身的 allowedTools),将其当 Fork 路径走
  2. 复用 `Agent.runToCompletion` 与 SubAgent 的工具过滤、消息装填路径
  3. 返回 finalText 行为不变(`host.appendAssistantMessage` 仍由 Executor 调)

## 非功能需求

- **N1**:工具列表稳定——主 Agent 看到的工具集不因 `.bluecode/agents/` 增减或 Agent 工具被调用而变化(防止 prompt cache 抖动)
- **N2**:Fork 路径首次请求命中 prompt cache——`buildForkedMessages` 拼接的消息列表与父对话末尾完全一致,系统提示一致
- **N3**:子 Agent 崩溃不影响主程序——`Manager.launch` 的 virtual thread 包 try/catch,任何 `Throwable` 转 `status=FAILED` + 错误信息回灌
- **N4**:启动期 fail-fast——内置定义 classpath 资源解析失败立刻抛 `RuntimeException`(代码 bug),用户/项目级定义文件解析失败仅 stderr 警告并跳过
- **N5**:与现有 ch11 Skill 系统、ch12 Hook 系统、ch08 权限系统、ch04 主 Agent loop 协同,不破坏既有测试
- **N6**:配置 `enableSubAgentBackground`(boolean,默认 true)关闭后,Agent 工具的 `run_in_background:true` / 超时切后台 / ESC 切后台全部失效,所有 SubAgent 强制前台同步;Fork 路径在此模式下报错「后台禁用,无法 Fork」
- **N7**:`<task-notification>` 注入主对话不消耗主 Agent 的工具调用配额,不出现在用户视窗(只对模型可见)

## 不做的事

- Worktree 文件隔离(独立章节)
- 多 Agent 团队编排(CrewAI / AutoGen 平等协作风格)
- 后台任务跨会话持久化——主程序退出后任务全部丢失
- 真正的插件系统(`SourcePlugin` 占位)
- 子 Agent 输出 schema 强制结构化(返回纯文本即可)
- Verification Agent 内置开关(`enableVerificationAgent` 不实现)
- `TaskCreate` 工具(本期仅 List/Get/Stop/SendMessage)
- 跨 SubAgent token 用量汇总到 /status(只在 Manager 内部记录)

## 验收标准

- **AC1**:Agent 工具注册成功,主 Agent 的工具列表里 schema 一致;子 Agent 看不到 Agent 工具
- **AC2**:`Agent` 工具调用 `{prompt:"...",subagent_type:"Explore"}` 时,主 Agent 看到的 tool_result 是 Explore 子 Agent 的最后一条 assistant 文本
- **AC3**:`Agent` 工具调用 `{prompt:"...",subagent_type:"non-existent"}` 时,主 Agent 看到的 tool_result 是结构化错误「未知 subagent_type」
- **AC4**:`Agent` 工具调用不传 subagent_type 时,子 Agent 收到的首条 user 消息以 `<fork_boilerplate>` 起头,且消息列表前缀与父对话一致(可由测试断言)
- **AC5**:Fork 子 Agent 的工具列表里仍有 Agent 工具(F22 设计),但调用 Agent 工具会被 QuerySource 拦截,tool_result 含「Fork 子 Agent 不能再启动 Agent」
- **AC6**:定义式子 Agent 的工具列表里没有 Agent 工具(被 `ALL_AGENT_DISALLOWED_TOOLS` 剔除)
- **AC7**:子 Agent 角色 frontmatter 写 `permissionMode: dontAsk`,Bash 等需要 Ask 的工具直接放行,无审批弹窗
- **AC8**:子 Agent 角色 frontmatter 不写 dontAsk,Bash 工具触发审批,弹窗带 `[来自 SubAgent X]` 标识
- **AC9**:`run_in_background:true` 时 tool_result 立即返回 `{task_id, status:"async_launched"}`,主 Agent 不阻塞
- **AC10**:前台子 Agent 跑超过 120 秒,自动切后台,主 Agent 看到 tool_result 含 `status:"timed_out_to_background"`
- **AC11**:前台子 Agent 跑动期间用户按 ESC,切到后台,TUI 继续接收主 Agent 输入
- **AC12**:后台子 Agent 跑完,主 Agent 下次 run 的 reminder 区出现 `<task-notification>` 块,含 result
- **AC13**:`TaskList` 工具返回当前后台任务列表,字段含 id/name/status/tool_count
- **AC14**:`TaskGet({task_id})` 返回 result;`TaskStop({task_id})` 触发取消,任务 status 变 CANCELLED
- **AC15**:`SendMessage({name,message})` 让一个仍存活的后台 Agent 接到新任务并重新跑动,跑完结果作为新 `<task-notification>` 注入主对话
- **AC16**:项目级 `.bluecode/agents/explore.md` 覆盖内置 `explore`,`resolve("explore")` 返回项目级版本
- **AC17**:Skill fork 模式调用走 SubAgent 底座——`tui/SkillFork.java` 的 `runSubAgent` 内部只是装饰参数后调 `subagent.LaunchFork.launch(...)`(或同等公共函数)
- **AC18**:N6 配置开关 `enableSubAgentBackground:false` 时,Fork 路径调用 Agent 工具返回结构化错误
- **AC19**:`<fork_boilerplate>` 出现在对话历史里 + Agent 工具被调用 → 拦截(QuerySource 失效兜底)
- **AC20**:子 Agent throw → status=FAILED,主 Agent 收到 `<task-notification>` 含错误描述,主程序不崩
- **AC21**:全新项目级自定义 Agent(`.bluecode/agents/<name>.md`)被 Catalog 加载;`subagent_type=<name>` 调用时,frontmatter 的 disallowedTools / permissionMode / maxTurns / systemPrompt 全部生效——子 Agent 看不到黑名单工具、按指定 mode 决策、不超 turns、按 systemPrompt 行事
- **AC22**:Agent 定义 frontmatter 的非法字段(unknown model / unknown permissionMode)在加载时 stderr 警告并 fallback 到默认值(model→inherit, mode→default),bluecode 不阻断启动,该 Agent 仍可被 resolve 与调用

```
  # 通用字段(每个事件都有)
  event: <事件名>
  session_id: <当前会话 ID>
  cwd: <项目工作目录>
  mode: <PermissionMode 名,default / plan>

  # 事件特化字段
  PreToolUse / PostToolUse:
    tool_name: <内部工具名,如 read_file>
    tool_input: <工具参数 JSON 对象>
    tool_result: <仅 PostToolUse,工具结果摘要文本>
    is_error: <仅 PostToolUse,bool>
  UserPromptSubmit / PreUserMessage:
    prompt: <用户输入文本>
  Notification:
    kind: approval | stream_error
    detail: <approval 含工具名;stream_error 含错误摘要>
  PreCompact / PostCompact:
    trigger: auto | emergency | manual
    before_tokens: <int,仅 PostCompact>
    after_tokens: <int,仅 PostCompact>
  SessionStart / SessionEnd / SessionResume:
    (仅通用字段)
  Stop:
    iter: <本轮 run 走完的迭代数>
  ```

### 条件表达式

- **F11**:条件表达式 `if:` 是一个对象,顶层只能出现 `all_of` 或 `any_of` 中**一个**——两个同时出现按加载错误处理;缺省 `if:` 视为无条件触发
- **F12**:`all_of` / `any_of` 的值是一个原子条件数组,每个原子条件包含 `field` 与 `match` 两个字段
  ```yaml
  if:
    all_of:
      - field: tool_name
        match: { type: exact, value: write_file }
      - field: tool_input.path
        match: { type: glob, value: "**/*.java" }
  ```
- **F13**:`field` 取 payload 中的字段路径,用 `.` 分隔嵌套(如 `tool_input.command`、`tool_input.path`);路径不存在按空字符串处理,不报错
- **F14**:`match` 取四种类型之一——
  - `{type: exact, value: "..."}`
  - `{type: glob, value: "..."}`
  - `{type: regex, value: "..."}`
  - `{type: not, inner: {type: ..., value/inner: ...}}`

  正则编译失败、`not` 缺少 `inner`、`inner` 自身非法均视为加载错误,跳过该 hook
- **F15**:条件求值在事件 emit 时实时进行,匹配器实例在加载期一次构造、运行期复用

### 动作类型

- **F16**:`action.type` 取 `shell` / `prompt` / `http` / `subagent` 之一,各自的字段:

#### shell 动作

- **F17**:`shell` 动作字段:`command`(字符串,由 `sh -c` 解释执行);执行时把事件 payload 序列化成单行 JSON 通过 stdin 传给命令——脚本侧可用 `jq` 取字段
- **F18**:`timeout` 默认 30 秒,超时按命令失败处理(记日志);async 时由后台 virtual thread 异步执行,超时同样按失败处理
- **F19**:拦截事件(PreToolUse / UserPromptSubmit)下的 shell 同步执行:
  - `exit code == 2` 视为拦截命中,`stderr || stdout` 合并去尾换行后作为拒绝原因
  - `exit code == 0` 视为放行
  - 其它非零 exit code 视为 hook 失败但**不拦截**(记日志、Agent 继续)

#### prompt 动作

- **F20**:`prompt` 动作字段:`text`(字符串);执行时把 `text` 加入"下一次 LLM 请求的 reminder 区"队列——所有 hook 注入的 prompt 按 hook 在 yaml 中的声明顺序拼接,置于现有 plan reminder 之后
- **F21**:reminder 队列仅本轮有效,下一轮重新装配;不入持久对话历史、不影响压缩
- **F22**:prompt 动作永不表达拦截——即使位于拦截类事件,动作执行后视为放行,仅做副作用注入

#### http 动作

- **F23**:`http` 动作字段:`url`(必填)、`method`(默认 POST)、`headers`(可选键值对)、`body`(可选字符串模板,支持 `${field}` 占位符渲染 payload 字段);缺省 `body` 时把事件 payload 序列化成 JSON 作为请求体
- **F24**:`timeout` 同 F18 默认 30 秒;async 时由后台 virtual thread 异步执行
- **F25**:拦截事件下的 http 同步执行:
  - 响应 status 2xx 且 body 解析成 `{"decision":"block","reason":"..."}` 时视为拦截命中,reason 作为拒绝原因
  - 其它情况(非 2xx、body 缺 `decision` 字段、`decision` 非 `block`)视为放行
  - 网络错误、超时、JSON 解析失败按 hook 失败但**不拦截**

#### subagent 动作

- **F26**:`subagent` 动作字段:`agent_name`(必填)、`prompt`(必填字符串模板);**本期占位实现**——加载时校验字段完整、执行时仅记一行 stderr 日志 `[hook subagent] not yet implemented, skipped: <name>`、不报错也不拦截;后续章节对接子 Agent 后再补完整逻辑

### 执行控制

- **F27**:`only_once: true` 标记的 hook 在同一会话内首次匹配成功并执行后被记录到 SessionRuntime 的内存集合(key = hook.name),后续相同事件再次匹配时直接跳过;`/clear`、`/resume` 进新会话时集合清空;**进程退出不写盘**——本期不做跨进程持久化
- **F28**:`async: true` 标记的 hook 在新 virtual thread 中执行;加载期校验:若 hook.event ∈ {PreToolUse, UserPromptSubmit} 且 async=true,加载层报错并跳过该 hook(拦截类不允许异步——异步无法表达拦截信号)
- **F29**:所有 hook 失败(命令非 0 exit 但非拦截信号、HTTP 错误、超时等)写一行 stderr `[hook <name>] <event> failed: <reason>`;不写日志文件、不弹 UI 通知;async 失败同上、不重试

### 集成点

- **F30**:Hook 系统由独立模块承载,内部至少包含规则加载器、引擎(事件分派 + 集合状态)、四类动作执行器、匹配器;Agent 在构造期通过构造器/Builder 注入 Hook 引擎
- **F31**:Agent.run 等关键路径在 11 个事件时刻调用引擎的事件分派接口,接口返回拦截判定与待注入 prompt 集合
- **F32**:拦截结果整合:
  - **PreToolUse 拦截**:把 reason 拼成 `[hook <name>] <reason>` 形式当 tool_result 回灌,跳过权限引擎与真实工具执行;PhaseStart/PhaseEnd 事件按当前实现继续 emit,PhaseEnd 的 isError=true
  - **UserPromptSubmit 拦截**:阻止该 user 消息写入对话历史,TUI 在输入框下方显示 `[hook <name>] <reason>`,焦点返回输入框等用户重新编辑
- **F33**:InjectedPrompts 集合在下一次 streamOnce 时拼到 reminder 串末尾,置于现有 plan reminder 之后;本轮无可拦截语义的事件(SessionStart 等)触发的 prompt 注入也走 reminder 队列

### Slash 命令

- **F34**:新增内置 Slash 命令 `/hooks`,KindLocal,零参数:输出当前已加载的所有 hook 的精简列表,按 `event` 分组、每条一行 `  <name>  <event>  <action.type>  <flags>`,flags 含 `[once]` / `[async]` 标志;末尾追加 `Loaded from: <加载来源文件列表>`
- **F35**:无任何 hook 时输出 `No hooks loaded.`

## 非功能需求

- **N1**:Hook 加载在进程启动期一次性完成;YAML 解析错误、字段缺失、event 未知、name 冲突、async + 拦截事件冲突、regex 编译失败等所有加载错误**一律 stderr 输出后继续启动**,不阻断 bluecode 进程
- **N2**:事件分派接口必须支持取消信号——拦截事件下同步等待、async 后台执行中线程中断都应及时退出,避免卡死 Agent.run
- **N3**:拦截事件下的同步 hook 串行执行,以单条 hook 的 timeout 累加;命令自身超时按 F18 处理,不再设全局上限
- **N4**:注入的 reminder 文本不入序列化对话历史、不参与 token 估算的"历史增长部分"(与 plan reminder 同语义)
- **N5**:only_once 内存集合放在 SessionRuntime 上,与 ActiveSkills 同生命周期;`/clear` 与 `/resume` 切换时清空
- **N6**:Hook payload JSON 序列化必须稳定字段顺序——key 按字母序,方便用户脚本对 JSON 直接 `grep`
- **N7**:扩展后的匹配器对权限规则与 Hook 条件共用同一实现,单元测试覆盖四种 type × 边界条件(空串、转义、嵌套 not、空 path)
- **N8**:subagent 占位日志输出固定格式 `[hook subagent] not yet implemented, skipped: <name>`,方便后续章节对接时文本搜索替换
- **N9**:hooks.yaml 文件不存在不报错;文件存在但整体 YAML 解析失败、顶层结构非法时打 stderr 但保持 bluecode 启动
- **N10**:HTTP 动作的请求体模板渲染失败按 hook 失败处理;模板默认只支持 `${field}` / `${nested.path}` 最基本字段访问,不开放函数调用

## 不做的事

- 不实现 subagent 动作的真实执行(仅占位日志),等后续章节对接 SubAgent 系统
- 不做 only_once 标记的跨进程持久化(重启进程后集合清空,hook 会重新触发一次)
- 不引入 hook 执行的显式优先级 / order 字段——加载层按 yaml 声明顺序自然有序
- 不做 hook 文件的热更新——加载在启动期一次完成,编辑文件后需重启 bluecode 才生效
- 不在 TUI 渲染 hook 触发的可视化轨迹(仅 stderr 日志)
- 不实现 hook 之间的依赖 / 互斥关系
- 不为 hook 提供独立日志文件、专属环境变量配置入口
- 不做 hook 失败的重试机制
- 不支持 hook 配置文件的 @include 或继承

## 验收标准

- **AC1**:写一份只含 `Bash(=git status)` 的精确规则到 `.bluecode/permissions.yaml`,启动后调用 `git status` 被该规则命中、调用 `git status -s` 不命中
- **AC2**:写一份 `Bash(~^npm (install|test)$)` 的正则规则,启动后调用 `npm install` 命中、`npm run dev` 不命中;写法非法(如未闭合括号、正则编译失败)启动期 stderr 打印 `rule "Bash(~..." parse failed: ...` 并跳过该条规则
- **AC3**:写一份 `Bash(!~^rm)` 的反向正则规则,调用 `rm -rf .` 不命中(以 rm 起头)、调用 `ls -lh` 命中(不以 rm 起头)
- **AC4**:在 `<projectRoot>/.bluecode/hooks.yaml` 写一条 PreToolUse hook——条件 `tool_name = write_file`,动作 `shell: "echo blocked >&2; exit 2"`;启动后 LLM 调用 write_file 工具时被拦截,tool_result 显示 `[hook <name>] blocked`,文件未被写入
- **AC5**:上面 AC4 的 hook 把动作命令改成 `exit 0`,再调用 write_file,hook 触发但放行,文件成功写入
- **AC6**:写一条 SessionStart hook——动作 `prompt: "用 zh-CN 回复"`;重启 bluecode 后首轮对话中 LLM reminder 区能看到该文本(通过调试通道观察),后续轮不再注入
- **AC7**:写一条 PostToolUse hook——条件工具名为 write_file 且 `is_error=false`,动作 `shell: "mvn -q spotless:apply -DspotlessFiles=\"$(jq -r .tool_input.path)\""`、async=true、timeout=5s;LLM 写一个 Java 文件后 spotless 异步在后台执行,主对话流不暂停;命令失败时 stderr 打印失败日志、Agent 不中断
- **AC8**:写一条 async + PreToolUse 的 hook,启动 bluecode 时 stderr 打印 `hook "<name>": async not allowed for blocking events, skipped` 并跳过该条
- **AC9**:写一条 only_once + PreUserMessage 的 hook,动作 `shell: "echo first-turn >&2"`;第一轮 PreUserMessage 时 stderr 出现 `first-turn`,后续轮不再出现;执行 `/clear` 进入新会话后下一轮再次出现 `first-turn`
- **AC10**:写一条 UserPromptSubmit hook——条件 prompt 正则匹配 `(?i)delete`,动作 `shell: "echo \"prompt contains delete keyword\" >&2; exit 2"`;用户在 TUI 输入"请帮我 delete 那个文件"时被拦截,输入框下方提示 `[hook <name>] prompt contains delete keyword`,消息未进入对话历史
- **AC11**:在 hooks.yaml 中写 `event: UnknownEvent`,启动后 stderr 打印 `hook "<name>": unknown event "UnknownEvent", skipped`,其余 hook 正常加载
- **AC12**:同时在用户级与项目级 hooks.yaml 各写一条 hook,启动后 `/hooks` 命令输出两条合并列表,末尾显示两个加载来源文件路径
- **AC13**:写一条 Stop hook——动作 `http: POST http://localhost:9999/done`;本地起一个 echo server,Agent.run 自然停止后该 server 收到一次 POST 请求且 body 含 `"event":"Stop"`
- **AC14**:写一条 PreToolUse hook——动作 `http: POST http://localhost:9999/check`;本地 server 对 Bash 工具返回 `{"decision":"block","reason":"network policy"}`,Bash 调用被拦截、其它工具不受影响
- **AC15**:写一条 SessionStart hook——动作 `subagent: agent_name=foo, prompt=test`;启动后 stderr 出现 `[hook subagent] not yet implemented, skipped: <name>`,Agent 主流程不受影响
- **AC16**:在 hook 的 `if` 中同时写 `all_of` 与 `any_of` 两个键,启动 stderr 报错跳过该条,其余 hook 加载正常
- **AC17**:tmux 内启动 bluecode,按 AC4 → AC6 → AC7 → AC10 顺序触发,整个过程不卡顿、无 panic(端到端见 checklist)

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