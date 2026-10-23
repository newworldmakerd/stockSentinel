package com.stocksentinel.chips;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ChipSettings {
    public record Parameters(BigDecimal concentration90,BigDecimal concentration70,BigDecimal minimumAmount) {
        public Parameters {
            if(concentration90==null||concentration70==null||minimumAmount==null||concentration70.signum()<0||concentration70.compareTo(concentration90)>0||concentration90.compareTo(BigDecimal.ONE)>0||minimumAmount.signum()<=0)throw new IllegalArgumentException("INVALID_CHIP_PARAMETERS");
        }
    }
    public final boolean enabled,schedule;public final Parameters parameters;
    public ChipSettings(@Value("${chips.enabled:false}")boolean enabled,@Value("${chips.schedule-enabled:false}")boolean schedule,
        @Value("${chips.concentration90:0.10}")BigDecimal c90,@Value("${chips.concentration70:0.06}")BigDecimal c70,
        @Value("${chips.minimum-amount:100000000}")BigDecimal amount){this.enabled=enabled;this.schedule=schedule;parameters=new Parameters(c90,c70,amount);}
}
