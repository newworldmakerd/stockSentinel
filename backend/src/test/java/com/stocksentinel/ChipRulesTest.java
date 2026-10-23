package com.stocksentinel;
import com.stocksentinel.chips.*;import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.*;import java.math.BigDecimal;import java.util.*;import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ChipRulesTest {
    static final ObjectMapper JSON=new ObjectMapper().findAndRegisterModules();
    static final LocalDate DAY=LocalDate.of(2026,10,20);
    static ObjectNode snapshot(String symbol,LocalDate day,OffsetDateTime now){
        return JSON.valueToTree(Map.ofEntries(Map.entry("symbol",symbol),Map.entry("name","四川美丰"),Map.entry("as_of",day.toString()),Map.entry("source","CHIP_EASTMONEY"),Map.entry("version","akshare-cyq-210-v1"),Map.entry("adjustment","NONE"),Map.entry("history_size",210),Map.entry("algorithm_hash","a".repeat(64)),Map.entry("window_hash","b".repeat(64)),Map.entry("average_cost","10"),Map.entry("close","10.2"),Map.entry("volume","10000"),Map.entry("profit_ratio","0.55"),Map.entry("low90","9"),Map.entry("high90","11"),Map.entry("low70","9.4"),Map.entry("high70","10.6"),Map.entry("concentration90","0.10"),Map.entry("concentration70","0.06"),Map.entry("average20_amount","100000000"),Map.entry("observed_at",now.toString())));
    }
    final ChipSettings.Parameters params=new ChipSettings.Parameters(new BigDecimal(".10"),new BigDecimal(".06"),new BigDecimal("100000000"));
    ObjectNode m(){return snapshot("000731",DAY,OffsetDateTime.parse("2026-10-21T10:21:05+08:00"));}
    @Test void inclusiveThresholdsAndBothConcentrationsAreRequired(){var value=m();assertEquals("MATCHED",ChipRules.evaluate(value,"000731",DAY,params));value.put("concentration90","0.100001");assertEquals("RULE_NOT_MATCHED",ChipRules.evaluate(value,"000731",DAY,params));value.put("concentration90","0.09").put("concentration70","0.060001");assertEquals("RULE_NOT_MATCHED",ChipRules.evaluate(value,"000731",DAY,params));}
    @Test void exclusionsRemainDistinctFromTechnicalNoMatch(){var value=m().put("average20_amount","99999999.99");assertEquals("LOW_LIQUIDITY",ChipRules.evaluate(value,"000731",DAY,params));value=m().put("volume","0");assertEquals("NO_TRADES",ChipRules.evaluate(value,"000731",DAY,params));value=m().put("name","*ST test");assertEquals("ST",ChipRules.evaluate(value,"000731",DAY,params));}
    @Test void identityBasisAdjustmentAndAlgorithmMustBeValid(){for(String key:List.of("symbol","as_of","source","version","adjustment","algorithm_hash","window_hash")){var value=m().put(key,"invalid");assertThrows(IllegalArgumentException.class,()->ChipRules.evaluate(value,"000731",DAY,params));}}
    @Test void malformedMetricsNeverBecomeCandidates(){for(String key:List.of("close","average_cost","low90","low70","concentration90","profit_ratio","volume","average20_amount")){var value=m().put(key,"NaN");assertThrows(IllegalArgumentException.class,()->ChipRules.evaluate(value,"000731",DAY,params));}var bad=m().put("low70","12");assertThrows(IllegalArgumentException.class,()->ChipRules.evaluate(bad,"000731",DAY,params));var shortHistory=m().put("history_size",119);assertThrows(IllegalArgumentException.class,()->ChipRules.evaluate(shortHistory,"000731",DAY,params));}
    @Test void invalidParameterRelationshipsAreRejected(){assertThrows(IllegalArgumentException.class,()->new ChipSettings.Parameters(new BigDecimal(".05"),new BigDecimal(".06"),BigDecimal.ONE));assertThrows(IllegalArgumentException.class,()->new ChipSettings.Parameters(BigDecimal.ONE,BigDecimal.ZERO,BigDecimal.ZERO));}
}
