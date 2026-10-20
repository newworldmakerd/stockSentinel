# 腾讯分钟行情后备接口

2026-10-20，用户授权“把腾讯接口作为后备接口”。已完成开发、自动测试、真实调用验证与本机服务更新。

## 当前行为

1. 每次仍先请求原 AKShare 东方财富一分钟接口；原有可选东财同厂商备用主机保持。
2. 主源请求失败、数据格式无效、当前日数据为空或末条记录落后超过 120 秒时，开启腾讯开关后请求腾讯完整一分钟 OHLCV。
3. 主源恢复后，后续请求自然返回主源数据；不做永久切换。
4. 腾讯也失败时返回脱敏 502。Java 保留交易日、交易时段、新鲜度、停牌无成交、规则、冷却和幂等校验。

腾讯接口：

```text
GET https://ifzq.gtimg.cn/appstock/app/kline/mkline?param=sh600487,m1,,320
GET https://ifzq.gtimg.cn/appstock/app/kline/mkline?param=sz000731,m1,,320
```

该接口实际返回完整分钟开、高、低、收和成交量；不同于 Leeks 使用的腾讯最新报价接口。通过真实请求确认两只股票均可获取，归一化后返回最近 21 个完整分钟。盘中扫描频率仍为每分钟第 5 秒。

## 数据契约及迁移

- 后备数据标识为 `source=TENCENT_1M`，仍为 `adjustment=NONE`、`unit=SHARE`、`amount_unit=CNY`。
- 腾讯原始成交量为手，按原流程乘以 100 转为股。
- 完整窗口来自同一个供应商；不拼接不同来源的高点、收盘价或量能，也不把报价快照生成虚构分钟 K 线。
- 校验响应股票身份、返回码、数组结构、时间顺序与日期。只保留当日数据；当前未结束分钟排除；窗口缺口及午休仍使相关窗口指标不可用。
- 腾讯记录没有可信的逐分钟成交额，`amount=null`，不填零、不使用成交价乘量估算，也不把其他返回字段误当成交额。
- V8 仅放宽 `market_minute_bar.amount`，并增加约束：只有 `source=TENCENT_1M` 才能为空。Java/Python 也做同样的源限定校验。日线表与既有迁移没有改变。
- 当前五类盘中规则只使用价格和成交量，成交额缺失不改变这些规则的判断。策略信号仍保存当次完整数据源及窗口快照。
- 同一分钟主备切换沿用原游标，不重复产生信号和通知；后续分钟继续沿用同类冷却。

## 配置与运行

Python：`MARKET_TENCENT_BACKUP_ENABLED=true` 启用，缺省 false。主源失败后腾讯最多发起一次请求，串行限速至少一秒，连接/读取超时分别为 3/6 秒。

Java：`MINUTE_HTTP_TIMEOUT_SECONDS` 缺省 25，合法范围 5–90。当前本机启动脚本设为 45，为主源及两级后备留出顺序请求时间。一轮耗时较长时不保证一分钟内完成所有股票，仍禁止重叠扫描。

```powershell
# 当前已经启动，无需再执行；重启本机服务时使用
.\.venv\Scripts\python.exe scripts/start_live_local.py

# 只读查看状态
Invoke-RestMethod http://127.0.0.1:8080/api/v1/monitoring/status
Invoke-RestMethod http://127.0.0.1:8080/api/v1/intraday/monitors
```

当前后台运行目录 `.runtime/20261020-173702-397789/`。原两只关注股票、阈值和群命令开关保持，企微已重新鉴权。禁用后备可将 Python 开关设为 false 后重启，保留 V8 结构与已有数据。

## 验收结果

- Java 75 项常规/协议测试 + 56 项 PostgreSQL 集成测试，共 131 项通过；JAR 打包成功。
- Python 64 项通过。使用本机 HTTP 模拟腾讯返回；覆盖主源成功不访问腾讯、主源失败/过期/格式异常切换、备用失败、身份不符、未来/重复时间、异常价格/成交量、分钟缺口及缺失成交额。
- PostgreSQL 测试确认：腾讯缺失成交额可入库，原三种命中仍生成信号/通知；同分钟切回主源不重复；非腾讯记录缺失成交额被拒绝。
- 真实本机 API 请求两只股票均返回 HTTP 200，`source=TENCENT_1M`、21 条完整分钟，最后时间为 2026-10-20 15:00，说明当前已实际走通主源故障后的腾讯链路。
- 当时已经收盘，Java 手动扫描返回 `SKIPPED / OUTSIDE_SESSION`，零信号。没有为了验收放宽时间条件、补发历史提醒或伪造规则信号。
- 交易时段数据实时性、长期可达性和实际规则命中后的群推送仍需下一交易时段观察，当前成功样本不构成供应商稳定性保证。

证据：`docs/tencent-backup-live-result.json`、`tencent-backup-java-final.log`、`tencent-backup-python-final.log`。

关键实现：`quant-service/app/providers/tencent_provider.py`、`quant-service/app/api/intraday.py`、`backend/src/main/java/com/stocksentinel/intraday/IntradayValidation.java`、`backend/src/main/resources/db/migration/V8__tencent_minute_amount.sql`。

本次使用 `postman:api-engineer` 工作流：沿用现有 `/internal/market/minutes` 契约，明确源限定的成交额空值，通过本机 HTTP 模拟、真实 Java 写库及授权后的供应商实测验证。
