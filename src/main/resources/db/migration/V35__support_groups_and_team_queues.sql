CREATE TABLE support_groups (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    group_code VARCHAR(32) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NULL,
    manager_id BIGINT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_support_group_code (tenant_id, group_code),
    UNIQUE KEY uk_support_group_name (tenant_id, name),
    INDEX idx_support_group_enabled (tenant_id, enabled)
);

CREATE TABLE support_group_members (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    member_role VARCHAR(16) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    joined_at DATETIME NOT NULL,
    added_by BIGINT NOT NULL,
    UNIQUE KEY uk_support_group_member (tenant_id, group_id, user_id),
    INDEX idx_support_group_member_user (tenant_id, user_id, active),
    INDEX idx_support_group_member_group (tenant_id, group_id, active)
);

ALTER TABLE tickets
    ADD COLUMN assignment_group_id BIGINT NULL AFTER assignee_id;

CREATE INDEX idx_tickets_group_status
    ON tickets (tenant_id, assignment_group_id, status);
