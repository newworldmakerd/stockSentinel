# 趋势策略本机部署记录 · 2026-10-22

用户授权：只读核验沪深300、四川美丰与亨通光电，通过后部署、迁移V21/V22、开启趋势调度及所属群提醒；文档模块保持关闭，不主动发测试消息。

## 已完成

- 最终发布包：`.runtime/release-trend-final-20261022-192127/`，保留冻结Python源码、JAR、无凭据启动脚本、SHA256清单及部署前JAR。
- 最终进程记录：`.runtime/20261022-192200-934509/processes.json`；Java/Python均本机启动，无Docker。
- Flyway从V20升级至V22，原迁移未改动；V21文档表虽创建，文档功能关闭。
- 原2条个人持仓迁移前后摘要相同，未转为趋势批次；没有记录虚构买卖。
- 企微恢复AUTHENTICATED，保留2个群及现有日线、盘中、做T开关。
- TREND_ENABLED与TREND_SCHEDULE_ENABLED已设为本机用户环境变量true；启动脚本显式禁止文档功能。

## 核验及修复

1. 腾讯真实沪深300报价的涨跌停价为-1，导致整批失败。只对固定沪深300将该值标记为空，个股负值仍拒绝；补回归。
2. 新Java客户端默认把LocalDate写为数组，引起Python422。改为ISO日期与精确小数字符串；测试使用非空日线检查真实HTTP请求格式。
3. 既有缓存原价日线约一年、新补前复权约两年，整窗不等长导致拒绝计算。仅裁去共同起点之前的数据，内部缺口和混源仍拒绝；补数据库测试。
4. 受前两个计算问题影响的新增趋势失败记录只提前重试时间，保留次数、状态及错误码，交给Java正常重算，没有手工伪造READY或指标。

真实证据：`.runtime/trend-live-capability-20261022.json`、`.runtime/trend-live-capability-verified-20261022.json`、`.runtime/trend-wire-live-smoke.json`。两股真实日线各QFQ/NONE486条；实际Java→Python HTTP计算通过，000691、000695、000697窗口修复后也由正式准备流程转为READY。

自动测试：Java168项、Python126项通过；原125项隔离数据库回归通过，新增保护及窗口测试后趋势21项通过。最新日志：`.runtime/trend-window-unit.log`、`.runtime/trend-predeploy-python.log`、`.runtime/trend-window-database.log`。

## 当前边界

- 全市场5224股底座分批准备，尚未全部完成；READY不代表符合买点，FAILED/PENDING不代表不符合规则。
- 沪深300东方财富日线曾成功返回486条，但随后多次ConnectionError，启动时指数基准缺失；记录TREND_INDEX_UNAVAILABLE，每15分钟重试。基准未恢复时暂停新买入观察，不能跳过过滤条件。已有持仓风控不依赖大盘强弱，但自身也必须具备有效日线/报价/批次。
- 部署时已过盘中调度时段，无盘中扫描结果是正常状态。交易日按分钟尝试；历史预算和报价轮转不保证每分钟覆盖全市场。
- 当日正式信号仍需收盘人工确认，沪深300源为TREND_CSI300_EASTMONEY；次日准备前一交易日数据不需要冒充当日最终确认。
- 未主动发测试消息；未验证新策略消息真实投递效果，等待符合条件的实际扫描。不增加每分钟模型调用，不自动交易。

最终核验快照：5224股中READY=364、EXCLUDED/ST=21、FAILED/TREND_RAW_HISTORY_FAILED=1、PENDING=4838；此前计算失败记录经正常Java重试均已清除。四川美丰、亨通光电与三只历史窗口验证股均READY。趋势通知0条、趋势批次0条；运行库持仓仍2条且摘要未变。该数量是部署时快照，随后由调度继续更新。机器可读结果见发布目录 `deployment-result.json` 及 `.runtime/trend-final-db-verification.txt`。

## 运维命令

```powershell
# 只读状态检查
Invoke-RestMethod http://127.0.0.1:8080/actuator/health
Invoke-RestMethod http://127.0.0.1:8000/health
Invoke-RestMethod http://127.0.0.1:8080/api/v1/monitoring/status
Invoke-RestMethod http://127.0.0.1:8080/api/v1/trend
Invoke-RestMethod http://127.0.0.1:8080/api/v1/documents/wecom

# 仅在按进程记录停止现有实例后启动；凭据全部从环境变量读取
.venv/Scripts/python.exe .runtime/release-trend-final-20261022-192127/start_live.py
```

回退需先停止当前实例，保留V21/V22附加表，使用发布目录中的previous-live.jar及原冻结Python版本启动；不通过删除新表回退数据库，不覆盖个人持仓。
