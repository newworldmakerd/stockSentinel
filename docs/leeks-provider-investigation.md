# 本机 Leeks 行情接口与 StockSentinel 断连调查

调查日期：2026-10-20。只读检查插件、配置及项目实现；未修改运行中的行情规则、数据源或 IDEA 配置，也未新发腾讯/新浪行情请求。

## 已确认结论

本机已安装 `com.huage2580.leeks`，名称 Leeks，版本 2.1.0。安装位置：

```text
C:/Users/admin/AppData/Roaming/JetBrains/IntelliJIdea2025.2/plugins/leeks/lib/leeks-2.1.0.jar
```

同时存在的 `IDEALeek.jar` 实际为 LeekScript 语言插件，不是本次股票插件。

通过 JDK21 javap 读取本机 JAR 的实际字节码，并与作者源代码交叉核对：

| 模块 | 请求 |
|---|---|
| TencentStockHandler.stepAction | `http://qt.gtimg.cn/q=` 加逗号分隔的市场前缀股票代码 |
| SinaStockHandler.pollStock | `http://hq.sinajs.cn/list=` 加同类股票代码 |
| StockWindow.factoryHandler | `key_stocks_sina=true` 选新浪，否则选腾讯 |

本机 `IntelliJIdea2025.2/options/other.xml` 的 `PropertyService` 没有 `key_stocks_sina` 项，`key_proxy` 为空，按照所检代码的默认路径使用腾讯。未抓取 IDEA 运行时网络流量，因此这是配置与代码证据，不冒充抓包证明。

两只股票的腾讯请求格式：

```text
http://qt.gtimg.cn/q=sz000731,sh600487
```

新浪备选格式：

```text
http://hq.sinajs.cn/list=sz000731,sh600487
```

作者说明股票默认 10 秒轮询，更新区间可自定义；不是持续 WebSocket 推送。腾讯处理器读取最新价、涨跌额、涨跌幅、行情时间、当日最高/最低价，多个代码合并为一个请求。请求失败时捕获异常，未成功更新的表格内容仍可能保留；腾讯界面刷新标签记录本机刷新时刻，另有报价自身时间字段，不能仅凭界面有数字判断持续取得新报价。

## 与当前项目的区别

StockSentinel 使用 AKShare `stock_zh_a_hist_min_em(period=1,adjust='')`，主要请求：

```text
https://push2his.eastmoney.com/api/qt/stock/trends2/get
```

显式启用后可尝试同厂商备用主机 `push2.eastmoney.com`。这是包含分钟 OHLC、分钟成交量和成交额的序列；Python 再计算五分钟变动、前二十分钟高点和平均成交量，Java 校验新鲜度并判断规则。

| 项目 | Leeks 腾讯路径 | StockSentinel 当前路径 |
|---|---|---|
| 供应商 | 腾讯 | 东方财富，经 AKShare 与备用连接 |
| 主要数据 | 当前报价快照 | 已结束分钟 K 线窗口 |
| 请求方式 | 多只股票合并 GET，默认约 10 秒 | 每只股票取分钟序列，每分钟调度 |
| 图形 | 另行加载图片；作者说明不自动刷新 | 当前没有图形界面 |
| 数据失败 | 界面可能保留上次结果 | 返回 502，拒绝陈旧数据参与信号 |

## 断连原因：证据边界

既有直接请求复现 `requests.ConnectionError`，底层 `RemoteDisconnected('Remote end closed connection without response')`。说明连接在取得 HTTP 响应状态/头之前被对端或链路中间设备关闭。Python API 捕获异常后映射为本机 502；并非先收到供应商 502，也没有取得可校验的分钟数据。此前备用主机曾返回两股各 241 条当日记录，随后同样断连，表明已有成功样本但可达性不稳定。

当前工具进程无 HTTP_PROXY、HTTPS_PROXY、ALL_PROXY；Requests 对两个东财地址解析出的代理配置也为空。插件本机配置未启用自定义代理。没有证据表明“插件走代理、项目不走代理”是这次差异的原因；对已启动进程的网络路径没有抓包，不能扩展为完整网络审计。

**尚未定位到供应商风控、限流、封 IP、TLS/客户端特征或网络中间设备中的具体哪一种原因。** 当前没有 HTTP 429/403、供应商拒绝说明或网络追踪证据，不能把可能原因当结论。既有证据见 `docs/group-monitoring.md:53`、`docs/group-monitoring-live-result.json`；客户端代码见 `quant-service/app/providers/akshare_provider.py` 和 `quant-service/app/api/intraday.py`。

## 可行的下一步及规则边界

可以借鉴插件，为最新价和价格阈值提醒增加腾讯报价源，避免所有提醒依赖东财分钟接口，但需先真实验证可达性、报价时间、单位与覆盖。插件能够显示报价，并不等于已证明该接口在本项目中长期可用。

突破放量规则仍需要此前二十个完整分钟的最高价与成交量。报价中的当日最高价、累计成交量不能替代该窗口；定时采集快照也不保证还原完整分钟内的最高价。若新增来源，必须保留来源、数据粒度和时间边界，不能静默改变原规则或将不同来源混算。本轮未实施切换。

## 证据定位

- 本机字节码：`.runtime/leeks-provider-bytecode.txt:108`（腾讯 URL），`:318`（新浪 URL）。
- 本机选择逻辑：`.runtime/leeks-settings-bytecode.txt:74`（factoryHandler）。
- [作者腾讯处理器源码，第 61 行请求、75–81 行解析报价字段](https://github.com/huage2580/leeks/blob/master/src/main/java/handler/TencentStockHandler.java#L61)
- [作者新浪处理器源码，第 19 行 URL](https://github.com/huage2580/leeks/blob/master/src/main/java/handler/SinaStockHandler.java#L19)
- [作者股票窗口与数据源选择代码](https://github.com/huage2580/leeks/blob/master/src/main/java/StockWindow.java)
- [作者 README：股票轮询频率、图片和代理说明](https://github.com/huage2580/leeks#使用)

本次使用 `postman:api-discovery` 技能组织接口调查，来源以本机插件实际 JAR 和作者源码为准。
