ALTER TABLE notification_templates
    ADD COLUMN locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN' AFTER channel,
    DROP INDEX uk_notification_template_route,
    ADD UNIQUE KEY uk_notification_template_route_locale (tenant_id, event_type, channel, locale),
    ADD INDEX idx_notification_template_locale (tenant_id, locale, enabled);

ALTER TABLE notification_template_versions
    ADD COLUMN submitted_by BIGINT NULL AFTER created_by,
    ADD COLUMN submitted_at DATETIME NULL AFTER submitted_by,
    ADD COLUMN reviewed_by BIGINT NULL AFTER submitted_at,
    ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by,
    ADD COLUMN review_comment VARCHAR(500) NULL AFTER reviewed_at;

ALTER TABLE notification_channel_profiles
    ADD COLUMN preferred_locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN' AFTER wecom_webhook_url,
    ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai' AFTER preferred_locale,
    ADD COLUMN delivery_window_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER time_zone,
    ADD COLUMN delivery_window_start TIME NULL AFTER delivery_window_enabled,
    ADD COLUMN delivery_window_end TIME NULL AFTER delivery_window_start,
    ADD COLUMN urgent_bypass BOOLEAN NOT NULL DEFAULT TRUE AFTER delivery_window_end;

ALTER TABLE notification_deliveries
    ADD COLUMN delivery_locale VARCHAR(16) NOT NULL DEFAULT 'zh-CN' AFTER rendered_content,
    ADD COLUMN scheduled_reason VARCHAR(255) NULL AFTER delivery_locale,
    ADD INDEX idx_notification_delivery_schedule (tenant_id, status, next_attempt_at);
