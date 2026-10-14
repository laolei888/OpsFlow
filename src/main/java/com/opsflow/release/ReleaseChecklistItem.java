package com.opsflow.release;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("release_checklist_items")
public class ReleaseChecklistItem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long releaseId;
    private ReleaseChecklistPhase phase;
    private String title;
    private Boolean requiredItem;
    private ReleaseChecklistStatus status;
    private String note;
    private Integer sortOrder;
    private Long verifiedBy;
    private LocalDateTime verifiedAt;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
