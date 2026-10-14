CREATE TABLE IF NOT EXISTS tickets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    ticket_no VARCHAR(32) NOT NULL UNIQUE,
    tenant_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    assignee_id BIGINT NULL,
    title VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    category VARCHAR(64) NULL,
    priority VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version INT NOT NULL DEFAULT 0,
    due_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_tickets_tenant_status (tenant_id, status),
    INDEX idx_tickets_assignee_status (assignee_id, status)
);
