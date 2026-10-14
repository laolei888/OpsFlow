package com.opsflow.problem;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("problem_change_links")
public class ProblemChangeLink {
    @TableId
    private Long problemId;
    private Long tenantId;
    private Long changeId;
    private String linkType;
    private String note;
    private Long createdBy;
    private LocalDateTime createdAt;
}
