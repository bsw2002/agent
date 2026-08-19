# 013：模型调用 Trace 与评估闭环（第一版）

日期：2026-08-18  
实施目录：`<project-root>`  
原始项目：`<original-project>`（本次未修改）

## 1. 本次目标与完成结论

本次按“先做简单、可运行、能查询、能评估”的原则，参考 `diet-agent` 的调用生命周期设计，实现了第一版模型可观测闭环：

1. 每一次真正进入 Spring AI `ChatModel` 或 `EmbeddingModel` 的调用都会生成独立调用记录；
2. 同一个 HTTP 请求内的意图识别、查询改写、RAG 回答、Agent 推理、记忆摘要等调用共享一个 `traceId`；
3. 支持按 trace、单次模型调用、会话、业务场景、状态和时间范围查询；
4. 支持无模型费用的规则评估、可选的 LLM-as-a-Judge 评估以及人工评分；
5. 对流式调用、异常、取消、Token、耗时、模型名、内容脱敏和租户隔离进行了处理；
6. 追踪存储失败只记录告警，不反向破坏正常的大模型业务调用。

这不是完整的 OpenTelemetry 分布式观测平台，而是适合当前单体 Spring Boot 项目的可落地第一版。

## 2. 对 DIET 方案的借鉴和改进

参考代码：

- `diet-agent/src/main/java/.../AgentTraceService.java`
- `diet-agent/src/main/java/.../AgentTraceController.java`
- `diet-agent/src/main/java/.../EvaluationService.java`
- `diet-agent/src/main/java/.../EvaluationJudgeService.java`

| 能力 | DIET 的思路 | 本项目第一版 |
|---|---|---|
| 生命周期 | `TraceScope` 在请求/任务开始与结束时建立和关闭 | `ModelTraceFilter + ModelTraceContext` 建立请求级 trace |
| 模型调用记录 | 业务代码通过 `callAgent` 等包装器主动上报 | 在 Spring AI 的 `ChatModel/EmbeddingModel` 边界统一 AOP 拦截，业务代码漏包一层也不会漏掉实际模型调用 |
| 业务语义 | 事件名、Agent 名 | `ModelCallScene` 标明意图识别、查询改写、RAG、Agent、记忆摘要等场景 |
| 存储 | Trace JSON/事件查询 | PostgreSQL 两张规范化表，支持条件查询和后续统计 |
| 评估 | 规则、模型裁判、人工标签 | 保留三种方式，但第一版只实现必要指标和接口 |
| 流式处理 | 以 Agent 调用边界为主 | 处理 Spring AI 的延迟订阅，在完成、错误或客户端取消时落库 |

关键取舍：DIET 的手工包装方式容易因为新增调用点时忘记接入而漏数。本项目把“是否真的调用了模型”交给 Spring AI 模型接口边界判断，业务层只负责补充“这次调用是做什么”的场景标签。

## 3. 调用链结构

```text
HTTP 请求
  └─ X-AI-Trace-Id（传入合法 UUID 则复用，否则服务端生成）
      ├─ 意图识别模型调用      callId=A, scene=INTENT_CLASSIFICATION
      ├─ 查询改写模型调用      callId=B, scene=QUERY_REWRITE
      ├─ Embedding 模型调用    callId=C, scene=EMBEDDING
      └─ RAG 回答模型调用      callId=D, scene=RAG_ANSWER

会话 publicChatId 可跨多个 trace；Agent 运行还会绑定 runId。
```

粒度定义与主流观测平台一致：一次请求/Agent 运行视为 trace，一次模型调用视为 trace 下的一条 observation。Langfuse 官方同样把单个步骤称为 observation、把一次请求称为 trace、把多轮交互归为 session；本项目目前用 `callId / traceId / publicChatId` 对应这三个层次。

## 4. 核心实现

### 4.1 统一模型边界拦截

`ModelCallTraceAspect` 精确拦截：

- `ChatModel.call(Prompt)`：同步聊天；
- `ChatModel.stream(Prompt)`：流式聊天；
- `EmbeddingModel.call(EmbeddingRequest)`：向量模型。

记录字段包括：

- `callId`、`traceId`、可选 `runId`；
- `tenantId`、`userId`、`publicChatId`；
- `scene`、调用类型、模型名、成功/失败/取消状态；
- 输入 SHA-256、可选输入/输出预览、Embedding 维度；
- 输入 Token、输出 Token、总 Token、耗时；
- 异常类型、脱敏后的异常摘要、开始和结束时间。

同步调用在返回或抛错时写入；流式调用通过 Reactor 终止信号在正常完成、异常和取消时各写一次，使用原子开关保证不会重复落库。

### 4.2 流式上下文传播

Spring AI 的 `ChatClient.stream()` 返回的是延迟执行 Flux，真正的 `ChatModel.stream()` 经常发生在控制器已经返回之后。如果只用普通 ThreadLocal，trace、用户和场景会丢失。

`ModelTraceContext.propagate(...)` 会在创建流时复制当前 trace 状态，并在订阅期间恢复：

- `traceId`；
- `tenantId/userId`；
- `publicChatId`；
- `runId`；
- `scene`。

这保证了流式聊天不会错误落到 `system/background` 或生成另一个 trace。

### 4.3 业务场景标签

当前场景枚举：

- `DIRECT_CHAT`
- `REPORT_GENERATION`
- `INTENT_CLASSIFICATION`
- `QUERY_REWRITE`
- `MULTI_QUERY_EXPANSION`
- `RAG_ANSWER`
- `AGENT_THINK`
- `MEMORY_SUMMARY`
- `KEYWORD_ENRICHMENT`
- `EMBEDDING`
- `EVALUATION_JUDGE`
- `UNKNOWN`

已在 AI 对话、ReAct Agent、混合意图识别、滚动摘要、查询改写、多查询扩展、关键词增强等现有调用位置增加场景作用域。即使未来有调用点没有添加场景，模型边界仍然会记录，只是使用默认场景。

### 4.4 PostgreSQL 存储

应用启动时按项目现有 JDBC Store 的模式创建：

1. `suvia_model_call_trace`：每行是一笔模型调用；
2. `suvia_trace_evaluation`：每行是一笔规则、模型或人工评估。

索引覆盖：

- `tenant_id + user_id + started_at`：用户自己的时间线查询；
- `trace_id + started_at`：trace 详情；
- `tenant_id + user_id + trace_id + created_at`：评估历史。

所有查询都把 `tenantId + userId` 放进 SQL 条件，不是查出后再做内存过滤，因此一个用户不能通过猜测 UUID 读取另一个用户的模型内容。

## 5. 查询和评估接口

接口前缀：`/ai/traces`

| 方法 | 地址 | 用途 |
|---|---|---|
| GET | `/ai/traces/{traceId}` | 查 trace 汇总、模型调用及评估历史 |
| GET | `/ai/traces/{traceId}/calls` | 只查 trace 下的模型调用 |
| GET | `/ai/traces/calls/{callId}` | 查单笔模型调用 |
| GET | `/ai/traces` | 按 chatId、scene、status、startAt、endAt、limit 筛选 |
| POST | `/ai/traces/{traceId}/evaluate` | 运行规则评估；可选模型裁判 |
| PUT | `/ai/traces/{traceId}/label` | 保存人工评分和原因 |

每个 HTTP 响应都会返回 `X-AI-Trace-Id`。前端应保存聊天请求响应头里的这个值，再用它查询详情。

示例：

```http
GET /ai/traces?scene=RAG_ANSWER&status=SUCCESS&limit=20
```

```http
POST /ai/traces/3b97cc9a-7d18-4c38-9c19-b6b24c7aa2c8/evaluate
Content-Type: application/json

{"includeLlmJudge": false}
```

```http
PUT /ai/traces/3b97cc9a-7d18-4c38-9c19-b6b24c7aa2c8/label
Content-Type: application/json

{"score": 4.5, "reason": "回答有引用且覆盖了核心问题"}
```

## 6. 评估规则

### 6.1 默认规则评估（不调用模型、不产生模型费用）

规则指标统一映射为 1～5 分：

- `success`：所有模型调用的成功比例；
- `nonEmptyOutput`：聊天输出非空或 Embedding 维度大于 0；
- `latency`：`<=2s / <=5s / <=10s / <=20s / >20s` 对应 `5/4/3/2/1`；
- `tokenEfficiency`：聊天总 Token `<=2k / <=4k / <=8k / <=16k / >16k` 对应 `5/4/3/2/1`。

`ruleScore` 是当前可观测指标的平均值。生产关闭内容采集时，系统不会把“因隐私配置而看不到输出”错误判断成空输出，而是跳过该项。

### 6.2 可选 LLM-as-a-Judge

传入 `{"includeLlmJudge": true}` 后，使用当前配置的聊天模型从以下维度分别给 1～5 分：

- relevance：是否回答了用户问题；
- correctness：内容是否正确；
- completeness：是否完整。

Judge 使用 Spring AI 结构化映射，不解析自由文本；trace 内容被包在不可信数据边界中，提示模型不得执行其中的指令。Judge 自己的调用也会被记录为 `EVALUATION_JUDGE`，但不会混入被评估 trace 的规则分数。

最终 `overallScore = (ruleScore + llmJudgeScore) / 2`。必须明确：没有标准答案和检索证据时，LLM Judge 的 correctness 只是模型判断，不能当作事实正确率。

生产默认不保存输入输出，所以直接运行 LLM Judge 会返回“没有可评估内容”，避免把 `null` 字符串送给裁判模型。要启用线上 Judge，应先建立合规采样、脱敏和保留策略。

### 6.3 人工评分

人工评分范围 1～5，原因最多 2000 字符。人工分单独存储，不覆盖规则分和模型分，便于以后用人工样本校准 Judge。

## 7. 配置

本地 `application.yml`：

```yaml
suvia:
  model-trace:
    enabled: true
    capture-content: true
    max-content-characters: 4000
```

生产 `application-prod.yml`：

```yaml
suvia:
  model-trace:
    capture-content: false
```

配置含义：

- `enabled=false`：完全关闭本项目自建的数据库调用追踪；
- `capture-content=true`：保存截断且脱敏后的用户输入和模型输出；
- `max-content-characters`：单字段最大字符数，代码最低限制为 256；
- Embedding 原文永不写入 trace，只存输入 SHA-256 和输出维度；
- 系统 Prompt 不写入 trace，只取最终 Prompt 中的用户消息；
- 对 `api-key/password/secret` 和常见 `sk-...` 形式进行二次脱敏。

数据库沿用现有 PostgreSQL 配置，不需要新增服务或 Key。模型裁判沿用当前 `qwen-plus` 的 `DASHSCOPE_API_KEY`，不会引入第二套模型配置。

## 8. 代码变更清单

新增核心文件：

- `src/main/java/org/suvia/trace/ModelCallTraceAspect.java`
- `src/main/java/org/suvia/trace/ModelTraceContext.java`
- `src/main/java/org/suvia/trace/ModelTraceFilter.java`
- `src/main/java/org/suvia/trace/JdbcModelTraceRepository.java`
- `src/main/java/org/suvia/trace/ModelTraceRepository.java`
- `src/main/java/org/suvia/trace/ModelTraceService.java`
- `src/main/java/org/suvia/trace/ModelCallRecord.java`
- `src/main/java/org/suvia/trace/TraceEvaluationRecord.java`
- `src/main/java/org/suvia/trace/TraceDetails.java`
- `src/main/java/org/suvia/trace/TraceEvaluationRequest.java`
- `src/main/java/org/suvia/trace/TraceLabelRequest.java`
- `src/main/java/org/suvia/trace/ModelCallScene.java`
- `src/main/java/org/suvia/trace/ModelCallType.java`
- `src/main/java/org/suvia/trace/ModelCallStatus.java`
- `src/main/java/org/suvia/controller/ModelTraceController.java`

增加场景或上下文绑定：

- `AIApp`
- `ToolCallAgent`
- `LlmTaskIntentClassifier`
- `ConversationSummarizerService`
- `QueryRewriter`
- `myMultiQueryExpander`
- `MyKeywordEnricher`
- `AgentRunCoordinator`
- `AiController`
- `TaskController`

配置：

- `src/main/resources/application.yml`
- `src/main/resources/application-prod.yml`

测试：

- `ModelCallTraceAspectTest`
- `ModelTraceContextTest`
- `ModelTraceServiceTest`

## 9. 测试结果

专项测试覆盖：

- 同步聊天成功记录；
- 原异常继续抛出且失败调用落库；
- Embedding 不保存原文并记录维度；
- 流式结果在结束时聚合并记录；
- AOP pointcut 能真实拦截 Spring AI `ChatModel` 代理；
- 延迟订阅时 trace、用户、会话和场景不丢失；
- 规则评估、人工评分、用户隔离；
- 隐私关闭时不误判空输出；
- 无内容时拒绝运行 LLM Judge。

最终命令：

```text
mvn test
```

最终确认统计：

```text
Tests run: 79, Failures: 0, Errors: 0, Skipped: 13
BUILD SUCCESS
```

13 项跳过测试是项目原有的真实外部环境测试，需要显式开启并连接模型、PostgreSQL/pgvector、MinIO 等环境；本轮没有伪造其通过结果。此前真实 `qwen-plus` 聊天请求仍受阿里云账户 `403 FreeTierOnly` 限制，修复账户权限后才可做真实 Judge 验证。

## 10. 与行业方案的关系

### Spring AI 原生 Observability

Spring AI 已基于 Micrometer Observation 对 `ChatClient`、Advisor、`ChatModel`、`EmbeddingModel` 和 VectorStore 提供指标与追踪，并默认不导出敏感 Prompt/Completion 内容。官方资料：<https://docs.spring.io/spring-ai/reference/observability/index.html>

本项目没有替代这套能力：当前数据库 trace 解决的是“在现有项目里马上查询具体模型调用并挂评估结果”；后续接入 Actuator、Micrometer Tracing 和 OTLP 时，两者可以并存。

### OpenTelemetry

OpenTelemetry Java 提供厂商无关的 trace、metric、log 体系，并可通过 Java Agent 或 Spring Boot Starter 自动采集。官方资料：<https://opentelemetry.io/docs/zero-code/java/spring-boot-starter/>

它适合服务拆分后把 HTTP、数据库、向量检索、工具调用和模型调用串成跨服务 trace。当前第一版没有引入 Collector，避免为一个单体项目增加独立部署和运维负担。

### Langfuse

Langfuse 的核心模型是 observation → trace → session，支持 Token/成本/延迟、人工分、LLM Judge、数据集与实验。官方资料：

- <https://langfuse.com/docs/observability/data-model>
- <https://langfuse.com/docs/evaluation/evaluation-methods/llm-as-a-judge>

如果项目需要可视化 Trace 树、Prompt 版本对比、采样评估和成本看板，Langfuse 比继续自建前端更合适。

### Arize Phoenix

Phoenix 是基于 OpenTelemetry/OpenInference 的开源 AI 可观测与评估平台，支持规则评估、LLM Judge、人工标注、数据集和实验。官方资料：

- <https://arize.com/docs/phoenix/>
- <https://arize.com/docs/phoenix/evaluation/evals>

它尤其适合后续评估 RAG 的 faithfulness、document relevance、NDCG、Precision@K、Hit Rate 等质量指标。

## 11. 第一版明确不做的内容

为避免把简单需求做成平台，本次暂不实现：

- 完整父子 Span 树和跨服务 trace propagation；
- Tool/Retriever 每一步的统一 OpenTelemetry Span；
- Token 单价和人民币成本计算；
- 首 Token 延迟（TTFT）；
- Trace 可视化前端和聚合看板；
- 自动抽样、批量离线 Judge、告警；
- Prompt 版本、模型版本、发布版本对比；
- PostgreSQL 分区、自动过期和冷存储。

## 12. 下一阶段建议

按收益排序：

1. 前端先读取 `X-AI-Trace-Id`，实现 trace 列表、详情和人工评分三个页面；
2. 为意图识别建立固定金标集，计算 Accuracy、Macro-F1 和澄清率，而不是用通用 Judge 评价分类；
3. 为 RAG 记录检索文档 ID、分数和最终引用，增加 Recall@K、MRR/NDCG、faithfulness；
4. 修复 DashScope 账户 403 后，仅对测试/采样数据开启 LLM Judge；
5. 当项目拆分服务或需要团队看板时，再用 Spring AI Micrometer Observation 导出 OTLP，接 Langfuse 或 Phoenix；
6. 上线前把建表语句迁入 Flyway/Liquibase，并增加 30～90 天保留和清理策略。

第一版已经能回答三个核心问题：某个用户请求到底调用了几次什么模型、每次调用耗时/Token/结果如何、这条 trace 的规则/模型/人工质量评分是多少。
