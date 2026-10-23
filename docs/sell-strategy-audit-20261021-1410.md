# 卖出策略运行排查（2026-10-21 14:10）

## 1. 背景与目标

日线卖出规则已开启，但没有成功执行的日线任务，也未生成卖出信号。当前群未配置公共止损；唯一启用的个人提醒属于当前允许群之外。没有证据表明是卖出消息投递失败。

## 2. 现状与影响点

- strategy_definition：SWING_TREND_V1 enabled=true，sellMode=DEAD_CROSS_BELOW_MA20。
- monitoring/status：日线任务与调度均true，机器人AUTHENTICATED，目标群数1。
- DailyScanScheduler.java:15：每日15:15，仅收盘日线。14:10尚未到今日自动调度时间。
- DailyScanService.java:50：今日必须人工确认收盘；数据库唯一日线任务#106在11:22被CLOSE_NOT_CONFIRMED跳过，processed_count=0；market_close_confirmation目前无记录。此刻未收盘，不应人为确认。
- SwingTrendStrategy.java:13与MacdCrossRule.java:12：上一交易日DIF-DEA>=0，当日<0，且当日收盘<MA20。持续死叉不会每天重发SELL_WATCH。
- ScreeningService.java:120：全市场选股只取BUY_WATCH，不落卖出信号，不能用全市场扫描完成代替日线关注股卖出扫描。
- 数据库signal_event目前只有INTRADAY_V1的7条BREAKOUT_WATCH和3条RAPID_RISE；SELL_WATCH/STOP_LOSS/TAKE_PROFIT/RISK_ALERT均0，相关notify_record也是0。
- 七只公共盘中监控均无止盈、止损价格。
- PersonalPriceService.java:34、:39：只扫描配置群对应的个人设置。唯一启用的四川美丰000731止盈7.65、止损7.50，群摘要不在当前配置集合，last_bar_at停在11:30，无个人事件。未输出用户ID或群ID，也未迁移提醒。
- MA60盘中风险已启用：6只READY/MA60_NO_NEW_CROSS；沈鼓集团601091因MA60_DAILY_BASIS_MISSING跳过。不会对启动前已处于均线下方的股票追发信号。

2026-10-20日线已存筛选快照的只读复核：

| 股票 | 前日DIF-DEA | 当日DIF-DEA | 收盘/MA20 | 新死叉且低于MA20 |
|---|---:|---:|---|---|
| 山东路桥000498 | 0.07887204 | 0.05171543 | 6.40/5.805 | 否 |
| 四川美丰000731 | -0.14551037 | -0.14756557 | 7.31/7.845 | 否 |
| 亨通光电600487 | -1.44831385 | -0.91834404 | 52.07/58.375 | 否 |
| 贵州茅台600519 | 9.75124788 | 10.11934479 | 1331.88/1272.702 | 否 |
| 西部矿业601168 | -0.30245361 | -0.36778466 | 31.32/34.8905 | 否 |
| 长飞光纤601869 | -12.00603481 | -10.61255503 | 339.09/404.272 | 否 |

沈鼓集团因历史不足121条未评估。以上是昨日数据，不能作为今日盘中已完成收盘计算。

## 3. 处理建议（未执行）

在实际使用的群重新明确设置个人提醒，或经用户指定将原群也加入允许集合；不要自动搬移旧群用户配置。日线仍按收盘确认流程执行，可另行增加晚确认补跑。若需要盘中趋势卖出，需要单独确定触发条件，不能擅自把日线MACD规则改为分钟指标或持续低于均线。

只读复核SQL：

```sql
SELECT id,trade_date,status,error_message,processed_count
FROM job_execution WHERE job_name='DAILY_SCAN' ORDER BY id DESC LIMIT 10;
SELECT trade_date,source,confirmed_at FROM market_close_confirmation;
SELECT strategy_code,signal_type,count(*) FROM signal_event GROUP BY strategy_code,signal_type;
```

## 4. 拦截与校验

交易日历、真实收盘时间、人工收盘确认、来源确认、历史与指标对齐、来源及复权一致性均保留。群与用户隔离、防重、有限重试、UNKNOWN不自动重发均保留。规则信号后模型解释，不自动下单。

## 5. 待明确事项

是否增加盘中卖出条件、是否允许晚收盘确认自动补跑，以及个人提醒要在哪个实际群重新设置。当前排查不改变这些口径。

## 6. 验证与风险

仅检查本机代码、PostgreSQL只读查询和本机状态接口；未调用真实行情/模型/企微，未修改业务数据、配置、策略或重启服务。无业务变更，不运行编译测试。原始故障链未发现已生成却投递失败的卖出记录。
