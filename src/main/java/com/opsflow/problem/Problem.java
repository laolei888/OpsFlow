package com.opsflow.problem;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("problems")
public class Problem {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private String problemNo;
    private Long requesterId;
    private Long ownerId;
    private String title;
    private String description;
    private String businessImpact;
    private ProblemPriority priority;
    private ProblemStatus status;
    private String rootCause;
    private String workaround;
    private Boolean knownError;
    private String resolution;
    private String requestKey;
    private String requestHash;
    private LocalDateTime resolvedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
