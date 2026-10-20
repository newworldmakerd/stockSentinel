package com.stocksentinel.strategy.engine;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class SwingTrendStrategy {
    public enum SignalType { BUY_WATCH, SELL_WATCH, RISK_ALERT }
    public record Decision(SignalType signalType,boolean matched,List<RuleResult> rules) {}
    public List<Decision> evaluate(StrategyContext context) {
        return List.of(group(SignalType.BUY_WATCH,context,new MacdCrossRule(true),new PriceMaRule(PriceMaRule.Mode.ABOVE_MA20),
                new MaRisingRule(),new VolumeConfirmationRule()),
            group(SignalType.SELL_WATCH,context,new MacdCrossRule(false),new PriceMaRule(PriceMaRule.Mode.BELOW_MA20)),
            group(SignalType.RISK_ALERT,context,new PriceMaRule(PriceMaRule.Mode.CROSS_BELOW_MA60)));
    }
    private Decision group(SignalType type,StrategyContext context,SignalRule... rules) {
        var results=java.util.Arrays.stream(rules).map(rule->rule.evaluate(context)).toList();
        return new Decision(type,results.stream().allMatch(RuleResult::passed),results);
    }
}