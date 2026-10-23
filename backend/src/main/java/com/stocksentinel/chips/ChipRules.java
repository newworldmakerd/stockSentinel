package com.stocksentinel.chips;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;import java.time.LocalDate;

public final class ChipRules {
    private ChipRules(){}
    public static BigDecimal n(JsonNode m,String key){return new BigDecimal(m.path(key).asText());}
    public static String evaluate(JsonNode m,String symbol,LocalDate date,ChipSettings.Parameters p){
        if(m==null||!symbol.equals(m.path("symbol").asText())||!date.toString().equals(m.path("as_of").asText())||!"CHIP_EASTMONEY".equals(m.path("source").asText())||!"akshare-cyq-210-v1".equals(m.path("version").asText())||!"NONE".equals(m.path("adjustment").asText())||!m.path("algorithm_hash").asText().matches("[a-f0-9]{64}")||!m.path("window_hash").asText().matches("[a-f0-9]{64}")||m.path("name").asText().isBlank())throw new IllegalArgumentException("CHIP_INVALID_SNAPSHOT");
        int history=m.path("history_size").asInt();if(history<120||history>210)throw new IllegalArgumentException("CHIP_INVALID_HISTORY");
        for(String key:new String[]{"close","average_cost","low90","high90","low70","high70"})if(n(m,key).signum()<=0)throw new IllegalArgumentException("CHIP_INVALID_PRICE");
        var c90=n(m,"concentration90");var c70=n(m,"concentration70");var profit=n(m,"profit_ratio");
        if(c70.signum()<0||c70.compareTo(c90)>0||c90.compareTo(BigDecimal.ONE)>0||profit.signum()<0||profit.compareTo(BigDecimal.ONE)>0||n(m,"low90").compareTo(n(m,"low70"))>0||n(m,"low70").compareTo(n(m,"high70"))>0||n(m,"high70").compareTo(n(m,"high90"))>0||n(m,"average_cost").compareTo(n(m,"low90"))<0||n(m,"average_cost").compareTo(n(m,"high90"))>0||n(m,"volume").signum()<0||n(m,"average20_amount").signum()<0)throw new IllegalArgumentException("CHIP_INVALID_METRICS");
        if(m.path("name").asText().toUpperCase(java.util.Locale.ROOT).contains("ST"))return "ST";
        if(n(m,"volume").signum()==0)return "NO_TRADES";
        if(n(m,"average20_amount").compareTo(p.minimumAmount())<0)return "LOW_LIQUIDITY";
        return c90.compareTo(p.concentration90())<=0&&c70.compareTo(p.concentration70())<=0?"MATCHED":"RULE_NOT_MATCHED";
    }
}
