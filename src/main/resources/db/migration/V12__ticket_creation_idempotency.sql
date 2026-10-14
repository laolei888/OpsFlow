CREATE TABLE IF NOT EXISTS ticket_idempotency_keys (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    ticket_id BIGINT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_ticket_idempotency_scope (tenant_id, requester_id, idempotency_key),
    INDEX idx_ticket_idempotency_ticket (tenant_id, ticket_id)
);
