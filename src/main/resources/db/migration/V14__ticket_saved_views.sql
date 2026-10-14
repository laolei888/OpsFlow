CREATE TABLE IF NOT EXISTS ticket_saved_views (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    name VARCHAR(60) NOT NULL,
    visibility VARCHAR(16) NOT NULL DEFAULT 'PRIVATE',
    status VARCHAR(32) NULL,
    priority VARCHAR(16) NULL,
    keyword VARCHAR(120) NULL,
    overdue TINYINT(1) NOT NULL DEFAULT 0,
    mine TINYINT(1) NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_ticket_saved_views_owner_name (tenant_id, owner_id, name),
    INDEX idx_ticket_saved_views_scope (tenant_id, visibility, updated_at),
    INDEX idx_ticket_saved_views_owner (tenant_id, owner_id, updated_at)
);