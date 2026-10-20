package com.stocksentinel.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database scaffold; JSONB fields contain serialized JSON. */
@TableName("stock_info")
public class StockInfo {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String symbol;
    public String name;
    public String exchange;
    public String industry;
    public LocalDate listingDate;
    public String status;
    public OffsetDateTime updatedAt;
}
