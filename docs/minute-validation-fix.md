# 分钟行情 INVALID_DATA 定位与修复（2026-10-21）

## 1. 背景与目标

本次已复现并修复主备行情同时被判无效的一项实际原因：供应商返回末尾下一分钟的未完成行，旧逻辑拒绝整个窗口。现在只剔除已观察到的末尾未完成行，保留真实已完成分钟；同时把泛化错误细分到具体校验代码、行号和字段。

这不是以重试掩盖数据异常，也不代表所有未来可能出现的行情故障已经消除。

## 2. 根因与实际证据

13:06:38～40 对 601869、600519、600487 分别读取东方财富主源与腾讯，共6份窗口。每份末尾都包含13:07，前一行是13:06。旧 `normalize_minutes` 在 `stamp > current_minute` 时抛出 `ValueError`，旧分类器把它收敛为 `INVALID_DATA`，Java最终记录 `MINUTE_PRIMARY_INVALID_DATA_BACKUP_INVALID_DATA`。

本机UTC约05:06:55，供应商HTTP Date约05:06:56，报价时间13:06:51；这些证据没有显示出足以解释下一分钟记录的本机时钟偏差。因此本次没有修改系统时间或时区，也没有把行情时间整体回移一分钟。

实际数据证据：`.runtime/minute-diagnostic-20261021-130640-989117.json`，只包含行情列、股票代码、来源、采集时间和固定诊断信息，没有凭据或群消息。回归样本提取其中601869两来源的最后23行，保存在 `quant-service/tests/fixtures/minute_forming_tail.json`。

代码影响点：

- `quant-service/app/api/intraday.py:95`：按行分别校验日期、精度、时段、顺序和未来时间。
- 同文件 `:116`：仅已知两个分钟源的末尾一行、时间恰为当前分钟加一分钟时，作为未完成行剔除；当前分钟本来就会剔除。
- `quant-service/app/providers/minute_failure.py:10`：固定诊断异常，仅保存代码、行号和白名单字段。
- `quant-service/app/api/intraday.py:175`：主备失败日志增加各自的行号和字段。
- `backend/src/main/java/com/stocksentinel/intraday/IntradayClient.java:43`：允许接收新增固定原因码，未知头值仍不进入日志或数据库。
- 腾讯元数据、行结构与日期顺序错误也改用具体代码；镜像分钟异常保留原有 `ProviderDataError` 类型兼容性。日线逻辑不变。

## 3. 修复行为与错误记录

### 时间规则

例如采集于13:06:40，末尾返回13:06、13:07：两行均不参与计算，保留到13:05的完整分钟。每条保留分钟的时间、价格、成交量均不平移或补造，窗口仍来自同一源。

仍拒绝以下情况：

- 超过下一分钟的未来行；位于窗口内部的未来行；未知来源的未来行。
- 非当日数据、秒或微秒不为零、交易时段外数据、重复或倒序时间。
- 非法数字、无穷值、无效价格、OHLC关系、负成交量或成交额、缺失字段。

### 记录在哪里

1. **数据库任务明细**：记录主源和后备的具体原因码，例如 `MINUTE_PRIMARY_DATA_TIME_FUTURE_BACKUP_DATA_OHLC`，不再一律压成INVALID_DATA。
2. **Python运行日志**：记录 `primary_row/primary_field`、`backup_row/backup_field`；行号从0开始。不会输出错误输入全文、请求凭据或上游异常正文。
3. **群异常提醒**：仍按原3轮阈值、15分钟冷却和恢复一次执行，展示任务ID、股票中文名/代码及具体原因码。

主要新增原因码：

| 代码 | 含义 |
|---|---|
| DATA_TIME_PARSE / DATA_TIME_DATE / DATA_TIME_PRECISION / DATA_TIME_SESSION | 时间解析失败 / 日期不符 / 精度异常 / 时段异常 |
| DATA_TIME_FUTURE / DATA_TIME_ORDER | 非允许剔除的未来行 / 重复倒序 |
| DATA_PRICE / DATA_OHLC | 价格范围 / 高低价关系不合法 |
| DATA_VOLUME / DATA_AMOUNT / DATA_NUMBER | 成交量 / 成交额 / 数值格式或有限性异常 |
| DATA_FIELD_MISSING / DATA_ROWS / DATA_EMPTY / DATA_STALE | 缺字段 / 行结构数量 / 空数据 / 过期 |
| DATA_IDENTITY / DATA_SHAPE / DATA_JSON | 来源身份 / 响应结构 / JSON异常 |

旧错误记录没有完整失败样本，不能据此精准回填每条旧INVALID_DATA；本次不修改历史任务或信号。

只读查询示例：

```sql
SELECT j.id, j.started_at, j.status, i.symbol, i.code
FROM job_execution j JOIN job_execution_item i ON i.job_id=j.id
WHERE j.job_name='INTRADAY_SCAN'
  AND j.started_at >= TIMESTAMPTZ '2026-10-21 13:12:29+08'
ORDER BY j.id DESC, i.id;
```

## 4. 保留边界

Java仍负责扫描、规则、数据库和通知；Python仅提供行情标准化及指标。完整分钟、120秒新鲜度、成交量单位、源限定成交额空值、交易时段和交易日、窗口连续性均保留。没有修改策略阈值、冷却、收盘确认、信号去重或通知UNKNOWN规则；不自动下单。

接口成功体结构与最终失败HTTP 502及原detail正文保持，原因头新增固定枚举值。Python异常仍继承ValueError，分钟镜像错误继续兼容ProviderDataError。没有数据库结构变更。

## 5. 待观察项

本次是针对已捕获样本的适配修复，不能以一次成功代替长期验收。继续观察真实扫描逐股成功率、耗时和新错误码；若出现其他异常，按新的具体原因排查，不扩大下一分钟剔除范围，不移除数据校验。

## 6. 验证与部署

- Python全量104项通过，包含两份真实公开行情样本回放、正常当前行及末尾下一分钟剔除、内部未来行和更远未来行拒绝、具体错误码/位置以及脱敏日志。
- Java相关单元/本机HTTP测试5项通过，验证具体原因头、非白名单拒绝、响应解码、连接失败和读超时。
- Java隔离数据库集成43项通过（盘中28、告警15），新增Python→HTTP→Java→PostgreSQL具体校验原因落库验证，同时覆盖原事务、去重及模拟WebSocket投递。
- 自动测试不调用真实行情、模型或企微。真实行情诊断和部署后只读验证另计，没有发送人工群测试消息。
- Java打包成功。本机部署目录 `.runtime/20261021-131229-309787/`，读取最新持久环境配置，未主动更改群目标。
- 部署后13:12:40～42，601869、600519、600487的本机行情API全部返回200，东方财富来源、21条完整分钟、末条13:11；企微状态AUTHENTICATED，告警开关开启。
- 修复后首轮真实扫描：任务127于13:13:05启动，SUCCESS，处理6只、成功6只、失败0、跳过0；13:13:10生成的分钟行情故障RECOVERY事件已成功投递。这是一轮实际恢复证据，不是长期稳定性保证。

证据日志：`.runtime/minute-validation-python-final.log`、`.runtime/minute-validation-java.log`、`.runtime/minute-validation-native.log`、`.runtime/minute-validation-package.log`。

```powershell
.\.venv\Scripts\python.exe -m pytest quant-service/tests -q -p no:cacheprovider
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml '-Dtest=IntradayClientTest,IntradayScanFailureTest' test
# 测试库连接从当前进程环境读取，密码不得写进命令文件
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml '-Dit.test=IntradayLocalDatabaseIT,ScanFaultLocalDatabaseIT' test-compile failsafe:integration-test failsafe:verify
```

旧JAR备份 `.runtime/minute-validation-before-20261021.jar`。如需完整回滚，Java包和Python分钟适配必须一并恢复；没有需回滚的数据库迁移，不能删除业务信号或历史告警记录。运行操作需按用户授权范围执行。

本次采用 `prod-debug` 报错→代码→实际数据流程，沿用 `postman:api-engineer` 契约和本机模拟验证；未发布外部API或共享资源。
