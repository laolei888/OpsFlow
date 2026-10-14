CREATE TABLE IF NOT EXISTS problems (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    problem_no VARCHAR(32) NOT NULL,
    requester_id BIGINT NOT NULL,
    owner_id BIGINT NULL,
    title VARCHAR(160) NOT NULL,
    description TEXT NOT NULL,
    business_impact VARCHAR(1000) NULL,
    priority VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    root_cause TEXT NULL,
    workaround TEXT NULL,
    known_error BOOLEAN NOT NULL DEFAULT FALSE,
    resolution TEXT NULL,
    request_key VARCHAR(128) NULL,
    request_hash CHAR(64) NULL,
    resolved_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_problems_tenant_no (tenant_id, problem_no),
    UNIQUE KEY uk_problems_idempotency (tenant_id, requester_id, request_key),
    INDEX idx_problems_tenant_status (tenant_id, status, created_at),
    INDEX idx_problems_tenant_priority (tenant_id, priority, status),
    INDEX idx_problems_owner (tenant_id, owner_id, status)
);

CREATE TABLE IF NOT EXISTS problem_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    assignee_id BIGINT NULL,
    sort_order INT NOT NULL DEFAULT 100,
    status VARCHAR(32) NOT NULL DEFAULT 'TODO',
    completed_at DATETIME NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_problem_tasks_problem_order (tenant_id, problem_id, sort_order, id),
    INDEX idx_problem_tasks_assignee (tenant_id, assignee_id, status)
);

CREATE TABLE IF NOT EXISTS problem_ticket_links (
    tenant_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    ticket_id BIGINT NOT NULL,
    link_type VARCHAR(32) NOT NULL DEFAULT 'SYMPTOM',
    note VARCHAR(500) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (tenant_id, problem_id, ticket_id),
    INDEX idx_problem_ticket_links_problem (tenant_id, problem_id),
    INDEX idx_problem_ticket_links_ticket (tenant_id, ticket_id)
);

CREATE TABLE IF NOT EXISTS problem_change_links (
    tenant_id BIGINT NOT NULL,
    problem_id BIGINT NOT NULL,
    change_id BIGINT NOT NULL,
    link_type VARCHAR(32) NOT NULL DEFAULT 'REMEDIATION',
    note VARCHAR(500) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (tenant_id, problem_id, change_id),
    INDEX idx_problem_change_links_problem (tenant_id, problem_id),
    INDEX idx_problem_change_links_change (tenant_id, change_id)
);
