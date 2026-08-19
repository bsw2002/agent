# 004：Agent 运行记录、事件流与检查点

## 目标

这一阶段把一次 Agent 调用从“控制器里同步执行、结束后只返回字符串”升级为可追踪的运行实体。每次执行都有稳定的 `runId`、明确的状态、按序事件和步骤检查点，并且所有查询都经过租户与用户所有权校验。

本次改动只发生在 `codexProject` 副本中，没有修改原项目代码。

## 原有问题

原实现存在以下生产化缺口：

1. Agent 调用没有运行标识，故障后无法定位某一次执行。
2. 中间步骤只出现在日志中，日志不是可靠的业务审计存储。
3. 进程或请求失败后，没有可查询的运行状态和最后完成步骤。
4. API 不能查询历史运行及其事件。
5. 多租户场景下，若只按 `runId` 查询，容易产生越权读取风险。
6. 用户原始请求若直接进入事件载荷，会扩大敏感信息的复制面。

## 设计与实现

### 1. 运行状态机

新增 `RunStatus`：

- `CREATED`
- `RUNNING`
- `WAITING_APPROVAL`
- `WAITING_USER`
- `SUCCEEDED`
- `FAILED`
- `CANCELLED`

当前同步执行链路使用 `CREATED -> RUNNING -> SUCCEEDED/FAILED`。等待审批、等待用户和取消状态为下一阶段的人机协作与异步执行预留，当前没有虚构实现这些能力。

成功状态只由 `complete(...)` 写入，并与最终输出一起持久化。普通步骤完成只能写 `RUNNING` 检查点，避免出现“数据库显示成功，但最终输出尚未落库”的短暂不一致。

### 2. 运行与事件模型

新增两类记录：

- `AgentRunRecord`：一次运行的当前快照。
- `AgentEventRecord`：只追加的生命周期事件。

主要事件包括创建、启动、步骤开始、步骤完成、步骤失败、检查点保存、运行成功、运行失败和取消。

事件载荷使用结构化 JSON，而不是拼接日志文本。步骤事件只记录步骤号、最大步骤数、Agent 状态和标准化错误码等元数据，不记录模型完整思考、工具参数、工具返回全文或用户原始请求。

### 3. PostgreSQL 持久化

新增 `JdbcAgentRunStore`，维护：

- `suvia_agent_run`
- `suvia_agent_event`

运行表保存租户、用户、公共会话 ID、请求 SHA-256、状态、当前步骤、最终输出、错误码和时间戳。事件表保存事件序号、类型、步骤号、JSONB 载荷和创建时间。

运行状态更新与对应事件追加使用 Spring `TransactionTemplate` 组成事务，减少快照和事件流分叉的风险。

说明：当前用 `CREATE TABLE IF NOT EXISTS` 保证副本可直接启动。正式部署应迁移到 Flyway 或 Liquibase，由版本化脚本管理 DDL、索引和回滚策略。

### 4. 执行协调器

新增 `AgentRunCoordinator`：

1. 创建运行记录并保存请求摘要。
2. 写入启动事件和初始检查点。
3. 通过 `AgentFactory` 创建一次性 Agent 实例。
4. 通过 `AgentStepObserver` 把步骤生命周期写入事件库。
5. 成功时原子保存最终输出与成功事件。
6. 失败时保存标准化错误码与失败事件，再向 API 层抛出带 `runId` 的异常。

`SpringAgentFactory` 使用 Spring `ObjectProvider` 获取新的 `MyManus` 实例，避免把有状态 Agent 作为跨请求共享对象。

### 5. 所有权隔离

运行查询不是单纯的 `WHERE run_id = ?`，而是同时匹配：

- `run_id`
- `tenant_id`
- `user_id`

事件查询前也先验证运行所有权。这样即使调用者猜到其他人的 UUID，也无法读取其运行和事件。

### 6. API 变化

`POST /ai/agent` 现在返回结构化的 `AgentRunResult`，包含：

- `runId`
- `status`
- `currentStep`
- `content`
- `errorCode`

新增：

- `GET /ai/agent/runs/{runId}`：查询当前运行快照。
- `GET /ai/agent/runs/{runId}/events`：查询有序事件流。

两个查询接口都使用当前认证身份做所有权校验。

## 与主流 Agent Harness 的对照

### Claude Code

Claude Code 的工程体验强调会话连续性、可观察的工具调用以及执行边界。这里没有照搬其内部实现，而是吸收了相同的工程原则：一次任务必须有稳定身份，中间动作必须可审计，失败不能伪装成正常回答。

### OpenAI Codex Harness

Codex 类 harness 通常把一次任务建模为运行，把输出建模为事件，并在危险动作前引入审批状态。本次先落地运行 ID、事件流和预留的 `WAITING_APPROVAL` 状态，为后续工具审批协议提供状态机基础。

### LangGraph

LangGraph 的核心生产能力之一是基于线程和 checkpointer 的持久化执行。本次实现的是 Java/Spring 风格的最小检查点层：保存当前步骤及事件，但尚未序列化完整图状态，因此还不能声称支持任意节点恢复。

### OpenHands

OpenHands 采用事件流组织 Agent 动作和观察，便于回放、UI 展示和调试。本项目新增的 append-only 事件表遵循相同方向，但当前事件粒度仍是运行/步骤级，下一阶段需要增加标准化工具请求、审批、结果摘要事件。

### Spring AI Alibaba

Spring AI Alibaba Graph 更适合表达显式节点、状态和检查点。本项目当前仍保留现有 ReAct 类层次，先补齐外围运行控制面。后续若工作流分支、暂停和恢复成为核心需求，应把执行内核迁移为显式图，而不是继续在 `while` 循环上堆条件。

### MCP 生态

MCP 解决的是模型与工具/资源之间的协议标准化，不替代运行持久化。本次事件模型未来可以把 MCP tool call 的请求 ID、工具名、审批决策和结果摘要纳入统一事件，但不能把未信任的工具结果直接当作高权限指令或完整写入日志。

## 测试

新增 `AgentRunCoordinatorTest`，覆盖：

1. 成功执行会产生运行、步骤和成功生命周期记录。
2. 检查点在最终提交前保持 `RUNNING`。
3. 请求只保存 SHA-256，不进入事件载荷。
4. 失败会持久化为 `FAILED`，并且异常文本不会被当作有效答案返回。

本阶段修正后定向测试通过。此前 P0 全部测试与本阶段测试合计 21 个，失败 0、错误 0、跳过 0。

## 当前边界与下一步

这次实现已经达到“可追踪、可审计、可按所有者查询”的基础线，但还不是完整的 durable execution：

1. 检查点仅包含步骤号和状态，进程重启后不能从某个工具调用中间继续。
2. 工具调用尚未拆成 `requested/approved/started/completed/failed` 事件。
3. 没有异步队列、租约、心跳、超时回收和幂等执行键。
4. `final_output` 当前是数据库明文，需要按数据等级增加加密、脱敏和 TTL。
5. DDL 应交给 Flyway/Liquibase；事件表需要按规模设计归档或分区。
6. 需要将请求关联 ID、模型用量、时延和 OpenTelemetry trace ID 加入可观测字段。

建议后续顺序：先完成分层记忆和上下文预算，再引入结构化意图/任务规格；之后把工具审批事件与可恢复图执行接到当前运行控制面上。
