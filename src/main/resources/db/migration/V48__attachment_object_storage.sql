ALTER TABLE ticket_attachments
    ADD COLUMN storage_backend VARCHAR(16) NOT NULL DEFAULT 'LOCAL' AFTER storage_path,
    ADD COLUMN storage_etag VARCHAR(128) NULL AFTER storage_backend,
    ADD COLUMN storage_version VARCHAR(255) NULL AFTER storage_etag,
    ADD INDEX idx_attachment_storage_backend (storage_backend, id);

UPDATE ticket_attachments
SET storage_backend = 'LOCAL'
WHERE storage_backend IS NULL OR storage_backend = '';
