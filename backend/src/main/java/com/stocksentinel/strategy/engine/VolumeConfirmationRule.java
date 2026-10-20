package com.stocksentinel.strategy.engine;

import java.math.BigDecimal;

public class VolumeConfirmationRule implements SignalRule {
    public String code() { return "VOLUME_CONFIRMATION"; }
    public RuleResult evaluate(StrategyContext context) {
        var sum=context.previousFiveVolumeSum();
        var volume=context.currentBar().volume;
        var multiplier=context.parameters().volumeMultiplier();
        boolean passed=sum.signum()>0 && volume.multiply(BigDecimal.valueOf(5)).compareTo(sum.multiply(multiplier))>=0;
        return new RuleResult(passed,code(),"current="+volume.toPlainString()+";previous5Sum="+sum.toPlainString(),
            multiplier.toPlainString(),sum.signum()==0 ? "此前5条日线均量为0，量能条件不可确认" : "当日量达到此前5条均量的配置倍数；使用原始量精确比较，不以舍入后的量能比判定");
    }
}