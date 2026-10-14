package com.opsflow.cmdb;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("cmdb_assets")
public class CmdbAsset {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String assetNo;
    private String name;
    private AssetType assetType;
    private AssetEnvironment environment;
    private AssetStatus status;
    private AssetCriticality criticality;
    private Long ownerId;
    private String businessSystem;
    private String location;
    private String description;
    private String metadataJson;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
