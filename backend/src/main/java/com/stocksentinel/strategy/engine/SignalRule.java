package com.stocksentinel.strategy.engine;

public interface SignalRule {
    String code();
    RuleResult evaluate(StrategyContext context);
}