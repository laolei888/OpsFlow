CREATE TABLE IF NOT EXISTS capacity_policies (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    active_tickets_threshold BIGINT NOT NULL,
    overdue_tickets_threshold BIGINT NOT NULL,
    active_incidents_threshold BIGINT NOT NULL,
    firing_alerts_threshold BIGINT NOT NULL,
    async_backlog_threshold BIGINT NOT NULL,
    attachment_backlog_threshold BIGINT NOT NULL,
    attachment_bytes_threshold BIGINT NOT NULL,
    database_bytes_threshold BIGINT NOT NULL,
    warning_percent INT NOT NULL DEFAULT 80,
    watch_days INT NOT NULL DEFAULT 30,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_capacity_policy_tenant (tenant_id)
);

CREATE TABLE IF NOT EXISTS capacity_maintenance_windows (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    starts_at DATETIME NOT NULL,
    ends_at DATETIME NOT NULL,
    reason VARCHAR(1000) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_capacity_maintenance_lookup (tenant_id, enabled, starts_at, ends_at),
    INDEX idx_capacity_maintenance_history (tenant_id, starts_at)
);

ALTER TABLE capacity_alerts
    ADD COLUMN policy_id BIGINT NULL AFTER snapshot_id,
    ADD COLUMN notification_suppressed BOOLEAN NOT NULL DEFAULT FALSE AFTER occurrence_count,
    ADD COLUMN suppressed_notification_count INT NOT NULL DEFAULT 0 AFTER notification_suppressed,
    ADD COLUMN suppressed_by_window_id BIGINT NULL AFTER suppressed_notification_count,
    ADD COLUMN suppressed_reason VARCHAR(500) NULL AFTER suppressed_by_window_id,
    ADD COLUMN last_suppressed_at DATETIME NULL AFTER suppressed_reason,
    ADD INDEX idx_capacity_alert_history (tenant_id, created_at, level, status);
