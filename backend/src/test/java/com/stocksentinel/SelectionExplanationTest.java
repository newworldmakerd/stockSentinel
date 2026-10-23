package com.stocksentinel;

import com.fasterxml.jackson.databind.*;
import com.stocksentinel.report.*;
import com.stocksentinel.pullback.*;
import com.stocksentinel.market.*;
import com.stocksentinel.stock.*;
import com.stocksentinel.strategy.*;
import com.stocksentinel.jobs.*;
import com.stocksentinel.common.entity.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SelectionExplanationTest {
    final SelectionExplanationMapper mapper=mock(SelectionExplanationMapper.class);
    final ReportMapper reports=mock(ReportMapper.class);final WatchlistMapper stocks=mock(WatchlistMapper.class);
    final StrategyContextReader contexts=mock(StrategyContextReader.class);final MarketMapper market=mock(MarketMapper.class);
    final TradingDatePolicy dates=mock(TradingDatePolicy.class);final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    final LocalDate today=LocalDate.of(2026,10,21),asOf=LocalDate.of(2026,10,20);
    SelectionExplanationService service;SelectionExplanationMapper.Item item;
    @BeforeEach void setup()throws Exception {
        service=new SelectionExplanationService(mapper,reports,stocks,contexts,market,PullbackRulesTest.parameters(),json,dates);
        when(dates.today()).thenReturn(today);var stock=new StockInfo();stock.symbol="000498";stock.name="山东路桥";when(stocks.stock("000498")).thenReturn(stock);
        var run=new ReportMapper.Run();run.asOf=asOf;run.status="COMPLETED";when(reports.run(today)).thenReturn(run);when(reports.previousOpen(today)).thenReturn(asOf);
        item=new SelectionExplanationMapper.Item();item.status="NO_MATCH";item.code="RULE_NOT_MATCHED";
        var params=new StrategyParameters(new java.math.BigDecimal("1.2"),MarketContracts.Adjustment.QFQ,"ma-macd-vol-v1","CROSS_BELOW_MA60","DEAD_CROSS_BELOW_MA20");
        item.details=json.writeValueAsString(Map.of("rules",List.of(Map.of("passed",false,"ruleCode","MACD_GOLDEN_CROSS","observedValue","false","thresholdValue","true")),"context",Map.of("parameters",params),"strategyVersion","1.0.0"));
        when(mapper.item(today,"000498")).thenReturn(item);
    }
    JsonNode explain(){return json.valueToTree(service.explain("000498",today));}
    @Test void stageFailuresExplainUnknownOutcomeWithoutInvokingMarket(){
        item.status="FAILED";
        for(String stage:List.of("RAW_HISTORY","QFQ_HISTORY","INDICATOR","CONTEXT","PULLBACK","RULES")){
            item.code="SCREEN_"+stage+"_FAILED";var result=explain();assertEquals("FAILED",result.path("status").asText());assertFalse(result.has("swing"));assertTrue(result.path("explanation").asText().contains("失败"));
        }
        verifyNoInteractions(contexts,market);
    }
    @Test void notStartedPendingExcludedAndFailedNeverInventRuleResults(){
        when(reports.run(today)).thenReturn(null);assertEquals("NOT_STARTED",explain().path("status").asText());
        var run=new ReportMapper.Run();run.asOf=asOf;run.status="RUNNING";when(reports.run(today)).thenReturn(run);
        for(String status:List.of("PENDING","EXCLUDED","FAILED")){
            item.status=status;item.code=status.equals("EXCLUDED")?"LOW_LIQUIDITY":null;
            assertEquals(status,explain().path("status").asText());assertFalse(explain().has("pullback"));
        }
        verifyNoInteractions(contexts,market);
    }
    @Test void savedPullbackCanMatchWhenSwingDoesNotAndReportsRanking()throws Exception {
        var candidate=new PullbackMapper.Candidate();candidate.details=json.writeValueAsString(Map.of("rules",List.of(Map.of("passed",true,"ruleCode","PREVIOUS_RISE","observedValue","32.53%","thresholdValue","15%")),"parameters",PullbackRulesTest.parameters()));
        candidate.referenceHigh=new java.math.BigDecimal("6.51");when(mapper.candidate(anyString(),any(),anyString())).thenReturn(candidate);when(mapper.rank(anyString(),any(),anyString())).thenReturn(6L);
        var result=explain();assertFalse(result.at("/swing/matched").asBoolean());assertTrue(result.at("/pullback/matched").asBoolean());
        assertEquals("32.53%",result.at("/pullback/rules/0/observedValue").asText());assertFalse(result.at("/pullback/inTopFive").asBoolean());assertTrue(result.at("/pullback/minuteMonitoringEligible").asBoolean());
        verifyNoInteractions(contexts,market);
    }
    void localBars(int days){
        var bars=PullbackRulesTest.bars(days);int size=bars.size();for(int i=0;i<size;i++)bars.get(i).tradeDate=asOf.minusDays(size-1-i);
        when(market.daily(eq("000498"),any(),eq(asOf),eq(MarketContracts.Adjustment.QFQ))).thenReturn(bars);
        when(contexts.read(eq("000498"),eq(asOf),any())).thenReturn(new StrategyContextReader.Prepared(PullbackRulesTest.context(bars),null));
    }
    @Test void oneDayDeclineShowsShortCircuitWithoutInventingOtherConditions(){
        localBars(1);var result=explain().path("pullback");assertEquals("RULE_NOT_MATCHED",result.path("status").asText());
        assertEquals("1",result.at("/rules/0/observedValue").asText());assertEquals(1,result.path("rules").size());assertEquals("NOT_EVALUATED_BY_ORIGINAL_RULE",result.path("remainingRulesStatus").asText());
    }
    @Test void recomputationReusesActualRuleEngineAndDoesNotClaimSavedEntry(){
        localBars(2);var result=explain().path("pullback");assertTrue(result.path("matched").asBoolean());assertEquals(6,result.path("rules").size());
        assertEquals("MATCHED_ON_RECALCULATION_NOT_SAVED",result.path("status").asText());
        verify(mapper,never()).rank(anyString(),any(),anyString());
    }
    @Test void missingOrStaleDataIsUnknownRatherThanRuleFailure(){
        when(contexts.read(anyString(),any(),any())).thenThrow(new StrategyContextReader.Skipped("STALE_INDICATORS"));
        var result=explain().path("pullback");assertEquals("DATA_UNAVAILABLE",result.path("status").asText());assertEquals("STALE_INDICATORS",result.path("code").asText());assertFalse(result.has("matched"));
    }
    @Test void corruptSavedSnapshotDoesNotFabricateAnExplanation(){item.details="{}";assertEquals("SNAPSHOT_UNAVAILABLE",explain().path("diagnosticStatus").asText());verifyNoInteractions(contexts,market);}
    @Test void rejectsFutureDateAndUnknownStock(){assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.explain("000498",today.plusDays(1)));assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.explain("000001",today));}
}
