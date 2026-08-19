# 012 - 移除 Spring AI Alibaba 并迁移到 Spring AI 1.1.8

## 1. 目标与边界

本次改动只发生在复制目录 `<project-root>`，没有修改原项目目录中的任何文件。

目标如下：

1. 删除 `Spring AI Alibaba` 依赖与 Alibaba 专用 Java API；
2. 升级到上游 `Spring AI 1.1.8`；
3. 通过百炼 OpenAI-Compatible 接口继续使用 `qwen-plus`；
4. 继续使用 `text-embedding-v1`，并保持 PgVector 维度为 1536；
5. 保持现有 Agent、工具调用、RAG、混合检索、意图识别和滚动摘要记忆功能；
6. 保证原有 66 项测试不回归，并增加真实模型冒烟测试。

## 2. 依赖迁移

### 2.1 删除的供应商绑定

从 `pom.xml` 删除：

```xml
<dependency>
    <groupId>com.alibaba.cloud.ai</groupId>
    <artifactId>spring-ai-alibaba-starter</artifactId>
</dependency>
```

同时删除 Java 代码中的以下 Alibaba 专用类型：

- `DashScopeChatOptions`
- 其他 `com.alibaba.cloud.ai.*` 引用

最终源码和 `pom.xml` 对 `spring-ai-alibaba`、`com.alibaba.cloud.ai` 的扫描结果均为 0。

### 2.2 新的上游 Spring AI 依赖

使用 BOM 统一锁定版本：

```xml
<spring-ai.version>1.1.8</spring-ai.version>
```

核心依赖为：

- `spring-ai-starter-model-openai`：通过标准 OpenAI-Compatible 协议连接百炼；
- `spring-ai-rag`：查询改写、多查询扩展、检索增强等 RAG API；
- `spring-ai-advisors-vector-store`：Spring AI 向量检索 Advisor；
- `spring-ai-starter-vector-store-pgvector`：PgVector Starter；
- `spring-ai-markdown-document-reader`；
- `spring-ai-pdf-document-reader`。

这种结构把“应用框架”和“模型供应商”解耦：业务代码只依赖 `ChatModel`、`EmbeddingModel`、`ChatClient` 和 `ToolCallback`，未来切换其他 OpenAI-Compatible 服务时，通常只需更换 URL、Key 和模型名。

## 3. 模型配置

`application.yml` 使用以下结构：

```yaml
spring:
  ai:
    model:
      chat: openai
      embedding: openai
    openai:
      api-key: '<百炼 API Key>'
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      chat:
        options:
          model: qwen-plus
      embedding:
        options:
          model: text-embedding-v1
    vectorstore:
      pgvector:
        dimensions: 1536
```

Spring AI 1.1.8 会分别请求：

- `POST https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions`
- `POST https://dashscope.aliyuncs.com/compatible-mode/v1/embeddings`

真实测试日志已经证明这两个最终 URL 拼接正确。

> 当前项目遵照本地运行要求保留了直接配置方式。若代码将上传 GitHub、Gitee 或交给他人，必须先吊销并替换当前 Key，生产环境仍应改用密钥管理服务或外部配置文件。

## 4. Spring AI 1.1.8 API 兼容改造

### 4.1 Agent 工具调用

原 Alibaba 专用配置：

```java
DashScopeChatOptions.builder()
        .withProxyToolCalls(true)
        .build();
```

迁移为上游 Spring AI：

```java
OpenAiChatOptions.builder()
        .internalToolExecutionEnabled(false)
        .build();
```

含义保持一致：模型负责决定调用哪个工具，项目自己的 Agent 循环负责真正执行工具、记录观察结果并进入下一步，避免 Spring AI 在模型层内部重复执行工具。

注册好的 `ToolCallback[]` 现在通过 `toolCallbacks(...)` 传入，不再误用接收任意工具对象的 `tools(Object...)`，同时消除了 Java 可变参数类型警告。

### 4.2 Advisor 链

旧版接口：

- `CallAroundAdvisor`
- `StreamAroundAdvisor`
- `AdvisedRequest`
- `AdvisedResponse`

1.1.8 接口：

- `CallAdvisor` / `StreamAdvisor`
- `ChatClientRequest` / `ChatClientResponse`
- `CallAdvisorChain.nextCall(...)`
- `StreamAdvisorChain.nextStream(...)`
- `ChatClientMessageAggregator`

自定义日志 Advisor 的行为没有改变，仍只记录请求和响应字符数，不输出用户正文、模型正文或密钥。

### 4.3 多轮记忆

Spring AI 1.1.8 的 `ChatMemory` 接口由：

```java
get(String conversationId, int lastN)
```

变为：

```java
get(String conversationId)
```

本项目实现了新的标准方法，并保留原 `get(id, lastN)` 作为项目级兼容重载。因此：

- `MessageChatMemoryAdvisor` 可以按 1.1.8 标准读取记忆；
- 原有显式窗口测试和内部调用不需要重写；
- “滚动摘要 + 最近窗口 + Token 预算”策略保持不变。

会话 ID 参数改用 `ChatMemory.CONVERSATION_ID`，不再依赖已删除的 `AbstractChatMemoryAdvisor` 常量。

### 4.4 RAG

`RetrievalAugmentationAdvisor` 在 1.1.8 中位于：

```text
org.springframework.ai.rag.advisor.RetrievalAugmentationAdvisor
```

关键词元数据增强器位于：

```text
org.springframework.ai.model.transformer.KeywordMetadataEnricher
```

查询改写、多查询扩展、上下文增强、混合检索和 RRF 业务实现本身未被删除，只迁移了框架包名和模块依赖。

### 4.5 PgVector 自动配置

自动配置类的新包名为：

```text
org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration
```

项目继续排除框架默认 PgVector Bean，保留现有自定义 `PgVectorVectorStoreConfig`，以维持表名、HNSW、余弦距离、1536 维和“仅空表入库”策略。

### 4.6 启动演示调用

原 `CommandLineRunner` 会在每次启动应用时自动调用一次模型，既增加启动失败点，也可能产生无意费用。现在由下列开关控制，默认关闭：

```yaml
suvia:
  demo:
    invoke-enabled: false
```

只有明确改为 `true` 时才执行该演示调用。

## 5. 新增真实模型测试

新增 `OpenAiCompatibleModelLiveTest`，只在下面的环境变量为 `true` 时执行：

```powershell
$env:SUVIA_RUN_LIVE_TESTS='true'
```

测试包含两项：

1. `qwenPlusReturnsAChatCompletion`：确认配置模型名为 `qwen-plus`，并要求模型返回非空文本；
2. `textEmbeddingV1ReturnsPgvectorCompatibleDimension`：确认配置模型名为 `text-embedding-v1`，执行真实 Embedding 请求并断言向量维度为 1536。

该测试使用一个最小 Spring Boot 上下文，只加载 Spring AI 模型自动配置，不连接 PostgreSQL、PgVector、MinIO，也不会执行 Agent 工具，因此测试结果能准确定位到模型供应商链路。

## 6. 验证结果

### 6.1 编译

```text
mvn -DskipTests compile
BUILD SUCCESS
```

### 6.2 原有离线回归

```text
原有测试：66
失败：0
错误：0
跳过：11（原有真实环境测试，需显式开关）
```

加入 2 个新的真实模型测试后，关闭真实环境开关时的总发现数为 68，其中原 66 项结果不变，新增 2 项按设计跳过。

### 6.3 真实供应商测试

允许网络访问后得到：

```text
text-embedding-v1：通过，返回 1536 维向量
qwen-plus：未通过，HTTP 403 AllocationQuota.FreeTierOnly
```

服务端信息表明：当前百炼账户的免费额度已耗尽，并且启用了“仅使用免费额度”。这同时证明：

- DNS、TLS 和网络连接正常；
- OpenAI-Compatible URL 正确；
- 请求已到达百炼；
- Key 并非格式错误或未被识别；
- Embedding 模型及 Spring AI 1.1.8 调用链已完全通过。

`qwen-plus` 尚不能声称真实调用通过，原因是账户计费限制，而不是代码测试被跳过或伪造成功。

## 7. 解除账户阻塞后的复测

在百炼控制台完成以下任一操作：

1. 充值并开通按量付费；
2. 关闭“仅使用免费额度 / use free tier only”限制；
3. 换用一个已开通 `qwen-plus` 计费权限的百炼 API Key。

然后在 PowerShell 中运行：

```powershell
$env:JAVA_HOME='C:\path\to\jdk-21'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
$env:SUVIA_RUN_LIVE_TESTS='true'
$env:SPRING_AI_RETRY_MAX_ATTEMPTS='1'
mvn.cmd -Dtest=OpenAiCompatibleModelLiveTest test
```

预期最终结果：

```text
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

## 8. 本次修改文件

- `pom.xml`
- `src/main/resources/application.yml`
- `src/main/java/org/suvia/AiAgentApplication.java`
- `src/main/java/org/suvia/agent/ToolCallAgent.java`
- `src/main/java/org/suvia/agent/MyManus.java`
- `src/main/java/org/suvia/agent/intent/LlmTaskIntentClassifier.java`
- `src/main/java/org/suvia/advisor/myAdvisor.java`
- `src/main/java/org/suvia/app/AIApp.java`
- `src/main/java/org/suvia/chatMemory/AbstractSummarizingChatMemory.java`
- `src/main/java/org/suvia/chatMemory/ConversationSummarizerService.java`
- `src/main/java/org/suvia/demo/invoke.java`
- `src/main/java/org/suvia/demo/rag/myMultiQueryExpander.java`
- `src/main/java/org/suvia/rag/HybridSearch/HybridSearchConfiguration.java`
- `src/main/java/org/suvia/rag/MyKeywordEnricher.java`
- `src/main/java/org/suvia/rag/PgVectorVectorStoreConfig.java`
- `src/main/java/org/suvia/rag/QueryRewriter.java`
- `src/main/java/org/suvia/rag/service/AppRagCustomAdvisorFactory.java`
- `src/main/java/org/suvia/rag/service/AppVectorStoreConfig.java`
- `src/main/java/org/suvia/tools/ToolRegistration.java`
- `src/test/java/org/suvia/integration/OpenAiCompatibleModelLiveTest.java`
- `docs/change-notes/012-spring-ai-1.1.8-provider-migration.md`
