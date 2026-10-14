CREATE TABLE IF NOT EXISTS knowledge_article_versions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    article_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    title VARCHAR(160) NOT NULL,
    slug VARCHAR(180) NOT NULL,
    category VARCHAR(64) NOT NULL,
    content LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    changed_by BIGINT NOT NULL,
    change_type VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_knowledge_article_version (article_id, version_no),
    INDEX idx_knowledge_version_tenant_article (tenant_id, article_id, version_no)
);

INSERT INTO knowledge_article_versions
    (tenant_id, article_id, version_no, title, slug, category, content, status, changed_by, change_type, created_at)
SELECT
    article.tenant_id, article.id, 1, article.title, article.slug, article.category, article.content,
    article.status, article.author_id, 'BACKFILL', article.updated_at
FROM knowledge_articles article
WHERE NOT EXISTS (
    SELECT 1
    FROM knowledge_article_versions version_row
    WHERE version_row.article_id = article.id
);