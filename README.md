# Friend Feed MVP

一个以 MySQL 为事实来源、Redis 为可降级缓存的好友动态 Feed。采用写扩散：发布事务写入帖子、作者 Inbox 和 Outbox，Dispatcher 将事件可靠投递到 Kafka，Consumer Group 异步且幂等地写入好友 Inbox。产品闭环包含用户资料与搜索、好友申请、拉黑、点赞评论、未读通知和受权限保护的图片/视频附件。

## 关键正确性约束

- **发布不丢事件**：`posts` 与 `outbox_events` 在同一个 MySQL 事务内提交。
- **发布接口幂等**：`(author_id, idempotency_key)` 是唯一键；相同键和相同请求返回首次结果，相同键用于不同请求返回 `409 Conflict`。
- **重复消费安全**：`feed_inbox(owner_id, post_id)` 是唯一键，使用 `INSERT IGNORE` 幂等写入。
- **异步写扩散**：MySQL Outbox 是状态事实来源，Kafka 负责传输；Kafka 至少一次投递不会造成重复 Inbox。
- **混合扩散可演进**：普通作者使用 PUSH 写扩散；系统按连接数自动识别高连接作者并切换为 PULL，手工策略优先。Feed 用双来源复合 Cursor 稳定合并并去重。
- **失败可恢复**：事件经历 `PENDING → PROCESSING → DISPATCHED → PROCESSED`，失败按指数退避重试，超过阈值进入 `FAILED` 死信状态，超时任务会自动回收。
- **权限实时生效**：Inbox 只是候选集；每次读取都重新检查帖子状态、当前好友关系、双向拉黑、包含名单和排除名单。
- **稳定分页**：按 `(published_at DESC, post_id DESC)` 排序，复合 Cursor 独立保存 Inbox 与 PULL 的微秒级读取位置，不使用 `OFFSET`。
- **Redis 故障可降级**：Redis 只缓存帖子快照；读取/写入 Redis 失败时继续访问 MySQL。
- **身份不可伪造**：业务接口仅接受 Spring Security 验证过的 Bearer JWT，客户端提交的 `X-User-Id` 不再参与身份判断。
- **会话可撤销**：Access Token 绑定服务端会话；主动登出或撤销 Refresh Token 后，关联的 Access Token 立即失效。
- **刷新重放防护**：Refresh Token 每次使用都会轮换，数据库只保存摘要；旧 Token 再次出现会撤销整个令牌族。

## 启动

Compose 会启动应用、MySQL、Redis、单节点 Kafka 与 MinIO 对象存储。首次启动先构建 Vue 3 静态资源和 Spring Boot JAR，再构建包含 FFmpeg 的 Java 运行镜像：

```bash
cd frontend
npm ci
npm run build
cd ..
mvn -DskipTests package
export JWT_SECRET=replace-with-at-least-32-random-bytes
docker compose up --build -d
```

PowerShell 使用 `$env:JWT_SECRET='replace-with-at-least-32-random-bytes'`。仓库中的默认密钥只用于本地开发；部署时必须通过环境变量设置至少 32 字节的随机值。生产部署应同时设置 `SPRING_PROFILES_ACTIVE=prod` 和 `DEMO_DATA_ENABLED=false`；生产配置保护会拒绝默认 JWT 密钥、演示数据及验证码日志。启动后访问 `http://localhost:8080/`，可通过 `docker compose ps` 查看健康状态，通过 `docker compose logs -f app` 查看应用日志。

停止服务使用 `docker compose down`；数据库、Kafka、Redis 和 MinIO 媒体对象保存在命名卷中。只有明确需要删除全部本地数据时才使用 `docker compose down -v`。

构建要求 JDK 21+、Maven 和 Node.js 24+；运行时只需要 Docker。若希望脱离 Docker 开发，可先用 `docker compose up -d mysql redis kafka` 启动基础设施，再执行前端构建和 `mvn spring-boot:run`。Access Token 默认有效期为 15 分钟，可用 `JWT_TTL` 调整；Refresh Token 的令牌族绝对有效期默认 30 天，可用 `REFRESH_TOKEN_TTL` 调整。

Compose 默认把 MySQL 暴露在宿主机 `3307`，避免与常见的本机 MySQL `3306` 冲突；容器内仍使用 `3306`。可设置 `MYSQL_PORT`、`REDIS_PORT`、`KAFKA_PORT` 改变基础设施的宿主端口。Docker 内的应用始终通过服务名和容器端口连接，不受这些宿主端口变化影响。

服务默认监听 `http://localhost:8080`，健康检查为 `GET /actuator/health`。

## 联调演示数据

Docker Compose 默认启用幂等的演示数据初始化器。它只追加 `demo_` 前缀的数据，不会清空或覆盖已有业务数据；初始化成功后再次启动会自动跳过。所有演示账号的统一密码是 `demo12345`：

- `demo_alice`：管理员账号，拥有好友、黑名单、收到和发出的好友申请、动态及未读通知，适合作为主要联调账号。
- `demo_bob`、`demo_carol`、`demo_erin`：Alice 的好友，包含点赞、评论以及不同动态可见范围。
- `demo_dave`：向 Alice 发出了待处理好友申请，同时是 Bob 的好友。
- `demo_frank`：收到 Alice 发出的待处理好友申请。
- `demo_george`：已被 Alice 拉黑。

演示动态覆盖 `ALL_FRIENDS`、`ONLY_ME`、`INCLUDE_LIST` 和 `EXCLUDE_LIST` 四种可见范围。非 Docker 环境默认不灌入数据；Compose 中如需禁用，可在启动前设置 `DEMO_DATA_ENABLED=false`。

## Vue 3 前端

前端位于 `frontend/`，采用 Vue 3 + Vite，并严格使用本文列出的同源 REST API。生产构建会直接输出到 Spring Boot 的 `src/main/resources/static/`：

```bash
cd frontend
npm install
npm run build
cd ..
mvn spring-boot:run
```

浏览器访问 `http://localhost:8080/`。本地开发可在 `frontend/` 中运行 `npm run dev`，Vite 会把 `/api` 和 `/actuator` 代理到 `http://localhost:8080`。

前端包含注册登录、Feed 与稳定翻页、发布及附件、四种可见范围、点赞评论、用户搜索、好友申请、好友与黑名单、通知、个人资料，以及仅管理员可见的 Outbox 运维页。Access Token 与 Refresh Token 保存在浏览器 `localStorage`；请求收到 401 时会进行一次并发合并的自动刷新和重试。受保护媒体会先携带 Bearer Token 向后端鉴权，再使用短时签名地址读取对象，不会绕过动态可见性判断。

## API 示例

### 邮箱/手机验证与密码找回

注册前先调用 `POST /api/auth/verification/register/request` 获取验证码挑战，再把 `challengeId`、验证码和联系方式一同提交到注册接口。忘记密码使用 `POST /api/auth/password-reset/request` 和 `POST /api/auth/password-reset/confirm`；重置成功会撤销该账号的全部旧会话。

验证码默认 10 分钟有效、最多尝试 5 次、60 秒内不可重复发送。生产环境通过 Webhook 接入邮件和短信供应商：

- `VERIFICATION_EMAIL_WEBHOOK_URL`：邮件发送 Webhook。
- `VERIFICATION_SMS_WEBHOOK_URL`：短信发送 Webhook。
- `VERIFICATION_WEBHOOK_TOKEN`：可选 Bearer 凭据。
- `VERIFICATION_TTL`、`VERIFICATION_RESEND_COOLDOWN`、`VERIFICATION_MAX_ATTEMPTS`：有效期、重发间隔和最大尝试次数。

Webhook 接收 JSON：`{ "channel", "target", "code", "purpose" }`。安全默认下未配置 Webhook 会拒绝发送；仅本地联调可显式设置 `VERIFICATION_LOG_CODE=true`，验证码会写入应用日志，API 响应始终不会返回验证码。


注册两个用户。注册成功会直接返回 Access Token：

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","nickname":"Alice","password":"alice-pass-123","channel":"EMAIL","target":"alice@example.com","challengeId":"ALICE_CHALLENGE_ID","verificationCode":"123456"}'
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","nickname":"Bob","password":"bob-pass-123","channel":"PHONE","target":"+8613812345678","challengeId":"BOB_CHALLENGE_ID","verificationCode":"123456"}'
```

登录：

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"alice","password":"alice-pass-123"}'
```

响应中的 `accessToken` 用于后续请求：

```text
Authorization: Bearer ACCESS_TOKEN
```

登录和注册响应还包含 `refreshToken`。刷新会同时返回新的 Access Token 和 Refresh Token，旧 Refresh Token 立即作废：

```bash
curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"REFRESH_TOKEN"}'
```

主动登出当前会话需要有效 Access Token；也可直接提交 Refresh Token 做幂等撤销（未知 Token 同样返回 204）：

```bash
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer ACCESS_TOKEN"
curl -X POST http://localhost:8080/api/auth/revoke \
  -H "Content-Type: application/json" \
  -d '{"refreshToken":"REFRESH_TOKEN"}'
```

登录失败默认按账号 5 次、客户端地址 20 次进行 15 分钟窗口限流，触发后返回 `429` 和 `Retry-After`。可通过 `LOGIN_ACCOUNT_MAX_ATTEMPTS`、`LOGIN_ADDRESS_MAX_ATTEMPTS`、`LOGIN_RATE_WINDOW`、`LOGIN_BLOCK_DURATION` 调整；Redis 故障时自动退化为单实例内存限流。

Alice 向 Bob 发送好友申请，Bob 接受后再发布动态：

```bash
curl -X POST http://localhost:8080/api/relationships/friend-requests \
  -H "Authorization: Bearer ALICE_ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"recipientId":2}'

curl -X POST http://localhost:8080/api/relationships/friend-requests/FRIEND_REQUEST_ID/accept \
  -H "Authorization: Bearer BOB_ACCESS_TOKEN"

curl -X POST http://localhost:8080/api/posts \
  -H "Authorization: Bearer ALICE_ACCESS_TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: 58d474a8-00a7-4c56-9959-6f1b0a775462" \
  -d '{"content":"hello feed","visibility":"ALL_FRIENDS"}'
```

`Idempotency-Key` 必须是客户端生成的 UUID，并应在一次逻辑发布的所有网络重试中保持不变。更换内容、可见范围或目标用户时必须生成新键。

读取 Bob 的 Feed；下一页把响应中的 `nextCursor` 原样传回：

```bash
curl "http://localhost:8080/api/feed?size=20" \
  -H "Authorization: Bearer BOB_ACCESS_TOKEN"
curl "http://localhost:8080/api/feed?size=20&cursor=NEXT_CURSOR" \
  -H "Authorization: Bearer BOB_ACCESS_TOKEN"
```

Feed 响应中的 `socialByPostId` 以帖子 ID 为键，包含 `likeCount`、`commentCount`、`likedByMe` 和附件列表；`items` 字段继续保持原有帖子结构。

发布权限类型：

- `ALL_FRIENDS`：当前有效好友可见。
- `ONLY_ME`：仅作者可见。
- `INCLUDE_LIST`：仅 `targetUserIds` 中仍为好友且未互相拉黑的人可见。
- `EXCLUDE_LIST`：除 `targetUserIds` 外的有效好友可见。

删除好友或任一方拉黑后，旧 Inbox 行无需立即清除，读取侧会立刻过滤。恢复好友关系不会补发未曾扩散的历史动态；之前已经写入 Inbox 且仍有效的动态会按当前权限重新可见。

## 社交产品 API

用户资料与搜索：

- `GET /api/users/me`、`PATCH /api/users/me`：读取和更新昵称、简介、头像 URL。
- `GET /api/users/{userId}`：查看用户资料。
- `GET /api/users/search?q=alice&afterId=0&size=20`：按用户名或昵称搜索。

好友与拉黑：

- `POST /api/relationships/friend-requests`：发送申请。
- `GET /api/relationships/friend-requests?box=INCOMING&status=PENDING`：查看收到或发出的申请。
- `POST /api/relationships/friend-requests/{id}/accept|reject`：接受或拒绝。
- `DELETE /api/relationships/friend-requests/{id}`：发送方撤回。
- `GET /api/relationships/friends`、`GET /api/relationships/blocks`：好友和黑名单列表。
- `DELETE /api/relationships/friends/{userId}`、`PUT|DELETE /api/relationships/blocks/{userId}`：删除好友、拉黑或取消拉黑。拉黑会同时解除好友关系并关闭待处理申请。

互动与通知：

- `PUT|DELETE /api/posts/{postId}/like`：点赞或取消点赞，操作幂等。
- `POST|GET /api/posts/{postId}/comments`：发表评论或按 ID 游标读取评论。
- `DELETE /api/comments/{commentId}`：评论作者或帖子作者删除评论。
- `GET /api/notifications?unreadOnly=true`：读取通知和未读数。
- `PATCH /api/notifications/{id}/read`、`PATCH /api/notifications/read-all`：标记已读。

点赞、评论和媒体读取都会重新执行帖子权限检查，删除好友、拉黑、删除动态后不能继续通过子资源接口访问内容。

## 图片与视频

生产链路先申请短时 PUT 地址，浏览器直传对象存储，完成后由服务端通过 HEAD 校验大小和类型；再把确认返回的媒体 UUID 放入发布请求的 `mediaIds`：

```bash
curl -X POST http://localhost:8080/api/media/uploads \
  -H "Authorization: Bearer ALICE_ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"filename":"photo.png","contentType":"image/png","sizeBytes":12345}'

curl -X PUT "PRESIGNED_UPLOAD_URL" -H "Content-Type: image/png" --data-binary @photo.png

curl -X POST http://localhost:8080/api/media/MEDIA_UUID/confirm \
  -H "Authorization: Bearer ALICE_ACCESS_TOKEN"
```

本地 `LOCAL` 模式及兼容客户端仍可使用服务端中转上传：

```bash
curl -X POST http://localhost:8080/api/media \
  -H "Authorization: Bearer ALICE_ACCESS_TOKEN" \
  -F "file=@photo.png"

curl -X POST http://localhost:8080/api/posts \
  -H "Authorization: Bearer ALICE_ACCESS_TOKEN" -H "Content-Type: application/json" \
  -H "Idempotency-Key: 58d474a8-00a7-4c56-9959-6f1b0a775462" \
  -d '{"content":"with image","visibility":"ALL_FRIENDS","mediaIds":["MEDIA_UUID"]}'
```

推荐先调用 `GET /api/media/{mediaId}/access?variant=ORIGINAL|PREVIEW`：接口按所属动态实时鉴权，S3/MinIO 模式返回默认 5 分钟有效的签名 GET 地址；`/content` 与 `/preview` 继续提供带 Bearer Token 的兼容读取。未绑定附件只有上传者可以读取或删除。

系统用持久状态机异步生成图片缩略图和视频封面（`PENDING → PROCESSING → READY|FAILED`），处理超时会回收重试；未确认的过期直传和超过保留期仍未绑定动态的对象会分批清理。默认支持 JPEG、PNG、GIF、WebP、MP4、WebM、MOV，单文件上限 20 MB，每条动态最多 9 个附件。

Compose 默认使用 MinIO；`http://localhost:9001` 是管理控制台。可通过 `MINIO_API_PORT`、`MINIO_CONSOLE_PORT`、`MINIO_ROOT_USER`、`MINIO_ROOT_PASSWORD` 调整本机配置。生产环境可设置 `MEDIA_S3_ENDPOINT`、`MEDIA_S3_PUBLIC_ENDPOINT`、`MEDIA_S3_BUCKET`、`MEDIA_S3_ACCESS_KEY`、`MEDIA_S3_SECRET_KEY` 对接兼容 S3 的私有桶；原有本地对象按每行记录的 `LOCAL` 提供方继续可读。

## 工程质量与测试

```bash
# 后端单元测试
mvn test

# 后端单元测试、JaCoCo 覆盖率门禁；跳过 Docker 集成测试
mvn -DskipITs verify

# 完整后端门禁；Testcontainers 自动启动 MySQL 8.4 和 Kafka
mvn verify

# 前端 ESLint、Vue 类型检查、Vitest 覆盖率和生产构建
cd frontend
npm ci
npm run quality
```

单元测试覆盖单源/复合 cursor 编解码、双来源重叠去重、不均衡来源连续翻页、权限过滤后的独立游标推进、PUSH/PULL 历史回填、删除/好友/拉黑后的权限原则，以及其他核心业务边界。JaCoCo 在 `verify` 阶段检查后端行覆盖率；Vitest 对前端语句、分支、函数和行覆盖率设置不可回退的基线阈值。

集成测试不再依赖本机 `3307` 端口，也不需要预先创建数据库。Failsafe 会在 `verify` 阶段运行 `*IntegrationTest`，Testcontainers 负责真实 MySQL/Kafka 的生命周期，因此本机或 CI 需要可用的 Docker Engine。

完整 Compose 与浏览器冒烟测试会构建五个服务、等待健康检查，再使用 Playwright 登录演示账号；脚本使用独立 Compose Project，并在结束后清理对应容器和卷：

```bash
cd frontend
npx playwright install chromium
cd ..
RUN_E2E=true bash scripts/compose-smoke.sh
```

`.github/workflows/ci.yml` 在 Push 和 Pull Request 上执行前端质量门禁、后端单元测试、Testcontainers 集成测试以及 Compose + Playwright E2E；Pull Request 还会阻止引入高危依赖。Dependabot 每周检查 Maven、npm 和 GitHub Actions 更新。

## Outbox 与 Kafka 运维

主要配置：

- `feed.fanout.max-attempts`：最大尝试次数，默认 8。
- `feed.fanout.initial-backoff` / `max-backoff`：指数退避下限与上限，默认 1 秒和 15 分钟。
- `feed.fanout.processing-timeout`：`PROCESSING` / `DISPATCHED` 超时回收阈值，默认 2 分钟。
- `feed.fanout.topic`：Kafka Topic，默认 `feed.post-published.v1`。

Prometheus 指标在 `/actuator/prometheus` 输出：

- `feed_outbox_backlog`：未完成事件数。
- `feed_outbox_failed`：FAILED 死信数。
- `feed_outbox_oldest_age_seconds`：最老积压事件年龄。
- `feed_outbox_processing_latency_seconds`：最近 5 分钟已完成事件的平均端到端延迟。

管理员可查看快照并重放死信事件：

```bash
curl http://localhost:8080/api/admin/outbox/metrics \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"

curl -X POST http://localhost:8080/api/admin/outbox/123/replay \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
```

只有数据库 `users.role='ADMIN'` 的用户重新登录后签发的 JWT 才包含管理员角色。重放仅接受 `FAILED` 事件，会清零尝试次数并立即重新进入投递流程。

## 混合扩散第三阶段

作者默认使用 `PUSH`。每条动态都会在发布事务中保存 `delivery_mode` 快照。管理员可以只修改后续发布策略，也可以切换模式并创建异步历史回填任务：

```bash
curl http://localhost:8080/api/admin/fanout-policies/10 \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"

curl -X PUT http://localhost:8080/api/admin/fanout-policies/10 \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"mode":"PULL","reason":"high degree author"}'

curl -X POST http://localhost:8080/api/admin/fanout-policies/10/switch \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN" -H "Content-Type: application/json" \
  -d '{"mode":"PULL","reason":"high degree author","historyLimit":500000}'

curl "http://localhost:8080/api/admin/fanout-backfills?authorId=10&size=20" \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"

curl -X POST http://localhost:8080/api/admin/fanout-backfills/BACKFILL_JOB_ID/pause \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
curl -X POST http://localhost:8080/api/admin/fanout-backfills/BACKFILL_JOB_ID/resume \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
curl -X POST http://localhost:8080/api/admin/fanout-backfills/BACKFILL_JOB_ID/retry \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
curl -X POST http://localhost:8080/api/admin/fanout-backfills/BACKFILL_JOB_ID/cancel \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"

curl -X DELETE http://localhost:8080/api/admin/fanout-policies/10 \
  -H "Authorization: Bearer ADMIN_ACCESS_TOKEN"
```

`PUSH` 动态继续通过 Kafka 写入好友 Inbox；`PULL` 动态的 Outbox 仍会正常进入 `PROCESSED`，但 Consumer 跳过好友 Inbox 写入。Feed 的 `v2` Cursor 分别记录 Inbox 与 PULL 读取位置，两路按 `(published_at, post_id)` 归并并按帖子 ID 去重，同时继续执行好友、拉黑和 ACL 实时鉴权。旧 `v1` Cursor 会自动升级为两路相同边界。

`POST /switch` 会立即修改后续发布策略，并返回持久化回填任务；`historyLimit` 省略或传 `null` 表示处理全部符合条件的历史动态，传 `0` 表示只切换后续发布策略。每位作者最多有一个 `PENDING/RUNNING/PAUSED` 任务，数据库唯一约束会阻止并发迁移。

任务按 `(published_at DESC, post_id DESC)` 游标分批执行，默认每批 500 条。每个批次把动态模式更新、PUSH Inbox 的 `INSERT IGNORE` 补写和任务检查点放在同一个 MySQL 事务中；失败只会回滚当前批次，已完成批次不会重复。执行实例持有可超时回收的任务租约，服务异常退出后任务会重新进入 `PENDING`。任务支持暂停、继续、取消和失败后从最后检查点重试，记录创建管理员、失败次数、错误、处理数量和 Inbox 写入数量。相关参数为：

- `FANOUT_BACKFILL_BATCH_SIZE`：单批动态数，默认 500。
- `FANOUT_BACKFILL_MAX_BATCHES_PER_RUN`：单次调度最多处理批次数，默认 10。
- `FANOUT_BACKFILL_PROCESSING_TIMEOUT`：执行租约超时，默认 5 分钟。
- `FANOUT_BACKFILL_DELAY_MS`：后台扫描间隔，默认 1 秒。

切到 PULL 时，已有 Inbox 在过渡期保留并由读取侧去重；切回 PUSH 时，每个批次原子地重标动态并幂等补写当前有效好友 Inbox。管理后台每 3 秒刷新任务进度并提供状态操作。

第三阶段增加自动策略、作者时间线缓存和影子读取：

- 自动任务默认每分钟按有效好友数扫描作者。好友数达到 `10000` 自动设置 `PULL/AUTO`，下降到 `8000` 以下恢复默认 PUSH，中间区间保持现状以避免抖动。`MANUAL` 策略永远不会被自动任务覆盖。
- 阈值、批量大小与执行间隔可通过 `FANOUT_AUTO_PULL_THRESHOLD`、`FANOUT_AUTO_PUSH_THRESHOLD`、`FANOUT_AUTO_BATCH_SIZE`、`FANOUT_AUTO_DELAY_MS` 调整。
- PULL 作者时间线缓存于 Redis Sorted Set，默认保留最近 500 条、TTL 5 分钟。缓存未命中、深分页或 Redis 故障时自动回源 MySQL；发布、Kafka 消费、删除和模式回填都会更新或失效缓存。
- 首页按 `FEED_SHADOW_SAMPLE_RATE` 采样执行 MySQL 旧 Feed 影子读取，比较顺序、丢失项、额外项和重复项。差异只写日志和 Micrometer 指标，不影响主请求。
- 管理员可调用 `POST /api/admin/fanout-policies/automation/run` 立即执行自动判定，调用 `GET /api/admin/feed-shadow/metrics` 查看影子读取结果；管理后台也展示这些数据。

默认生产阈值较高，本地验证可临时降低阈值。当前自动计算使用对称好友连接数；若产品升级为关注/粉丝模型，应改为粉丝数、活跃粉丝数和发布频率的组合评分。自动策略触发历史回填、完整策略变更审计查询和 Inbox 分片归档仍属于后续阶段。

## 当前范围

本版采用带服务端会话撤销校验的短期 HS256 JWT、轮换式 Refresh Token、一次性邮箱/手机验证码、单 Kafka 集群，以及支持 S3/MinIO 直传和本地存储兼容的媒体层；同时包含自动策略、Redis 作者时间线、影子校验和可恢复异步历史回填的 PUSH/PULL 混合扩散。尚未包含 CDN、内容审核、自动策略触发历史回填和 Inbox 分片归档。生产环境若拆分多个独立服务，建议迁移到独立身份服务和非对称密钥签名，并把 Kafka Topic 副本数提升到至少 3。
