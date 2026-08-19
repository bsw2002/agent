# 文献智研 Agent

面向科研文献场景的 Spring AI 智能体应用，提供多轮对话、PDF 知识库、混合检索、工具调用、可终止的 Agent 执行循环、会话与长期记忆，以及模型调用 Trace 和人工评估能力。

## 技术栈

- Java 21、Spring Boot 3.5、Spring AI 1.1.8
- PostgreSQL、pgvector、MinIO
- OpenAI-Compatible Chat/Embedding 模型接口
- ReAct Agent、Tool Calling、MCP
- JDBC 会话记忆、滚动摘要、受治理的长期记忆
- 向量检索 + 关键词检索 + RRF 融合

## 本地配置

仓库中的 `application.yml` 不包含真实凭据。复制示例文件：

```powershell
Copy-Item `
  src/main/resources/application-local.example.yml `
  src/main/resources/application-local.yml
```

然后只在 `application-local.yml` 中填写 PostgreSQL、模型、搜索服务和 MinIO 配置。该文件已被 Git 忽略，应用会自动导入，不需要把凭据写入系统环境变量。

PostgreSQL 需要预先创建 `ai_agent` 数据库并启用 pgvector：

```sql
CREATE EXTENSION IF NOT EXISTS vector;
```

MinIO 需要预先创建配置中指定的 bucket，默认名称为 `rag`。

## 启动

```powershell
./mvnw.cmd spring-boot:run
```

默认地址：

- 应用：`http://localhost:8123/api/`
- Swagger：`http://localhost:8123/api/swagger-ui.html`
- OpenAPI：`http://localhost:8123/api/v3/api-docs`

## 主要 API

- `POST /api/ai/tasks`：识别意图并路由到直接对话、RAG 或工具 Agent。
- `POST /api/ai/chat`：普通多轮对话。
- `POST /api/ai/rag`：知识库问答。
- `POST /api/ai/agent`：执行 Agent 任务。
- `POST /api/rag/upload`：上传并摄取 PDF。
- `POST /api/ai/memories`、`GET /api/ai/memories`：长期记忆管理。
- `GET /api/ai/traces`：查询模型调用 Trace 与评估数据。

## 测试

```powershell
./mvnw.cmd test
```

默认测试不会访问真实模型和外部基础设施。只有显式启用 live tests，并提供相应配置时，才会调用真实模型、PostgreSQL/pgvector 和 MinIO。

## 改造说明

设计方案和每次改动记录位于：

- `docs/architecture/industry-upgrade-plan.md`
- `docs/change-notes/`

其中包含 Agent 循环终止、工具安全、身份边界、运行事件、记忆管理、意图识别、模型迁移、Trace 与评估等完整说明。
