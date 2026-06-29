```Markdown
# Agent Team Spec

## 背景

ch13 SubAgent 把任务从单 Agent 委派给子 Agent,实现了消息、权限账本、文件读缓存与 token 计数的隔离;ch14 Worktree 给每个子 Agent 配上独立工作目录,文件系统层并发也安全。但这两章合起来仍是「星型」拓扑——所有子 Agent 只能与主 Agent 通信,子 Agent 之间没有横向通道;主 Agent 既要决策、又要中转,既是大脑也是邮局。对「同时重构四个模块」「三个角度查同一个 bug」这类持续性、需要互相交流的工作,星型结构的瓶颈很明显。

本章把 bluecode 从星型升级到「网状」:

- 主 Agent 创建 **Team** 后升任 **Lead**,Team 是一个长期存在的小组对象,记名称、负责人、成员花名册、持久化位置
- 每个 **队员**(Teammate)是一个独立的 Agent 实例,有自己的 Conversation、自己的 Worktree
- 三种执行后端 `tmux` / `iterm2` / `in-process` 覆盖不同环境;按优先级一次性自动检测,启动后不静默回退
- 队员之间通过**共享任务列表**与**邮箱**直接通信,不必经过 Lead 中转;协作工具仅在 Team 上下文出现
- 队员可暂停可续写,自然停下后 session 留盘,Lead 调 `SendMessage` 会从磁盘恢复后继续指派
- Lead 可选启用 **Coordinator Mode**(独立于 Team,但典型场景一起用),双锁机制下剥夺 Write/Edit 工具,只保留调度、读类操作与 shell(用于 git merge)
- 收敛阶段由 Lead 用 Bash 跑 `git merge` 逐个合各队员的 worktree 分支,冲突由 LLM 推理解决,搞不定就 `git merge --abort` 保留 worktree 上报用户

bluecode 现有相关基础设施:
- ch13 `task.Manager` 已支持后台任务管理 + `sendMessage` 续派 + `AgentNameRegistry` (`byName` 字段已是 name → id 映射);本章扩展为多 Team 寻址
- ch13 `agent.AgentTool.execute` 已是子 Agent 启动入口,本章新增 `teamName` 参数走 Team spawn 分支
- ch13 工具过滤 `tool.applyAgentToolFilter` 已支持多层防线;本章新增 Team 专属白名单(协作工具)与 Coordinator Mode 白名单
- ch14 `worktree.Manager` 已支持嵌套 slug(`team/alice` → `.bluecode/worktrees/team+alice/`),本章直接复用做队员 worktree(slug 形式 `team-<teamName>/<member>`)
- ch12 session 持久化(`.bluecode/sessions/<id>/conversation.jsonl`)按对话粒度落盘;本章给每个队员单独申请一个 session,队员 stop 不删 session,SendMessage 续派时通过 session 反序列化 Conversation
- ch10 `dev.bluecode.command` slash 命令系统,本章新增 `/team` 系列
- ch07 `permission` 已支持 `plan` 模式,本章给 `planModeRequired` 队员的 Plan 提交-Lead 审批工作流套用同一引擎

本章**只做**到「Lead 多人协作 + Plan 审批 + Coordinator 收敛」。跨进程跨机器分布式团队、队员之间实时流式通信、复杂任务依赖约束(优先级 / deadline)、Windows 平台 iTerm2 适配均不在范围内。

## 目标

- **G1**: 提供 `Team` 与 `TeamManager`——Team 封装小组生命周期(name、leadAgentId、members、configPath);Manager 在单 bluecode 进程内管理多个 Team(典型场景同时只有一个活跃 Team)
- **G2**: 提供 `TeamCreate` 工具——主 Agent 调用即创建 Team、调 `detectBackend` 确定后端、写 `~/.bluecode/teams/<sanitizedName>/config.json`、把 Lead 注册成第一个成员;同名团队自动后缀 `-2` / `-3` 避免冲突
- **G3**: 扩展 `Agent` 工具——增加 `teamName` 可选参数,非空时走 Team spawn 分支:加载定义 → 创建队员 Worktree → 注入协作工具 → 按后端分流 spawn → 注册到 `AgentNameRegistry` → 写入 `team.members`
- **G4**: 提供 `TeamDelete` 工具——确认所有成员 `isActive=false` 后,删队员 worktree + 删 team 目录,Lead 退出团队;有活跃成员时拒绝删除
- **G5**: 三种执行后端 `tmux` / `iterm2` / `in-process`,统一抽象 `Backend` 接口;`detectBackend` 按 `$TMUX → $TERM_PROGRAM=iTerm.app && which it2 → which tmux → in-process` 优先级一次性决定,不做运行时回退
- **G6**: 队员注入 5 个协作工具 `TaskCreate` / `TaskGet` / `TaskList` / `TaskUpdate`(后者支持 `addBlocks` / `addBlockedBy` 依赖字段) / `SendMessage`;主 Agent 与普通 SubAgent 看不到这些工具
- **G7**: `SendMessage` 寻址支持 `to="<name>"`、`to="<agentId>"`、`to="*"` 广播三种;通过 `AgentNameRegistry` 解析 name → agentId,写邮箱;Tmux/iTerm2 后端额外通过 `send-keys` 唤醒目标 pane
- **G8**: 邮箱文件并发安全——每个收件人独占一个 lock 文件(`StandardOpenOption.CREATE_NEW`),抢锁失败按 5-100ms 随机抖动重试,最多 10 次;持锁超过 10 秒视为 stale 直接清掉;消息文件 read-modify-write,走 `Files.move(...,ATOMIC_MOVE)` 原子替换
- **G9**: 三种结构化消息——纯文本(必带 5-10 词 `summary`)、`shutdown_request` / `shutdown_response`(优雅退出协商)、`plan_approval_response`(Plan 审批回复,只允许 Lead 发送);全部走同一 SendMessage 入口,以 `type` 字段分流
- **G10**: 队员收到的未读消息在下一轮 Agent Loop 开头被读出,以 `<incoming-messages>` system reminder 形式注入到 LLM 输入;读后批量标记为 read
- **G11**: 队员 spawn 两种路径——指定 `subagentType` 走定义式(从空白对话起步)、留空走 Fork 路径(继承 Lead 完整对话历史);Fork 路径受 `FORK_TEAMMATE` feature flag 控制,默认关闭
- **G12**: 队员 `runToCompletion` 结束后自动通知 Lead——团队 config 里把该成员 `isActive=false`、Lead 邮箱收到 `idle_notification`;队员的 Conversation 已通过 ch12 Writer 实时写入 session 文件
- **G13**: 队员续写——Lead 调 `SendMessage(to="alice", message="…")`,系统检测 alice 已 stop 时,从 ch12 session 反序列化 Conversation、新建一条 virtual thread 走 `runToCompletion(initialMessage=newMessage)`;Conv 沿用历史
- **G14**: `planModeRequired:true` 的队员被 spawn 时强制以 plan 模式起步——计划生成后通过 SendMessage 发给 Lead,Lead 用 `plan_approval_response` 回复 approve 或 reject;approve 时队员权限模式切到 Lead 的当前模式继续执行,reject 时队员按 feedback 调整后重新提交
- **G15**: Coordinator Mode 独立于 Team——`Coordinator.isEnabled() = feature(COORDINATOR_MODE) && envTruthy(MEWCODE_COORDINATOR_MODE)`,两把锁全开才生效;开启后 Lead 工具集收窄到 `Agent / TeamCreate / TeamDelete / TaskCreate / TaskGet / TaskList / TaskUpdate / SendMessage / read_file / glob / grep / bash`(剥夺 `write_file` / `edit_file`),并注入 coordinator 系统提示词引导 Research / Synthesis / Implementation / Verification 四阶段
- **G16**: 收敛全部由 LLM 推理驱动——Lead 用 Bash 跑 `git merge worktree-team-<team>+<member> --no-ff -m "merge: <member>"` 逐个合,冲突由 Lead 用 Read / Edit / Bash 自行解决;搞不定就 `git merge --abort`,保留队员 worktree,把冲突上下文上报给用户
- **G17**: 提供 TUI slash 命令 `/team list` / `/team info <name>` / `/team delete <name>` / `/team kill <member>`,辅助用户人工介入
- **G18**: 与 ch04~ch14 既有功能协同——主 Agent 平时(未 TeamCreate)看到的工具列表不变;协作工具仅在 Team 上下文出现;ch13 后台任务 / AdoptRunning / SendMessage 续派路径保留,Team 队员的续派复用同一套底层 `TaskManager`

## 功能需求

### Team 数据结构与 Manager

- **F1**: `Team` 字段——`name`(原始名)、`sanitizedName`(经 `sanitize` 处理后用于路径)、`leadAgentId`、`members List<TeammateInfo>`、`configDir`(`<homeDir>/.bluecode/teams/<sanitizedName>/`)、`configPath`(`<configDir>/config.json`)、`createdAt Instant`、`backend BackendType`
- **F2**: `TeammateInfo` 字段——`name`(Lead 分配的队员名,Team 内唯一)、`agentId`(对应 `BackgroundTask.id`)、`agentType`(使用的 subagent 定义名;Fork 路径下为 `""`)、`model`(覆盖,空表 inherit)、`worktreePath`(绝对路径)、`branch`(对应 worktree 分支名)、`backendType`(可 per-member 不同)、`paneId`(tmux pane / iterm2 split id,in-process 为空)、`isActive Boolean`(`null` 或 `true` 表活跃,`false` 表空闲;终止后直接从 `members` 移除)、`planModeRequired boolean`、`sessionDir`(队员独立 session 目录绝对路径)
- **F3**: `TeamManager` 字段——`lock ReentrantLock`、`teams Map<String,Team>`(按 `sanitizedName` 索引)、`homeDir`(`System.getProperty("user.home")`)、`worktreeManager`、`taskManager`、`registry AgentNameRegistry`
- **F4**: `TeamManager(Path homeDir, WorktreeManager wt, TaskManager taskMgr, AgentNameRegistry reg)`——校验 `<homeDir>/.bluecode/teams/` 可写;扫描该目录还原 `teams` map(每个子目录读一次 `config.json`,跳过解析失败的并 stderr 警告)
- **F5**: `TeamManager.create(name, agentType)`——
  1. `sanitized = sanitize(name)`(只保留 `[a-zA-Z0-9._-]`,其他替换为 `-`,首尾去 `-`,空字符串拒绝)
  2. 同名冲突时在 `sanitized` 后追加 `-2` / `-3` 直到唯一
  3. 创建 `configDir`,落 `config.json`(原子写)
  4. 调 `detectBackend()` 写入 `team.backend`
  5. 取当前 Lead Agent id(本期 Lead = 主 Agent,固定 `"lead"`)
  6. 把 Lead 注册成第一个成员(`new TeammateInfo("lead","lead", null, ...)`,`isActive=null`)
  7. 加入 `teams` map,返回 Team
- **F6**: `TeamManager.get(name)`——按 sanitized name 查询,返回 `Optional<Team>`
- **F7**: `TeamManager.delete(name, force)`——
  1. 取 Team;不存在抛 `TeamNotFoundException`
  2. 非 force 时若有 `member.isActive != Boolean.FALSE`(包括 null 和 true)抛 `TeamHasActiveMembersException`
  3. 逐个删队员 Worktree(调 `worktreeManager.remove(name, new RemoveOptions(true))`,失败只警告不中断)
  4. 删队员 session 目录(`Files.walk(...).forEach(Files::delete)`,失败只警告)
  5. 删 `configDir`
  6. 从 `teams` map 移除
- **F8**: `Team.addMember(TeammateInfo info)`——校验 name 在 Team 内唯一;加入 `members`;持久化 `config.json`(原子写——先写 `.tmp` 再 `Files.move(...,ATOMIC_MOVE)`)
- **F9**: `Team.setMemberActive(name, active)`——更新 `isActive`,持久化
- **F10**: `Team.removeMember(name)`——从 `members` 移除,持久化

### 后端检测与抽象

- **F11**: `BackendType` 枚举,取值 `TMUX` / `ITERM2` / `IN_PROCESS`,带 `wireValue()` 返回 `"tmux"` / `"iterm2"` / `"in-process"`
- **F12**: `Backend` 接口——
  ```java
  public interface Backend {
      BackendType type();
      // spawn 在后端启动一个新队员;返回 PaneID(in-process 返回空)。
      // 对 Pane 后端,spawn 会执行 split-window / it2 split + send-keys 启动 CLI。
      // 对 in-process 后端,spawn 在同进程起一条 virtual thread 跑 runToCompletion。
      SpawnResult spawn(SpawnRequest req) throws IOException;
      // wake 用于消息到达时唤醒目标 pane。in-process 后端为 no-op。
      void wake(String paneId, String agentId) throws IOException;
      // kill 终止 pane(Pane 后端)或 cancel virtual thread(in-process)。
      void kill(String paneId, String agentId) throws IOException;
  }

  public record SpawnResult(String paneId, String agentId) {}
  ```
- **F13**: `SpawnRequest`(record)字段——`teamName`、`memberName`、`agentId`、`worktreePath`、`sessionDir`、`agentType`、`model`、`initialPrompt`、`planModeRequired`、`subAgent Object`(in-process 用,实际类型 `Agent`,用 Object 避免反向依赖)、`conv Object`(`Conversation`)、`taskManager Object`(`TaskManager`)
  - 对 Pane 后端(tmux / iterm2),`initialPrompt` **不**走命令行——在 `backend.spawn` 调用前由 `TeamManager.spawnTeammate` 预写入 alice 的 mailbox(类型 `text`,from `lead`),子进程启动后读 mailbox 自然拿到。这样避免长 prompt 在命令行里 shell-quote 的边界问题。
- **F14**: `Backend.detect()`——按以下优先级一次性决定:
  1. `System.getenv("TMUX") != null` → `TMUX`
  2. `"iTerm.app".equals(System.getenv("TERM_PROGRAM"))` && PATH 中存在 `it2` → `ITERM2`
  3. PATH 中存在 `tmux` → `TMUX`(外部 spawn 新 session)
  4. 否则 → `IN_PROCESS`

### tmux 后端

- **F15**: `TmuxBackend` 实现 `Backend` 接口
  - `spawn`:`tmux split-window -h -P -F "#{pane_id}" -- <cmd>`(横向 split,-P 打印 pane id,-F 指定格式);`cmd` 为 `bluecode --team-member --team <teamName> --member <memberName> --agent-id <agentId> --session-dir <sessionDir> --worktree <worktreePath> [--agent-type <type>] [--model <model>] [--plan-mode]`
  - `--agent-id` 是关键:Lead spawn 时已生成的 agentId 直接传给子进程,子进程不需要读 Lead 还没写完的 `config.json` 找自己
  - `wake`:`tmux send-keys -t <paneId> "" Enter`(回车触发子进程 stdin scanner 读到一行,立刻去 mailbox 轮询;in-process 后端无此动作)
  - `kill`:`tmux kill-pane -t <paneId>`(忽略 pane 不存在错误)
- **F16**: 若当前在 tmux 会话外但本机有 tmux,spawn 走 `tmux new-session -d`(detached 新 session);若失败回落到错误而非 in-process(不静默回退)

### iterm2 后端

- **F17**: `Iterm2Backend` 实现 `Backend` 接口
  - `spawn`:`it2 split --new-pane --command "<cmd>"`,`<cmd>` 与 F15 同构(含 `--agent-id`);通过 `it2` CLI 解析输出取 pane id
  - `wake`:`it2 send-text --pane <paneId> ""`(空文本即唤醒)
  - `kill`:`it2 close-pane --pane <paneId>`

### in-process 后端

- **F18**: `InProcessBackend` 实现 `Backend` 接口
  - `spawn`:复用 `TaskManager.launch`——创建带 `withCwd(worktreePath)` 的子 Agent,在 virtual thread 里跑 `runToCompletion`;返回空 `paneId`,内部用 `BackgroundTask.id` 关联
  - `wake`:no-op(同进程,下一轮 Loop 自动读邮箱)
  - `kill`:调 `TaskManager.stop(agentId)`
- **F19**: in-process 后端的队员**只允许同步子 Agent**——其 `Agent` 工具看不到 `teamName` 参数(`teamName` 被拦截);后台子 Agent 也禁用(过滤 `runInBackground=true`)

### Pane 后端子进程的 team-member 模式

- **F19a**: `bluecode --team-member` 在 Pane 后端被 spawn 的 bluecode 子进程**不启动 TUI**,而是跑一个自治循环(`dev.bluecode.cli.TeamMemberRunner` 的 `run` 方法):
  1. 从 CLI 解析 `--team / --member / --agent-id / --session-dir / --worktree / --agent-type / --model / --plan-mode`(用 picocli 或 Apache Commons CLI 解析,本项目选 picocli `info.picocli:picocli`)
  2. `System.setProperty("user.dir", workTree)` + `Path.of(workTree).toAbsolutePath()` 作为后续所有 IO 的根;让该进程的 `Path.of("").toAbsolutePath()` 与权限沙箱根都指到 worktree
  3. 构造**单独的** `TeamManager`、provider、registry、permission engine、hook engine(完整复用 Lead wire 代码,但不构造 TUI)
  4. 构造队员 `Agent`,设 `dontAsk=true`(子进程无 TUI 接 ApprovalRequest)、注入 `<team-context>` reminder、用 `setCtxDecorator` 注入 `TeammateContext`(含 mailbox client)
  5. 启动 stdin scanner virtual thread:任何来自 tmux send-keys 的回车都推到 `wakeQueue`(`SynchronousQueue<Object>` 或 `BlockingQueue` size=1),触发立刻去 mailbox 轮询(0~2s 内响应)
  6. 进入主循环:
     - 读 `mailbox.readUnread(agentId)`
     - 空 → 阻塞 `wakeQueue.poll(2, SECONDS)` 兜底轮询
     - 有未读:`text` 拼成 task,`plan_approval_response(approve=true)` 触发 `setPermissionMode(DEFAULT)` + 续派 prompt,`shutdown_request` 触发优雅退出
     - 调 `agent.runToCompletion(conv, task, eventConsumer)` 让队员跑到底
     - 完成后:写 `summary="<name> idle"` 到 Lead mailbox,再 `Team.setMemberActive(name, false)`
     - 检测到 mailbox 目录已被删除(Lead 调用 `/team delete`)→ 优雅退出
- **F19b**: 该自治循环的最小事件转 stdout 打印:`TextEvent` 直接 `System.out.println`、`ToolEvent` 打 `● tool(args)` 行、`DoneEvent` 打分隔横线、错误打 stderr。pane 内 UX 是只读的「日志流」,不接受用户输入(任何回车都被 stdin scanner 消费做 Wake 信号)
- **F19c**: 跨进程 `config.json` 写入并发:Lead 与子进程是不同进程,各持一份内存中的 Team 对象。`Team.addMember` 与 `Team.setMemberActive` 在持锁后**先从磁盘 reload `members` 字段**再修改+原子 save(`reloadFromDiskLocked`)。否则会出现「子进程内存看不到自己,setMemberActive 静默 no-op」的丢更新问题

### TeamCreate 工具

- **F20**: 工具名 `TeamCreate`,参数 schema:
  - `teamName`(string,必填):团队名,经 sanitize 后做 `Team.sanitizedName`
  - `description`(string,可选):团队描述,写入 `config.json` 的 `description` 字段
  - `agentType`(string,可选):本期保留位,实际不使用
- **F21**: `TeamCreate.execute`——
  1. 解析参数
  2. 调 `TeamManager.create(name, agentType)` 创建 Team
  3. 返回 JSON `{"teamName":"<sanitized>","backend":"<type>","configPath":"<path>"}`
  4. Lead 创建 Team 后保持原有工具集(非 Coordinator Mode 下不剥夺工具)

### TeamDelete 工具

- **F22**: 工具名 `TeamDelete`,参数 `teamName`(必填)、`force`(可选 boolean)
- **F23**: `TeamDelete.execute`——调 `TeamManager.delete(name, force)`,返回成功/失败消息

### Agent 工具扩展 (teamName)

- **F24**: `Agent` 工具参数 schema 新增字段:
  - `teamName`(string,可选):非空时走 Team spawn 分支
- **F25**: 当 `teamName` 非空,`Agent.execute` 走 Team 分支:
  1. 校验 `teamName` 对应的 Team 存在(`TeamManager.get`),否则报错
  2. 校验当前调用者权限:
     - 主 Agent / Lead → 允许
     - in-process 队员调 Team spawn → 拒绝(抛 `InProcessTeammateNoSpawnException`)
     - Pane 队员可以调(README:Pane 队员拥有完整 Agent 工具),但 `teamName` 参数被屏蔽(队员不能往 Team 加人,只 Lead 在 Coordinator Mode 或普通 Lead 调用时可以)
  3. 加载 `SubAgentDefinition`(指定 `subagentType` 走 Catalog;留空且 `FORK_TEAMMATE` 开启走 Fork 定义;留空且 flag 关闭则用 `general-purpose`)
  4. 调 `worktreeManager.create("team-"+sanitized+"/"+memberName, "HEAD", false)` 创建 Worktree
  5. 申请新 session 目录(复用 `session` 包接口),作为 `sessionDir`
  6. 构造 in-process 子 Agent(若后端为 in-process)或仅构造 SpawnRequest(若 Pane 后端);把协作工具注入到子 Agent 的 allowed tools 集合
  7. 注入队员系统提示词附录(F39)
  8. 注入 `<team-context>` initial system reminder 到子 Agent Conv
  9. **若是 Pane 后端**,在 `backend.spawn` 之前把 `initialPrompt` 作为 `text` 消息(`from=lead, summary=initial task`)预写入 alice 的 mailbox(F13);in-process 后端不需要,`initialPrompt` 直接作为 `TaskManager.launch` 的 task 参数
  10. 调 `Backend.spawn(req)` spawn,记 `paneId`
  11. 注册到 `AgentNameRegistry`:`memberName → agentId`
  12. 构造 `TeammateInfo` 加入 `team.members`,持久化(F19c 的 reload-before-modify 兜底)
  13. 返回 JSON `{"memberName":"<name>","agentId":"<id>","worktree":"<path>","backend":"<type>","paneId":"<id 或空>"}`

### 协作工具

- **F26**: `TaskCreate` 工具——参数 `title`(必填)、`description`(可选)、`assignee`(可选,队员名)、`blockedBy`(可选 `List<String>`,任务 id);返回新建 `taskId`(`task_<6位 hex>`);写入 Team 的 `tasks.json`(原子)
- **F27**: `TaskGet` 工具——参数 `taskId`,返回任务详情
- **F28**: `TaskList` 工具——参数可选 `status` 过滤(`pending`/`in_progress`/`completed`/`blocked`);返回任务数组,带依赖关系标注(`blockedBy`、`blocks`、是否 `isReady`(无未完成 blocker))
- **F29**: `TaskUpdate` 工具——参数 `taskId`(必填)、`title`(可选)、`description`(可选)、`status`(可选)、`assignee`(可选)、`addBlocks`(可选 `List<String>`)、`addBlockedBy`(可选 `List<String>`)、`removeBlocks` / `removeBlockedBy`(可选 `List<String>`);更新后持久化
- **F30**: `tasks.json` 结构:
  ```json
  {
    "tasks": [
      {
        "id": "task_a1b2c3",
        "title": "...",
        "description": "...",
        "status": "pending",
        "assignee": "alice",
        "blockedBy": ["task_xxx"],
        "blocks": ["task_yyy"],
        "createdAt": 1234567890,
        "updatedAt": 1234567890
      }
    ]
  }
  ```
  写入走 `<teamConfigDir>/tasks.json`,read-modify-write,文件锁 `tasks.lock`(同邮箱 lock 机制)

### SendMessage 工具与邮箱

- **F31**: `SendMessage` 工具——参数:
  - `to`(string,必填):队员名 / agentId / `"*"` 广播
  - `summary`(string,纯文本消息时必填,5-10 词)
  - `message`(string,可选,纯文本消息体)
  - `type`(string,可选,默认 `"text"`):取值 `"text"` / `"shutdown_request"` / `"shutdown_response"` / `"plan_approval_response"`
  - `payload`(object,可选):结构化消息的载荷(如 `shutdown_response` 的 `{approve, reason}`)
- **F32**: 邮箱文件路径——`<teamConfigDir>/mailbox/<agentId>.json`,结构:
  ```json
  {
    "messages": [
      {
        "from": "lead",
        "to": "alice",
        "type": "text",
        "summary": "interface change",
        "content": "...",
        "payload": null,
        "timestamp": 1234567890,
        "read": false
      }
    ]
  }
  ```
- **F33**: `Mailbox` 提供 `write(agentId, msg)` / `read(agentId)` / `markRead(agentId, indices)` 接口
  - `write`:抢 `<teamConfigDir>/mailbox/<agentId>.lock`(`Files.newOutputStream(..., StandardOpenOption.CREATE_NEW)`),失败 5-100ms 随机抖动重试 10 次;持锁超 10 秒视为 stale(`Files.getLastModifiedTime` 判定)直接删 lock 重试;成功后 read-modify-write,`Files.move(tmp, target, ATOMIC_MOVE)` 原子替换
  - 广播 `to="*"` 时,write 对 Team 内除发件人外所有成员的 mailbox 各 write 一次
- **F34**: `SendMessage.execute`——
  1. 校验调用者在 Team 内
  2. 解析 `to`:若 `"*"` 走广播;否则通过 `AgentNameRegistry.resolve(to)` 取 agentId(name 优先,失败按 agentId 直查);解析不到报错
  3. `plan_approval_response` 仅 Lead 可发,否则报错
  4. `shutdown_response` 只能发给 Lead,否则报错
  5. 调 `Mailbox.write`
  6. 取目标的 `backendType` 与 `paneId`,若是 Pane 后端调 `backend.wake(paneId, agentId)`
  7. 若目标 agentId 已 stop(in-process 后端):触发续写(F45)
  8. 返回 `{"deliveredTo":["<agentId>"],"timestamp":<ts>}`

### Agent 名称注册表

- **F35**: `AgentNameRegistry` 字段——`lock ReentrantLock`、`byName Map<String,String>`(name → agentId)、`byId Map<String,String>`(agentId → name,反查)
- **F36**: 接口 `register(name, agentId)`、`unregister(name)`、`resolve(nameOrId)` 返回 `Optional<String>`、`nameOf(agentId)` 返回 `Optional<String>`
- **F37**: 注册时机——`Agent` 工具 spawn 队员时(F25 step 11);`AgentTool` 的 `name` 参数非空时(ch13 已有,本章统一这套 registry,替换 `TaskManager.byName` 的内部 map)
- **F38**: 命名冲突——后注册的覆盖前注册的(README 称「弱引用,后启动覆盖前面的弱引用」)

### 队员系统提示词附录

- **F39**: 在子 Agent 的 systemPrompt 后追加(若 spawn 进 Team)以下文本(无变量):
  ```
  IMPORTANT: You are running as an agent in a team.
  Just writing a response in text is not visible to others
  on your team - you MUST use the SendMessage tool.
  The user interacts primarily with the team lead.
  Your work is coordinated through the task system
  and teammate messaging.
  ```
- **F39a**: 所有 Team 队员(三种后端共有)一律以 `dontAsk=true` 启动,**覆盖角色定义里的 `permissionMode`**。理由:队员没有可交互的 TUI 接 `ApprovalRequest`(in-process 走 TaskManager 聚合事件不响应、Pane 子进程更没有 TUI),Ask 工具会无人应答地永远阻塞。队员的安全边界由 allowed 工具集 + Worktree 隔离 + Plan 模式控制,不靠逐次 ask 弹窗(子进程没人在看)。
- **F40**: 在 spawn 时把 `<team-context>` 注入子 Conv 的首条 system reminder:
  ```
  <team-context>
  team: <teamName>
  你的成员名: <memberName>
  你的 agentId: <agentId>
  worktree 目录: <worktreePath>
  当前团队成员: <name1>(<role1>), <name2>(<role2>) ...
  </team-context>
  ```

### 邮箱读取与消息注入

- **F41**: 子 Agent 的 Loop 在每轮请求 LLM **之前**先调 `Mailbox.read(agentId)`;若有未读消息,构造 `<incoming-messages>` system reminder 追加到本轮请求的 systemReminders,然后调 `markRead`
- **F41a**: Lead 侧不通过 ctx hook 自动读 mailbox(Lead 没有 `TeammateContext`),而是由 TUI 在初始化时启动后台 virtual thread `consumeLeadMail`(实现于 `dev.bluecode.tui.LeadMailWatcher`):
  - 每秒调 `TeamManager.pollLeadMailboxes()`,遍历所有 Team 的 `<configDir>/mailbox/lead.json` 读未读消息,标 read,返回 `List<LeadMessage>`
  - 把这批消息渲染成 `<team-update>` reminder(与 `<incoming-messages>` 不同,Lead 视角语义更清晰;消息内容截断上限 8000 字符,允许队员的完整报告完整透传),调 `runtime.appendReminders(...)` 推到 `pendingReminders`
  - **同时**往 `leadMailQueue`(`LinkedBlockingQueue` capacity=1)`offer` 一个信号(非阻塞,buffer=1 合并掉重复)
  - Lead 下一轮 Run 迭代头部 `buildReminder` 自动取出。**Lead 即便正在长 Run 中也能中途惊醒**——下一个 LLM 调用前就会看到队员更新
  - 这是 Pane 后端队员通知 Lead 的关键路径:in-process 队员还有 `TaskManager.subscribeDone` → TUI `<task-notification>` 的额外路径,但 Pane 队员只能靠 mailbox + 本机制
- **F41b**: Lead idle 时的自动续推。TUI 通过 `LeadMailWaiter`(订阅 `Flow.Publisher`)阻塞在 `leadMailQueue` 上,收到信号后通过 GUI thread 提交 `LeadMailEvent`:
  - 若 `model.state == SessionState.IDLE`,调 `beginAutonomousTurn`:合成一条 user 消息 `"[team-update] 队员发来新消息,请按 Coordinator 流程处理..."` 加入对话历史(用户在 scrollback 也看得见,清楚是系统通知触发而非自己输入),然后走 `beginTurn` 启 Run
  - 若 `model.state` 非 idle(`STREAMING`/`APPROVING`):reminder 已经在 `pendingReminders` 里,Lead 当前 Run 的下一轮迭代头部自然取出,不需要主动 wake
  - 末尾 re-arm `LeadMailWaiter` 让后续信号也能接住
  - 这避免了「队员都 idle 了,Lead 在 IDLE 等用户输入,reminder 静默积累没人取」的卡死场景——这正是 ch15 协作 UX 的关键
- **F42**: `<incoming-messages>` 格式:
  ```
  <incoming-messages>
  收到 N 条新消息:
  [1] 来自 <from>(type=<type>,ts=<时间>): <summary>
      <content 前 200 字>
  [2] ...
  </incoming-messages>
  ```
- **F43**: 收到 `shutdown_request` 时,队员可在下一轮自主选择回复 `shutdown_response(approve=true)` 然后停止,或 `approve=false` 拒绝并附 reason(LLM 决策,不强制)
- **F44**: 收到 `plan_approval_response(approve=true)` 时,队员的权限模式自动切换到 Lead 当前模式(从 Team config 取);`approve=false` 时队员根据 `feedback` 调整重新发 Plan

### 队员空闲与续写

- **F45**: 队员 `runToCompletion` 自然结束时(`TaskManager.runTask` 完成路径):
  1. 调 `Team.setMemberActive(memberName, false)`
  2. 给 Lead 邮箱写一条 `idleNotification`(`type="text", summary="<member> idle", content="agent <id> finished work, available for new tasks"`)
- **F46**: SendMessage 检测到目标 agentId 已 stop 且为 in-process 队员(`BackgroundTask.status` 不是 `RUNNING`):
  1. 从 `TeammateInfo.sessionDir` 反序列化 Conversation(`Session.load`)
  2. 调 `TaskManager.sendMessage(parentCtx, name, message)` 复用 ch13 已有续派接口
  3. `TaskManager.sendMessage` 重置 `status=RUNNING`,起新 virtual thread 跑 `runToCompletion(newMessage)`
  4. 续派前调 `Team.setMemberActive(memberName, true)`
- **F47**: Pane 后端队员的续写——SendMessage 写邮箱后,目标 pane 内的 bluecode 实例下一轮 Loop 自然读到消息;若 pane 已死(`tmux list-panes` 查不到 `paneId`),报错让 Lead 决定是否重新 spawn

### Plan 审批工作流

- **F48**: `Agent` 工具 spawn 队员时,若 `planModeRequired=true`(来自 SubAgentDefinition 的新字段或 spawn 参数),把子 Agent 的初始 `Permission.Mode` 设为 `PLAN`
- **F49**: 队员在 plan 模式下生成 Plan 后(通过常规 LLM 推理),用 `SendMessage(to="lead", type="text", summary="plan ready", content="<plan text>")` 发给 Lead——本期不强制结构化 Plan 类型(Lead 自行识别)
- **F50**: Lead 用 `SendMessage(to="<member>", type="plan_approval_response", payload={"approve":true|false,"feedback":"..."})` 回复
- **F51**: 队员收到 `plan_approval_response`:
  - `approve=true`:从 Team config 读 Lead 当前 `permissionMode`(本期固定 `DEFAULT`),切到该模式继续执行 plan
  - `approve=false`:把 `feedback` 当作新的用户消息加入对话,重新进入 plan 模式

### Coordinator Mode

- **F52**: 提供 `Coordinator.isEnabled()` 静态方法:
  ```java
  public static boolean isEnabled(AppConfig cfg) {
      if (!Feature.has("COORDINATOR_MODE", cfg)) {
          return false;
      }
      return envTruthy(System.getenv("MEWCODE_COORDINATOR_MODE"));
  }
  ```
  `Feature.has` 通过 `dev.bluecode.config` 读 `features.coordinatorMode` 字段;`envTruthy` 接受 `"1"` / `"true"` / `"yes"`(大小写不敏感)
- **F53**: Coordinator Mode 允许工具白名单常量:
  ```java
  public static final List<String> ALLOWED_TOOLS = List.of(
      "Agent", "TeamCreate", "TeamDelete",
      "TaskCreate", "TaskGet", "TaskList", "TaskUpdate",
      "SendMessage",
      "read_file", "glob", "grep", "bash"
  );
  ```
- **F54**: Lead 启动时(`tui` 主循环创建 Agent 后),若 `Coordinator.isEnabled(cfg)`:
  1. 把 Lead 的 allowed tools 设为 `Coordinator.ALLOWED_TOOLS`(调 `Agent.setAllowedTools` 已有接口)
  2. 在 systemPrompt 后追加 coordinator 提示词(F55)
  3. TUI 状态栏显示 `[COORDINATOR]` 模式标签
- **F55**: Coordinator 系统提示词追加在 systemPrompt 末尾,核心是「四阶段 + 派完不许自己干」纪律。最终文案见 [src/main/java/dev/bluecode/coordinator/Coordinator.java:SYSTEM_PROMPT_SUFFIX](../../src/main/java/dev/bluecode/coordinator/Coordinator.java),关键约束:
  - **派完队员就停手等汇报**:派出 Agent / SendMessage 后**禁止**立刻调 read_file / glob / grep / bash 自己探索;**禁止**用 sleep / TaskList 轮询凑时间。`TaskManager` 完成时自然推送 `<task-notification>` reminder,Lead 下一轮被唤醒后再继续
  - 唯一该做的事:发一行总结「已派 N 名队员探索 X,等结果」,让本轮结束
  - 允许自己用 read_file/glob/grep 的场景仅限:Research 第一次目标定位;Synthesis 阶段读**队员产出的报告文件**;Verification 阶段 git diff / git status 等收敛操作

  这段纪律是为了对抗「LLM 派完队员后等不及自己 glob 代码库重复劳动」的常见行为——纯 prompt 引导,不强制(LLM 偶尔仍会越线,弱模型尤甚)。

### 收敛阶段

- **F56**: 收敛由 LLM 推理驱动,**不提供专门的 merge 工具**——Lead(无论是否 Coordinator Mode)在所有任务 `completed` 后,自主用 Bash 跑:
  ```bash
  git merge worktree-team-<sanitizedTeam>+<member> --no-ff -m "merge: <member>"
  ```
- **F57**: 冲突解决也由 Lead 推理——Lead 用 `read_file` 看冲突文件、`edit_file`(非 Coordinator Mode)或 `bash`(Coordinator Mode)写入解决方案、`bash` 跑 `git add` + `git commit`
- **F58**: 回滚——Lead 判断搞不定时,自主调 `bash` 跑 `git merge --abort`,然后给用户报告冲突文件 + 队员 worktree 路径;**不删队员 worktree**

### TUI Slash 命令

- **F59**: `/team list`——遍历 `TeamManager.teams`,每行 `<name>  <backend>  <memberCount> 成员  [<active>/<total>] 活跃`
- **F60**: `/team info <name>`——展示 Team 详情:配置路径、各成员的 name/agentId/backend/worktreePath/isActive/任务计数
- **F61**: `/team delete <name> [--force]`——调 `TeamManager.delete(name, force)`
- **F62**: `/team kill <member>`——查到 member 所属 Team,调对应 backend.kill,然后 `removeMember`

### 持久化与恢复

- **F63**: `~/.bluecode/teams/<sanitizedName>/config.json` 结构:
  ```json
  {
    "name": "...",
    "sanitizedName": "...",
    "leadAgentId": "lead",
    "backend": "tmux",
    "description": "",
    "createdAt": 1234567890,
    "members": [
      {
        "name": "alice",
        "agentId": "agent-a1b2c3d",
        "agentType": "worker",
        "model": "",
        "worktreePath": "/abs/path/.bluecode/worktrees/team-foo+alice",
        "branch": "worktree-team-foo+alice",
        "backendType": "tmux",
        "paneId": "%5",
        "isActive": null,
        "planModeRequired": false,
        "sessionDir": "/abs/path/.bluecode/sessions/<id>"
      }
    ]
  }
  ```
  所有写操作原子(先写 `.tmp` 再 `Files.move(..., ATOMIC_MOVE)`),受 `Team.lock` 保护。**跨进程**(Pane 后端)下,Lead 与子进程是不同进程的不同 Team 内存对象——`addMember` 与 `setMemberActive` 在持锁后**先 `reloadFromDiskLocked` 重读 disk members**再改写+ atomic save(F19c)
- **F64**: bluecode 启动时(`new TeamManager(...)`)扫描所有 Team 目录:
  - 解析 `config.json`,失败的目录跳过并 stderr 警告
  - **不**自动恢复 in-process 队员(进程重启后 in-process 队员状态丢失,isActive 视为 false)
  - Pane 队员根据 `paneId` 探测后端是否仍在(`tmux has-session` / `it2 list-panes`),不在的 isActive 标 false
- **F65**: 队员 session 沿用 ch12 session 持久化机制,路径 `<projectRoot>/.bluecode/sessions/<id>/conversation.jsonl`;Team 删除时一并删除
- **F66**: `TeamManager.delete(name, force=true)` 步骤(顺序重要):
  1. 持锁,校验 `force` 或全员 isActive=false
  2. 对每个非 lead 成员:用 `BackendFactory.create` 解析其 `backendType` 拿 `Backend` 实例,调 `backend.kill(paneId, agentId)` 杀掉 pane(tmux/iterm2)或 cancel virtual thread(in-process);Pane 子进程检测到 mailbox 目录消失会自行优雅退出兜底
  3. 调 `cleanupMemberResources` 删 session 目录与 worktree
  4. 递归删 `team.configDir` 整个 Team 目录
  5. 从 Manager 的 in-memory map 移除

## 非功能需求

- **N1**: 主 Agent 平时(未 TeamCreate)看到的工具列表保持稳定——`TeamCreate` / `TeamDelete` 总是可见;`Agent` 工具的 `teamName` 参数对模型可见但仅在调用时校验
- **N2**: 协作工具(TaskCreate 等)仅在队员上下文出现,主 Agent 与普通 SubAgent 看不到——通过 `applyAgentToolFilter` 在 spawn 时收窄
- **N3**: 邮箱写入对所有后端共用一套并发安全机制(文件锁);in-process 多 virtual thread 写同一 mailbox 也由文件锁串行
- **N4**: 所有 Team 状态变更受 `Team.lock` 保护;Team 之间互不相关,各自一把锁;`TeamManager.lock` 仅保护 `teams` map
- **N5**: 后端 spawn / kill 调用不持 `Team.lock`(避免长锁);只在更新 `members` 时短暂持锁
- **N6**: 与 ch04~ch14 既有测试零破坏——`mvn test` 全绿
- **N7**: 中文友好——错误消息、TUI 输出、coordinator 提示词全部中文(对齐 bluecode 其他模块风格);代码注释中文
- **N8**: Coordinator Mode 一旦启用,Lead 不可在运行时解锁(避免 LLM 被注入后自行解锁);取消的唯一方式是退出 bluecode 重启
- **N9**: 权限沙箱(`dev.bluecode.permission.Sandbox`)允许写入项目根**之外**的 `/tmp` 与 macOS 真实路径 `/private/tmp` 作为系统临时目录白名单。理由:工具脚本和队员经常需要 `/tmp` 做中转文件,严格限定在项目根内会导致大量正常用法被沙箱误杀。这一开放对 file-class 工具(read_file / write_file / edit_file)生效;bash 走 exec-class 权限,本来就不受沙箱约束

## 不做的事

- 跨 bluecode 进程的 Team 共享(同一仓库同一时刻只支持一个 bluecode 实例操作活跃 Team)
- 跨机器分布式 Team
- 队员之间实时流式通信(走 mailbox 文件 + 轮询/Wake,不走 socket)
- 复杂任务依赖约束(优先级、deadline、SLA)
- 任务自动分配(Lead 与队员都靠 LLM 推理领任务,系统不做调度)
- 队员的细粒度资源限额(token 上限、超时硬限制)
- Plan 审批的结构化 Plan 类型(本期 Plan 文本就是 SendMessage content,Lead 自行识别)
- Windows 平台特殊适配(iTerm2 后端仅 macOS;tmux 在 WSL 可用但不保证;本期以 macOS / Linux 为主)
- Coordinator Mode 的运行时解锁与重新进入
- 跨 Team 寻址(SendMessage 只能在同一 Team 内寻址)
- 插件来源的 Team 后端

## 验收标准

- **AC1**: `new TeamManager(...)` 在 `~/.bluecode/teams/` 不存在时自动创建;已有时正确扫描子目录还原 `teams` map
- **AC2**: `TeamManager.create("refactor auth", "")` 把 `"refactor auth"` sanitize 为 `"refactor-auth"`,在 `~/.bluecode/teams/refactor-auth/config.json` 落地,`backend` 字段反映 `detectBackend` 结果
- **AC3**: 同名 Team 二次 create 自动后缀 `-2`,目录与 sanitizedName 都生效
- **AC4**: `TeamManager.delete(name, false)` 在有 `isActive != Boolean.FALSE` 成员时抛 `TeamHasActiveMembersException`,目录仍在
- **AC5**: `TeamManager.delete(name, true)` 删 Worktree、删 session 目录、删 configDir
- **AC6**: `Backend.detect()` 在 `$TMUX` 设置时返回 `TMUX`;未设但 `$TERM_PROGRAM=="iTerm.app"` 且 `it2` 可执行返回 `ITERM2`;都无但 `tmux` 二进制在 PATH 返回 `TMUX`;否则 `IN_PROCESS`
- **AC7**: `Agent` 工具带 `teamName="<existing>"` 时,在 `.bluecode/worktrees/team-<sanitized>+<member>/` 落地 Worktree、调对应 `Backend.spawn` 并在 `team.members` 里出现该成员;不带 `teamName` 时维持 ch13 原行为
- **AC8**: in-process 后端队员的 `Agent` 工具调用 `teamName` 参数被拦截,抛 `InProcessTeammateNoSpawnException`
- **AC9**: 协作工具 `TaskCreate` / `TaskGet` / `TaskList` / `TaskUpdate` / `SendMessage` 在主 Agent 工具列表里**不**可见;在 Team 队员的工具列表里**可见**
- **AC10**: `TaskCreate` 落 `<teamConfigDir>/tasks.json`,`TaskUpdate(taskId, addBlockedBy=[id])` 正确更新双向 `blockedBy` / `blocks` 关系
- **AC11**: `TaskList(status="pending")` 返回的任务带 `isReady` 字段,反映其 `blockedBy` 是否全部 `completed`
- **AC12**: `SendMessage(to="alice", summary="hi", message="hello")` 在 `<teamConfigDir>/mailbox/<aliceAgentId>.json` 追加一条 unread 消息
- **AC13**: `SendMessage(to="*", ...)` 广播给 Team 内除发件人外所有成员;每人邮箱各得一条
- **AC14**: 并发 10 条 virtual thread 同时向同一 mailbox `write`,最终 10 条消息全部落盘且无丢失/无截断(集成测试)
- **AC15**: mailbox lock 文件 `Files.getLastModifiedTime` 超过 10 秒时,新的 write 会清掉旧 lock 并继续(集成测试)
- **AC16**: 队员 LLM 调用前,未读消息以 `<incoming-messages>` reminder 注入 systemReminders;调用后标记 read(单测断言)
- **AC17**: 队员 `runToCompletion` 自然结束后,`Team.config.json` 里该成员 `isActive=false`,Lead mailbox 收到 `summary="<member> idle"` 消息
- **AC18**: `SendMessage(to="alice", message="new task")` 当 alice 已 stop 时,从其 sessionDir 恢复 Conv 并续派(in-process 后端,`TaskManager` 状态从 CANCELLED/COMPLETED 回到 RUNNING)
- **AC19**: `Agent(teamName="t", subagentType="planner", planModeRequired=true, ...)` spawn 后,该队员初始权限模式为 `PLAN`
- **AC20**: Lead 发 `SendMessage(to="planner", type="plan_approval_response", payload={"approve":true})` 后,planner 队员下一轮权限模式切回 `DEFAULT`
- **AC21**: `Feature.has("COORDINATOR_MODE")=true` 且 `MEWCODE_COORDINATOR_MODE=1` 时,Lead 的 allowed tools 收窄为 `Coordinator.ALLOWED_TOOLS`,`write_file` / `edit_file` 不在其中;TUI 状态栏显示 `[COORDINATOR]`
- **AC22**: Coordinator Mode 关闭时,Lead 工具列表与 ch13 一致(`write_file` / `edit_file` 可见)
- **AC23**: tmux 后端 spawn 后,`tmux list-panes` 看到新 pane,pane 内 bluecode 实例启动并连接到该 Team
- **AC24**: tmux 后端 `wake(paneId)` 通过 `tmux send-keys` 触发目标 pane 输入(集成测试可观察 pane 内容)
- **AC25**: in-process 后端队员与主 Agent 在同一进程内运行,共享 `TaskManager`,但有独立 `withCwd(worktreePath)`
- **AC26**: `/team list` slash 命令输出含所有 Team 摘要;`/team info <name>` 输出成员详情;`/team delete <name>` 调 `TeamManager.delete`
- **AC27**: 项目编译无错误 `mvn -q -DskipTests package`、所有单元测试通过 `mvn test`、`mvn spotbugs:check` 通过
- **AC28**: tmux 实跑(端到端):
  - 步骤 1:在 tmux 会话内启动 `bluecode`
  - 步骤 2:输入 prompt 让主 Agent 调 `TeamCreate(teamName="demo")`,看到状态栏出现 team 标识,`~/.bluecode/teams/demo/config.json` 落地
  - 步骤 3:Agent 调 `Agent(teamName="demo", subagentType="general-purpose", name="alice", prompt="在 worktree 里 echo hello > /tmp/test_alice.txt")`
  - 步骤 4:观察 tmux 新增 pane,pane 内出现 bluecode 子实例;`.bluecode/worktrees/team-demo+alice/` 目录创建;`/tmp/test_alice.txt` 文件创建,内容 `hello`
  - 步骤 5:`/team info demo` 显示 alice 成员
  - 步骤 6:Lead 调 `SendMessage(to="alice", summary="ping", message="再写一行 world 到 /tmp/test_alice.txt")`,观察 alice pane 被唤醒(send-keys 触发)、`/tmp/test_alice.txt` 多一行 `world`
  - 步骤 7:`/team delete demo --force`,worktree 和 team 目录清空
- **AC29**: in-process 后端实跑(端到端,不依赖 tmux):
  - 步骤 1:`unset TMUX TERM_PROGRAM`,启动 `bluecode`(自动 fallback in-process)
  - 步骤 2:主 Agent 调 `TeamCreate("inproc")`,创建后端为 `in-process`
  - 步骤 3:`Agent(teamName="inproc", name="bob", prompt="...")` 在同进程 virtual thread 启动 bob
  - 步骤 4:bob 完成后 `Team.config.json` 标记 `isActive=false`、Lead mailbox 收到 idle 消息
  - 步骤 5:Lead 调 `SendMessage(to="bob", message="再做一件事")`,bob 从 sessionDir 恢复对话上下文继续
- **AC30**: Coordinator Mode 实跑——`MEWCODE_COORDINATOR_MODE=1` 启动 bluecode,主 Agent 的 `write_file` 工具调用被拒绝(`isError=true`);`bash git merge` 调用允许

```
  <worktree-context>
  你当前在一个独立的 Git Worktree 副本中工作,与父 Agent 隔离。
  - 父目录: <parentCwd>
  - 你的工作目录: <wtPath>
  - 父 Agent 提到的绝对路径基于父目录,你需要翻译成本地路径(替换前缀)再读写
  - 编辑文件前,必须先在本地 Worktree 重新 `read_file` 一次,避免使用过时内容
    </worktree-context>
  ```
- **F23**: 后台 SubAgent + isolation 协同——若 `background && isolation:worktree`,本期强制走前台路径(忽略 background 标志);后续章节再扩展异步路径

### TUI Slash 命令

- **F24**: `/worktree create <slug>`——调 `manager.create(slug, "HEAD", true)` (`manual=true`),输出 Worktree path + branch
- **F25**: `/worktree list`——遍历 `manager.list()`,每行格式 `<name>  <path>  <branch>  [active?]`
- **F26**: `/worktree exit [--remove] [--discard]`——退出当前 session;`--remove` 时调 `exit(name, REMOVE, new ExitOptions(discard))`,`--discard` 跳过变更保护
- **F27**: `/worktree remove <slug> [--discard]`——直接调 `manager.remove(slug, ...)`
- **F28**: `/worktree enter <slug>`——调 `manager.enter(slug)`,把 ctx cwd 写到 TUI 的 `activeCwd` 字段,主 Agent 下次 Run 用这个 cwd 注入 ctx
- **F29**: slash 命令属于 `KindLocal`(只读)或 `KindUI`(改 TUI 状态),不进对话历史;输出走 `ui.println`

### 持久化与恢复

- **F30**: `WorktreeSession` 用 Jackson(或同等 JSON 库)序列化,字段名采用小写下划线(`@JsonProperty`);原子写——先写 `<sessionFile>.tmp` 再 `Files.move(..., StandardCopyOption.ATOMIC_MOVE)`
- **F31**: bluecode 启动时(`new WorktreeManager` 内),读 `sessionFile` 反序列化;若文件内容为 `null` 或空,`currentSession=null`;若 `worktreePath` 不存在,清空文件并 `currentSession=null`(stderr 警告 "session worktree gone, cleared")
- **F32**: `--resume` (bluecode 现有恢复入口)读到已有 session 时,把 `activeCwd` 设置到 `session.worktreePath`,主 Agent 后续工具调用都按 explicit cwd 走

### 后台过期清理

- **F33**: `manager.sweepStale(cutoff)` 返回 `List<String> removed`——
  - 1. 遍历 `worktreeDir` 子目录
  - 2. **第一层** 名字匹配正则 `^agent-a[0-9a-f]{7}$`(本期只识别 SubAgent 临时模式)
  - 3. **第二层** 目录 mtime > cutoff 跳过;`currentSession.worktreePath().equals(子目录)` 跳过
  - 4. **第三层** `hasWorktreeChanges(子目录, 该 wt 的 headCommit)` 为 true 跳过(fail-closed);额外跑 `git -C <子目录> rev-list --max-count=1 HEAD --not --remotes`,非空跳过(有未推送 commit 也保留)
  - 5. 通过三层的子目录调 `remove(name, new ExitOptions(true))`,记入 `removed`
- **F34**: bluecode 启动时跑一次 `Thread.startVirtualThread(() -> manager.sweepStale(Instant.now().minus(24, HOURS)))`(异步、后台执行),不阻塞启动

### .gitignore 更新

- **F35**: 在项目根 `.gitignore` 追加 `.bluecode/worktrees/` 与 `.bluecode/worktree_session.json` 两行;bluecode 启动时若发现 `.gitignore` 不含这两行,**只警告不修改**(尊重用户配置)

## 非功能需求

- **N1**: 主 Agent 看到的工具列表稳定——ctx 注入不改 schema,既有缓存不抖动
- **N2**: Worktree 创建后设置失败 (F7-F10) 不阻塞创建;主路径只在 git worktree add 本身失败时抛异常
- **N3**: Manager 所有状态变更受 `ReentrantLock lock` 保护;Worktree 内部 git 操作不持锁,避免长锁
- **N4**: 不使用 JVM 进程级 `chdir`(JVM 不支持也不应模拟);所有 cwd 行为通过 `ToolContext` 与 `ProcessBuilder.directory(...)` 实现
- **N5**: Worktree session 文件被破坏(非法 JSON)启动时只警告并清空,不阻断 bluecode 启动
- **N6**: 与 ch04~ch13 既有测试零破坏——`mvn test` 全绿
- **N7**: 中文友好——错误消息与命令输出全部中文(对齐 bluecode 其他模块风格)

## 不做的事

- Worktree 间的合并策略(交给上层 `git merge` / `git cherry-pick`)
- 跨 Worktree 代码同步、文件 watcher
- 多 Agent 并行编排 / Agent Team(下一章)
- 主 Agent 用专用 merge 工具(README 章末已说明)
- Plugin 来源的 Worktree 配置
- Windows 平台特殊支持(symlink 行为在 Windows 上不保证;本期 bluecode 以 macOS / Linux 为主)
- 跨 bluecode 进程实例的 Worktree 共享(同一仓库同一时刻只支持一个 bluecode 实例操作 worktree session)
- Worktree 内部 git 操作的 retry / exponential backoff(用一次性 `Thread.sleep(100)` 解决 lockfile 竞态即可)

## 验收标准

- **AC1**: `WorktreeSlug.validate` 对 `"feature/a"` 通过,对 `"../etc"` / `".."` / `"a//b"` / `"a/b "` 拒绝
- **AC2**: `manager.create("alice", "HEAD", true)` 在 `.bluecode/worktrees/alice/` 下落地 Worktree,分支为 `worktree-alice`
- **AC3**: `manager.create("team/alice", "HEAD", true)` 在 `.bluecode/worktrees/team+alice/` 下落地,分支 `worktree-team+alice`
- **AC4**: 已存在 worktree 目录时再调 create 走快速恢复——不调 `git worktree add`,毫秒级返回(单测可断言 git 子进程未启动)
- **AC5**: 创建后设置 A——主仓库存在 `.bluecode/settings.local.yaml` 时,Worktree 内同位置出现该文件
- **AC6**: 创建后设置 B——主仓库 `.husky/` 存在时,Worktree 的 `.git/config` 含 `core.hooksPath`
- **AC7**: 创建后设置 C——主仓库有 `node_modules/` 时,Worktree 内是软链(`Files.isSymbolicLink(...)` 为 true)
- **AC8**: 创建后设置 D——主仓库有 `.worktreeinclude` 含 `*.env`,且主仓库存在被忽略的 `.env`,Worktree 内出现 `.env`
- **AC9**: `manager.enter(name)` **不**改变 JVM 当前目录 `Path.of("").toAbsolutePath()`;返回 session 含正确字段
- **AC10**: `manager.exit(name, REMOVE, new ExitOptions(false))` 当 Worktree 有未提交修改时,抛 `WorktreeHasChangesException`,Worktree 目录仍在
- **AC11**: `manager.exit(name, REMOVE, new ExitOptions(true))` 显式 discard 时,目录被删,分支被删
- **AC12**: `manager.autoCleanup(name)` 对 `manual=true` 直接 keep;对 `manual=false` 且无变更直接 remove
- **AC13**: 工具 `read_file` / `write_file` / `edit_file` / `bash` / `glob` / `grep` 在 ctx 注入 cwd 后,以 cwd 为基准解析相对路径(单测断言)
- **AC14**: `bash` 工具在 ctx cwd 注入下,`ProcessBuilder.directory()` 等于 cwd(单测 / 集成测试可断言)
- **AC15**: `subagent.Definition#isolation()` 为 `"worktree"` 时,`AgentTool#execute` 创建临时 Worktree、注入 worktree notice、传 ctx cwd、跑完后调 autoCleanup
- **AC16**: SubAgent + worktree 路径上,子 Agent 写文件不影响主 Agent 工作目录(集成测试或 tmux 实跑可观察)
- **AC17**: `/worktree create alice` slash 命令成功落地 Worktree,`/worktree list` 输出含 alice
- **AC18**: `/worktree exit --remove` 在 Worktree 有未提交修改时报错;加 `--discard` 后成功删除
- **AC19**: `manager.sweepStale(cutoff)` 只删命名匹配 `agent-a[0-9a-f]{7}` 的目录、跳过当前 session、跳过有变更或有未推送 commit 的目录
- **AC20**: `WorktreeSession` 持久化到 `.bluecode/worktree_session.json`,启动时读取;指向的 Worktree 目录被外部删除后,启动时清空 session 并 stderr 警告
- **AC21**: 项目编译无错误 (`mvn -q -DskipTests package`)、所有单元测试通过 (`mvn test`)、Spotless 检查通过 (`mvn spotless:check`)
- **AC22**: tmux 实跑——`bluecode` 启动 + 触发 `isolation:worktree` 子 Agent 改文件 + 验证主目录 `server.py`(若改的是 `server.py`)未变,Worktree 副本里 `server.py` 已变;Worktree 留盘 / 自动清理符合预期

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