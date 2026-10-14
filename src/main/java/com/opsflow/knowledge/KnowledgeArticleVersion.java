package com.opsflow.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_article_versions")
public class KnowledgeArticleVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long articleId;
    @TableField("version_no")
    private Integer versionNo;
    private String title;
    private String slug;
    private String category;
    private String content;
    private KnowledgeStatus status;
    @TableField("changed_by")
    private Long changedBy;
    private String changeType;
    private LocalDateTime createdAt;
}