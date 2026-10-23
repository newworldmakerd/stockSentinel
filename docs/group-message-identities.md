# 群消息分类与策略标识

2026-10-23：已完成展示层修改及编译/回归，11:18获用户明确授权后重启Java并验证上线。目的为区分群里不同策略的提醒；没有修改触发条件、扫描时间、参数、交易日/完整K线校验、所属群/用户、去重或重试，也不新增大模型调用。

## 首屏格式

```text
图标【消息类别】提醒动作
策略：中文策略名｜适用范围｜确认阶段
所属用户：个人消息在这里显示
股票：中文名称 六位代码
```

公共观察不填写个人归属；个人提醒保留真实所属用户，趋势批次的归属与股票从原消息末尾提前到首屏。模型建议仍是辅助内容，不参与标题分类，不修改规则事实。

| 类别 | 对应消息 | 第二行区分点 |
| --- | --- | --- |
| 🔎 选股候选 | 日线买入观察、缩量回踩早晚候选、回踩转强 | 日线波段/强势股缩量回踩；收盘信号/未转强/盘中转强确认 |
| ⚡ 盘中异动 | 快速拉升、快速跳水、普通分钟突破 | 短线异动；公共观察；盘中完整分钟 |
| 🛡️ 持仓风控 | 日线卖出/MA60风险、公共价位、个人价位、个人仓位约束、ATR止损 | 具体策略；公共观察/个人价位/个人持仓；触及/待核对/次日生效 |
| 🔄 做T观察 | RSI减仓、买入/回补、恢复底仓 | 双周期RSI/做T成交账本；个人持仓；盘中/收盘前检查 |
| 📈 中长线趋势 | 20日突破预告与收盘确认 | 趋势跟踪；公共观察；盘中预告/收盘确认 |
| 🌐 大盘状态 | 指数缺失、市场偏弱、过滤通过 | 趋势跟踪·大盘过滤；全市场状态 |
| 🧰 系统状态 | 扫描异常及恢复 | 运行诊断（非选股）；本轮异常/恢复 |
| 📊 每日汇总 | 收盘日报 | 日线技术选股与行情汇总；公开汇总；交易日快照 |

风险触及保留🚨文字强调；分类同时使用中文，不只依靠颜色或图标。公共日线卖出不伪装成针对某位用户的卖出指令；公共价位与个人价位明确分开。

## 预览

完整本地模拟预览见[group-message-preview-20261023.md](group-message-preview-20261023.md)。公共消息正文由实际NotificationTemplate渲染，其余展示统一标题器的首屏；示例不是实际行情，不发送企微、不写业务库。

```text
⚡【盘中异动】盘中突破观察
策略：短线异动｜公共观察｜盘中完整分钟

🔄【做T观察】减仓观察
策略：双周期RSI｜个人持仓｜盘中完整K线

📈【中长线趋势】盘中突破预告
策略：趋势跟踪｜公共观察｜盘中预告
```

## 影响面与验证

统一标识器为`backend/src/main/java/com/stocksentinel/notification/MessageIdentity.java`；公共模板为同目录NotificationTemplate.java。其他修改仅在PullbackDigestService、TObservationService、PersonalPriceService、PositionService、TrendLedger、TrendService、ReportNarrative及ScanFaultService的消息构造处。

Java169项单元测试通过（含4000字节公共消息限制、净化、建议位置、日报表格/3900字节和来源保留）；132项隔离数据库回归通过，覆盖趋势、做T、盘中、群助手、通知、日报和异常提醒。测试使用模拟客户端，未调真实行情/模型/企微。日志为`.runtime/message-identity-unit-final.log`、`.runtime/message-identity-database.log`。

冻结发布包`.runtime/release-message-identity-20261023-110442/`已上线，Java PID111888；Python监听PID95144保持不变。进程记录在`.runtime/20261023-111815-202856/processes.json`，其中Python标为保留实例。没有新增数据库迁移，仍V22；2条个人持仓摘要与重启前相同。没有重写已冻结的历史消息或重发旧信号。只有重新构造的消息使用新标题，已入队旧payload继续按原队列规则处理。

上线检查：Java/Python均UP，企微AUTHENTICATED且2个群；日线、盘中、做T、个人提醒及趋势等原开关和策略参数与部署前快照一致，文档模块保持关闭。证据在发布目录deployment-result.json、predeploy-state.json及`.runtime/message-identity-predeploy-db.json`/`message-identity-postdeploy-db.json`。未主动发测试消息；本次授权为重启与验证，真实群样例投递未执行。
