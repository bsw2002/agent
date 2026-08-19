# 001 - Agent 循环终止语义与最终答案

## 要解决的问题

原实现在模型不再请求工具时只返回 `false`，但不把 Agent 设为完成。因此：

1. Agent 会继续运行到 `maxSteps`。
2. 每轮都把 `nextStepPrompt` 作为新的 `UserMessage` 加入历史，造成指令冒充用户输入和上下文膨胀。
3. 模型生成的最终答案没有作为 `run()` 结果返回，用户看到的是内部步骤记录。
4. 达到最大步数被当成 `FINISHED`，实际上这应是可观测的失败。
5. `ReActAgent.step()` 吞掉异常并返回自然语言，上层无法区分正常输出与执行失败。

## 代码改动

### `BaseAgent`

- 新增 `finalOutput`，明确区分面向用户的最终答案和内部工具观测。
- 当状态进入 `FINISHED` 时立即返回最终答案。
- 超过最大步数抛出 `AgentMaxStepsExceededException`，状态设为 `ERROR`。
- 执行失败抛出 `AgentExecutionException`，不再伪装成模型回答。
- 使用 JDK `String.isBlank()` 替换 `jsoup` 内部工具类。

### `ReActAgent`

- `think() == false` 表示已经生成最终回答，立即进入 `FINISHED`。
- 移除捕获所有异常并 `printStackTrace()` 的逻辑。

### `ToolCallAgent`

- 模型无工具调用时，将 `AssistantMessage.text` 保存为 `finalOutput`。
- `nextStepPrompt` 只作为当前模型请求的 system execution guidance，不写入用户历史。
- 模型调用失败时抛出类型化异常。

### 新增异常

- `AgentExecutionException`
- `AgentMaxStepsExceededException`

## 框架与同类项目对照

### Claude Code / Claude Agent SDK

Claude Agent SDK 会区分 assistant message、tool use、tool result 和 result message，并提供 `max_turns`、interrupt 和 session control。本变更首先在现有代码中建立同样的基本语义：工具观测不等于最终回答，超限不等于成功。

### OpenAI Agents SDK

OpenAI Agents SDK 的 Runner 负责循环、工具执行和终止，最终返回结构化 `RunResult`，而不是将每一步拼成字符串。P1 将在本次修复之上引入项目自己的 `Run` 和 `AgentEvent`。

### LangGraph

LangGraph 使用显式 graph state 和 stop condition，能够区分继续执行、等待人工输入、成功与失败。当前修改是从布尔值 ReAct 循环向显式状态机迁移的第一步。

### OpenHands

OpenHands 将 Agent 的 `Action` 和 Runtime 的 `Observation` 写入 EventStream，最终状态由 AgentController 管理。这避免了本项目原来把工具文本结果直接当作整个 Agent 运行结果的问题。

### Spring AI Alibaba

新版 ReactAgent/Graph 已包含更完整的循环和状态能力。本阶段先修复现有循环，以便用测试固化期望行为；后续升级框架时，这些测试将作为迁移验收标准。

## 测试

新增 `BaseAgentLoopTest`，不访问模型、数据库或网络，覆盖：

1. 一次工具行动后返回模型最终答案。
2. 无需工具时一步结束。
3. 达到 `maxSteps` 后以失败结束。
4. 内部异常被包装为 `AgentExecutionException` 并向上层传播。

执行命令：

```powershell
mvn.cmd -q -Dtest=BaseAgentLoopTest test
```

结果：`4` 个测试全部通过。

## 已知限制

- 当前仍然使用字符串作为公开 `run()` 结果。
- 尚未实现 `WAITING_APPROVAL`、`CANCELLED` 和持久化 checkpoint。
- `TerminateTool` 仍存在，后续将取消对模型主动调用 terminate 才能正常结束的依赖。
- 旧的 Spring Boot 集成测试会访问真实模型和数据库，不适合作为快速回归测试；评测改造会在后续变更集处理。

