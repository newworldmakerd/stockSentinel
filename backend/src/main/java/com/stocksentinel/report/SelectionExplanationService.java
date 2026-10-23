package com.stocksentinel.report;

import com.fasterxml.jackson.databind.*;
import com.stocksentinel.market.*;
import com.stocksentinel.pullback.*;
import com.stocksentinel.stock.*;
import com.stocksentinel.strategy.*;
import com.stocksentinel.strategy.engine.*;
import com.stocksentinel.jobs.TradingDatePolicy;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

/** Read-only explanations. Never collects prices, calculates indicators or changes candidates. */
@Service
public class SelectionExplanationService {
    private final SelectionExplanationMapper mapper;private final ReportMapper reports;
    private final WatchlistMapper stocks;private final StrategyContextReader contexts;private final MarketMapper market;
    private final PullbackSettings parameters;private final ObjectMapper json;private final TradingDatePolicy dates;
    public SelectionExplanationService(SelectionExplanationMapper mapper,ReportMapper reports,WatchlistMapper stocks,
            StrategyContextReader contexts,MarketMapper market,PullbackSettings parameters,ObjectMapper json,TradingDatePolicy dates){
        this.mapper=mapper;this.reports=reports;this.stocks=stocks;this.contexts=contexts;this.market=market;this.parameters=parameters;this.json=json;this.dates=dates;
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> explain(String symbol,LocalDate date) {
        WatchlistService.symbol(symbol);if(date==null||date.isAfter(dates.today()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_SCAN_DATE");
        var stock=stocks.stock(symbol);if(stock==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"STOCK_NOT_FOUND");
        var result=new LinkedHashMap<String,Object>();result.put("symbol",symbol);result.put("name",stock.name);result.put("scanDate",date);
        result.put("note","只读数据库结果，不是实时行情；日线入选不等于盘中转强，不自动关注或下单。未评估或数据缺失不能解释为规则不满足。");
        var run=reports.run(date);if(run==null){result.put("status","NOT_STARTED");return result;}
        result.put("dataDate",run.asOf);result.put("currentDigestUsesThisBasis",date.equals(dates.today())&&run.asOf.equals(reports.previousOpen(dates.today())));result.put("scanStatus",run.status);result.put("coverage",reports.coverage(date));
        var item=mapper.item(date,symbol);if(item==null){result.put("status","NOT_IN_SCAN");return result;}
        result.put("status",item.status);result.put("code",item.code==null?"NOT_EVALUATED":item.code);
        result.put("eligibility",eligibility(item));
        if(!Set.of("CANDIDATE","NO_MATCH").contains(item.status)){
            result.put("explanation",reason(item.code,item.status));return result;
        }
        try {
            var saved=json.readTree(item.details);if(saved==null||!saved.path("rules").isArray()||saved.path("rules").isEmpty())throw new IllegalStateException("Missing saved rules");var swing=new LinkedHashMap<String,Object>();
            swing.put("basis","SAVED_SCAN_SNAPSHOT");swing.put("matched","CANDIDATE".equals(item.status));
            swing.put("strategyVersion",saved.path("strategyVersion"));swing.put("rules",saved.path("rules"));
            Long rank=mapper.swingRank(symbol,date);swing.put("rank",rank);swing.put("inTopFive",rank!=null&&rank<=5);
            result.put("swing",swing);
            result.put("pullback",pullback(symbol,run.asOf,saved));
        }catch(Exception invalid){result.put("diagnosticStatus","SNAPSHOT_UNAVAILABLE");result.put("explanation","已存规则快照无法完整读取，不能补造具体条件或结论。");}
        return result;
    }
    private Map<String,Object> eligibility(SelectionExplanationMapper.Item item){
        var r=new LinkedHashMap<String,Object>();r.put("status",item.status);r.put("reason",reason(item.code,item.status));
        r.put("average20AmountYuan",item.averageAmount);r.put("minimumAverage20AmountYuan",100000000);return r;
    }
    private Map<String,Object> pullback(String symbol,LocalDate asOf,JsonNode saved) throws Exception {
        var result=new LinkedHashMap<String,Object>();result.put("enabled",parameters.enabled());
        result.put("strategyVersion",parameters.version());result.put("parameters",parameters);
        if(!parameters.enabled()){result.put("status","DISABLED");return result;}
        var candidate=mapper.candidate(symbol,asOf,parameters.version());
        if(candidate!=null){
            var snapshot=json.readTree(candidate.details);result.put("basis","SAVED_CANDIDATE_SNAPSHOT");result.put("matched",true);
            result.put("rules",snapshot.path("rules"));result.put("parameters",snapshot.path("parameters"));
            result.put("rankingNote","按该依据日当前尚未转强候选的成交额排序，不代表历史发送记录；是否当前推送依据见currentDigestUsesThisBasis");
            result.put("referenceHigh",candidate.referenceHigh);result.put("triggeredAt",candidate.triggeredAt);
            Long rank=mapper.rank(symbol,asOf,parameters.version());result.put("rank",rank);result.put("inTopFive",rank!=null&&rank<=Math.min(5,parameters.poolLimit()));
            result.put("minuteMonitoringEligible",rank!=null&&rank<=parameters.poolLimit());
            result.put("status",candidate.triggeredAt==null?"OBSERVATION_CANDIDATE":"ALREADY_STRENGTHENED");return result;
        }
        result.put("basis","READ_ONLY_RECALCULATION_CURRENT_LOCAL_DATA");
        result.put("note","按当前回踩参数与本地日线复算，不代表当时扫描已保存的回踩结论；本查询不会写入候选池。");
        try {
            var swingParameters=json.treeToValue(saved.path("context").path("parameters"),StrategyParameters.class);
            var c=contexts.read(symbol,asOf,swingParameters).context();
            var bars=market.daily(symbol,asOf.minusYears(1),asOf,MarketContracts.Adjustment.QFQ);
            if(bars.size()<27||!asOf.equals(bars.getLast().tradeDate)){
                result.put("status","DATA_UNAVAILABLE");result.put("code","INSUFFICIENT_PULLBACK_WINDOW");return result;
            }
            int end=bars.size()-1,days=0;
            while(end-days>0&&bars.get(end-days).closePrice.compareTo(bars.get(end-days-1).closePrice)<0)days++;
            if(days<parameters.minDays()||days>parameters.maxDays()){
                result.put("status","RULE_NOT_MATCHED");result.put("matched",false);
                result.put("rules",List.of(new RuleResult(false,"PULLBACK_DAYS",String.valueOf(days),parameters.minDays()+"～"+parameters.maxDays(),"连续收盘回调天数；按原规则在此短路，其他回踩指标未计算")));
                result.put("remainingRulesStatus","NOT_EVALUATED_BY_ORIGINAL_RULE");return result;
            }
            if(end-days<20){result.put("status","DATA_UNAVAILABLE");result.put("code","INSUFFICIENT_PREVIOUS_RISE_WINDOW");return result;}
            var decision=PullbackRules.evaluate(bars,c,parameters);result.put("matched",decision.matched());result.put("rules",decision.rules());
            result.put("status",decision.matched()?"MATCHED_ON_RECALCULATION_NOT_SAVED":"RULE_NOT_MATCHED");
        }catch(StrategyContextReader.Skipped missing){result.put("status","DATA_UNAVAILABLE");result.put("code",missing.code);}
        catch(Exception unavailable){result.put("status","DATA_UNAVAILABLE");result.put("code","LOCAL_RECALCULATION_UNAVAILABLE");}
        return result;
    }
    private static String reason(String code,String status){
        if(code==null)return "PENDING".equals(status)?"尚未处理，不能判断是否符合规则":"未记录原因";
        return switch(code){
            case "NO_CURRENT_TRADES"->"扫描时缺少当日报价或报价无成交，未进入技术规则评估（不直接认定停牌）";
            case "ST"->"扫描时名称包含ST，按股票池规则排除";
            case "HISTORY_UNDER_121","INSUFFICIENT_HISTORY"->"有效本地历史不足121条，未完成规则评估（不直接认定上市时间不足）";
            case "LOW_LIQUIDITY"->"近20日平均成交额低于1亿元";
            case "AMOUNT_MISSING"->"近20日成交额不完整，无法核验流动性";
            case "HISTORY_OR_CALCULATION_UNAVAILABLE"->"历史获取或指标计算失败，不能判断规则是否符合";
            case "SCREEN_RAW_HISTORY_FAILED"->"不复权历史获取或流动性核验失败，尚未完成规则判断";
            case "SCREEN_QFQ_HISTORY_FAILED"->"前复权历史准备失败，尚未完成规则判断";
            case "SCREEN_INDICATOR_FAILED"->"指标计算失败，尚未完成规则判断";
            case "SCREEN_CONTEXT_FAILED"->"策略上下文准备失败，尚未完成规则判断";
            case "SCREEN_PULLBACK_FAILED"->"缩量回踩观察计算失败，尚未完成后续规则判断";
            case "SCREEN_RULES_FAILED"->"规则评估或结果保存失败，不能补造选股结论";
            case "RULE_NOT_MATCHED"->"日线买入观察条件未全部满足；不代表缩量回踩也不满足";
            case "BUY_WATCH"->"日线买入观察条件已满足，是否进入前5名另看排序";
            default->"数据校验或处理状态："+code+"；不能据此补造未评估指标";
        };
    }
}
