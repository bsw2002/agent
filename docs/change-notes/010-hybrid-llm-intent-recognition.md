# 010：参考 DIET 的混合意图识别与澄清路由

## 本次目标

将 `007` 中的纯规则意图识别升级为“模型理解语义、Java 规则守住权限、失败可降级”的混合方案，同时保持现有 Spring AI 技术栈，不引入 AgentScope，也不修改原项目。

本次所有代码只修改：

```text
<project-root>
```

原项目 `<original-project>` 下的既有源码和参考项目 `<diet-agent>` 均只读参考，没有写入。

## DIET 项目中值得借鉴的结构

DIET 不是用一个传统的本地 DIET 分类模型，而是基于 AgentScope 构建独立的 `IntentAgent`：

1. `IntentAgentBuilder` 使用轻量模型完成意图分类和结构化抽取。
2. `IntentResult` 输出 `intent + slots + confidence`，而不是让后续代码解析自然语言。
3. `IntentAgentService` 在模型调用或 JSON 解析失败时回退到关键词规则。
4. `IntentReviseService` 结合会话状态、风险规则和置信度二次修正。
5. `DietOrchestratorService` 在修正后才选择业务分支，并记录 `INTENT_RECOGNIZED`、`INTENT_REVISED` 事件。
6. 缺信息时进入 `CLARIFY_NEEDED`，不会直接猜测并执行。

本项目保留这条设计主线，但针对通用科研 Agent 做了两项加强：

- 意图输出同时包含 capability；模型只能提出能力需求，不能决定最终授权。
- 文件写入、PDF 生成、资源下载、终端执行等高风险能力必须有确定性规则证据，模型单独声称需要该能力不会被采纳。

## 改造后的执行链

```text
用户请求
  -> RuleBasedTaskIntentClassifier：提取确定性证据和保底结果
  -> LlmTaskIntentClassifier：Spring AI 结构化语义分类
  -> TaskIntentValidator：闭集校验、字段校验、风险重新推导
  -> TaskIntentReviseService：否定语义、高风险证据、低置信度矫正
  -> HybridTaskIntentClassifier：失败时回退规则结果
  -> TaskOrchestrator
       -> CLARIFICATION：先追问，不调用业务模型和工具
       -> DIRECT_CHAT
       -> RETRIEVAL_AUGMENTED_CHAT
       -> TOOL_AGENT：只暴露 capability 白名单对应的工具
```

这不是“让大模型决定是否执行命令”。模型仅负责语义判断；Java 宿主负责校验、授权边界、路由和降级。

## 具体代码改动

### 1. Spring AI 结构化模型分类器

新增 `LlmTaskIntentClassifier` 和 `LlmIntentResult`：

- 使用项目现有 `ChatModel`/`ChatClient`，不额外引入 AgentScope 依赖。
- 系统提示明确列出允许的 intent、capability 和 risk 枚举。
- 要求识别否定表达和信息不足场景。
- 通过 Spring AI 的 `entity(LlmIntentResult.class)` 获取结构化对象。
- 分类阶段不注入业务工具，也不使用对话记忆，避免分类器自身产生副作用。

当前版本复用 `spring.ai.dashscope.chat.options.model` 指定的模型。这能减少配置复杂度，但尚未像 DIET 一样区分主模型和轻量分类模型；后续可在升级 Spring AI 稳定版时增加独立 intent `ChatModel` Bean。

### 2. 输出校验与 fail-closed

新增 `TaskIntentValidator`：

- intent、capability、risk 必须来自代码中的闭集枚举。
- 未知枚举、缺失字段、非法置信度直接判定模型结果无效。
- 模型声明的 intent 必须与 capability 推导出的 intent 一致。
- `RiskLevel` 不信任模型输出，由 Java 根据 capability 重新计算。
- 校验失败抛出异常，由混合分类器回退到规则结果。

这样即使模型输出了不存在的工具能力、格式漂移或把终端执行说成只读，也不会把错误结果直接交给执行层。

### 3. 规则证据与模型结果二次矫正

新增 `TaskIntentReviseService`，并把公共推导逻辑提取到 `TaskIntentPolicy`：

- `FILE_WRITE`、`PDF_WRITE`、`RESOURCE_DOWNLOAD`、`TERMINAL_EXECUTION` 必须由规则识别到明确动作词才会保留。
- 模型遗漏了用户明确要求的高风险动作时，规则证据会补回对应 capability。
- 对“不需要联网”“不要查知识库”“不要生成 PDF”等否定表达移除相关 capability。
- 模型主动建议澄清、规则认为模糊，或 confidence 低于阈值，都会设置 `requiresClarification=true`。
- 每次矫正后重新推导 intent 和 risk，避免字段互相矛盾。

同时修复了规则分类器对“不要生成 PDF”仍可能授予 `PDF_WRITE` 的问题。

### 4. 混合分类与故障降级

新增 `HybridTaskIntentClassifier` 并标记为 `@Primary`，因此现有调用方无需改接口：

- 规则分类始终先运行，提供安全证据和本地保底。
- 开启模型分类时，采用“模型候选结果 + Java 矫正”。
- 模型超时、网络故障、限流、JSON 解析失败或校验失败时，记录错误类型并回退规则结果。
- 日志不打印用户原文和模型完整输出。
- 可通过环境变量关闭模型分类，立即退回纯规则模式。

### 5. 真正的澄清分支

原代码虽然有 `requiresClarification` 字段，但编排器仍会继续执行。本次新增：

- `TaskExecutionMode.CLARIFICATION`
- `TaskClarificationService`
- `TaskOrchestrator` 的提前返回分支

当需要澄清时，系统返回确定性的中文问题，不调用聊天模型、RAG 或 Agent，也不会暴露/执行工具。例如：

- 文件操作不清楚时，询问目标文件及读写动作。
- PDF 任务不清楚时，询问生成还是读取，以及内容/文件位置。
- 终端任务不清楚时，询问命令、目标和预期结果。

澄清问题使用 Java 模板而不是再调用一次模型，目的是降低延迟、成本和不可控性。

### 6. 避免同一请求重复分类

原来 `TaskOrchestrator` 分类一次，进入工具 Agent 后 `AgentRunCoordinator` 又分类一次。模型分类上线后会导致重复调用和结果漂移。

本次给 `AgentRunCoordinator` 增加接收既有 `TaskSpec` 的重载，统一入口把已校验的分类结果直接传入运行协调器。旧三参数方法保留，避免破坏已有调用者。

## application.yml 配置

新增：

```yaml
suvia:
  intent:
    llm-enabled: ${SUVIA_INTENT_LLM_ENABLED:true}
    clarification-threshold: ${SUVIA_INTENT_CLARIFICATION_THRESHOLD:0.5}
```

需要配置的环境变量：

| 环境变量 | 是否必需 | 说明 |
|---|---:|---|
| `DASHSCOPE_API_KEY` | 开启模型意图识别时必需 | 与主聊天模型共用 DashScope 密钥 |
| `DASHSCOPE_CHAT_MODEL` | 否 | 当前也用于意图分类，沿用项目默认值即可 |
| `SUVIA_INTENT_LLM_ENABLED` | 否 | 默认 `true`；设为 `false` 可退回纯规则分类 |
| `SUVIA_INTENT_CLARIFICATION_THRESHOLD` | 否 | 默认 `0.5`，合法范围由应用配置约束使用 |

在 VMware 中可按现有启动方式注入：

```bash
export DASHSCOPE_API_KEY='你的密钥'
export DASHSCOPE_CHAT_MODEL='qwen-plus'
export SUVIA_INTENT_LLM_ENABLED='true'
export SUVIA_INTENT_CLARIFICATION_THRESHOLD='0.5'
```

如果暂时不想让每次 `/ai/tasks` 请求多一次意图模型调用：

```bash
export SUVIA_INTENT_LLM_ENABLED='false'
```

其余 PostgreSQL、pgvector、MinIO、搜索 API 配置仍按 `009-local-runtime-configuration.md`，本次没有改变。

## 接口行为变化

统一入口仍为：

```http
POST /ai/tasks
```

明确请求继续进入 `DIRECT_CHAT`、`RETRIEVAL_AUGMENTED_CHAT` 或 `TOOL_AGENT`。模糊请求现在会返回：

```json
{
  "mode": "CLARIFICATION",
  "clarificationRecommended": true,
  "answer": "请补充你希望执行的具体任务、目标对象和期望输出。"
}
```

前端应在 `mode === "CLARIFICATION"` 时把 `answer` 当成追问展示，并把用户下一条消息作为正常新请求提交。当前版本尚未引入专门的 `clarificationId` 或待恢复工作流状态。

## 测试改动

新增或扩展以下测试：

- `TaskIntentValidatorTest`：非法枚举、非法置信度、intent/capability 冲突、风险重算。
- `TaskIntentReviseServiceTest`：高风险能力证据、否定表达、低置信度澄清。
- `HybridTaskIntentClassifierTest`：模型成功、模型异常回退、关闭模型分类。
- `TaskOrchestratorTest`：澄清时不调用聊天模型、RAG 和 Agent；工具模式复用已分类 `TaskSpec`。
- `IntentRoutingGoldenEvalTest`：增加“不要生成 PDF”的否定样本。

使用 JDK 21 执行完整 Maven 测试结果：

```text
Tests run: 66, Failures: 0, Errors: 0, Skipped: 11
BUILD SUCCESS
```

11 个跳过项是原项目中依赖真实外部环境/凭据的测试，不是本次失败。构建中仍有原依赖 `protobuf-java-util:3.22.1` POM 元数据警告，但未影响编译和测试。

## 本次涉及的文件

新增生产代码：

- `intent/LlmIntentResult.java`
- `intent/LlmTaskIntentClassifier.java`
- `intent/HybridTaskIntentClassifier.java`
- `intent/TaskIntentValidator.java`
- `intent/TaskIntentReviseService.java`
- `intent/TaskIntentPolicy.java`
- `orchestration/TaskClarificationService.java`

修改生产代码：

- `intent/RuleBasedTaskIntentClassifier.java`
- `orchestration/TaskExecutionMode.java`
- `orchestration/TaskOrchestrator.java`
- `agent/runtime/AgentRunCoordinator.java`
- `src/main/resources/application.yml`

新增/修改测试：

- `HybridTaskIntentClassifierTest.java`
- `TaskIntentValidatorTest.java`
- `TaskIntentReviseServiceTest.java`
- `TaskOrchestratorTest.java`
- `intent-routing-golden.json`

## 当前边界与下一步

本次已经达到可上线试运行的“混合意图路由”基线，但不能直接宣称整个意图系统达到成熟工业平台水平，仍有以下工作：

1. 当前意图模型复用主模型，应增加独立轻量模型、超时和最大重试配置，降低分类成本与长尾延迟。
2. 需要积累真实标注集，统计 intent macro-F1、各 capability precision/recall、高风险误授权率和澄清率，而不能只看单元测试。
3. 应先用 shadow 模式记录模型候选与规则结果，不改变路由，观察一段时间后再全量启用。
4. 当前矫正只看本轮文本；“把刚才那个保存为 PDF”这类上下文意图需要引入受裁剪的最近会话状态。
5. 写入和终端能力还应增加显式 `WAITING_APPROVAL -> APPROVED/DENIED` 状态，而不只是澄清。
6. 应把 raw intent、revised intent、fallback 原因、模型耗时和 token 使用写入脱敏观测事件，形成离线评测闭环。

推荐推进顺序：先接真实前端做 shadow 采样和错例标注，再增加独立轻量模型，最后实现高风险操作审批协议。
