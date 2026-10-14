CREATE TABLE IF NOT EXISTS knowledge_article_feedback (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    article_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    helpful BOOLEAN NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_knowledge_feedback_user (tenant_id, article_id, user_id),
    INDEX idx_knowledge_feedback_article (tenant_id, article_id, helpful)
);