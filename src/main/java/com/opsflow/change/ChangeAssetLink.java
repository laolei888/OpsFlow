package com.opsflow.change;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("change_asset_links")
public class ChangeAssetLink {
    @TableId
    private Long changeId;
    private Long tenantId;
    private Long assetId;
    private String linkType;
    private String note;
    private Long createdBy;
    private LocalDateTime createdAt;
}
