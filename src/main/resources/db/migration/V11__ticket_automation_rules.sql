CREATE TABLE IF NOT EXISTS ticket_automation_rules (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    enabled TINYINT(1) NOT NULL DEFAULT 1,
    sort_order INT NOT NULL DEFAULT 100,
    match_category VARCHAR(64) NULL,
    match_priority VARCHAR(16) NULL,
    match_keyword VARCHAR(80) NULL,
    target_assignee_id BIGINT NULL,
    target_priority VARCHAR(16) NULL,
    target_status VARCHAR(32) NULL,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_ticket_rules_tenant_enabled_order (tenant_id, enabled, sort_order, id),
    INDEX idx_ticket_rules_assignee (tenant_id, target_assignee_id)
);
