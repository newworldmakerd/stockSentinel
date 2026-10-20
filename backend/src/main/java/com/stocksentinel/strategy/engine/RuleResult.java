package com.stocksentinel.strategy.engine;

public record RuleResult(boolean passed,String ruleCode,String observedValue,String thresholdValue,String explanation) {}