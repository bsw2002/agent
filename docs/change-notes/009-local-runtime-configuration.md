# 009：从原 application.yml 安全映射本地运行配置

## 背景

原项目 `application.yml` 已经包含数据库、DashScope、搜索 API 和 MinIO 配置。改进副本没有删除这些能力，而是把敏感值改为环境变量占位符，避免密钥继续进入源码、Git、日志和发布包。

检查环境变量为空，只说明当前 shell 没有注入配置，不能说明用户没有原始配置。本变更增加安全的本地迁移启动方式。

## 配置映射

| 原 YAML 路径 | 副本环境变量 |
|---|---|
| `spring.datasource.url` | `SUVIA_DB_URL` |
| `spring.datasource.username` | `SUVIA_DB_USERNAME` |
| `spring.datasource.password` | `SUVIA_DB_PASSWORD` |
| `spring.ai.dashscope.api-key` | `DASHSCOPE_API_KEY` |
| `spring.ai.dashscope.chat.options.model` | `DASHSCOPE_CHAT_MODEL` |
| `searchAPI.api-key` | `SEARCH_API_KEY` |
| `minio.endpoint` | `MINIO_ENDPOINT` |
| `minio.access-key` | `MINIO_ACCESS_KEY` |
| `minio.secret-key` | `MINIO_SECRET_KEY` |
| `minio.bucket-name` | `MINIO_BUCKET` |
| `server.port` | `SERVER_PORT` |

## 启动脚本

新增 `scripts/run-local-from-original-config.ps1`。它会：

1. 只读原项目的 `src/main/resources/application.yml`。
2. 在当前 PowerShell 子进程中设置环境变量。
3. 不输出密钥，也不在副本中生成带密钥的 YAML 或 `.env`。
4. 使用 Java 21 和副本内 Maven 缓存启动 Spring Boot。
5. 默认本地开发身份模式；不会修改生产 `application-prod.yml`。

运行：

```powershell
cd <project-root>
.\scripts\run-local-from-original-config.ps1
```

若需要 Maven 联网：

```powershell
.\scripts\run-local-from-original-config.ps1 -Online
```

也可以指定其他原始配置路径：

```powershell
.\scripts\run-local-from-original-config.ps1 `
  -OriginalApplicationYml 'D:\secure\application.yml'
```

## application.yml 应如何配置

副本中的写法是预期写法，不建议把原密钥重新粘贴进去：

```yaml
spring:
  datasource:
    url: ${SUVIA_DB_URL:jdbc:postgresql://localhost:5432/ai_agent}
    username: ${SUVIA_DB_USERNAME:postgres}
    password: ${SUVIA_DB_PASSWORD:}
  ai:
    dashscope:
      api-key: ${DASHSCOPE_API_KEY:}

searchAPI:
  api-key: ${SEARCH_API_KEY:}

minio:
  endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
  access-key: ${MINIO_ACCESS_KEY:}
  secret-key: ${MINIO_SECRET_KEY:}
  bucket-name: ${MINIO_BUCKET:rag}
```

本地、测试和生产应使用不同的 secret source。正式环境建议由容器 secret、Kubernetes Secret、Vault 或云 KMS 注入，不应依赖原项目 YAML。

## 安全说明

- 脚本不会打印或复制原始凭据。
- 环境变量只对启动进程及其子进程有效。
- 原项目文件保持只读，不做修改。
- 如果原 YAML 曾提交到版本库或共享过，仍建议轮换其中的 API key、数据库密码和 MinIO 密钥。
