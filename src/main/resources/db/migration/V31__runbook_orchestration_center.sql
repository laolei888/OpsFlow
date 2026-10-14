CREATE TABLE IF NOT EXISTS runbooks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    slug VARCHAR(140) NOT NULL,
    description TEXT NULL,
    category VARCHAR(64) NOT NULL,
    target_type VARCHAR(24) NOT NULL DEFAULT 'GENERAL',
    version_no INT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_runbooks_tenant_slug (tenant_id, slug),
    INDEX idx_runbooks_tenant_enabled (tenant_id, enabled, category)
);

CREATE TABLE IF NOT EXISTS runbook_steps (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    runbook_id BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 100,
    title VARCHAR(180) NOT NULL,
    instruction TEXT NOT NULL,
    verification_hint VARCHAR(1000) NULL,
    required_step BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_runbook_steps_runbook (tenant_id, runbook_id, sort_order, id)
);

CREATE TABLE IF NOT EXISTS runbook_executions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    execution_no VARCHAR(32) NOT NULL,
    runbook_id BIGINT NOT NULL,
    runbook_version INT NOT NULL,
    source_type VARCHAR(24) NOT NULL,
    source_id BIGINT NULL,
    source_ref VARCHAR(64) NULL,
    title VARCHAR(180) NOT NULL,
    status VARCHAR(24) NOT NULL,
    initiated_by BIGINT NOT NULL,
    completed_by BIGINT NULL,
    outcome_note VARCHAR(1000) NULL,
    started_at DATETIME NOT NULL,
    completed_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_runbook_executions_tenant_no (tenant_id, execution_no),
    INDEX idx_runbook_executions_tenant_status (tenant_id, status, started_at),
    INDEX idx_runbook_executions_source (tenant_id, source_type, source_id)
);

CREATE TABLE IF NOT EXISTS runbook_execution_steps (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    execution_id BIGINT NOT NULL,
    runbook_step_id BIGINT NULL,
    sort_order INT NOT NULL DEFAULT 100,
    title VARCHAR(180) NOT NULL,
    instruction TEXT NOT NULL,
    verification_hint VARCHAR(1000) NULL,
    required_step BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    evidence VARCHAR(2000) NULL,
    completed_by BIGINT NULL,
    completed_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_runbook_execution_steps_execution (tenant_id, execution_id, sort_order, id),
    INDEX idx_runbook_execution_steps_pending (tenant_id, status, required_step)
);
