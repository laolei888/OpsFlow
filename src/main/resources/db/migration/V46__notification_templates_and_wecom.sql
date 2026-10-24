ALTER TABLE notification_channel_profiles
    ADD COLUMN wecom_webhook_url VARCHAR(1000) NULL AFTER webhook_secret;

ALTER TABLE notification_event_preferences
    ADD COLUMN wecom_enabled BOOLEAN NOT NULL DEFAULT FALSE AFTER webhook_enabled;

ALTER TABLE notification_deliveries
    ADD COLUMN template_version_id BIGINT NULL AFTER delivery_secret,
    ADD COLUMN rendered_title VARCHAR(300) NULL AFTER template_version_id,
    ADD COLUMN rendered_content TEXT NULL AFTER rendered_title,
    ADD INDEX idx_notification_delivery_template_version (template_version_id);

CREATE TABLE notification_templates (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NULL,
    event_type VARCHAR(64) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    active_version_id BIGINT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_notification_template_route (tenant_id, event_type, channel),
    INDEX idx_notification_template_tenant (tenant_id, updated_at),
    INDEX idx_notification_template_active_version (active_version_id)
);

CREATE TABLE notification_template_versions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    template_id BIGINT NOT NULL,
    version_no INT NOT NULL,
    status VARCHAR(16) NOT NULL,
    title_template VARCHAR(300) NOT NULL,
    content_template TEXT NOT NULL,
    change_note VARCHAR(500) NULL,
    created_by BIGINT NOT NULL,
    published_by BIGINT NULL,
    published_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_notification_template_version (template_id, version_no),
    INDEX idx_notification_template_version_tenant (tenant_id, template_id, created_at),
    INDEX idx_notification_template_version_status (tenant_id, status, updated_at)
);
