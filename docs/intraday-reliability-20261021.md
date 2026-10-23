# 盘中行情失败与可靠性修复（2026-10-21）

后续已补充主动群异常提醒，见[扫描故障提醒](scan-fault-alerts.md)。本文保留下方第一轮修复时的验收范围。

## 1. 背景与目标

已完成短暂行情故障的有限重试、跨分钟校验修正和脱敏诊断，并更新本机服务。历史失败已定位到部分 Python 行情请求返回 502；由于旧代码吞掉了主备异常，不能还原所有历史 502 的底层原因，也不能宣称供应商间歇故障已全部消失。

本次范围仅为盘中失败与可靠性，不更改策略条件、推荐频率、个人提醒归属或收盘确认规则。

## 2. 现状与影响点

- 检查时最近 20 轮盘中任务有 64 条 `INTRADAY_PROCESSING_FAILED`。原 Java 泛化异常，Python 最终 502 也没有主备原因。
- `.runtime/20261021-112434-943858/quant.out.log` 的分钟请求中存在 600487、600519、601869 等股票返回 502 的记录。未发现相同时段能够解释这些真实股票失败的数据库约束错误；数据库日志中的 999620 是隔离测试样本，不能当成真实行情故障。
- 在午休期间对 601869、600487 各做一次主源、镜像、腾讯只读诊断，六次调用均成功，末条为当日 11:30。没有写业务数据或发送群消息。这说明当前可达，不能反推早前具体异常。
- 原主源归一化使用请求开始时间。等待限速或网络响应跨过分钟边界时，新返回的当前分钟可能被错误判为未来时间；此缺陷通过固定时钟测试复现，不代表它已经被证明是上述历史 64 条失败的共同原因。

修改点：

| 文件定位 | 修改 |
|---|---|
| `quant-service/app/providers/tencent_provider.py:17` | 后备请求对有限类型短暂错误最多重试一次，保留串行限速与连接/读取超时 |
| `quant-service/app/providers/minute_failure.py:1` | 只生成固定错误码，不输出异常消息、响应体或URL |
| `quant-service/app/providers/akshare_provider.py:51` | 分别记录东方财富主源和镜像的脱敏原因；日线调用逻辑不变 |
| `quant-service/app/api/intraday.py:114` | 按请求完成时间归一化；502增加固定原因头，响应体原文和状态码不变 |
| `backend/src/main/java/com/stocksentinel/intraday/IntradayClient.java:24` | 将HTTP状态、超时、连接错误和解码失败转换为安全代码；原因头仅接受白名单 |
| `backend/src/main/java/com/stocksentinel/intraday/IntradayScanService.java:57` | 区分获取行情、个人提醒、盘中写入和回踩确认阶段；记录job、symbol、stage、code和异常类型 |

## 3. 已实施方案

### 有限重试

腾讯后备遇到连接错误、超时、HTTP 502/503/504，最多请求两次。两次之间仍至少间隔一秒。HTTP 429、403、其他业务拒绝、无效JSON和行情校验错误不重试，避免扩大限流或反复使用无效数据。

分钟API把请求开始后40秒作为额外重试预算参考；如果剩余不足以容纳下一次请求及间隔，就不追加重试。它不代表整个请求的硬截止时间：已有锁等待和网络读超时仍可能影响总时长，不能保证任何股票池都在一分钟内完成。

Java不重试写库、规则执行或通知，也没有新增整轮扫描重试。保留原有事务和幂等边界。

### 时间校验

用请求入口时间加单调时钟经过时长得到采集完成时间。跨分钟时仍排除当前未完成分钟，只允许真实已完成分钟参与计算；不会接受未来分钟、补造K线或拼接不同源窗口。

### 诊断契约

最终失败仍返回 `HTTP 502`，正文保持：

```json
{"detail":"Minute provider unavailable or invalid"}
```

新增可选响应头示例：

```text
X-Minute-Primary-Error: CONNECTION
X-Minute-Backup-Error: HTTP_503
```

Java对应落库代码示例：

```text
MINUTE_PRIMARY_CONNECTION_BACKUP_HTTP_503
MINUTE_HTTP_TIMEOUT
MINUTE_HTTP_CONNECTION_FAILED
MINUTE_RESPONSE_INVALID
INTRADAY_WRITE_DATABASE_FAILED
PERSONAL_PRICE_DATABASE_FAILED
PULLBACK_CONFIRM_DATABASE_FAILED
```

其中 primary 表示主源及其可选镜像组成的获取链路；主源与镜像之间的原因通过 Python `minute_mirror` 日志进一步区分。旧Python没有原因头时，Java仍兼容记录 `MINUTE_HTTP_502`。不识别的头值被丢弃，不能直接进入日志或数据库。

历史记录不回填、不删除。后续按下面的只读SQL查看具体失败；参数时间应按实际部署时间设置：

```sql
SELECT j.id, j.started_at, i.symbol, i.stage, i.code
FROM job_execution j
JOIN job_execution_item i ON i.job_id = j.id
WHERE j.job_name = 'INTRADAY_SCAN'
  AND j.started_at >= TIMESTAMPTZ '2026-10-21 11:38:36+08:00'
  AND i.status = 'FAILED'
ORDER BY j.id DESC, i.id;
```

## 4. 保留的拦截与校验

- 交易日、交易时段、停牌或无成交、行情时效、完整分钟与窗口连续性保持。
- 未完成分钟排除、异常价格/成交量拒绝、腾讯缺失成交额的源限定保持。
- 同一股票失败后继续处理下一只；不把缺数据当成没有风险，不生成假信号。
- 冷却、游标、信号/通知幂等、个人提醒独立归属和UNKNOWN投递隔离保持。
- 每10分钟重复推荐需求保持；不自动下单，不调用模型修正本地规则。
- 没有数据库结构变更，无新增迁移。

## 5. 后续待观察项

下午交易时段需观察新错误码、逐股失败率及整轮耗时。如果发现持续超时或特定源数据错误，再根据实际证据调整源切换或资源隔离；本次没有贸然扩大并发或修改策略阈值。

本次没有新增群故障推送，以免引入未定义的频率和群噪声。没有补发历史信号或发送群验收消息。

## 6. 验证、部署与回滚

验证结果：

- Java常规测试109项通过，包含真实本机HTTP服务器模拟502、HTML错误、JSON解码失败、拒绝连接和读超时。
- 隔离PostgreSQL集成测试27项通过，测试临时schema已清理；验证错误码入库、失败股票之后仍继续处理、无假信号，以及既有事务回滚和去重。
- Python全部88项通过，覆盖后备503后恢复、连接错误/超时恢复、持续故障次数上限、429/403不重试、坏数据不重试、预算不足不重试、请求跨分钟校验及既有主备源契约。
- 自动测试全部使用模拟行情、模型和企微。只有前述六次只读诊断访问真实行情源。
- Java打包成功；Postman本机Python健康检查HTTP 200及一项断言通过。
- 本机部署目录：`.runtime/20261021-113836-308544/`。Java/Python健康正常，企微连接状态AUTHENTICATED；盘中、日线、个人提醒及群对话开关保持开启。
- 本次处于午休，尚无修复后交易时段连续运行结果，不能拿午休跳过任务作为失败率改善证据。

复现命令：

```powershell
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml test
.\.venv\Scripts\python.exe -m pytest quant-service/tests -q -p no:cacheprovider
# 数据库集成测试：先在当前进程设置 STOCKSENTINEL_TEST_DB_URL/USER/PASSWORD，密码只从环境读取
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml '-Dit.test=IntradayLocalDatabaseIT' test-compile failsafe:integration-test failsafe:verify
```

证据日志：`.runtime/reliability-java-full.log`、`.runtime/reliability-native-final.log`、`.runtime/reliability-python-final.log`、`.runtime/reliability-package.log`。

旧JAR保存在 `.runtime/reliability-before-20261021.jar`。如需回滚，先核实并停止本项目Java进程，恢复旧JAR再启动；新增Python原因头与旧Java兼容。若需完整恢复Python旧行为，还需撤回本次三个行情文件变更及新增错误码模块，不能只换JAR就声称完成全量回滚。回滚不删除行情、信号或历史任务记录，具体运行操作应另行确认。

使用技能：`postman:api-engineer`、`postman:api-simulation`及`postman:api-testing`，沿用既有分钟API，以本机HTTP模拟、Java→Python→隔离PostgreSQL链路和只读健康断言验证；未创建或发布共享Postman资源。
