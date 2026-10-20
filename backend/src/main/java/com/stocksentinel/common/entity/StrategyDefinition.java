package com.stocksentinel.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database scaffold; JSONB fields contain serialized JSON. */
@TableName("strategy_definition")
public class StrategyDefinition {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String code;
    public String name;
    public String version;
    public String parameters;
    public Boolean enabled;
    public OffsetDateTime createdAt;
}
