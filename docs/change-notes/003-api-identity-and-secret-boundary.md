# 003 - API、身份、会话与敏感数据边界

## 要解决的问题

原实现存在以下边界问题：

- 聊天和 Agent 使用 GET，用户消息会出现在 URL、浏览器历史、网关日志和监控标签中。
- SSE 被包在普通 `BaseResponse` 里，不是规范的 `text/event-stream` 响应。
- `chatId` 完全由客户端提供并直接作为存储键，不同用户传入同一 ID 可读写同一会话。
- 配置文件中存在明文数据库、模型、搜索和对象存储凭据。
- Advisor、AIApp 和 ToolCallAgent 在 INFO 日志中记录完整 Prompt、模型答案、工具参数和工具输出。
- 请求不存在结构化校验，可向模型提交空字符串或过大内容。

## API 协议改动

新接口：

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/chat` | 普通聊天 |
| POST | `/api/ai/chat/stream` | 规范 SSE 流 |
| POST | `/api/ai/rag` | 文献 RAG 聊天 |
| POST | `/api/ai/agent` | Agent 运行 |

请求使用 JSON：

```json
{
  "message": "...",
  "chatId": "optional-client-visible-id"
}
```

- `message` 必填，最大 20,000 字符。
- `chatId` 最大 128 字符，仅允许字母、数字、`_` 和 `-`。
- 不传 `chatId` 时由服务端生成 UUID。
- 同步响应返回 `chatId + content`。
- SSE 返回类型化 `token` 和 `complete` 事件，不再包装在普通 JSON response 对象内。

## 会话隔离

`ConversationKeyFactory` 区分：

- 客户端可见 `publicChatId`。
- 服务端存储 `storageConversationId`。

存储键由以下内容 SHA-256 派生：

```text
tenantId + NUL + userId + NUL + publicChatId
```

因此两个用户即使传入相同 `chatId`，也不会命中同一会话记录。这一变更保持现有 `ChatMemory` 接口不变，后续数据库模型升级后仍应把 tenant/user 作为显式字段和复合索引，而不只依赖 hash。

## 认证与生产模式

新增 Spring Security OAuth2 Resource Server：

- 本地开发默认 `suvia.security.enabled=false`，启动时打印明确的 WARN。
- `prod` profile 强制开启 JWT resource server。
- JWT `sub` 作为 user ID。
- 可配置 JWT claim（默认 `tenant_id`）作为 tenant ID。
- 生产模式下 token 缺少 subject 或 tenant 时拒绝请求。
- 服务端 session 设为 stateless，禁用针对 cookie session 的 CSRF 链路。

生产启动示例：

```powershell
$env:SPRING_PROFILES_ACTIVE='prod'
$env:SUVIA_JWT_ISSUER_URI='https://identity.example.com/realms/suvia'
```

## 凭据和配置

`application.yml` 中的所有以下凭据已改为环境变量：

- PostgreSQL password。
- DashScope API key。
- Search API key。
- MinIO access key / secret key。

同时将原来的内网数据库和 MinIO 默认端点改为 localhost。检查结果：5 个 secret 配置项全部使用 `${...}` 占位符。

> 注意：如果原凭据曾被提交到 Git 或分享给其他人，只从当前文件删除是不够的；需要在 PostgreSQL、DashScope、Search API 和 MinIO 端执行凭据轮换。

## 日志改动

- Advisor 只记录请求/回答字符数。
- AIApp 只记录请求完成和响应长度。
- ToolCallAgent 只记录工具名称和数量，不记录 arguments 和 response data。
- 客户端错误返回 incident ID；普通 INFO/ERROR 日志不打印用户内容或底层异常详情。
- 完整 stack trace 只位于 DEBUG，生产默认 INFO。

## 框架和业界方案对照

### Claude Code / Claude Agent SDK

Claude Code 会话与项目目录绑定，支持 resume/fork，但它是本地单用户工具。SaaS Agent 不能直接复制“用户传 session ID”的假设，必须将 tenant 和 user 加入服务端会话命名空间。

### OpenAI Agents SDK

OpenAI Agents SDK 的 tracing 支持关闭 sensitive data，并用 trace/group ID 连接运行。本变更先停止无条件记录模型内容；P1 将把 incident ID 升级为贯穿模型、工具和 checkpoint 的 run/trace ID。

### Spring Security

OAuth2 Resource Server 适合把身份验证交给 Keycloak、Auth0、Azure Entra ID 或企业 OIDC 服务。项目只验证 JWT 并读取 tenant claim，不自己保存密码。

### LangGraph / OpenHands

这些框架的服务化部署同样会区分 conversation/session 和用户身份。框架 checkpoint 并不自动提供 tenant authorization；在数据库和 API 边界仍需要强制所有权。

### 常见替代方案

- **API key**：实现简单，但对多用户、tenant、过期、撤销和权限范围的支持较弱，不作为本项目的生产主方案。
- **HTTP session/cookie**：适合同源 Web UI，但需要 CSRF、session store 和横向扩展策略。
- **mTLS + service identity**：适合纯内部服务间 Agent API，但不能单独替代终端用户身份。

## 测试

新增并通过：

- 相同 public chat ID 在不同 tenant/user 下生成不同 storage ID。
- 同一身份和 chat ID 生成稳定存储键。
- 非法 chat ID 被拒绝。
- 开发模式生成明确的本地身份。
- 安全模式拒绝匿名身份。

与前两个变更集合并执行 `19` 个无外部服务依赖的测试，结果为 `19 passed, 0 failures, 0 errors`。

## 已知限制

- `/agent` 已返回作用域化 chat ID，但 `MyManus` 本身仍未接入持久化 Agent run/checkpoint，将在 P1 完成。
- 当前 JDBC memory 表仍只有 hash 后的 `conversation_id`；后续迁移为 tenant/user/conversation 显式列和数据库级约束。
- 尚未实现角色/能力级授权，例如只允许部分用户运行网络工具或生成报告。
- Swagger 在当前生产配置中仍可匿名访问；真实生产环境应根据部署策略关闭或只允许内网访问。

