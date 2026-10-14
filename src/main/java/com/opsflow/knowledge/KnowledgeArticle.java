package com.opsflow.knowledge;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("knowledge_articles")
public class KnowledgeArticle {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long tenantId;
    private Long authorId;
    private String title;
    private String slug;
    private String category;
    private String content;
    private KnowledgeStatus status;
    @Version
    private Integer version;
    private Long viewCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
