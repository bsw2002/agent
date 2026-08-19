# codexProject 改造副本

这是从原项目隔离复制并逐步改造的工作副本。原项目源码没有被修改。

## 阅读顺序

1. `docs/change-notes/000-baseline-copy.md`
2. `docs/change-notes/001-agent-loop-termination.md`
3. `docs/change-notes/002-tool-security-boundary.md`
4. `docs/change-notes/003-api-identity-and-secret-boundary.md`
5. `docs/change-notes/004-agent-run-event-store.md`
6. `docs/change-notes/005-token-budgeted-conversation-memory.md`
7. `docs/change-notes/006-governed-long-term-memory.md`
8. `docs/change-notes/007-structured-intent-and-capability-routing.md`
9. `docs/change-notes/008-verification-and-release-gates.md`
10. `docs/change-notes/009-local-runtime-configuration.md`
11. `docs/architecture/industry-upgrade-plan.md`

## 本地离线测试

项目需要 Java 21。当前工作区验证命令：

```powershell
$env:JAVA_HOME='C:\path\to\jdk-21'
$repo=(Resolve-Path -LiteralPath '.m2\repository').Path
mvn.cmd -o -q "-Dmaven.repo.local=$repo" test
```

默认不会运行需要真实模型、PostgreSQL/pgvector 和外部网络的 live integration tests。

显式设置以下变量后才会启用它们：

```powershell
$env:SUVIA_RUN_LIVE_TESTS='true'
```

同时需要在环境变量中提供数据库、模型、搜索和对象存储配置；不要把凭据写回 YAML。

如果配置仍保存在原项目 YAML 中，可用以下脚本只在启动进程中完成映射：

```powershell
.\scripts\run-local-from-original-config.ps1
```

## 推荐入口

- `POST /api/ai/tasks`：自动识别 direct chat / RAG / tool agent。
- `POST /api/ai/memories`：显式保存长期语义记忆。
- `GET /api/ai/memories`：按用户/会话作用域召回。
- `DELETE /api/ai/memories/{memoryId}`：软删除记忆。
- `GET /api/ai/agent/runs/{runId}`：查询 Agent 运行。
- `GET /api/ai/agent/runs/{runId}/events`：查询运行事件。

旧 `/chat`、`/rag`、`/agent` 入口仍保留。
