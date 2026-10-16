ALTER TABLE async_message_outbox
    ADD COLUMN notification_type VARCHAR(32) NULL AFTER event_type,
    ADD COLUMN title VARCHAR(160) NULL AFTER notification_type,
    ADD INDEX idx_async_message_tenant_type (tenant_id, notification_type, created_at);
