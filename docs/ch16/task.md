# GitHub Pages 展示站 Tasks

## File List

| Action | File | Responsibility |
| --- | --- | --- |
| Create | `site/index.html` | 展示站页面结构 |
| Create | `site/assets/style.css` | 展示站视觉和响应式布局 |
| Create | `site/assets/app.js` | demo 播放和交互 |
| Create | `site/demos/*.json` | 预录制 demo 数据 |
| Create | `.github/workflows/pages.yml` | 测试、构建和 Pages 部署 |
| Create | `README.md` | 公开项目首页文档 |
| Create | `SECURITY.md` | 安全边界和漏洞报告 |
| Create | `LICENSE` | 开源许可证 |
| Create | `docs/ch16/*` | 本功能文档 |

## T1: 创建展示站页面

**Files:** `site/index.html`, `site/assets/style.css`

**Depends On:** None

**Steps:**
1. 创建首屏仿终端布局。
2. 添加场景切换、工具面板、操作按钮。
3. 添加能力、架构、本地运行区块。
4. 编写桌面和移动端响应式样式。

**Verification:** 本地打开 `site/index.html`, 页面结构和样式可加载。

## T2: 实现 demo 播放

**Files:** `site/assets/app.js`, `site/demos/fix.json`, `site/demos/mcp.json`, `site/demos/team.json`

**Depends On:** T1

**Steps:**
1. 定义 demo 数据结构。
2. 实现场景切换、重新播放、逐字输出。
3. 实现工具状态 queued/running/done。
4. 实现复制运行命令和 toast。
5. 提供内置 fallback 数据。

**Verification:** 三个 demo 可切换播放, 工具进度随步骤变化。

## T3: 配置 GitHub Pages 部署

**Files:** `.github/workflows/pages.yml`, `site/.nojekyll`

**Depends On:** T1

**Steps:**
1. 创建 Pages workflow。
2. 设置 `contents: read`, `pages: write`, `id-token: write` 权限。
3. 设置 JDK 21 并运行 `./gradlew test shadowJar`。
4. 上传 `site/` 为 Pages artifact。
5. 使用 `actions/deploy-pages` 发布。

**Verification:** Workflow 语法可读, 推送后 Actions 能部署 Pages。

## T4: 补齐公开文档

**Files:** `README.md`, `SECURITY.md`, `LICENSE`

**Depends On:** None

**Steps:**
1. 编写项目定位、能力、运行方式和结构说明。
2. 说明 `.bluecode/config.yaml` 不应提交真实密钥。
3. 说明展示站安全边界。
4. 添加 MIT License。

**Verification:** 打开 README 后能找到展示站、运行命令和安全提示。

## T5: 编写 ch16 文档

**Files:** `docs/ch16/spec.md`, `docs/ch16/plan.md`, `docs/ch16/task.md`, `docs/ch16/checklist.md`

**Depends On:** T1, T2, T3, T4

**Steps:**
1. 按项目既有格式记录需求、设计、任务、验收。
2. 保持要求可观察、可执行。

**Verification:** 四个文档齐全, checklist 能覆盖 spec 的验收标准。

## T6: 最终验证

**Files:** All

**Depends On:** T1, T2, T3, T4, T5

**Steps:**
1. 检查站点关键文件存在。
2. 运行 `./gradlew test`。
3. 运行 `./gradlew shadowJar`。
4. 检查 git diff, 确认没有提交敏感文件。

**Verification:** 命令通过, 且新增文件符合计划范围。

## Execution Order

```text
T1 -> T2 -> T3 -> T4 -> T5 -> T6
```
