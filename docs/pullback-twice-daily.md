# 缩量回踩早晚候选推送

用户确认：仅交易日，上海时区09:40和15:30各推送一次候选汇总。原盘中每10分钟推送停止。最多5只，早晚允许推荐同一股票，多群独立幂等。

只修改候选消息调度及状态说明；原交易时段内每10分钟的数据准备/失败重试、分钟转强即时提醒、15:30日报、策略阈值、日线依据日及候选池上限保持。晚间仍使用当前保存且校验通过的日线依据，不自动认定当日收盘或重算当日日线。

投递窗口09:40～09:50、15:30～15:40，结束时刻不包含；保持10分钟过期、最多3次已知失败重试、UNKNOWN不自动重发。节点错过触发时刻不额外追发。不更改历史发送记录或补发今天早间消息。筛选未完成或缺数据时仍明确标注。

API GET /api/v1/pullback 的recommendations将intervalMinutes=10替换为frequencyPerTradingDay=2、times=["09:40","15:30"]。群助手策略说明同步更新。Flyway无新迁移。

实现：PullbackDigestService中tick为两个独立cron；refreshScreening保留原数据准备cron。队列、ACK、防重、数据来源及模型调用不变。PullbackController与GroupAssistantTools同步展示新频率。

验证包含：只在两次指定时间入队、旧盘中时段不入队、晚间可投递、结束边界停止、休市及关闭开关不发送、早晚股票可重复、每轮最多5只、原数据准备仍重试、SUCCESS/UNKNOWN分别记录。仅本地模拟客户端测试，不发送真实群测试消息。

验收：131项普通测试通过，编译打包成功（`.runtime/pullback-twice-daily-checked.log`、`.runtime/pullback-twice-daily-package.log`）。2026-10-21 14:55已重启Java加载，运行目录 `.runtime/20261021-145521-672610`，PID71888。状态API显示frequencyPerTradingDay=2、times=[09:40,15:30]，机器人AUTHENTICATED、健康UP；Python保持。未补发历史汇总或发送测试群消息。
