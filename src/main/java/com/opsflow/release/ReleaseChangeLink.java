package com.opsflow.release;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("release_change_links")
public class ReleaseChangeLink {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long releaseId;
    private Long changeId;
    private Integer sortOrder;
    private Long createdBy;
    private LocalDateTime createdAt;
}
