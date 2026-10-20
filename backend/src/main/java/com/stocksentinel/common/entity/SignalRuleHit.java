package com.stocksentinel.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database scaffold; JSONB fields contain serialized JSON. */
@TableName("signal_rule_hit")
public class SignalRuleHit {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long signalId;
    public String ruleCode;
    public String observedValue;
    public String thresholdValue;
    public Boolean passed;
    public String explanation;
}
