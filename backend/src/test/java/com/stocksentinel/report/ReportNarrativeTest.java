package com.stocksentinel.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocksentinel.stock.WatchlistMapper;
import com.stocksentinel.common.entity.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReportNarrativeTest {
    final ReportMapper mapper=mock(ReportMapper.class);final WatchlistMapper watches=mock(WatchlistMapper.class);
    final ObjectMapper json=new ObjectMapper();final LocalDate date=LocalDate.of(2026,10,20);
    ReportMapper.Run run;
    StockInfo stock(String symbol,String exchange){var s=new StockInfo();s.symbol=symbol;s.exchange=exchange;s.name=symbol.equals("600487")?"亨通光电":"四川美丰";return s;}
    Map<String,String> quote(String key,String price,String previous){return Map.of("key",key,"symbol",key.substring(2),"name",key,"price",price,"previous_close",previous,"volume","10000","quoted_at","2026-10-20T15:00:00+08:00");}
    @BeforeEach void setup()throws Exception {
        var ht=stock("600487","SH");var mf=stock("000731","SZ");when(mapper.universe()).thenReturn(List.of(ht,mf));when(watches.stock("600487")).thenReturn(ht);when(watches.stock("000731")).thenReturn(mf);
        var w=new StockWatchlist();w.symbol="600487";w.enabled=true;when(watches.list()).thenReturn(List.of(w));
        run=new ReportMapper.Run();run.tradeDate=date;run.asOf=date.minusDays(1);run.total=2;run.status="SOURCE_UNAVAILABLE";
        run.overview=json.writeValueAsString(Map.of("quotes",List.of(quote("sh000001","100.12","100"),quote("sz399001","99.86","100"),quote("sz399006","99.74","100"),quote("sh600487","103.62","100"),quote("sz000731","99.46","100"))));
        when(mapper.run(date)).thenReturn(run);
        when(mapper.coverage(date)).thenReturn(List.of(Map.of("status","FAILED","count",1),Map.of("status","PENDING","count",1)));
        when(mapper.jobs(date)).thenReturn(List.of(Map.of("status","SKIPPED","count",2)));
    }
    @Test void marketAndRelativeStrengthAreDerivedWithoutExposingStatusCodes(){
        String text=new ReportNarrative(mapper,watches,json).render(date);
        assertTrue(text.contains("三大指数表现分化"));assertTrue(text.contains("较沪指强 3.50 个百分点"));
        assertTrue(text.contains("没有有效交易时段扫描记录"));assertTrue(text.contains("待处理 1"));
        assertFalse(text.contains("SOURCE_UNAVAILABLE"));assertFalse(text.contains("SKIPPED"));assertFalse(text.contains("PENDING"));
    }
    @Test void explainsMissingRuleRatherThanGivingUnsupportedBuyAdvice()throws Exception {
        var ctx=Map.of("current",Map.of("ma20",100),"previous",Map.of("ma20",99),"currentBar",Map.of("volume",1500),"previousFiveVolumeSum",5000,"parameters",Map.of("volumeMultiplier",1.2));
        when(mapper.stockDetails(date,"600487")).thenReturn(json.writeValueAsString(Map.of("asOf","2026-10-19","context",ctx,"average20Amount",100000000,"rules",List.of(Map.of("ruleCode","MACD_GOLDEN_CROSS","passed",false)))));
        String text=new ReportNarrative(mapper,watches,json).render(date);
        assertTrue(text.contains("量能 1.50 倍"));assertTrue(text.contains("尚待确认：MACD金叉"));assertTrue(text.contains("20日均成交额 1.00 亿元"));
    }
    @Test void onlyCompleteScreenCanClaimNoMatches(){
        String partial=new ReportNarrative(mapper,watches,json).render(date);assertTrue(partial.contains("尚未齐"));
        run.status="COMPLETED";when(mapper.coverage(date)).thenReturn(List.of(Map.of("status","NO_MATCH","count",2)));
        String complete=new ReportNarrative(mapper,watches,json).render(date);assertTrue(complete.contains("没有股票同时满足4项"));
    }
    @Test void tablesAreContiguousAndByteLimitPreservesProvenance()throws Exception {
        var ctx=Map.of("current",Map.of("ma20",100),"previous",Map.of("ma20",99),"currentBar",Map.of("volume",1500),"previousFiveVolumeSum",5000,"parameters",Map.of("volumeMultiplier",1.2));
        String details=json.writeValueAsString(Map.of("asOf","2026-10-19","context",ctx,"average20Amount",100000000,"rules",List.of(Map.of("passed",true))));
        when(mapper.stockDetails(any(),anyString())).thenReturn(details);
        var candidates=new ArrayList<Map<String,Object>>();
        for(int i=0;i<5;i++)candidates.add(Map.of("symbol","600487","average_amount",100000000,"details",details,"explanation","模型解释内容".repeat(60)));
        when(mapper.candidates(date)).thenReturn(candidates);
        String text=new ReportNarrative(mapper,watches,json).render(date);
        assertTrue(text.contains("📊"));assertTrue(text.contains("| 指数 |"));assertTrue(text.contains("| 股票 |"));
        assertTrue(text.contains("5. 亨通光电 600487"));assertTrue(text.contains("技术依据截至 2026-10-19"));assertTrue(text.contains("不自动关注或下单"));
        assertTrue(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length<=3900);
        String block=text.substring(text.indexOf("| 1. 亨通光电"),text.indexOf("| 5. 亨通光电"));assertFalse(block.contains(">"));
    }
    @Test void missingQuotesNeverInventMarketDirection(){run.overview=null;String text=new ReportNarrative(mapper,watches,json).render(date);assertTrue(text.contains("暂不判断市场方向"));assertFalse(text.contains("较沪指强"));}
}
