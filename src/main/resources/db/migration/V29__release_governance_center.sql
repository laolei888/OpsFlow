CREATE TABLE IF NOT EXISTS release_plans (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    release_no VARCHAR(32) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description TEXT NULL,
    environment VARCHAR(24) NOT NULL,
    release_manager_id BIGINT NOT NULL,
    planned_start DATETIME NULL,
    planned_end DATETIME NULL,
    status VARCHAR(24) NOT NULL,
    go_no_go_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    decision_comment VARCHAR(500) NULL,
    decided_by BIGINT NULL,
    decided_at DATETIME NULL,
    outcome_note VARCHAR(1000) NULL,
    started_at DATETIME NULL,
    completed_at DATETIME NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_release_plans_tenant_no (tenant_id, release_no),
    INDEX idx_release_plans_tenant_status (tenant_id, status, planned_start),
    INDEX idx_release_plans_manager (tenant_id, release_manager_id, status)
);

CREATE TABLE IF NOT EXISTS release_change_links (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    change_id BIGINT NOT NULL,
    sort_order INT NOT NULL DEFAULT 100,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_release_change_links (tenant_id, release_id, change_id),
    INDEX idx_release_change_links_release (tenant_id, release_id, sort_order, id),
    INDEX idx_release_change_links_change (tenant_id, change_id)
);

CREATE TABLE IF NOT EXISTS release_checklist_items (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    release_id BIGINT NOT NULL,
    phase VARCHAR(24) NOT NULL,
    title VARCHAR(180) NOT NULL,
    required_item BOOLEAN NOT NULL DEFAULT TRUE,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    note VARCHAR(1000) NULL,
    sort_order INT NOT NULL DEFAULT 100,
    verified_by BIGINT NULL,
    verified_at DATETIME NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_release_checklist_release (tenant_id, release_id, phase, sort_order, id),
    INDEX idx_release_checklist_pending (tenant_id, status, required_item)
);
