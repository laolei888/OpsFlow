CREATE TABLE IF NOT EXISTS dashboard_report_subscriptions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    frequency VARCHAR(16) NOT NULL,
    range_days INT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_run_at DATETIME NOT NULL,
    last_run_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_report_subscriptions_owner (tenant_id, owner_id, created_at),
    INDEX idx_report_subscriptions_due (enabled, next_run_at)
);