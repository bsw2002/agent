# 011：按原 VMware 地址改为 application.yml 直接配置

## 变更目的

用户的 PostgreSQL、pgvector 和 MinIO 均运行在 VMware 虚拟机中，本地启动时不准备通过环境变量注入地址、端口和账号。本次只读取原项目 `application.yml`，并将其中能确定的配置写入 `codexProject` 副本。

原项目配置没有被修改。

## 已直接写入的配置

| 配置项 | 副本中的直接配置 |
|---|---|
| PostgreSQL | 使用原 VMware 主机、`5432` 端口和 `ai_agent` 数据库 |
| PostgreSQL 账号 | 与原项目一致 |
| 服务端口 | `8123` |
| API context path | `/api` |
| DashScope 模型 | `qwen-plus` |
| 搜索 API | 使用原项目现有配置 |
| MinIO | 使用原 VMware 主机和 `9000` 端口 |
| MinIO bucket | `rag` |
| 意图识别 | 启用模型分类，澄清阈值 `0.5` |
| 本地安全模式 | `false`，与当前本地开发方式兼容 |
| 会话记忆参数 | 使用此前环境变量中的默认值作为直接值 |

为避免在说明文档中再次复制凭据，数据库密码、搜索 API key 和 MinIO 密钥只存在于 `application.yml` 中，本文不展示明文。

## DashScope API Key

原项目的配置通过 `API_KEY` 注入：

```yaml
spring:
  ai:
    dashscope:
      api-key: ${API_KEY}
```

原文件没有密钥明文，但当前启动环境中存在原项目正在使用的 `API_KEY`。本次已将其写入副本 `application.yml`，因此副本启动时不再依赖该环境变量。本文不记录或展示密钥值。

不要把真实密钥提交到公开仓库。若副本将进入 Git，建议后续迁移到不提交的 `application-local.yml`，并由本地 profile 加载。

## 当前连接关系

```text
前端
  -> http://运行后端的主机:8123/api

Spring Boot
  -> PostgreSQL/pgvector: VMware主机:5432/ai_agent
  -> MinIO: VMware主机:9000
  -> DashScope: qwen-plus（密钥已直接配置）
```

## VMware 启动前检查

1. VMware 虚拟机 IP 未发生变化。
2. PostgreSQL 监听虚拟机网卡地址，而不只是 `127.0.0.1`。
3. PostgreSQL 的 `pg_hba.conf` 允许宿主机网段连接。
4. 虚拟机防火墙开放 TCP `5432` 和 `9000`。
5. PostgreSQL 中已安装 `vector` 扩展并存在 `ai_agent` 数据库。
6. MinIO 中存在 `rag` bucket，或当前账号允许应用创建它。
7. 启动项目使用 JDK 21。

## 安全边界

这种直接配置方式适合个人本地开发，但不适合公开仓库或生产发布，因为数据库、搜索 API 和 MinIO 凭据会进入源码及构建产物。后续如果需要推送 GitHub，应先轮换已暴露凭据，并迁移到本地 profile、容器 Secret 或密钥管理服务。
