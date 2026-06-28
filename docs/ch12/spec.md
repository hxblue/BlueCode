```Markdown
# Hook 生命周期挂钩系统 Spec

## 背景

把可复用 SOP 搬出源码做成 Skill 包之后,BlueCode 在"用户怎么扩展行为"这条路径上还差最后一环:**在 Agent 生命周期的固定时刻自动跑一段用户配置的动作**。当前的扩展点都是显式触发——Skill 要 `/<name>` 唤起、Slash 命令要用户手敲。如果想做这种"触发条件明确、动作固定"的重复事,只能每次手动来:

- 写完文件想立刻 `mvn spotless:apply`,得手动跑或写监听脚本
- 想阻止 Agent 跑 `rm -rf` 之类的命令,权限规则要逐个加 deny
- 想在每轮用户提交前提醒 Agent "记得用 zh-CN",没现成机制
- 想在 Agent 长跑结束后给自己发个 IM 通知,要自己起进程

ch08 的权限引擎覆盖了"该不该允许工具调用",但**只在工具调用前判定一次、动作仅 Allow/Deny/Ask**,做不了命令格式化、上下文注入、外部通知这些副作用。Hook 系统补的是这条缝:在 Agent 生命周期的 11 个固定时刻挂自动化动作,把"触发条件明确、动作固定"的重复工作从人工变成机器。

设计上沿用 ch08 已有的权限匹配器做条件表达式底层——但需先把单一通配匹配扩展成"精确/反向/正则/glob"四种,让 Hook 条件、未来的权限规则共用同一套匹配语义。

## 目标

- **G1**:把 Agent 生命周期上的 11 个固定时刻抽象成事件总线,事件 emit 时同步驱动 Hook 引擎;现有内部事件(工具 Start/End、Compact、Approval)继续走 `Flow.Publisher<AgentEvent>` 主流,不受影响
- **G2**:用户用 YAML 文件声明式配置 Hook 规则,启动期一次性加载并校验,**配置错误立即报到 stderr 并跳过出错规则,不阻断进程**
- **G3**:每条 Hook 是"事件 + 条件 + 动作"三要素,条件可省略表示无条件触发
- **G4**:把 ch08 权限规则的匹配语法从单一通配扩展成"精确 exact / 反向 not / 正则 regex / glob"四种;Hook 条件表达式与扩展后的权限规则共用同一套匹配器
- **G5**:条件表达式支持嵌套字段访问,多条件用 `all_of` / `any_of` 二选一组合,**不允许嵌套混用**
- **G6**:在 PreToolUse 时刻,Hook 的 shell 动作通过 `exit code 2` 表达拦截、stderr 作为拒绝原因——被拒原因当 tool_result 回灌让模型调整;在 UserPromptSubmit 时刻同理拦截用户提交、原因回显到对话
- **G7**:四种动作类型——执行 shell 命令、注入提示词、发 HTTP 请求、启动子 Agent(**子 Agent 本期占位不实现,等后续章节对接**)
- **G8**:三种执行控制——only_once(同一会话内只跑一次)、async(异步后台执行不阻塞主流程)、timeout(命令最大执行时长);**拦截类事件(PreToolUse / UserPromptSubmit)不允许 async,加载期校验出错**
- **G9**:Hook 自身失败(命令非零退出、HTTP 超时、HTTP 解析错等)**只记日志、不中断 Agent 主流程**——除非该 hook 是同步拦截类且通过约定方式表达拦截信号

## 功能需求

### 权限匹配语法扩展(前置基础)

- **F1**:把权限规则 Pattern 形态从单一字符串扩展成结构化匹配类型 `{type, value}`,type 取 `exact`、`not`、`regex`、`glob` 之一;缺省类型沿用现有 glob 语义,保证向后兼容
- **F2**:规则 YAML 串语法升级——除 `Bash(rm *)` 这种"工具(简洁串)"写法保留代表 glob 类型外,新增显式类型前缀:
  - `Bash(=value)` 精确(整串相等)
  - `Bash(!inner)` 反向(对 inner 取反,inner 自身仍按规则解析,支持 `!=value`、`!~regex`、`!glob`)
  - `Bash(~regex)` 正则
  - `Bash(value)` 不带前缀沿用 glob 语义
- **F3**:精确匹配做整串相等比较;glob 沿用现有 wildcard / matchPath 实现;正则在加载期编译并缓存,编译失败按 F4 处理;反向是"任意其它类型的取反包装",支持嵌套(如 `Bash(!=value)`)
- **F4**:扩展后权限引擎的 Allow/Deny 判定语义不变,但规则解析失败原本静默跳过,现在改为"stderr 打印失败规则与原因、其余规则正常加载"
- **F5**:现有 ch08 的所有权限测试、既有的 `.bluecode/permissions.yaml` 用户配置(仅写 `Bash(git *)` 这种)必须继续工作,不破坏向后兼容

### Hook 配置文件

- **F6**:YAML 配置文件位置按以下顺序扫描,找到就加载、找不到就跳过:
  - 项目级:`<projectRoot>/.bluecode/hooks.yaml`
  - 用户级:`~/.bluecode/hooks.yaml`
- **F7**:两层规则**叠加合并**——所有规则共同参与事件分派;不存在"覆盖同名"概念,hook 的 name 仅用于日志和 only_once 跟踪;两层中出现同名 hook 时,加载期 stderr 提示冲突并跳过后到者
- **F8**:YAML 顶层结构:`hooks:` 数组,每条 hook 为对象,字段如下:
  - `name`(必填):字符串,用于日志、only_once 跟踪、冲突检测
  - `event`(必填):事件名,11 选 1(见 F9)
  - `if`(可选):条件表达式对象,省略表示无条件
  - `action`(必填):动作对象,含 `type` 与各类型独有字段
  - `only_once`(可选 bool,默认 false):会话内只跑一次
  - `async`(可选 bool,默认 false):是否后台异步执行
  - `timeout`(可选时长字符串如 `30s`,默认 30s):命令 / HTTP 最大执行时长

### 生命周期事件

- **F9**:11 个事件名及触发时机:
  - **SessionStart**:bluecode 启动初次进入会话或 `/clear` 新建会话后、env context 装配完毕、首条 user 消息进入对话历史**之前**
  - **SessionEnd**:进程关闭前、`/clear` 关闭旧会话前、`/resume` 切换离开旧会话前
  - **SessionResume**:`/resume` 选中历史会话、恢复完成、首条 user 消息进入**之前**
  - **UserPromptSubmit**:TUI 提交一条非 Slash 命令的 user 消息、写入对话历史**之前**——可拦截
  - **Stop**:Agent.run 自然停止后、`Done: true` 事件 emit 之前;取消、出错路径不触发
  - **PreUserMessage**:每轮 streamOnce 调 `provider.stream` 之前;payload 含当前 conversation 末尾的 user 消息
  - **PreToolUse**:executeBatched 对每条 tool call 准备执行**之前**、权限引擎 check 之**前**——可拦截
  - **PostToolUse**:单条 tool call 拿到 result 之后、emit PhaseEnd 之前;权限被 Deny 的也触发,payload.is_error=true
  - **PreCompact**:`CompactManager.manageContext` 调用之前(自动/紧急/手动三路径合并)
  - **PostCompact**:`CompactManager.manageContext` 返回后
  - **Notification**:权限 Ask 弹出审批时、Stream 返回 Failed 事件时
- **F10**:每个事件对应一份固定的 payload schema,作为 Hook 条件表达式与动作输入的数据源
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