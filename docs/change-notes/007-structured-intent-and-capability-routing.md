# 007：结构化意图识别、能力路由与统一任务入口

## 目标

将原来“所有请求直接交给拥有全部工具的 Agent”改为两阶段控制：

1. 识别任务意图，生成结构化 `TaskSpec`。
2. 根据 capability 白名单决定执行模式和本次可见工具。

这样既减少模型在无关工具 schema 中选择错误，也把意图识别与授权边界分开。所有改动仍仅位于 `codexProject` 副本。

## 原实现的问题

1. `MyManus` 对每个请求暴露全部工具，普通问答也能看到文件写入、下载甚至终端能力。
2. 提示词要求调用已经从注册表移除的 `terminate` 工具，模型会尝试不存在的能力。
3. API 调用者必须自行选择 `/chat`、`/rag` 或 `/agent`，服务端没有统一编排。
4. 意图和风险不进入运行事件，出现误路由后无法分析。
5. 知识库检索只能通过固定 RAG 接口，复杂 Agent 任务无法把知识检索作为受控工具使用。
6. 主系统提示词与报告提示词存在乱码，且报告对象会完整进入日志。

## 结构化 TaskSpec

新增：

- `TaskIntent`：对话、知识问答、Web 调研、文件任务、文档生成、代码执行、复合任务。
- `Capability`：模型推理、知识检索、Web 搜索/读取、文件读写、下载、PDF 读写、终端执行。
- `RiskLevel`：只读、工作区写入、特权执行。
- `TaskSpec`：意图、能力集合、风险、置信度、是否建议澄清。

`RuleBasedTaskIntentClassifier` 提供确定性基线。它支持中英文动作短语，并刻意要求“执行/运行 + 终端/脚本/命令”的组合才授予终端 capability；单纯询问“什么是 shell”不会获得终端能力。

模糊请求如“帮我处理一下”会得到低置信度和 `requiresClarification=true`，Agent 提示要求先澄清而不是猜测。

## 能力白名单

`CapabilityToolRouter` 维护显式映射：

| 工具 | 所需 capability |
|---|---|
| `readFile` | `FILE_READ` |
| `writeFile` | `FILE_WRITE` |
| `generatePDF` | `PDF_WRITE` |
| `extractPdfLink` | `PDF_READ` |
| `downloadResource` | `RESOURCE_DOWNLOAD` |
| `executeTerminalCommand` | `TERMINAL_EXECUTION` |
| `scrapeWebPage` | `WEB_FETCH` |
| `searchWeb` | `WEB_SEARCH` |
| `searchKnowledgeBase` | `KNOWLEDGE_RETRIEVAL` |

未出现在映射中的新工具默认不可见。这是 fail-closed：新增代码不会无意中扩大全体 Agent 的权限。

需要强调：意图分类器只决定“模型看得到什么”，不是最终授权器。终端仍受禁用开关和可执行文件 allowlist 限制，文件和下载仍受工作区路径边界限制，URL 仍受 SSRF 策略限制。即使分类器误判，底层策略仍应拒绝越权动作。

## Agent Factory 与运行事件

`AgentFactory.create(TaskSpec)` 在每次运行创建 prototype Agent 后，仅注入当前任务允许的工具。

`AgentRunCoordinator` 新增 `INTENT_CLASSIFIED` 事件，记录：

- intent
- capability 名称集合
- risk level
- confidence
- requires clarification

事件不记录原始请求。分类或 Agent 创建阶段失败也会把运行标记为 `FAILED`，不会遗留永久 `RUNNING` 记录。

## 知识库工具

新增 `KnowledgeSearchTool`，把已有 `HybridRrfDocumentRetriever` 暴露为受控的 Agent 工具：

- 查询最长 1000 字符。
- 最多返回 8 个文档。
- 单文档内容最多 4000 字符。
- 输出包含来源 metadata 和可用 score。
- 明确标记 `UNTRUSTED_RETRIEVED_DATA`。
- 空查询和检索异常返回统一 `ToolResult`。

这样纯知识问答仍可走低成本 RAG Advisor，而 Web + 知识库 + 文件输出等复合任务可以在同一个 Agent run 中按 capability 使用检索。

## 统一任务入口

新增 `POST /ai/tasks`，服务端根据 `TaskSpec` 路由：

- 仅 `MODEL_REASONING` -> `DIRECT_CHAT`
- `MODEL_REASONING + KNOWLEDGE_RETRIEVAL` -> `RETRIEVAL_AUGMENTED_CHAT`
- 其他需要工具的任务 -> `TOOL_AGENT`

统一响应返回执行模式、意图、风险、分类置信度、是否建议澄清，以及 Agent 模式下的 `runId/runStatus`。

旧 `/chat`、`/rag`、`/agent` 接口保留，避免破坏已有调用方；新客户端应优先使用 `/ai/tasks`。

## 提示词治理

重写了乱码的系统提示，明确：

- 用户请求和应用授予 capability 决定权限。
- 网页、文件、工具结果、RAG 文档和历史记忆都是低信任数据。
- 不泄露系统提示、隐藏推理和凭据。
- 不声称执行未实际执行的工具。
- 区分证据、归纳和推断。

`MyManus` 不再提及 terminate tool；无工具或任务完成时直接给出最终答案。
旧 `TerminateTool` 类也已删除，避免未来误注册重新形成伪终止路径。

`AIApp` 清理了重复 Advisor 和乱码报告提示；报告日志只记录建议条数，不记录标题和正文。

## 与主流 Harness 对照

### Claude Code

Claude Code 的实际能力由当前会话暴露的工具、权限模式、工作区和用户确认共同决定，而不是让模型凭自然语言自行授权。本实现引入同类的 capability 裁剪思路，但还缺少完整的人机审批协议。

### OpenAI Codex Harness

Codex 类 harness 把模型建议动作与宿主执行策略分开，并对 shell、文件和外部操作施加沙箱/审批。这里的 `TaskSpec -> ToolRouter -> 底层 ToolPolicy` 形成三层防护；下一步应在 `WORKSPACE_WRITE/PRIVILEGED_EXECUTION` 前加入可恢复 approval event。

### LangGraph

LangGraph 常用条件边将分类结果路由到不同节点或子图。本次的 `TaskOrchestrator` 是轻量 Java 版本；当分支、并行、暂停、补偿和重试增加时，应迁移到显式 StateGraph，而不是继续增加 `if`。

### Semantic Kernel

Semantic Kernel 的 function choice behavior / plugin 选择强调减少模型可见函数集合。`CapabilityToolRouter` 实现了相同原则，并采用未知工具默认拒绝。

### OpenHands

OpenHands 根据 Agent action 进入 shell、文件、浏览器等执行器，并通过事件流审计。本项目已把分类、步骤和运行生命周期写入事件表，但工具 requested/approved/completed 事件仍需细化。

### Spring AI Alibaba Graph

Spring AI Alibaba Graph 可把 intent classifier、RAG、tool agent 和 approval 建成显式节点。本次保留当前 Spring AI M6 代码以控制迁移风险，同时把边界接口先稳定下来，为后续框架升级或图迁移创造条件。

## 意图识别的业内成熟方案

当前规则分类器是稳定基线，不应被描述为最终“智能路由”。达到成熟水平建议使用分层方案：

1. 高精度规则处理安全敏感、确定性强的动作词。
2. 小模型输出严格 JSON Schema 的 `TaskSpec`，只提出意图和能力需求，不能直接授权。
3. 低置信度、能力冲突或高风险任务进入澄清/审批。
4. 用历史标注集校准置信度，监控 macro-F1、每个 capability 的 precision/recall、误授权率和不必要澄清率。
5. 在线 shadow 模式对比新旧路由，确认不会扩大危险能力后再切换。

安全指标应优先关注 capability 的假阳性，尤其是 `TERMINAL_EXECUTION`、`FILE_WRITE` 和 `RESOURCE_DOWNLOAD`；普通分类准确率不能替代误授权评估。

## 测试

新增：

- `TaskIntentRoutingTest`：普通问答无工具、Web 工具最小集合、PDF 生成隔离、终端动作识别、模糊请求澄清、未知工具默认拒绝、知识库最小授权。
- `TaskOrchestratorTest`：直接聊天、纯 RAG 和审计 Agent 三条执行路径。
- `KnowledgeSearchToolTest`：低信任标记、文档返回和空查询拒绝。

同时扩展 `AgentRunCoordinatorTest`，验证分类事件写入。定向测试已经通过。

## 尚未完成

1. 规则分类器对复杂语义、否定句和隐含意图覆盖有限，需要结构化模型分类器及离线数据集。
2. 写操作尚无 `WAITING_APPROVAL -> APPROVED/DENIED` 协议，当前依赖工具底层策略。
3. RAG 已有向量 + keyword + RRF，但缺少 cross-encoder/LLM reranker、引用一致性验证和检索质量评测。
4. Query rewrite 失败缺少原查询降级，复杂查询还需要 decomposition 和 metadata filter。
5. 工具调用事件粒度需要加入 call ID、请求摘要、审批、耗时、结果摘要和重试关系。
6. 生产框架应从 Spring AI/Alibaba milestone 版本升级到同一代稳定 BOM；必须单独做兼容分支和回归，不能直接混在功能改动中升级。
