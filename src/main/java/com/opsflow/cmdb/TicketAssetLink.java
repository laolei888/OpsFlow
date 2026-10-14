package com.opsflow.cmdb;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ticket_asset_links")
public class TicketAssetLink {
    @TableId
    private Long ticketId;
    private Long tenantId;
    private Long assetId;
    private String linkType;
    private String note;
    private Long createdBy;
    private LocalDateTime createdAt;
}
