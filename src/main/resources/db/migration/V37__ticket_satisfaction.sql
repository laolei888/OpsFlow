CREATE TABLE ticket_satisfaction_responses (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    ticket_id BIGINT NOT NULL,
    requester_id BIGINT NOT NULL,
    assignee_id BIGINT NULL,
    assignment_group_id BIGINT NULL,
    rating TINYINT NOT NULL,
    tags VARCHAR(500) NULL,
    comment VARCHAR(2000) NULL,
    submitted_at DATETIME NOT NULL,
    CONSTRAINT uk_ticket_satisfaction UNIQUE (tenant_id, ticket_id),
    INDEX idx_satisfaction_tenant_time (tenant_id, submitted_at),
    INDEX idx_satisfaction_assignee_time (tenant_id, assignee_id, submitted_at),
    INDEX idx_satisfaction_group_time (tenant_id, assignment_group_id, submitted_at)
);
