# Friend Feed 生产运维 Runbook

## 通用检查

1. 在 Alertmanager 确认告警开始时间、实例和持续时长，不要直接清理数据。
2. 用告警时间窗口和 `traceId` 查询 JSON 日志，再到 Jaeger 查看完整 HTTP、JDBC、Kafka Trace。
3. 检查 `/actuator/health`、`/actuator/prometheus`、MySQL、Redis、Kafka 和对象存储健康状态。
4. 变更或重放前记录操作人、事件 ID、判断依据；恢复后观察至少两个告警评估周期。

## FriendFeedDown

- 查看应用启动日志、容器退出码和健康检查，优先排查数据库迁移、JWT 密钥文件、OIDC JWK 地址及依赖服务。
- 配置错误应回滚到上一完整镜像；依赖故障先恢复依赖，不要绕过生产配置保护。

## FriendFeedHighHttp5xxRate

- 按 `traceId` 聚合异常类型与接口，确认是单一请求、依赖超时还是全局资源耗尽。
- 检查 Hikari、Redis、Kafka 连接指标。必要时限流或回滚最近发布，不要扩大线程池掩盖下游故障。

## FriendFeedJvmHeapPressure

- 查看 GC 暂停、堆使用趋势和流量变化。先限制大请求或降级非核心任务，再获取堆转储进行离线分析。
- 禁止仅通过无限增大堆内存处理持续泄漏。

## FriendFeedOutboxBacklog

- 查看 `feed_outbox_oldest_age_seconds`、Kafka Broker 和消费者组延迟。
- 确认 Dispatcher 是否持续运行、事件是否卡在 `PROCESSING/DISPATCHED`，等待恢复任务处理超时事件。

## FriendFeedOutboxFailed

- 在运维页或 `GET /api/admin/outbox/metrics` 确认数量，查询失败事件的 `last_error`。
- 修复根因后调用 `POST /api/admin/outbox/{eventId}/replay`；接口只接受 `FAILED` 状态并保留重放计数。

## FriendFeedKafkaDeadLettersPending

- 调用 `GET /api/admin/kafka-dead-letters?status=PENDING` 查看原 Topic、分区、Offset、异常类和原始负载。
- 若代码或数据已修复，调用 `POST /api/admin/kafka-dead-letters/{id}/replay`。原消费链路保持幂等，重复投递不会重复扩散。
- 确认消息不可恢复后调用 `POST /api/admin/kafka-dead-letters/{id}/discard` 并填写原因。禁止直接删除 DLT 或数据库记录。

## 自动扩散策略与回填

- 调用 `GET /api/admin/fanout-policies/automation` 查看阈值和最近评估量；管理员手动执行使用 `POST /api/admin/fanout-policies/automation/run`。
- 自动策略切换会原子创建全量历史回填任务。若 `blockedThisRun` 增长，先通过 `GET /api/admin/fanout-backfills?status=RUNNING` 检查同作者活动任务，不要绕过唯一约束直接改表。
- `failuresThisRun` 或 `feed_fanout_auto_failed_total` 增长时，按作者 ID 检查应用日志、数据库事务异常和外键数据；失败作者会在后续扫描再次评估。
- 调用 `GET /api/admin/fanout-policy-audits?authorId={id}&size=20` 核对变更前后模式、触发来源、操作者和关联回填任务。审计表为追加写，禁止更新或删除记录。

## JWT 与 OIDC

- RSA 轮换：先让验证端同时信任新旧公钥，再切换签名私钥和 `kid`，至少等待一个 Access Token TTL 后移除旧公钥。
- OIDC 模式需验证 issuer、audience、JWK TLS 证书及 `JWT_USER_ID_CLAIM` 的正整数映射。外部 IdP 故障期间不要退回 HMAC。
- Refresh Cookie 必须保持 `HttpOnly; Secure; SameSite=Strict`，退出与轮换均应返回清理或替换 Cookie。

## 可观测栈

使用 `OTLP_TRACING_ENABLED=true docker compose --profile observability up --build -d` 启动本地验收栈：Prometheus `:9090`、Alertmanager `:9093`、Jaeger `:16686`、告警接收器 `:8090`。

Alertmanager 默认把告警发送到 `alert-receiver`。该服务会以结构化日志记录告警；生产环境设置 `ALERT_FORWARD_URL` 将原始 Alertmanager Webhook 转发到团队通知网关，网关需要 Bearer 凭据时再通过 Secret 注入 `ALERT_FORWARD_BEARER_TOKEN`。转发失败会返回 `502`，Alertmanager 因而保留并重试通知，禁止把凭据直接提交到配置文件。

发布验收使用独立 Compose Project，覆盖前端浏览器冒烟、监控组件就绪、Alertmanager Webhook 投递，以及 Kafka DLT 捕获、丢弃和重放：

```bash
RUN_E2E=true RUN_OBSERVABILITY_E2E=true RUN_DLT_E2E=true \
  bash scripts/compose-smoke.sh
```
