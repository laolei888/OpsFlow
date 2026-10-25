CREATE TABLE IF NOT EXISTS attachment_storage_policies (
    tenant_id BIGINT PRIMARY KEY,
    quota_bytes BIGINT NOT NULL,
    warning_percent INT NOT NULL DEFAULT 80,
    last_reconciled_at DATETIME NULL,
    updated_by BIGINT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL
);

CREATE TABLE IF NOT EXISTS attachment_storage_findings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    finding_fingerprint CHAR(64) NOT NULL,
    tenant_id BIGINT NOT NULL,
    storage_backend VARCHAR(16) NOT NULL,
    finding_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    version_id VARCHAR(255) NULL,
    size_bytes BIGINT NOT NULL DEFAULT 0,
    object_last_modified_at DATETIME NULL,
    latest_version TINYINT(1) NOT NULL DEFAULT 1,
    eligible_delete_at DATETIME NULL,
    detected_at DATETIME NOT NULL,
    last_seen_at DATETIME NOT NULL,
    resolved_at DATETIME NULL,
    handled_by BIGINT NULL,
    handling_reason VARCHAR(500) NULL,
    failure_reason VARCHAR(1000) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_attachment_storage_finding (finding_fingerprint),
    INDEX idx_attachment_storage_finding_queue (tenant_id, status, finding_type, last_seen_at),
    INDEX idx_attachment_storage_finding_object (tenant_id, storage_backend, object_key(191))
);
