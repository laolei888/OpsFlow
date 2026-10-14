CREATE TABLE monitoring_alert_sources (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    source_key VARCHAR(48) NOT NULL,
    api_key_hash VARCHAR(100) NOT NULL,
    default_asset_id BIGINT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    last_received_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_monitoring_source_key UNIQUE (source_key),
    CONSTRAINT uk_monitoring_source_tenant_name UNIQUE (tenant_id, name),
    KEY idx_monitoring_source_tenant_enabled (tenant_id, enabled)
);

CREATE TABLE monitoring_alert_rules (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    source_id BIGINT,
    minimum_severity VARCHAR(16) NOT NULL DEFAULT 'HIGH',
    action VARCHAR(32) NOT NULL,
    schedule_id BIGINT,
    sort_order INT NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_monitoring_rule_tenant_name UNIQUE (tenant_id, name),
    KEY idx_monitoring_rule_match (tenant_id, enabled, sort_order, minimum_severity)
);

CREATE TABLE monitoring_alert_silences (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    source_id BIGINT,
    asset_id BIGINT,
    fingerprint_prefix VARCHAR(128),
    starts_at DATETIME NOT NULL,
    ends_at DATETIME NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_monitoring_silence_tenant_name UNIQUE (tenant_id, name),
    KEY idx_monitoring_silence_active (tenant_id, enabled, starts_at, ends_at)
);

CREATE TABLE monitoring_alerts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    alert_no VARCHAR(32) NOT NULL,
    tenant_id BIGINT NOT NULL,
    source_id BIGINT NOT NULL,
    rule_id BIGINT,
    fingerprint VARCHAR(128) NOT NULL,
    external_alert_id VARCHAR(128),
    title VARCHAR(160) NOT NULL,
    description TEXT,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(20) NOT NULL,
    asset_id BIGINT,
    labels_json TEXT,
    occurrence_count INT NOT NULL DEFAULT 1,
    generation INT NOT NULL DEFAULT 1,
    ticket_id BIGINT,
    incident_id BIGINT,
    acknowledged_by BIGINT,
    acknowledged_at DATETIME,
    resolved_by BIGINT,
    resolved_at DATETIME,
    first_seen_at DATETIME NOT NULL,
    last_seen_at DATETIME NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_monitoring_alert_no UNIQUE (tenant_id, alert_no),
    CONSTRAINT uk_monitoring_alert_fingerprint UNIQUE (tenant_id, source_id, fingerprint),
    KEY idx_monitoring_alert_queue (tenant_id, status, severity, last_seen_at),
    KEY idx_monitoring_alert_asset (tenant_id, asset_id, last_seen_at)
);

CREATE TABLE monitoring_alert_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    alert_id BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    payload_json TEXT,
    actor_id BIGINT,
    created_at DATETIME NOT NULL,
    KEY idx_monitoring_alert_event_timeline (tenant_id, alert_id, created_at, id)
);
