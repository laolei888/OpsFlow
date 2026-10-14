CREATE TABLE IF NOT EXISTS ticket_comments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    ticket_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    content TEXT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_ticket_comments_ticket (tenant_id, ticket_id, created_at),
    CONSTRAINT fk_ticket_comments_ticket FOREIGN KEY (ticket_id) REFERENCES tickets(id)
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    actor_id BIGINT NULL,
    resource_type VARCHAR(32) NOT NULL,
    resource_id BIGINT NOT NULL,
    action VARCHAR(64) NOT NULL,
    before_value JSON NULL,
    after_value JSON NULL,
    detail VARCHAR(255) NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_audit_logs_resource (tenant_id, resource_type, resource_id, created_at),
    INDEX idx_audit_logs_actor (tenant_id, actor_id, created_at)
);