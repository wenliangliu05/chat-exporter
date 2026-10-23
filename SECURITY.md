# 安全与隐私

这个项目要读你的 **账号 Cookie**，所以把边界写清楚。

## 什么会离开你的电脑

**只有一种情况**：你自己点「开始导出」时，程序用你的 Cookie 去平台的官方接口拉取你自己的对话数据。
除此之外没有任何遥测、统计、云端同步。

## 什么永远不会离开

| 数据 | 位置 |
|---|---|
| Cookie / 登录态 | `<profile>/cookie-*.txt`（`.gitignore` 内）|
| 快照库 | `<profile>/snapshot.db`（`.gitignore` 内）|
| 导出的 Markdown / 附件 | `<profile>/markdown`、`<profile>/assets`（`.gitignore` 内）|
| 本地记忆 | `~/.chatexporter/settings.json`（在仓库之外）|

## 三道防误提交

1. `.gitignore` 显式规则
2. `.githooks/pre-commit`：路径 + 内容 + 体积三检（启用：`scripts/setup-hooks.cmd`）
3. CI 隐私扫描（`.github/workflows/ci.yml`）

## 报告问题

如果发现凭据可能被写进了仓库历史，请**先轮换该 Cookie / Token**，再处理历史（`git filter-repo` 等）。
Cookie 一旦泄露，等同于把账号登录态交出去，优先级永远高于"保留提交历史"。
