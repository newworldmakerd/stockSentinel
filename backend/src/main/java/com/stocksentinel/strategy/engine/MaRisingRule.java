package com.stocksentinel.strategy.engine;

public class MaRisingRule implements SignalRule {
    public String code() { return "MA20_RISING"; }
    public RuleResult evaluate(StrategyContext context) {
        return new RuleResult(context.current().ma20.compareTo(context.previous().ma20)>0,code(),
            context.current().ma20.toPlainString(),context.previous().ma20.toPlainString(),"MA20严格高于前一条完成日线的MA20");
    }
}