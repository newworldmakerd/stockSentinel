package com.stocksentinel.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database scaffold; JSONB fields contain serialized JSON. */
@TableName("stock_watchlist")
public class StockWatchlist {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String symbol;
    public Boolean enabled;
    public String remark;
    public OffsetDateTime createdAt;
}
