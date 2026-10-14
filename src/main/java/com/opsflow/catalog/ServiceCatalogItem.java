package com.opsflow.catalog;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.opsflow.ticket.TicketPriority;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("service_catalog_items")
public class ServiceCatalogItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String name;
    private String description;
    private String category;
    private TicketPriority defaultPriority;
    private Boolean approvalRequired;
    private Boolean enabled;
    private Integer sortOrder;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
