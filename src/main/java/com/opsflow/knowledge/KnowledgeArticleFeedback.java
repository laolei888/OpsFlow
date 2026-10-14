package com.opsflow.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_article_feedback")
public class KnowledgeArticleFeedback {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long articleId;
    private Long userId;
    private Boolean helpful;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}