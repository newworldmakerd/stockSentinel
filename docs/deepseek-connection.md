# DeepSeek 连接验证

2026-10-20：从本机环境变量DEEPSEEK_API_KEY读取凭据，未打印或写入任何文件。模型列表鉴权HTTP200，返回deepseek-flash与deepseek-v4-pro。对deepseek-flash发起一次连接测试，HTTP200、finish_reason=stop，JSON状态校验通过，共59 tokens（54输入、5输出）。非敏感结果见deepseek-connectivity-result.json。

本次只验证服务鉴权及结构化生成，没有上传股票信号，没有生成股票交易建议，没有写入signal_advice，没有调用企微。此前基于模拟客户端的业务验收不能代替后续真实股票分析验收。

项目现支持DEEPSEEK_API_KEY作为LLM_API_KEY未设置时的后备；显式LLM_API_KEY保留优先级。密钥仅通过环境变量注入，不写.env。当前不自动开启模型，也不修改本机永久开关。

PowerShell终端读取到现有DEEPSEEK_API_KEY后，设置非敏感配置并启动Java即可使用：

```powershell
$env:LLM_BASE_URL = 'https://api.deepseek.com'
$env:LLM_MODEL = 'deepseek-flash'
$env:LLM_ENABLED = 'true'
# 先按README配置数据库环境变量；已有DEEPSEEK_API_KEY，无需复制密钥
java -jar backend/target/stocksentinel-0.1.0-SNAPSHOT.jar --server.address=127.0.0.1
```

新进程才能继承后来修改的系统环境变量，已打开的终端可能需要重新打开。不开启行情或任务也可对已有信号调用手动分析入口；不存在规则信号时不调用模型。当前数据库尚无真实规则信号，需先完成真实指标/策略评估。开启模型后新信号提交会按既有流程调用并产生供应商费用。

本次最小连接测试显式关闭thinking并限制64输出tokens；现有通用股票分析客户端使用既有1200输出tokens和默认供应商行为。DeepSeek当前默认开启thinking，真实复杂股票建议的耗时和截断情况尚需单独验证；截断会按原逻辑记录分析失败，不自动重试。未为连接测试擅自改变通用客户端行为。

官方资料：[JSON输出](https://api-docs.deepseek.com/guides/json_mode/)、[Thinking模式](https://api-docs.deepseek.com/guides/thinking_mode/)。
