CREATE TABLE IF NOT EXISTS release_freeze_windows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    environment VARCHAR(24) NULL,
    starts_at DATETIME NOT NULL,
    ends_at DATETIME NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_release_freeze_windows_tenant_time (tenant_id, enabled, starts_at, ends_at),
    INDEX idx_release_freeze_windows_environment (tenant_id, environment, starts_at)
);
