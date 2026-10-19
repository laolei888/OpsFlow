ALTER TABLE ticket_attachments
    ADD COLUMN scan_status VARCHAR(16) NOT NULL DEFAULT 'PENDING' AFTER storage_path,
    ADD COLUMN scan_engine VARCHAR(64) NULL AFTER scan_status,
    ADD COLUMN scan_result VARCHAR(1000) NULL AFTER scan_engine,
    ADD COLUMN sha256 CHAR(64) NULL AFTER scan_result,
    ADD COLUMN scan_attempts INT NOT NULL DEFAULT 0 AFTER sha256,
    ADD COLUMN next_scan_at DATETIME NULL AFTER scan_attempts,
    ADD COLUMN scan_claim_token VARCHAR(64) NULL AFTER next_scan_at,
    ADD COLUMN scan_claimed_at DATETIME NULL AFTER scan_claim_token,
    ADD COLUMN scanned_at DATETIME NULL AFTER scan_claimed_at,
    ADD COLUMN quarantined_at DATETIME NULL AFTER scanned_at,
    ADD COLUMN released_by BIGINT NULL AFTER quarantined_at,
    ADD COLUMN released_at DATETIME NULL AFTER released_by,
    ADD COLUMN release_reason VARCHAR(500) NULL AFTER released_at,
    ADD COLUMN updated_at DATETIME NULL AFTER created_at,
    ADD INDEX idx_attachment_scan_due (scan_status, next_scan_at, id),
    ADD INDEX idx_attachment_tenant_scan (tenant_id, scan_status, updated_at),
    ADD INDEX idx_attachment_sha256 (tenant_id, sha256);

UPDATE ticket_attachments
SET scan_status = 'CLEAN',
    scan_engine = 'LEGACY_TRUSTED',
    scan_result = '升级前已存在的可信附件',
    scanned_at = created_at,
    updated_at = created_at
WHERE updated_at IS NULL;

ALTER TABLE ticket_attachments
    MODIFY COLUMN updated_at DATETIME NOT NULL;
