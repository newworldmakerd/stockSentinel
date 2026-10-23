# 个人止盈止损提醒与日线启用

## 群助手用法

在已配置群中@机器人，使用明确的股票名/六位代码和人民币价格，例如（仅演示格式，价位由用户自行指定）：

- “设置亨通光电个人止盈60元，止损48元”。
- “把亨通光电止损改为49元”——保留本人此前止盈价。
- “查看我的止盈止损提醒”“查询亨通光电的个人提醒”。
- “关闭亨通光电的个人价位提醒”——同时停用本人该股两项，保留配置供查询。

首版只支持明确的单个价格，不支持区间、百分比、成本推算或移动止损；不允许从历史对话猜价格或股票。模型必须调用工具，服务端核对价格与本条原文，拒绝查询消息写入或指定别人userid。新增设置不需要先执行新增公共关注工具；每条消息最多一次写操作。

## 隔离与触发

V13新增personal_price_alert，唯一键为群摘要+企微userid+股票代码。同一股票，不同人可分别设置价位；不会改全群intraday_monitor、其他人或公共关注名单。只启用个人提醒的股票也加入分钟扫描，同股行情复用一次。最多4位小数，价格须为正；同时设置时止损必须低于止盈。部分修改保留另一项，重复相同设置保持版本。

交易时段，校验完整分钟、不复权、股票身份、单位、新鲜度和日期后判断：分钟收盘价≥个人止盈价，或≤个人止损价。不是逐笔触价或自动成交。同规则同用户同交易日只触发一次；只修改某项价格会重置该项判断，保留另一项触发状态。重新启用但价格不变不重复触发当天已触发规则，次交易日可再触发。

按用户确认，仍发本群，明确显示提醒所属用户（自设昵称及企微userid）、股票名称代码、触发时间、分钟收盘价和个人价位。自设昵称不是通讯录核验身份。

## 投递与边界

触发状态和personal_price_event在同一事务保存，独立持久化投递队列，不混入全群signal_event，不额外调用模型分析。通过原智能机器人连接发送，没有新建同Bot连接。独立单线程每2秒尝试投递；成功ACK记SUCCESS，不明结果记UNKNOWN且不自动重发；明确可重试最多3次；超过15分钟过期不补发。

停用会阻止后续触发和待发送队列，已发送或已在途通知无法撤回。修改价位不会回撤已经触发的历史事件。规则触发日期不代表消息已经发送成功。

配置PERSONAL_PRICE_ENABLED默认false，本机授权启动脚本设为true；PERSONAL_PRICE_WORKER_ENABLED可关闭自动投递用于测试。GET /api/v1/monitoring/status新增personalPriceEnabled。个人配置仅通过群回调绑定身份，不开放接受任意userid的公共写API。

## 模型工具契约

| 工具 | 字段 | 行为 |
|---|---|---|
| set_personal_price_alert | stock_query，take_profit/stop_loss至少一项数值 | 仅设置本人的个人价位，未提供另一项保留 |
| list_my_price_alerts | stock_query、offset均可选 | 查询本人，20只一页；含启用状态、价位、触发日期和版本 |
| disable_personal_price_alert | stock_query | 明确关闭整只股票个人价位提醒，只影响本人 |

模型不能提供user_id、group_hash、SQL、URL等字段。数据表存放用户主动指定的提醒信息，密钥仍只取环境变量。

## 日线策略启用

本机启动脚本已把JOBS_ENABLED和DAILY_SCAN_SCHEDULE_ENABLED设为true。按Asia/Shanghai每天15:15尝试扫描关注股，依据显式交易日历；计算买入观察、卖出观察（MACD死叉且收盘低于MA20）和风险预警（向下穿越MA60）。保持原规则、指标版本、数据来源和复权校验。

当天日线仍需15:00后人工确认来源，不自动确认；未确认会记录CLOSE_NOT_CONFIRMED并跳过。若确认晚于15:15，需要手动补跑。确认表示操作者已核实完整收盘数据，应确认实际使用的来源（AKSHARE_EASTMONEY或AKSHARE_TENCENT_DAILY），不能在盘中伪造确认。

```powershell
# 收盘后核实来源，再确认；示例来源应与实际行情一致。
Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/v1/market/close-confirmations -ContentType application/json -Body '{"tradeDate":"2026-10-21","source":"AKSHARE_EASTMONEY"}'
# 若备用源也已核实完整收盘，可分别确认其来源；未确认的来源不能写入当日日线。
Invoke-RestMethod -Method Post http://127.0.0.1:8080/api/v1/jobs/daily-scan -ContentType application/json -Body '{"tradeDate":"2026-10-21"}'
```

GET /api/v1/monitoring/status新增dailyJobsEnabled及dailyScheduleEnabled。启用不等于盘中已产生当天日线信号。

## 2026-10-21实测

104项Java单元测试、83项本机数据库集成测试通过。覆盖多人价位隔离、部分修改保留另一项、相同设置幂等、规则独立重新判断、跨日触发、个人独立股票扫描、停用待发取消、ACK成功去重、不明结果不重发，以及模型价格必须来自本条原文/禁止代他人设置。测试使用本地夹具和模拟机器人，不调用真实服务。

独立真实DeepSeek只读工具联调成功，无群测试发送。本机personalPriceEnabled、dailyJobsEnabled、dailyScheduleEnabled均为true，企微AUTHENTICATED；启用后盘中调用日线任务，返回SKIPPED/CLOSE_NOT_CONFIRMED、processed=0，确认没有绕过收盘边界。没有替用户预设真实价位；真实个人触发与群送达等待用户实际设置后的行情命中。状态证据见[personal-price-integration-20261021.json](personal-price-integration-20261021.json)。
