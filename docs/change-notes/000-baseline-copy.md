# 000 - 隔离副本与基线建立

## 变更目标

在不修改原项目的前提下，建立一个可独立改造、编译和测试的工作副本。

## 目录边界

- 原项目（只读基线）：`<original-project>`
- 改造副本：`<project-root>`
- 后续所有源码、配置、测试和文档修改都必须位于改造副本内。

## 已复制的内容

- `.mvn/`
- `src/`
- `.gitattributes`
- `.gitignore`
- `mvnw`
- `mvnw.cmd`
- `pom.xml`

## 有意不复制的内容

- `.idea/`：IDE 本地状态，不属于产品源码。
- `.m2/`：本地 Maven 依赖缓存，不应成为副本源码的一部分。
- `target/`：旧的编译产物，避免将过期 class 文件当作新基线。
- `chat-memory/`：运行时会话数据，可能包含用户内容。
- `tmp/`：临时文件。

## 验证

对所有复制的 81 个文件计算 SHA-256，原件与副本的不匹配数为 `0`。这说明改造开始前的副本内容与原项目一致。

## 变更文档规则

后续以“一个可独立验证的变更集”为单位新增 Markdown，文件名使用递增编号，每份文档至少包含：

1. 要解决的问题与风险。
2. 修改的类、配置和数据结构。
3. 架构决策及未选方案。
4. Claude Code、Codex、Spring AI Alibaba、LangGraph、OpenHands 等框架或项目的对照。
5. 测试方法与结果。
6. 已知限制和下一步。

## 业界方案对照

- **Claude Code**：将会话、项目指令、工具权限、sandbox、hooks 和 checkpoint 分层，不把它们混在单个 ReAct 类中。
- **OpenAI Codex harness**：强调代码库内可机械验证的规则、可观测环境和反馈循环。
- **LangGraph**：以显式状态、checkpoint 和 durable execution 表达长任务。
- **OpenHands**：以 EventStream 连接 AgentController、State 和 Runtime，将决策与环境执行分离。
- **Spring AI Alibaba**：当前版本已提供 Graph、ReactAgent、routing、handoff、hooks 和 skills，后续评估替代手写循环，但业务状态、安全策略与审计数据仍由项目自己管理。
