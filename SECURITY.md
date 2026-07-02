# Security Policy

## Supported Versions

当前仓库处于早期开发阶段。请优先使用 `main` 分支或最新 Release。

## Reporting A Vulnerability

如果你发现 BlueCode 中的安全问题, 请不要公开提交可利用细节。可以通过 GitHub Security Advisory 或私下联系维护者报告。

报告时请尽量包含:

- 影响范围和复现步骤。
- 相关配置, 但不要包含真实 API key、token、cookie、私钥或完整会话日志。
- 你期望的安全边界和实际行为。

## Public Demo Boundary

`site/` 下的 GitHub Pages 展示站只播放预录制 JSON demo。它不会:

- 调用真实 LLM。
- 执行 shell 命令。
- 读取访问者本地文件。
- 上传访问者输入。
- 使用或暴露任何 API key。

## Local Runtime Boundary

BlueCode 本地运行时可能读取、写入文件并执行命令。请在不信任仓库中使用更严格的权限模式, 并在批准工具调用前检查目标路径和命令内容。
