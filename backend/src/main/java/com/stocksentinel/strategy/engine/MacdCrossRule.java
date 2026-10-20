package com.stocksentinel.strategy.engine;

public class MacdCrossRule implements SignalRule {
    private final boolean golden;
    public MacdCrossRule(boolean golden) { this.golden=golden; }
    public String code() { return golden ? "MACD_GOLDEN_CROSS" : "MACD_DEAD_CROSS"; }
    public RuleResult evaluate(StrategyContext context) {
        var previous=context.previous().dif.subtract(context.previous().dea);
        var current=context.current().dif.subtract(context.current().dea);
        boolean passed=golden ? previous.signum()<=0 && current.signum()>0 : previous.signum()>=0 && current.signum()<0;
        return new RuleResult(passed,code(),"previous="+previous.toPlainString()+";current="+current.toPlainString(),
            golden ? "previous<=0,current>0" : "previous>=0,current<0",golden ? "DIF由不高于DEA转为高于DEA" : "DIF由不低于DEA转为低于DEA");
    }
}