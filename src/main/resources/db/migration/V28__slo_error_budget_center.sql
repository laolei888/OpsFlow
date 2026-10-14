CREATE TABLE slo_definitions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    status_component_id BIGINT,
    target_percent DECIMAL(6,3) NOT NULL,
    window_days INT NOT NULL DEFAULT 30,
    warning_budget_percent DECIMAL(5,2) NOT NULL DEFAULT 40.00,
    critical_budget_percent DECIMAL(5,2) NOT NULL DEFAULT 10.00,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_slo_definition_tenant_name UNIQUE (tenant_id, name),
    KEY idx_slo_definition_component (tenant_id, status_component_id, enabled)
);

CREATE TABLE slo_measurements (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    definition_id BIGINT NOT NULL,
    source VARCHAR(24) NOT NULL,
    source_alert_id BIGINT,
    total_requests BIGINT NOT NULL,
    good_requests BIGINT NOT NULL,
    availability_percent DECIMAL(6,3) NOT NULL,
    note VARCHAR(1000),
    occurred_at DATETIME NOT NULL,
    recorded_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    KEY idx_slo_measurement_window (tenant_id, definition_id, occurred_at),
    KEY idx_slo_measurement_alert (tenant_id, source_alert_id)
);

CREATE TABLE slo_breach_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    breach_no VARCHAR(32) NOT NULL,
    definition_id BIGINT NOT NULL,
    source_measurement_id BIGINT,
    level VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    availability_percent DECIMAL(6,3) NOT NULL,
    budget_remaining_percent DECIMAL(6,3) NOT NULL,
    title VARCHAR(180) NOT NULL,
    linked_ticket_id BIGINT,
    linked_incident_id BIGINT,
    acknowledged_by BIGINT,
    acknowledged_at DATETIME,
    resolved_by BIGINT,
    resolved_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_slo_breach_no UNIQUE (tenant_id, breach_no),
    KEY idx_slo_breach_queue (tenant_id, status, level, updated_at),
    KEY idx_slo_breach_definition (tenant_id, definition_id, status)
);
