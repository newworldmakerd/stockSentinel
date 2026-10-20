package com.stocksentinel.strategy.engine;

public class PriceMaRule implements SignalRule {
    public enum Mode { ABOVE_MA20, BELOW_MA20, CROSS_BELOW_MA60 }
    private final Mode mode;
    public PriceMaRule(Mode mode) { this.mode=mode; }
    public String code() { return switch(mode) {
        case ABOVE_MA20 -> "CLOSE_ABOVE_MA20";
        case BELOW_MA20 -> "CLOSE_BELOW_MA20";
        case CROSS_BELOW_MA60 -> "CROSS_BELOW_MA60";
    }; }
    public RuleResult evaluate(StrategyContext context) {
        var current=context.currentBar().closePrice;
        if (mode==Mode.CROSS_BELOW_MA60) {
            var previous=context.previousBar().closePrice.subtract(context.previous().ma60);
            var spread=current.subtract(context.current().ma60);
            return new RuleResult(previous.signum()>=0 && spread.signum()<0,code(),
                "previous="+previous.toPlainString()+";current="+spread.toPlainString(),"previous>=0,current<0","收盘价从MA60上方或相等位置跌到下方");
        }
        var average=context.current().ma20;
        boolean passed=mode==Mode.ABOVE_MA20 ? current.compareTo(average)>0 : current.compareTo(average)<0;
        return new RuleResult(passed,code(),current.toPlainString(),average.toPlainString(),
            mode==Mode.ABOVE_MA20 ? "收盘价严格高于MA20" : "收盘价严格低于MA20");
    }
}