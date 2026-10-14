package com.opsflow.statuspage;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("status_page_components")
public class StatusPageComponent {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String slug;
    private String name;
    private String description;
    private Long assetId;
    private StatusPageComponentStatus status;
    private String statusMessage;
    private Integer displayOrder;
    private Boolean enabled;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
