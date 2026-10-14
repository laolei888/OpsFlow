CREATE TABLE IF NOT EXISTS webhook_endpoints (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    url VARCHAR(500) NOT NULL,
    secret VARCHAR(200) NOT NULL,
    event_types VARCHAR(2000) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_webhook_endpoints_tenant_name (tenant_id, name),
    INDEX idx_webhook_endpoints_tenant_enabled (tenant_id, enabled)
);

CREATE TABLE IF NOT EXISTS webhook_deliveries (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    endpoint_id BIGINT NOT NULL,
    event_id CHAR(64) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    resource_type VARCHAR(80) NULL,
    resource_id BIGINT NULL,
    payload_json LONGTEXT NOT NULL,
    signature CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL,
    response_status INT NULL,
    last_error VARCHAR(1000) NULL,
    delivered_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_webhook_delivery_endpoint_event (endpoint_id, event_id),
    INDEX idx_webhook_deliveries_tenant_status (tenant_id, status, next_attempt_at),
    INDEX idx_webhook_deliveries_endpoint_created (endpoint_id, created_at)
);
