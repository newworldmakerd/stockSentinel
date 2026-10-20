package com.stocksentinel.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database scaffold; JSONB fields contain serialized JSON. */
@TableName("market_daily_bar")
public class MarketDailyBar {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String symbol;
    public LocalDate tradeDate;
    public BigDecimal openPrice;
    public BigDecimal highPrice;
    public BigDecimal lowPrice;
    public BigDecimal closePrice;
    public BigDecimal volume;
    public BigDecimal amount;
    public BigDecimal turnoverRate;
    public String adjustment;
    public String source;
    public OffsetDateTime fetchedAt;
}
