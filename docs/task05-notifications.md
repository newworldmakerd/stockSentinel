# TASK-05 企微智能机器人通知

日期：2026-10-20（Asia/Shanghai）。代码、编译及本地模拟验收完成；真实企微群消息验收未完成。用户已选择群聊，尚未提供具体 chatid。本阶段不实现 TASK-06 行情扫描、交易日历和每日复盘汇总。

## 协议与范围

遵循用户确认的 Bot ID + Secret 长连接方式，使用 JDK21 WebSocket，非群 Webhook。协议依据：[企业微信官方 SDK](https://github.com/WecomTeam/aibot-node-sdk)、[SDK 长连接源码](https://github.com/WecomTeam/aibot-node-sdk/blob/main/src/ws.ts)、[主动发送源码](https://github.com/WecomTeam/aibot-node-sdk/blob/main/src/client.ts)。官方文档入口 https://developer.work.weixin.qq.com/document/path/101463 在本次工具中不可直接打开，协议以官方 SDK 源码交叉核对。

连接地址为 wss://openws.work.weixin.qq.com；先 aibot_subscribe 鉴权，随后 aibot_send_msg 主动发送 Markdown，按 headers.req_id 匹配 ACK，只有整数 errcode=0 标记成功。每30秒发送 JSON ping 并等待 ACK；连接失败按1、2、4秒指数退避，最大30秒，连续连接失败最多10次。认证拒绝或 disconnected_event 接管事件停止重连，避免抢占同一机器人连接。无需公网回调地址；不处理用户聊天内容，不自动回复。

## 配置与启动

默认 WECOM_ENABLED=false，不创建真实客户端、不连接企微、不新建通知任务。开启时必须配置 SMART_BOT 模式、Bot ID、Secret、目标 chatid；缺失则启动失败。凭据仅使用环境变量，不写入 .env、配置、测试、日志或文档。测试代码中的 test-only 值为固定虚构占位。

无需 Docker，数据库和 Python/Java 启动见 README。PowerShell 7 中可按下列方式配置；开启将建立真实连接并发送待投递通知，必须先获得真实联调授权：

```powershell
$env:WECOM_ENABLED = 'true'
$env:WECOM_MODE = 'SMART_BOT'
$env:WECOM_BOT_ID = Read-Host 'Bot ID' -MaskInput
$env:WECOM_BOT_SECRET = Read-Host 'Bot Secret' -MaskInput
$env:WECOM_CHAT_ID = Read-Host 'Target group chatid'
# 先按README配置数据库环境变量
java -jar backend/target/stocksentinel-0.1.0-SNAPSHOT.jar --server.address=127.0.0.1
```

本次没有执行上述真实连接命令。WECOM_CHAT_ID 必须是目标群聊的实际会话标识，不是群名或 Bot ID；目标必须满足平台权限与可发送条件，尚未真实验证。当前固定官方公网网关，私有部署网关适配未实现。

## 消息与持久化

- 支持 BUY_WATCH、SELL_WATCH、RISK_ALERT。包含股票代码、交易日、不复权参考价、策略版本、规则依据与风险；不虚构价格、建议或持仓。
- 消息明确区分“本地确定性规则”和“大模型辅助建议”，模型内容不改变规则结论。首次认领时若已有成功模型结果则附加，否则标明暂未生成或未启用。模型可能仍在执行；当前不等待模型完成，也不另发补充消息。
- Markdown 不接受原始输入格式，过滤控制字符及危险标记；UTF-8 最大4000字节，按码点安全截断，始终保留辅助分析和不自动下单说明。
- 新信号发布事件后，BEFORE_COMMIT 在同事务插入 notify_record；信号回滚时通知也回滚。持久化任务独立于进程，重启后可继续认领未发送任务。
- 唯一键为信号ID + Bot ID/目标chatid的SHA256摘要。重复评估、重复请求及并发不会重复创建同一目标任务。每个任务固定目标，首次认领冻结消息，重试不重新生成文案。
- 更换机器人或目标后，只认领与当前配置摘要一致的任务；旧配置任务不会借另一机器人发送。已有信号可通过显式测试接口向当前新目标入队。
- worker 默认每5秒认领一条，使用 FOR UPDATE SKIP LOCKED 和认领令牌防止并发发送；120秒租约。数据库事务在外部网络调用前结束。
- V4 仅向 notify_record 增加 target_chat_id/payload/claim_token/lease_until 和查询索引；不修改 V1/V2/V3。旧 WECOM 渠道记录不会自动投递。

## 状态及重试

| 状态 | 含义 |
|---|---|
| PENDING | 已入队，尚未尝试 |
| SENDING | 已认领，发送中 |
| SUCCESS | 收到匹配请求的成功业务 ACK |
| RETRY | 发送前连接不可用，或收到已识别的临时错误 ACK |
| FAILED | 明确拒绝、输入无效，或已到三次尝试上限 |
| UNKNOWN | 发送后未确认 ACK、客户端异常，或进程退出导致发送租约到期 |

最多三次尝试，第一次可立即发送，第二次等待30秒，第三次等待60秒；临时业务错误仅识别 -1 和45009，其他拒绝不盲目重试。认证失败/连接被接管不继续重连。原始 errmsg、异常文本、握手凭据和报文不保存或打印，只保留固定内部错误码。

UNKNOWN 不自动重发，重新调用测试接口仍返回原记录，不绕过幂等。这样避免“平台已收到、ACK丢失”情况下自动重复通知；不声称跨网络严格 exactly-once。确认群内接收情况并决定人工处置后，再单独处理未知记录；未提供强制重发接口。迟到的旧 worker 不能覆盖已过期状态。worker/数据库故障不影响已经提交的规则信号。

## 本地 API 契约

服务默认按 README 绑定127.0.0.1，当前没有应用层身份认证，不应直接公网暴露。

| 方法与路径 | 行为 |
|---|---|
| POST /api/v1/notifications/test | 默认 dryRun=true，返回200/DRY_RUN，不查询数据库、不入队、不发消息；允许空请求体 |
| POST /api/v1/notifications/test | 显式 dryRun=false 且 signalId 为正整数时，把已存在真实规则信号入队；返回200与 notificationId/status，由worker异步发送 |
| GET /api/v1/notifications | limit默认50、最大200，offset默认0，返回数组 |
| GET /api/v1/notifications/{id} | 状态、尝试次数、重试时间、固定错误码、发送时间；不存在404 |

返回数据不包含目标chatid、payload、认领令牌、幂等摘要或凭据。非法分页/缺少signalId返回400；发送关闭返回503；显式入队的信号不存在返回404。接口不接受任意通知正文或上传虚构行情。返回PENDING不是群内送达证明，需查询最终状态。

```powershell
# 安全预览，无需打开WECOM_ENABLED
Invoke-RestMethod -Method Post 'http://127.0.0.1:8080/api/v1/notifications/test'
Invoke-RestMethod 'http://127.0.0.1:8080/api/v1/notifications?limit=20'
# 以下显式入队示例仅在真实发送获授权、目标已配置且信号确实存在时执行
# Invoke-RestMethod -Method Post 'http://127.0.0.1:8080/api/v1/notifications/test' -ContentType 'application/json' -Body '{"signalId":123,"dryRun":false}'
```

## 验证与运行命令

本次 Java55项常规测试 +26项原生PostgreSQL集成测试，共81项通过，Python38项回归通过，JAR打包成功。证据：task05-full-verification.log、task05-python-test.log。

- 协议测试通过真实loopback WebSocket握手与掩码帧，覆盖认证、连接复用、分片ACK、成功、拒绝、未收到ACK、JSON心跳、连接恢复和接管停止。使用本地协议模拟器，无真实凭据；HTTP模拟工具不能替代该WebSocket协议测试。
- 12项通知数据库集成测试在随机独立schema实际提交，验证原子入队、回滚、并发去重、发送时无数据库事务、有限退避、冻结正文、结果未知不重发、租约过期、令牌隔离、目标配置隔离及测试API默认dry-run。
- 两项数据库测试进一步把真实Java WebSocket客户端接到本地模拟器，验证成功ACK与丢失ACK分别落库SUCCESS/UNKNOWN，而非仅替换客户端返回值。
- myrag.public 已应用V4。600487 QFQ/NONE各432条保留；指标、信号、模型建议和通知均0条。临时TASK-04/05测试schema残留0，见task05-verification.json。未修改RAG表，未调用真实行情、模型或企微。
- 上轮行情联调遗留的两个临时Java子进程已按日志与端口精准清理，恢复可执行JAR打包；未停止其他应用。

```powershell
$env:STOCKSENTINEL_TEST_DB_URL = 'jdbc:postgresql://localhost:5432/myrag'
$env:STOCKSENTINEL_TEST_DB_USER = 'myrag_user'
$env:STOCKSENTINEL_TEST_DB_PASSWORD = Read-Host 'Test database password' -MaskInput
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml '-Dit.test=LocalDatabaseIT,IndicatorLocalDatabaseIT,StrategyLocalDatabaseIT,NotificationLocalDatabaseIT' verify
Remove-Item Env:STOCKSENTINEL_TEST_DB_PASSWORD
.\.venv\Scripts\python.exe -m pytest quant-service/tests -q
```

只读排查SQL：

```sql
SELECT version,success FROM public.flyway_schema_history ORDER BY installed_rank;
SELECT id,signal_id,channel,message_type,status,attempts,next_retry_at,last_error,sent_at
FROM public.notify_record ORDER BY id DESC LIMIT 50;
SELECT status,count(*) FROM public.notify_record GROUP BY status;
```

停止真实连接和新通知：移除 WECOM_ENABLED 或设为false后重启Java；已有通知记录保留。尚未验证真实机器人鉴权、群权限、发送配额、目标chatid及真实消息送达。TASK-06扫描与每日汇总、分布式机器人连接主节点协调仍未实现；当前仅支持单个实例持有同一机器人的真实长连接，不能以数据库认领去重替代连接主节点选举。

## 关键位置

- backend/src/main/java/com/stocksentinel/notification/SmartBotClient.java:15：长连接、认证、ACK、心跳与接管。
- backend/src/main/java/com/stocksentinel/notification/NotificationListener.java:12：提交前原子入队。
- backend/src/main/java/com/stocksentinel/notification/NotificationMapper.java:16：任务认领与令牌隔离。
- backend/src/main/java/com/stocksentinel/notification/NotificationService.java:40：冻结文案与状态更新。
- backend/src/main/java/com/stocksentinel/notification/NotificationWorker.java:20：有限速率投递。
- backend/src/main/java/com/stocksentinel/notification/NotificationController.java:26：默认dry-run测试入口。
- backend/src/main/resources/db/migration/V4__notification_delivery.sql:1：新增迁移。

## 获取群chatid

已提供独立只接收模式，无需预先配置chatid，也不启动数据库或发送消息：

```powershell
$env:WECOM_BOT_ID = Read-Host 'Bot ID' -MaskInput
$env:WECOM_BOT_SECRET = Read-Host 'Bot Secret' -MaskInput
java -jar backend/target/stocksentinel-0.1.0-SNAPSHOT.jar --discover-wecom-chatid
```

鉴权成功后在目标群 @机器人，控制台显示chatid后退出。真实连接前需获授权，并先停止同一机器人的其他连接实例。完整说明见 [chatid发现模式](wecom-chatid-discovery.md)。
