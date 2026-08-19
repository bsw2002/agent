# 002 - 工具安全边界与结构化结果

## 要解决的问题

原工具层直接把模型输出作为文件路径、URL 或 `cmd.exe /c` 命令使用，主要风险包括：

- `../` 目录穿越和绝对路径越界。
- 通过符号链接跳出工作目录。
- 访问 localhost、私有 IP、云 metadata 等 SSRF 风险。
- 无限制下载、大网页或大文本消耗内存和上下文。
- 任意 shell 操作符、管道、重定向、脚本解释器和子命令。
- 所有工具只返回自然语言，Agent 无法可靠区分成功、错误和策略拒绝。

## 通用安全组件

### `SafePathResolver`

- 所有路径转换为绝对、normalize 后的 `Path`。
- 仅允许工作目录内的相对路径。
- 拒绝绝对路径、父目录越界和已存在的符号链接路径。
- 文件、PDF 和下载工具使用同一边界，避免每个工具自己拼字符串。

### `SafeUrlPolicy`

- 仅允许 HTTP/HTTPS。
- 拒绝 URL user-info 凭据。
- 支持显式域名 allowlist。
- DNS 解析后拒绝 loopback、link-local、site-local、multicast 和 unspecified 地址。
- PDF 页面中提取出的二次 URL 也重新经过策略检查。

### `ToolResult<T>`

工具统一返回：

```json
{
  "status": "SUCCESS | ERROR | DENIED",
  "data": {},
  "error": {"code": "...", "message": "..."},
  "retryable": false
}
```

错误消息不再直接返回堆栈、系统路径或底层异常文本。

## 各工具改动

### 文件读写

- 限定在 `workspace/file` 子目录。
- 限制 UTF-8 文件大小。
- 写入采用同目录临时文件 + atomic move，减少进程崩溃留下半个文件的概率。

### 资源下载

- URL 必须通过 `SafeUrlPolicy`。
- 关闭自动 redirect，避免第二跳绕过 SSRF 检查。
- 增加 connect/request timeout、Content-Length 预检查和流式字节上限。
- 超限或失败时删除临时文件，成功后再 atomic move。

### 网页读取与 PDF 链接

- 关闭自动 redirect，限制超时和 body 大小。
- 网页工具只返回规范化文本，不再将整页 HTML 放入模型上下文。
- 用 `<untrusted_external_content>` 标记外部内容信任边界。

### Web Search

- 结果仅保留 title、URL 和 snippet，不再把供应商的整个原始 JSON 交给模型。
- 查询限制为 500 字符，处理搜索结果少于 5 条的情况。
- 显式标记 `contentTrust=UNTRUSTED_EXTERNAL`。

### Terminal

- 默认 `enabled=false`，未开启时不注册到模型工具列表。
- 取消 `cmd.exe /c <model text>`。
- 调用协议改为 `executable + arguments[]`，`ProcessBuilder` 直接执行，不经 shell 解析。
- 可执行程序必须来自显式 allowlist，不允许传入程序路径。
- 增加超时、输出长度限制和 interrupt 处理。

### Terminate 工具

从默认工具注册中移除。Agent 在模型不再请求工具时就应正常结束，不应为了弥补运行循环缺陷而增加一个 terminate 工具。

## 配置

新增 `suvia.tools.security` 配置组，包含：

- 工作目录。
- 网络超时。
- 下载、网页和文本大小上限。
- 网络域名 allowlist。
- Terminal 开关、超时、输出限制和可执行程序 allowlist。

生产环境应当配置显式 `allowed-domains`；当前空列表表示允许任意公网 HTTP(S) 域名，仅作为开发默认值。

## 框架与业界方案对照

### Claude Code

Claude Code 把模型工具权限和 OS 级 sandbox 作为两层不同的防御：前者决定某个工具能否尝试，后者限制进程实际能读写的文件和能访问的网络。本变更实现了工具能力层的最小权限，但还不是 OS 级 sandbox。

### OpenAI Codex

Codex 的工具 orchestrator 将 approval、sandbox selection、execution 和 escalation/retry 分层。本项目后续 P1 会在 `ToolResult` 之上增加 `TOOL_PROPOSED -> POLICY_DECISION -> TOOL_STARTED -> TOOL_FINISHED` 事件和人工审批。

### OpenHands

OpenHands Runtime/Sandbox 与 Agent 的 Action 生成相互独立。本次新增的 `ToolResult` 是将环境 Observation 从普通字符串中分离出来的过渡层。

### Spring AI / Spring AI Alibaba

Spring AI 提供 ToolCallback schema 和 tool observability，Spring AI Alibaba 提供 hooks 和异步工具。但框架不会自动知道项目的文件边界、允许域名和业务风险，这些仍必须由应用层实现。

### MCP

MCP 能统一工具发现和 schema，但“来自 MCP server”不等于“安全”。MCP 工具同样需要能力列表、输入校验、凭据范围、输出信任标记和审批策略。

## 测试

本变更集与上一变更集共执行 `14` 个无外部网络、模型和数据库依赖的测试，全部通过。新增覆盖：

- 安全文件读写。
- `../../` 路径穿越。
- PDF 扩展名和输出路径校验。
- localhost / RFC1918 私网 SSRF。
- 域名 allowlist。
- Terminal 默认禁用和程序白名单。
- 空搜索查询短路拒绝。

## 已知限制和下一步

- DNS 检查与真实 TCP 连接之间仍存在 DNS rebinding/TOCTOU 窗口。生产级实现应结合 egress proxy、固定解析地址或网络 sandbox。
- Java `ProcessBuilder` 限制了 shell 注入，但它不是 OS sandbox；白名单程序自身仍可能具有宽泛能力。
- 尚未实现人工审批和按 tenant/run 生成独立工作目录。
- 尚未对网页内容运行独立 Prompt Injection classifier；当前只建立了信任标记和大小边界。

