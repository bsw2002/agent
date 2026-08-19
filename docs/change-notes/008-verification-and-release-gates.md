# 008：最终验证、测试分层与发布门禁

## 目标

对前 8 个变更集执行完整离线回归，确认副本可编译、测试可重复、配置不含明文凭据，并把仍需外部基础设施的测试与默认 CI 分离。

## 副本隔离验证

所有写操作均指向：

```text
<project-root>
```

最终检查时，原目录（排除 `codexProject`）最近一次文件写入仍为：

```text
<original-project>\.gitignore
2026-08-10T00:43:02.6009433+08:00
```

它早于本轮副本改造时间。原项目源码没有被修改。

## 默认测试与 Live Integration 分层

原项目中的多项 `@SpringBootTest` 会直接依赖：

- DashScope 模型 API；
- PostgreSQL 与 pgvector；
- 搜索 API 和外部网络；
- 本地真实语料或对象存储。

这些测试若默认运行，会让普通 `mvn test` 依赖个人密钥和机器状态，不适合作为 CI 门禁。现在它们使用：

```java
@EnabledIfEnvironmentVariable(
    named = "SUVIA_RUN_LIVE_TESTS",
    matches = "true"
)
```

默认离线构建跳过；只有显式设置以下变量才启用：

```powershell
$env:SUVIA_RUN_LIVE_TESTS='true'
```

这不是删除集成测试，而是把“可重复单元/组件测试”和“需要真实环境的验证”分层。生产 CI 应单独配置 integration stage，使用隔离账号、临时数据库和受控测试数据。

## 意图黄金集

新增：

- `src/test/resources/evals/intent-routing-cases.json`
- `IntentRoutingGoldenEvalTest`

当前黄金集覆盖普通对话、Web 搜索、Web 抓取、PDF 生成、文件读写、终端执行、知识库、下载、模糊请求，以及 Web/文件/终端否定句。

黄金集首次运行发现英文文件写入语序窗口过窄，规则已修复；高风险终端窗口没有随之扩大。它证明评测集不是形式测试，而能直接阻止路由行为回退。

当前 15 条只是工程骨架。正式上线需要从真实流量匿名抽样、红队样例和多语言场景扩展到至少数百条，并独立统计高风险 capability 假阳性。

## 最终测试结果

执行命令：

```powershell
$env:JAVA_HOME='C:\path\to\jdk-21'
$repo=(Resolve-Path -LiteralPath '.m2\repository').Path
mvn.cmd -o -q "-Dmaven.repo.local=$repo" test
```

最终 Surefire 汇总：

```text
TEST_SUITES=22
TESTS=54
FAILURES=0
ERRORS=0
SKIPPED=11
```

11 个 skipped 均为需要外部环境的 live integration tests；43 个离线测试实际执行并通过。

测试覆盖的核心风险：

- Agent 正确终止、最大步数和异常传播；
- run/event/checkpoint 成功与失败生命周期；
- 路径穿越、SSRF、终端 allowlist、下载边界和结构化工具结果；
- 身份/租户会话隔离；
- token-budget 摘要、记忆损坏、低信任标签和内部会话键；
- 长期记忆来源/作用域/所有权/软删除/敏感写入拒绝；
- 意图、否定句、risk、最小工具集合、未知工具 fail-closed；
- direct chat / RAG / tool agent 统一编排；
- 知识检索工具的低信任输出。

## 配置与敏感信息检查

对 `application*.yml` 扫描非环境变量形式的 `api-key/password/access-key/secret-key`：

```text
LITERAL_SECRET_CONFIG_MATCHES=0
```

生产所需配置必须通过环境变量或 secret manager 提供。若原项目中的旧凭据曾提交、共享或进入构建日志，仍应在供应商侧轮换；从副本删除明文不能使旧凭据自动失效。

## 本轮产物

变更说明：

1. `000-baseline-copy.md`
2. `001-agent-loop-termination.md`
3. `002-tool-security-boundary.md`
4. `003-api-identity-and-secret-boundary.md`
5. `004-agent-run-event-store.md`
6. `005-token-budgeted-conversation-memory.md`
7. `006-governed-long-term-memory.md`
8. `007-structured-intent-and-capability-routing.md`
9. `008-verification-and-release-gates.md`

总体目标架构与行业方案：

- `docs/architecture/industry-upgrade-plan.md`

副本入口说明：

- `README-CODEX.md`

## 当前发布判断

### 可作为下一阶段开发基线

以下基础边界已经建立并有离线测试：

- Agent loop；
- 工具安全；
- 身份隔离；
- 运行事件；
- 短期/长期记忆域；
- 意图与 capability 路由；
- 统一任务入口。

### 不能直接作为无条件生产发布

在正式处理真实客户数据或开放终端/文件写入前，至少需要：

1. Flyway/Liquibase 与 Testcontainers PostgreSQL/pgvector 测试。
2. 写入/下载/终端的人机审批和真正可恢复 checkpoint。
3. 容器/OS 级 sandbox、网络 egress 控制和资源配额。
4. OpenTelemetry、metrics、SLO、token/cost 观测。
5. KMS 加密、记忆 TTL、用户导出/删除和审计。
6. 规模化 intent/RAG/memory 评测与红队。
7. Spring AI Alibaba M6 到当前稳定 BOM 的独立迁移验证。

## 建议的 CI 门禁

每个提交：

- compile；
- 43+ 离线测试；
- secret scan；
- dependency/SCA scan；
- intent 高风险黄金集；
- 工具安全回归。

合并到主干：

- Testcontainers 数据库集成；
- API contract tests；
- migration up/down validation；
- prompt injection 与 SSRF red-team cases。

发布前：

- 真实模型 shadow eval；
- RAG 固定语料 benchmark；
- memory deletion/export audit；
- sandbox escape tests；
- 故障注入与 checkpoint resume；
- 灰度指标无退化后再扩大流量。
