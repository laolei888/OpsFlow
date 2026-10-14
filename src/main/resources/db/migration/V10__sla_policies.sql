CREATE TABLE IF NOT EXISTS sla_policies (
    tenant_id BIGINT PRIMARY KEY,
    urgent_hours INT NOT NULL DEFAULT 4,
    high_hours INT NOT NULL DEFAULT 8,
    medium_hours INT NOT NULL DEFAULT 24,
    low_hours INT NOT NULL DEFAULT 72,
    updated_by BIGINT NULL,
    updated_at DATETIME NOT NULL,
    INDEX idx_sla_policies_updated_by (updated_by, updated_at)
);
