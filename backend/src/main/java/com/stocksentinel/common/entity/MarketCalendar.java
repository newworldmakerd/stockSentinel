package com.stocksentinel.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database scaffold; JSONB fields contain serialized JSON. */
@TableName("market_calendar")
public class MarketCalendar {
    @TableId(type = IdType.INPUT)
    public LocalDate tradeDate;
    public String exchange;
    public Boolean isOpen;
    public String source;
    public OffsetDateTime updatedAt;
}
