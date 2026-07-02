# GitHub Pages 展示站 Plan

## Architecture Overview

展示站采用纯静态架构:

- `site/index.html` 承载单页结构。
- `site/assets/style.css` 提供响应式深色终端风格界面。
- `site/assets/app.js` 管理 demo 数据加载、场景切换、逐字输出、工具状态渲染和复制命令。
- `site/demos/*.json` 保存可维护的预录制场景。
- `.github/workflows/pages.yml` 负责测试、构建和 GitHub Pages 部署。

## Module Design

### Static Page

**Responsibility:** 展示 BlueCode 产品入口、仿终端 demo、工具调用面板、能力概览、架构概览和本地运行命令。

**Public Interface:** 浏览器访问 `index.html`。

**Dependencies:** 相对路径加载 CSS、JS、JSON, 不依赖后端。

### Demo Runtime

**Responsibility:** 从 `site/demos/*.json` 加载 demo 数据; 如果本地文件访问阻止 `fetch`, 使用 JS 内置 fallback 数据。

**Public Interface:** 页面上的场景按钮、重新播放按钮、复制运行命令按钮。

**Data Shape:**

```json
{
  "title": "代码修复",
  "mode": "acceptEdits",
  "context": "200k",
  "result": "补丁预览",
  "tools": [{ "name": "Read", "summary": "..." }],
  "steps": [{ "type": "assistant", "text": "..." }]
}
```

### Pages Workflow

**Responsibility:** 在 `main` 分支变更后运行 Java 验证, 上传 `site/` artifact, 并部署到 GitHub Pages。

**Public Interface:** GitHub Actions workflow `Deploy BlueCode Pages`。

**Dependencies:** GitHub 官方 Pages actions、JDK 21、Gradle wrapper。

## Module Interactions

浏览器加载 `index.html` 后引入 `assets/app.js`; JS 尝试读取 `demos/*.json`, 成功则使用文件数据, 失败则使用内置数据。用户点击场景按钮时, JS 重置终端和工具面板, 按 steps 顺序播放文本和状态。

推送代码到 `main` 后, GitHub Actions checkout 仓库, 设置 Java 21, 执行 `./gradlew test shadowJar`, 然后将 `site/` 上传为 Pages artifact 并发布。

## File Organization

```text
site/
  index.html
  .nojekyll
  assets/
    style.css
    app.js
  demos/
    fix.json
    mcp.json
    team.json
.github/workflows/pages.yml
README.md
SECURITY.md
LICENSE
docs/ch16/
  spec.md
  plan.md
  task.md
  checklist.md
```

## Technical Decisions

| Decision | Choice | Reason |
| --- | --- | --- |
| 前端技术 | 原生 HTML/CSS/JS | 第一版无需构建链, 可直接由 GitHub Pages 托管 |
| Demo 数据 | JSON + JS fallback | Pages 可读取 JSON, 本地双击也能播放 |
| 部署方式 | GitHub Pages Actions | 避免混用 `docs/` 章节文档和站点发布源 |
| 运行安全 | 静态回放 | 不暴露 API key, 不执行访问者输入 |
| 许可证 | MIT | 便于公开源码和他人试用 |
