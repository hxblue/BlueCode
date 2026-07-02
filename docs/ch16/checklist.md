# GitHub Pages 展示站 Checklist

> Verify each item by running code or observing behavior.

## Implementation Completeness

- [ ] `site/index.html` 可直接打开并显示 BlueCode 仿终端首屏。
- [ ] `site/assets/style.css` 在桌面和移动端都能让页面内容不重叠。
- [ ] `site/assets/app.js` 能播放逐字输出和工具调用状态。
- [ ] `site/demos/fix.json`, `site/demos/mcp.json`, `site/demos/team.json` 均为有效 JSON。
- [ ] 页面包含 GitHub、复制运行命令、配置示例、Release 入口。
- [ ] README 包含在线展示页、运行命令、配置方式和安全边界。
- [ ] SECURITY 说明展示站不会调用真实模型、执行命令或读取访问者文件。

## Integration

- [ ] 本地双击 `site/index.html` 时, 即使 JSON fetch 失败也能播放 demo。
- [ ] 通过本地 HTTP 服务访问 `site/` 时, demo JSON 可被加载并播放。
- [ ] `.github/workflows/pages.yml` 上传 `site/` 而不是整个仓库。
- [ ] Workflow 在部署前运行 Java 测试和 shadowJar 构建。

## Build And Test

- [ ] 运行 `./gradlew test` 成功。
- [ ] 运行 `./gradlew shadowJar` 成功。
- [ ] 检查 `git status --short` 未出现 `.bluecode/config.yaml`, sessions, memory, logs, `.env` 或 key 文件。

## End-To-End Scenarios

- [ ] Scenario 1: 打开展示页 -> 点击“代码修复” -> 看到用户输入、BlueCode 回复、Read/Grep/Edit/Bash 状态依次完成。
- [ ] Scenario 2: 点击“MCP 工具” -> 看到 McpManager/context7/Read/Bash 状态依次完成。
- [ ] Scenario 3: 点击“Team 协作” -> 看到 TeamCreate/Agent/TaskCreate/SendMessage 状态依次完成。
- [ ] Scenario 4: 点击“复制运行命令” -> 剪贴板得到本地运行命令或页面给出复制失败提示。
- [ ] Scenario 5: GitHub Actions 完成后 -> 打开 `https://hxblue.github.io/BlueCode/` -> 页面资源正常加载。
