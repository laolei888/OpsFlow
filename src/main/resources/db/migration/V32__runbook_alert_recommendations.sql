CREATE TABLE IF NOT EXISTS runbook_alert_matchers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    runbook_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    alert_source_id BIGINT NULL,
    minimum_severity VARCHAR(24) NULL,
    title_keyword VARCHAR(160) NULL,
    sort_order INT NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_runbook_alert_matchers_runbook (tenant_id, runbook_id, enabled, sort_order),
    INDEX idx_runbook_alert_matchers_source (tenant_id, alert_source_id, minimum_severity)
);
