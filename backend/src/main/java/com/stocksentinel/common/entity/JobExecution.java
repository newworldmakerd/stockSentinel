package com.stocksentinel.common.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** Database scaffold; JSONB fields contain serialized JSON. */
@TableName("job_execution")
public class JobExecution {
    @TableId(type = IdType.AUTO)
    public Long id;
    public String jobName;
    public String status;
    public OffsetDateTime startedAt;
    public OffsetDateTime finishedAt;
    public Integer processedCount;
    public Integer successCount;
    public Integer failedCount;
    public String errorMessage;
}
