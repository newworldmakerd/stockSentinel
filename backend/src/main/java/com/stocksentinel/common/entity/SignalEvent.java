package com.stocksentinel.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database scaffold; JSONB fields contain serialized JSON. */
@TableName("signal_event")
public class SignalEvent {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String symbol;
    public LocalDate tradeDate;
    public String strategyCode;
    public String strategyVersion;
    public String signalType;
    public BigDecimal score;
    public BigDecimal referencePrice;
    public String reason;
    public String riskNote;
    public String details;
    public OffsetDateTime createdAt;
    public OffsetDateTime signalAt;
}
