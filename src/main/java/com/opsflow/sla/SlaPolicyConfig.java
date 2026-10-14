package com.opsflow.sla;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("sla_policies")
public class SlaPolicyConfig {
    @TableId
    private Long tenantId;
    private Integer urgentHours;
    private Integer highHours;
    private Integer mediumHours;
    private Integer lowHours;
    private Long updatedBy;
    private LocalDateTime updatedAt;
}
