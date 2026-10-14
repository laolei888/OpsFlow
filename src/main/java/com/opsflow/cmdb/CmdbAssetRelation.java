package com.opsflow.cmdb;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("cmdb_asset_relations")
public class CmdbAssetRelation {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long sourceAssetId;
    private Long targetAssetId;
    private AssetRelationType relationType;
    private String description;
    private Long createdBy;
    private LocalDateTime createdAt;
}
