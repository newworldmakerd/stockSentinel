# 群内添加关注与本机启用记录

最新追加：已接入并开启 [腾讯分钟后备](tencent-backup.md)，原主源失败时两只股票均已真实返回分钟数据。当前运行目录为 `.runtime/20261020-173702-397789/`，下文断连结果与旧运行目录为此前阶段记录；交易时段完整推送仍待观察。

2026-10-20，按用户明确授权启用真实行情、企微长连接与群内监控指令。无需 Docker。

## 已开启

- 四川美丰 `000731`（SZ）、亨通光电 `600487`（SH），均已根据本地股票目录核对并写入关注名单、启用盘中监控。
- 每分钟第 5 秒尝试扫描，仅交易时段处理已结束分钟；5 分钟涨跌 ±2%，突破此前 20 分钟高点且量能达到 1.5 倍，同类提醒冷却 15 分钟。
- 止盈、止损价格未提供，保持空值。重复添加保留已有阈值、价格、备注；禁用项会被重新开启。
- 企微长连接已真实鉴权，监控及群命令开关开启。监听已配置群中的文本消息。
- 当前仅启用盘中规则提醒与群命令意图模型。`LLM_ENABLED=false`，不自动生成交易分析；每日收盘任务保持关闭。
- 本机服务绑定 `127.0.0.1`，业务数据库仍为 `myrag`，Flyway 已到 V7。

## 群内用法

在已配置的企微群 **@机器人**，每条消息指定一只股票，例如：

```text
请监控四川美丰
帮我关注亨通光电
监控 600487
```

DeepSeek 返回唯一允许的 `add_stock_monitor(stock_query)` 工具调用后，Java 校验参数必须出自原文，在真实股票目录中唯一匹配，再向固定本机 HTTP API 请求添加。模型不能提供 URL、价格或其他动作。无法唯一匹配时回复使用六位代码；取消、删除、否定与过期消息不会执行新增。没有实现群内删除、自动交易或任意 API 执行。

使用独立线程和最多 32 条等待队列处理模型请求，避免阻塞企微 ACK。按机器人、群和消息 ID 的 SHA-256 去重，V7 只保存摘要、执行结果、股票代码与反馈状态，不保存用户原文、用户 ID、群 ID、模型原始输出或凭据。反馈未收到确认时不自动重复发送。

## 接口契约

`POST /api/v1/intraday/subscriptions`：原子添加关注并启用监控；已有参数保持。

```json
{"symbol":"600487"}
```

成功 HTTP 200：

```json
{"status":"ADDED","symbol":"600487","name":"亨通光电"}
```

已经启用时为 `ALREADY_MONITORED`。代码非法、目录不存在或不支持的交易所按已有校验返回 4xx，整笔订阅回滚。

`GET /api/v1/monitoring/status`：返回盘中、调度、群命令开关和企微连接状态，不包含凭据或目标群。

```powershell
Invoke-RestMethod http://127.0.0.1:8080/api/v1/monitoring/status
Invoke-RestMethod http://127.0.0.1:8080/api/v1/intraday/monitors
```

## 行情连接与实际限制

实测原 AKShare 分钟接口所用 `push2his.eastmoney.com` 出现 `RemoteDisconnected`。同厂商 `push2.eastmoney.com` 的相同分钟接口曾分别返回两只股票 241 条当日记录，最后时间为 2026-10-20 15:00；后续重试也出现断连，**不能宣称行情源已稳定或完成真实盘中提醒验收**。

增加 `MARKET_MINUTE_MIRROR_ENABLED`，默认 false；本机启动脚本显式开启。只在原分钟请求发生连接类错误后尝试同厂商备用主机，不影响日线接口。备用响应校验代码、市场、字段结构，沿用原单位换算、时间和新鲜度校验。两个连接都失败则返回 502，逐股任务记录失败，绝不用旧数据冒充当前行情。

调度在收盘后返回 `OUTSIDE_SESSION`，不会因启用而补发历史盘中信号。当前缺少交易时段真实信号及群内实际用户消息的最终验收；本地协议、模型工具调用与数据库链路均有测试，真实 DeepSeek 工具解析已通过。

## 日历维护

已通过 Java API 导入 `local-data/calendar-20261020-20261231.json` 共 73 个明确日期，覆盖至 2026-12-31。依据沪深交易所 2026 年休市公告，期间无公告节假日，周末关闭。运行时仍只查询已导入日历，缺日期时跳过。

- [上交所 2026 年休市公告](https://www.sse.com.cn/disclosure/announcement/general/c/c_20251222_10802507.shtml)
- [深交所 2026 年休市公告](https://investor.szse.cn/disclosure/notice/general/t20251222_618087.html)

2027 年开市前必须核对新公告并补日历；临时休市也需人工维护，不保证未来日历永久有效。

## 启停与验证

```powershell
# 显式开启真实服务；从进程 / 用户 / 系统环境读取凭据，不写入文件
.\.venv\Scripts\python.exe scripts/start_live_local.py
```

需要环境变量 `POSTGRES_PASSWORD`、`WECOM_BOT_ID`、`WECOM_BOT_SECRET`、`WECOM_CHAT_ID`、`DEEPSEEK_API_KEY`。JDK 默认为本机已安装的 21.0.10，可用 `STOCKSENTINEL_JAVA_HOME` 指定 JDK21；不会使用系统遗留的 JDK8。启动自动执行 Flyway；端口被占用时退出，避免两个机器人实例互相挤掉连接。启动脚本不重置用户监控名单或导入日历。

当前进程记录与日志：`.runtime/20261020-161407-246936/`；运行状态保存在 `processes.json`，Python 重启后已更新。只终止该记录所对应的本工程进程，不要批量关闭机器上的 Java/Python。服务当前为后台进程，重启电脑后需重新启动；休眠或断网期间不能监控。

```powershell
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml verify
.\.venv\Scripts\python.exe -m pytest quant-service/tests -q -p no:cacheprovider
```

Java 单元及协议测试 74 项、显式配置本机测试数据库后的集成测试 55 项通过；Python 52 项通过。所有自动测试只使用模拟客户端 / 本机 HTTP、WebSocket，不访问真实供应商。真实模型检查为人工授权脚本 `scripts/VerifyMonitorIntent.java`，不属于自动测试。

证据：`build-group-full.log`、`build-group-runtime.log`、`group-python-final.log`、`docs/group-monitoring-live-result.json`。本机 PostgreSQL 集成测试需沿用 README 的 `STOCKSENTINEL_TEST_DB_*` 环境变量，密码仅在内存中注入。
