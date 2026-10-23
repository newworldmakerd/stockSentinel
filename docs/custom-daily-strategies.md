# 自定义日线策略管理

首版实现本机可视化配置、不可变版本、数据预览和手动扫描。入口为 Java 服务的 `/manage/`。不替换任何内置策略，不自动扫描、不调用模型、不自动下单。

## 使用步骤

1. 从价量共振、MACD 金叉、筹码集中模板开始，或新建策略。
2. 选择公开关注列表、已申报持仓的股票代码，或手动指定代码；每次最多100只。
3. 组合 AND/OR 条件，最多20条、两层条件组。选择统一的 QFQ/NONE 口径。
4. 保存草稿，选择已收盘交易日，验证本地数据。可选最多5只预览样本。
5. 至少一只股票实际完成评估且没有跳过，才允许启用该版本。验证不要求一定命中，也不代表收益有效。
6. 手动扫描启用版本。页面显示每股结果、逐条比较值、缺失原因及新增事件数。

编辑保存产生新版本，旧启用版本继续有效；必须另行验证、启用才能切换。未保存修改不能用于预览或扫描。暂停按钮可以停用旧版本。首版没有版本删除、任意代码执行、自然语言生成规则或全市场自动采集。

## 因子与数据边界

17个因子：开高低收、MA5/10/20/60、DIF/DEA/MACD柱、前5日均量口径量比、成交量（股）、成交额（元）、90%/70%筹码集中度、筹码获利比例。

支持大于、等于、小于及包含等号的比较、上下穿越、较前日升降。因子对比必须同量纲；比例0.10表示10%。升降和穿越均使用前一交易日实际值。数值最多15位有效数字、8位小数。

只读已有行情和指标；不触发行情下载。行情必须日期、来源、复权口径一致；指标须与行情日期和计算版本对应，且没有落后于行情入库时间。任何所需因子缺失、陈旧或历史异常，整股跳过，即使另一个 OR 分支成立。ST、非沪深或非正常状态股票跳过。筹码模板要求已经有对应筹码数据，不因存在模板就自动启动筹码采集。

当日日线须收盘后人工确认，并确认具体价格源；当日筹码另要求确认 `CHIP_EASTMONEY` 来源。历史扫描不发通知。

## 配置与访问

```powershell
$env:CUSTOM_STRATEGIES_ENABLED = 'true'
$env:CUSTOM_STRATEGY_NOTIFICATIONS_ENABLED = 'false'
```

两项默认均为false。管理页及新接口仅允许本机回环地址，校验 Host/Origin，禁止跨源写入和页面嵌入；没有远程登录系统，不能直接作为公网管理站点发布。既有业务接口不受新过滤器影响。

开启群通知还需现有机器人启用、群目标配置以及每次扫描显式勾选推送。只允许当日启用版本命中后入队；按策略、版本、股票、交易日、群去重。入队不等于送达。暂停或切换版本后旧待发通知失效；超时/结果不明任务不会盲目重发。

## 接口契约

基路径：`/api/v1/custom-strategies`。JSON请求拒绝未知字段。

| 方法与路径 | 用途 |
|---|---|
| GET `/catalog` | 因子、操作符及限制 |
| GET 基路径 | 模块开关、策略列表、建议日期 |
| GET `/{id}` | 当前定义、当前/启用版本、修订和最近20次运行 |
| POST 基路径 | 保存新策略 |
| PUT `/{id}` | 携带 expectedRevision 保存新版本 |
| POST `/{id}/state` | `{ "revision": 1, "enabled": true }`；false暂停 |
| POST `/{id}/preview` | 预览并记录验证审计，无事件和通知 |
| POST `/{id}/scan` | 运行启用版本；写事件，按请求决定是否入队 |

新策略请求示例：

```json
{
  "expectedRevision": 0,
  "name": "均线上方观察",
  "description": "收盘价高于MA20",
  "definition": {
    "schemaVersion": "1", "adjustment": "QFQ",
    "universe": "SYMBOLS", "symbols": ["600487"],
    "rules": { "logic": "AND", "children": [
      { "factor": "CLOSE", "operator": "GT", "operand": { "factor": "MA20" } }
    ] }
  }
}
```

预览请求为 `{"revision":1,"tradeDate":"2026-10-22"}`，可附加 `symbols` 样本。扫描请求为 `{"revision":1,"tradeDate":"2026-10-22","notify":false,"requestId":"manual-001"}`。扫描不接受覆盖股票范围；相同请求标识返回原结果，改变日期、版本或通知口径需新的标识。

错误返回 `{"error":"CODE"}`。400为无效输入，404为不存在，409包含版本冲突、未验证、未启用、未收盘确认、范围为空/超过100、历史通知禁止等，503为模块未开启。扫描结果含 matched、evaluated、skipped、newEvents、notificationsQueued 及逐股 conditions。

## 数据库与部署

V25新增策略、不可变修订、运行审计、规则事件、通知队列5张表，不改持仓及原规则表。当前候选包包含尚未部署的V24和V25，部署会同时执行这两份迁移；筹码策略及智能文档仍可保持关闭。部署需明确授权。

## 校验命令

```powershell
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml test
mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml failsafe:integration-test failsafe:verify '-Dit.test=CustomStrategiesLocalDatabaseIT' '-Dspring.datasource.hikari.maximum-pool-size=4' '-Dspring.datasource.hikari.minimum-idle=0'
node --check backend/src/main/resources/static/manage/manager.js
```

集成测试读取 STOCKSENTINEL_TEST_DB_URL/USER/PASSWORD 环境变量，创建并清理独立测试schema；机器人和行情客户端使用模拟实现。`postman/tests/custom-strategy-catalog.js` 提供4项目录契约断言。

隔离页面预览可使用 `scripts/custom_manager_preview.py start|stop`，需已准备候选JAR及本机数据库环境；脚本使用模拟数据和随机回环端口，不修改 public。验收截图保存在 `.runtime/custom-manager-*.png`，截图股票为模拟样例。
