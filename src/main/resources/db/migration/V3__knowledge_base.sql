CREATE TABLE IF NOT EXISTS knowledge_articles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    slug VARCHAR(180) NOT NULL,
    category VARCHAR(64) NOT NULL,
    content LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 0,
    view_count BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_knowledge_tenant_slug (tenant_id, slug),
    INDEX idx_knowledge_tenant_status (tenant_id, status),
    FULLTEXT KEY ft_knowledge_title_content (title, content)
);
