package com.opsflow.incident;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("incident_asset_links")
public class IncidentAssetLink {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long incidentId;
    private Long assetId;
    private String impactLevel;
    private String note;
    private Long createdBy;
    private LocalDateTime createdAt;
}
