ALTER TABLE notifications
    ADD COLUMN in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER dedupe_key;

CREATE TABLE notification_channel_profiles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    email_address VARCHAR(254) NULL,
    webhook_url VARCHAR(1000) NULL,
    webhook_secret VARCHAR(200) NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_notification_channel_profile_user (tenant_id, user_id),
    INDEX idx_notification_channel_profile_tenant (tenant_id, updated_at)
);

CREATE TABLE notification_event_preferences (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    webhook_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_notification_event_preference (tenant_id, user_id, event_type),
    INDEX idx_notification_event_preference_user (tenant_id, user_id, updated_at)
);

CREATE TABLE notification_deliveries (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    notification_id BIGINT NOT NULL,
    recipient_id BIGINT NOT NULL,
    notification_type VARCHAR(64) NOT NULL,
    channel VARCHAR(16) NOT NULL,
    destination VARCHAR(1000) NULL,
    delivery_secret VARCHAR(200) NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME NOT NULL,
    response_status INT NULL,
    last_error VARCHAR(1000) NULL,
    delivered_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_notification_delivery_channel (notification_id, channel),
    INDEX idx_notification_delivery_due (status, next_attempt_at, id),
    INDEX idx_notification_delivery_tenant_status (tenant_id, status, updated_at),
    INDEX idx_notification_delivery_recipient (tenant_id, recipient_id, created_at)
);
