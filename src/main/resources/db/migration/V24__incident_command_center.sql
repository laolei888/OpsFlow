CREATE TABLE IF NOT EXISTS incidents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    incident_no VARCHAR(32) NOT NULL,
    title VARCHAR(160) NOT NULL,
    summary TEXT NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(32) NOT NULL,
    commander_id BIGINT NULL,
    declared_by BIGINT NOT NULL,
    business_impact VARCHAR(1000) NULL,
    customer_impact VARCHAR(1000) NULL,
    communication_channel VARCHAR(200) NULL,
    postmortem TEXT NULL,
    request_key VARCHAR(128) NULL,
    request_hash CHAR(64) NULL,
    started_at DATETIME NOT NULL,
    resolved_at DATETIME NULL,
    closed_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_incidents_incident_no (incident_no),
    UNIQUE KEY uk_incidents_request (tenant_id, declared_by, request_key),
    INDEX idx_incidents_tenant_status (tenant_id, status, created_at),
    INDEX idx_incidents_tenant_severity (tenant_id, severity, created_at)
);

CREATE TABLE IF NOT EXISTS incident_updates (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    incident_id BIGINT NOT NULL,
    update_type VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_incident_updates_tenant_incident (tenant_id, incident_id, created_at)
);

CREATE TABLE IF NOT EXISTS incident_tasks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    incident_id BIGINT NOT NULL,
    title VARCHAR(160) NOT NULL,
    assignee_id BIGINT NULL,
    sort_order INT NOT NULL DEFAULT 100,
    status VARCHAR(32) NOT NULL,
    completed_at DATETIME NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_incident_tasks_tenant_incident (tenant_id, incident_id, sort_order)
);

CREATE TABLE IF NOT EXISTS incident_ticket_links (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    incident_id BIGINT NOT NULL,
    ticket_id BIGINT NOT NULL,
    link_type VARCHAR(32) NOT NULL,
    note VARCHAR(500) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_incident_ticket_links (tenant_id, incident_id, ticket_id),
    INDEX idx_incident_ticket_links_ticket (tenant_id, ticket_id)
);

CREATE TABLE IF NOT EXISTS incident_asset_links (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    incident_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    impact_level VARCHAR(32) NOT NULL,
    note VARCHAR(500) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_incident_asset_links (tenant_id, incident_id, asset_id),
    INDEX idx_incident_asset_links_asset (tenant_id, asset_id)
);
