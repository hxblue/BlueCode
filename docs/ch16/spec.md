# GitHub Pages 展示站 Spec

## Background

BlueCode 当前是一个本地终端 AI 编程助手, 已具备 TUI、LLM provider、工具调用、权限、MCP、SubAgent、Team 和 Worktree 等能力。项目需要一个别人可访问的公开入口, 让访问者快速理解 BlueCode 的能力和运行方式, 但第一阶段不开放公网真实执行。

## Goals

- 提供可通过 GitHub Pages 访问的静态展示页。
- 展示 BlueCode 的真实产品形态: 终端对话、工具调用、权限/模式、SubAgent/Team 协作。
- 提供 GitHub、安装命令、配置示例和 Release 的明确入口。
- 保证展示页不连接真实 LLM、不执行命令、不读取访问者文件、不暴露 API key。
- 保留项目既有文档驱动流程, 为本功能提供 spec、plan、task、checklist。

## Functional Requirements

- F1: 访问者打开展示页后, 首屏应看到 BlueCode 仿终端演示, 而不是纯介绍页。
- F2: 展示页应提供至少三个可切换 demo 场景: 代码修复、MCP 工具、Team 协作。
- F3: 每个 demo 应展示用户输入、BlueCode 回复和工具调用状态变化。
- F4: 页面应提供查看 GitHub、复制运行命令、查看配置示例、下载 Release 的入口。
- F5: 页面应提供项目能力和架构概览。
- F6: demo 数据应可作为静态 JSON 文件维护, 同时本地直接打开页面时仍能播放。
- F7: GitHub Actions 应在部署前运行 Java 测试和 shadowJar 构建。
- F8: GitHub Actions 应使用 GitHub Pages 官方 artifact 部署流程发布 `site/`。
- F9: 公开文档应说明本地运行方式、配置方式和安全边界。

## Non-Functional Requirements

- N1: 第一版不引入 React、Vite 或后端服务。
- N2: 页面应在桌面和移动端布局正常, 文本不互相遮挡。
- N3: 展示站不得包含真实密钥、会话日志或本地敏感配置。
- N4: 静态资源应使用相对路径, 兼容 GitHub Pages 子路径 `/BlueCode/`。
- N5: 文案以中文为主, 保留必要英文技术名词。

## Out Of Scope

- 不提供在线真实对话 API。
- 不为访问者创建沙箱执行环境。
- 不实现登录、计费、配额或多人在线使用。
- 不上传用户输入到任何远程服务。

## Acceptance Criteria

- AC1: 本地打开 `site/index.html` 可以看到仿终端 demo。
- AC2: 三个 demo 场景都能切换并播放工具调用状态。
- AC3: 复制运行命令按钮能把本地运行命令写入剪贴板或给出失败提示。
- AC4: 页面中的 GitHub、配置示例、Release 链接指向公开仓库相关位置。
- AC5: `README.md` 说明项目定位、运行方式、展示站入口和安全边界。
- AC6: `.github/workflows/pages.yml` 包含测试、shadowJar、Pages artifact 上传和部署步骤。
- AC7: `./gradlew test` 和 `./gradlew shadowJar` 能成功运行。
- AC8: GitHub Pages 部署后, `https://hxblue.github.io/BlueCode/` 能加载页面。
