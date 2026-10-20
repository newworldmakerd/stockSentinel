package com.stocksentinel.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database scaffold; JSONB fields contain serialized JSON. */
@TableName("market_indicator_daily")
public class MarketIndicatorDaily {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String symbol;
    public LocalDate tradeDate;
    public BigDecimal ma5;
    public BigDecimal ma10;
    public BigDecimal ma20;
    public BigDecimal ma60;
    public BigDecimal dif;
    public BigDecimal dea;
    public BigDecimal macdHist;
    public BigDecimal volumeRatio;
    public String calculationVersion;
    public String adjustment;
    public OffsetDateTime calculatedAt;
}
