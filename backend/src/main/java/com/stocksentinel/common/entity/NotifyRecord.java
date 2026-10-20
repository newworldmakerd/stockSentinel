package com.stocksentinel.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database scaffold; JSONB fields contain serialized JSON. */
@TableName("notify_record")
public class NotifyRecord {
    @TableId(type = IdType.AUTO)
    public Long id;
    public Long signalId;
    public String channel;
    public String messageType;
    public String idempotencyKey;
    public String status;
    public Integer attempts;
    public OffsetDateTime nextRetryAt;
    public String lastError;
    public OffsetDateTime sentAt;
    public OffsetDateTime createdAt;
}
