const fallbackDemos = {
  fix: {
    title: "代码修复",
    mode: "acceptEdits",
    context: "200k",
    result: "补丁预览",
    tools: [
      { name: "Read", summary: "读取 Agent.java 中的 ReAct 循环。" },
      { name: "Grep", summary: "定位 approval 处理分支。" },
      { name: "Edit", summary: "收紧取消令牌检查顺序。" },
      { name: "Bash", summary: "运行 ./gradlew test --tests AgentTest。" }
    ],
    steps: [
      {
        type: "user",
        text: "BlueCode 帮我查一下 Agent 在审批被拒绝后为什么还会继续跑工具, 并给出最小修复。"
      },
      {
        type: "assistant",
        text: "我先沿着 Agent 循环和审批事件看调用链, 只做最小范围修改。"
      },
      { type: "tool", name: "Read", status: "running" },
      { type: "tool", name: "Read", status: "done" },
      { type: "tool", name: "Grep", status: "running" },
      { type: "tool", name: "Grep", status: "done" },
      {
        type: "assistant",
        text: "问题在审批拒绝后没有立即中止当前 pending tool。修复点可以放在工具执行前, 让拒绝结果先更新取消令牌。"
      },
      { type: "tool", name: "Edit", status: "running" },
      { type: "tool", name: "Edit", status: "done" },
      { type: "tool", name: "Bash", status: "running" },
      { type: "tool", name: "Bash", status: "done" },
      {
        type: "assistant",
        text: "已完成最小修复, AgentTest 通过。变更只影响审批拒绝后的工具调度路径。"
      }
    ]
  },
  mcp: {
    title: "MCP 工具",
    mode: "default",
    context: "128k",
    result: "文档查询",
    tools: [
      { name: "McpManager", summary: "读取 .bluecode.yaml 中的 context7 server。" },
      { name: "context7", summary: "查询当前库文档和配置示例。" },
      { name: "Read", summary: "对照本地 ConfigLoader 实现。" },
      { name: "Bash", summary: "执行配置加载相关测试。" }
    ],
    steps: [
      {
        type: "user",
        text: "帮我确认 BlueCode 的 MCP 配置写法, 最好参考一下最新 Context7 文档。"
      },
      {
        type: "assistant",
        text: "我会先读取项目 MCP 配置, 再通过已配置的文档源查当前用法。"
      },
      { type: "tool", name: "McpManager", status: "running" },
      { type: "tool", name: "McpManager", status: "done" },
      { type: "tool", name: "context7", status: "running" },
      { type: "tool", name: "context7", status: "done" },
      { type: "tool", name: "Read", status: "running" },
      { type: "tool", name: "Read", status: "done" },
      {
        type: "assistant",
        text: "当前配置可以继续使用 HTTP MCP endpoint。建议把示例放到 docs/mcp, 并提醒用户不要提交真实 token。"
      },
      { type: "tool", name: "Bash", status: "running" },
      { type: "tool", name: "Bash", status: "done" },
      {
        type: "assistant",
        text: "配置加载测试通过, MCP server 失败时会降级为跳过远程工具并给出 warn。"
      }
    ]
  },
  team: {
    title: "Team 协作",
    mode: "plan",
    context: "200k",
    result: "协作计划",
    tools: [
      { name: "TeamCreate", summary: "创建 review-squad 团队。" },
      { name: "Agent", summary: "派生 Explore 与 Plan 子 Agent。" },
      { name: "TaskCreate", summary: "登记分模块任务和依赖。" },
      { name: "SendMessage", summary: "向 teammate 发送补充上下文。" }
    ],
    steps: [
      {
        type: "user",
        text: "把权限系统、会话系统和 worktree 协作流程分给不同 Agent 调研, 最后汇总一个执行计划。"
      },
      {
        type: "assistant",
        text: "我会创建一个 Team, 每个成员拿独立 worktree, Lead 只负责拆解、收敛和验收。"
      },
      { type: "tool", name: "TeamCreate", status: "running" },
      { type: "tool", name: "TeamCreate", status: "done" },
      { type: "tool", name: "Agent", status: "running" },
      { type: "tool", name: "Agent", status: "done" },
      { type: "tool", name: "TaskCreate", status: "running" },
      { type: "tool", name: "TaskCreate", status: "done" },
      {
        type: "assistant",
        text: "团队已启动: Explore 负责现状扫描, Plan 负责技术方案, Lead 保留合并决策。"
      },
      { type: "tool", name: "SendMessage", status: "running" },
      { type: "tool", name: "SendMessage", status: "done" },
      {
        type: "assistant",
        text: "已收到 teammate 的计划片段。下一步会进入收敛阶段, 冲突通过 Lead 的 worktree 合并解决。"
      }
    ]
  }
};

const installCommand = `git clone https://github.com/hxblue/BlueCode.git
cd BlueCode
./gradlew shadowJar
java -jar build/libs/bluecode.jar`;

const state = {
  demos: { ...fallbackDemos },
  activeDemo: "fix",
  playToken: 0,
  toolState: new Map()
};

const terminalOutput = document.querySelector("#terminalOutput");
const toolList = document.querySelector("#toolList");
const toolProgress = document.querySelector("#toolProgress");
const modeStat = document.querySelector("#modeStat");
const contextStat = document.querySelector("#contextStat");
const resultStat = document.querySelector("#resultStat");
const replayButton = document.querySelector("#replayDemo");
const copyInstallButton = document.querySelector("#copyInstall");
const toast = document.querySelector("#toast");

async function loadDemoFiles() {
  await Promise.all(
    Object.keys(fallbackDemos).map(async (id) => {
      try {
        const response = await fetch(`demos/${id}.json`, { cache: "no-store" });
        if (!response.ok) {
          return;
        }
        state.demos[id] = await response.json();
      } catch {
        // 直接双击 index.html 时浏览器可能禁止读取本地 JSON, 内置数据会接管。
      }
    })
  );
}

function setActiveTab(id) {
  document.querySelectorAll(".scenario-tab").forEach((tab) => {
    const active = tab.dataset.demo === id;
    tab.classList.toggle("is-active", active);
    tab.setAttribute("aria-selected", String(active));
  });
}

function resetTools(demo) {
  state.toolState = new Map();
  demo.tools.forEach((tool) => {
    state.toolState.set(tool.name, {
      ...tool,
      status: "waiting"
    });
  });
  renderTools();
}

function renderTools() {
  const tools = Array.from(state.toolState.values());
  const done = tools.filter((tool) => tool.status === "done").length;
  toolProgress.textContent = `${done}/${tools.length}`;
  toolList.replaceChildren(
    ...tools.map((tool) => {
      const item = document.createElement("article");
      item.className = `tool-card is-${tool.status}`;

      const topline = document.createElement("div");
      topline.className = "tool-topline";

      const name = document.createElement("span");
      name.className = "tool-name";
      name.textContent = tool.name;

      const status = document.createElement("span");
      status.className = "tool-status";
      status.textContent = statusText(tool.status);

      const summary = document.createElement("p");
      summary.textContent = tool.summary;

      topline.append(name, status);
      item.append(topline, summary);
      return item;
    })
  );
}

function statusText(status) {
  if (status === "running") {
    return "running";
  }
  if (status === "done") {
    return "done";
  }
  return "queued";
}

function addTerminalLine(type, label) {
  const row = document.createElement("div");
  row.className = `terminal-line ${type}`;

  const labelEl = document.createElement("span");
  labelEl.className = "terminal-label";
  labelEl.textContent = label;

  const textEl = document.createElement("span");
  textEl.className = "terminal-text";

  row.append(labelEl, textEl);
  terminalOutput.append(row);
  terminalOutput.scrollTop = terminalOutput.scrollHeight;
  return textEl;
}

async function typeInto(element, text, token, speed = 9) {
  element.classList.add("cursor");
  for (let index = 0; index < text.length; index += 1) {
    if (token !== state.playToken) {
      return;
    }
    element.textContent += text[index];
    terminalOutput.scrollTop = terminalOutput.scrollHeight;
    await sleep(speed);
  }
  element.classList.remove("cursor");
}

async function playDemo(id) {
  const demo = state.demos[id] || fallbackDemos.fix;
  const token = state.playToken + 1;
  state.playToken = token;
  state.activeDemo = id;
  setActiveTab(id);
  terminalOutput.replaceChildren();
  resetTools(demo);
  modeStat.textContent = demo.mode;
  contextStat.textContent = demo.context;
  resultStat.textContent = demo.result;

  const boot = addTerminalLine("notice", "system");
  await typeInto(boot, `BlueCode demo: ${demo.title}。当前页面只播放安全回放, 不执行真实命令。`, token, 4);
  await sleep(180);

  for (const step of demo.steps) {
    if (token !== state.playToken) {
      return;
    }
    if (step.type === "tool") {
      updateTool(step);
      await sleep(step.status === "running" ? 360 : 220);
      continue;
    }
    const line = addTerminalLine(step.type, step.type === "user" ? "user" : "bluecode");
    await typeInto(line, step.text, token);
    await sleep(220);
  }
}

function updateTool(step) {
  const current = state.toolState.get(step.name);
  if (!current) {
    return;
  }
  state.toolState.set(step.name, {
    ...current,
    status: step.status
  });
  renderTools();
}

function sleep(ms) {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms);
  });
}

async function copyInstallCommand() {
  try {
    if (navigator.clipboard) {
      await navigator.clipboard.writeText(installCommand);
    } else {
      const textarea = document.createElement("textarea");
      textarea.value = installCommand;
      document.body.append(textarea);
      textarea.select();
      document.execCommand("copy");
      textarea.remove();
    }
    showToast("已复制本地运行命令");
  } catch {
    showToast("复制失败, 可以手动复制页面底部命令");
  }
}

function showToast(message) {
  toast.textContent = message;
  toast.classList.add("is-visible");
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => {
    toast.classList.remove("is-visible");
  }, 2400);
}

document.querySelectorAll(".scenario-tab").forEach((button) => {
  button.addEventListener("click", () => {
    playDemo(button.dataset.demo);
  });
});

replayButton.addEventListener("click", () => {
  playDemo(state.activeDemo);
});

copyInstallButton.addEventListener("click", copyInstallCommand);

loadDemoFiles().finally(() => {
  playDemo(state.activeDemo);
});
