CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    ticket_id BIGINT NULL,
    type VARCHAR(32) NOT NULL,
    title VARCHAR(160) NOT NULL,
    content VARCHAR(500) NOT NULL,
    dedupe_key VARCHAR(160) NOT NULL,
    read_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_notifications_dedupe (tenant_id, recipient_id, dedupe_key),
    INDEX idx_notifications_recipient_read (recipient_id, read_at, created_at),
    INDEX idx_notifications_tenant_created (tenant_id, created_at)
);