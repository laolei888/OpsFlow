ALTER TABLE users
    MODIFY COLUMN password_hash VARCHAR(100) NULL,
    ADD COLUMN email VARCHAR(254) NULL AFTER display_name,
    ADD COLUMN auth_source VARCHAR(16) NOT NULL DEFAULT 'LOCAL' AFTER email,
    ADD COLUMN last_login_at DATETIME NULL AFTER token_version,
    ADD INDEX idx_users_tenant_email (tenant_id, email),
    ADD INDEX idx_users_tenant_auth_source (tenant_id, auth_source);

CREATE TABLE oidc_provider_mappings (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    registration_id VARCHAR(64) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    auto_provision BOOLEAN NOT NULL DEFAULT TRUE,
    default_role VARCHAR(32) NOT NULL DEFAULT 'REQUESTER',
    allowed_email_domains VARCHAR(1000) NULL,
    required_group VARCHAR(191) NULL,
    username_claim VARCHAR(64) NOT NULL DEFAULT 'preferred_username',
    display_name_claim VARCHAR(64) NOT NULL DEFAULT 'name',
    email_claim VARCHAR(64) NOT NULL DEFAULT 'email',
    groups_claim VARCHAR(64) NOT NULL DEFAULT 'groups',
    created_by BIGINT NOT NULL,
    updated_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_oidc_provider_registration (registration_id),
    INDEX idx_oidc_provider_tenant (tenant_id, enabled, updated_at)
);

CREATE TABLE user_external_identities (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    provider_id BIGINT NOT NULL,
    issuer VARCHAR(500) NULL,
    subject VARCHAR(191) NOT NULL,
    email VARCHAR(254) NULL,
    linked_at DATETIME NOT NULL,
    last_login_at DATETIME NOT NULL,
    UNIQUE KEY uk_external_identity_subject (provider_id, subject),
    UNIQUE KEY uk_external_identity_user_provider (user_id, provider_id),
    INDEX idx_external_identity_tenant_user (tenant_id, user_id),
    CONSTRAINT fk_external_identity_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_external_identity_provider FOREIGN KEY (provider_id) REFERENCES oidc_provider_mappings(id)
);

CREATE TABLE oidc_login_exchanges (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code_hash CHAR(64) NOT NULL,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    expires_at DATETIME NOT NULL,
    consumed_at DATETIME NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_oidc_exchange_hash (code_hash),
    INDEX idx_oidc_exchange_expiry (expires_at, consumed_at),
    CONSTRAINT fk_oidc_exchange_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE permissions (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    resource_name VARCHAR(64) NOT NULL,
    action_name VARCHAR(32) NOT NULL,
    description VARCHAR(300) NOT NULL,
    UNIQUE KEY uk_permission_code (code)
);

CREATE TABLE role_permissions (
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES roles(id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permissions(id)
);

CREATE TABLE user_permission_overrides (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    effect VARCHAR(8) NOT NULL,
    reason VARCHAR(300) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_user_permission_override (user_id, permission_id),
    INDEX idx_permission_override_tenant_user (tenant_id, user_id),
    CONSTRAINT fk_permission_override_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT fk_permission_override_permission FOREIGN KEY (permission_id) REFERENCES permissions(id)
);

INSERT INTO permissions (code, resource_name, action_name, description) VALUES
    ('USER_MANAGE', 'USER', 'MANAGE', '创建、停用用户并调整角色'),
    ('IDENTITY_PROVIDER_MANAGE', 'IDENTITY_PROVIDER', 'MANAGE', '配置企业身份提供商和账号映射'),
    ('PERMISSION_MANAGE', 'PERMISSION', 'MANAGE', '维护用户级权限覆盖策略'),
    ('TICKET_READ', 'TICKET', 'READ', '查看租户范围内允许访问的工单'),
    ('TICKET_MANAGE', 'TICKET', 'MANAGE', '分派、更新和批量处理工单'),
    ('KNOWLEDGE_MANAGE', 'KNOWLEDGE', 'MANAGE', '创建、发布和归档知识文章'),
    ('OPERATIONS_READ', 'OPERATIONS', 'READ', '查看运营、可靠性和容量数据'),
    ('INTEGRATION_MANAGE', 'INTEGRATION', 'MANAGE', '管理 Webhook、消息和外部集成'),
    ('SECURITY_MANAGE', 'SECURITY', 'MANAGE', '管理安全策略和审计配置');

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p WHERE r.code = 'ADMIN';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p
    ON p.code IN ('TICKET_READ', 'TICKET_MANAGE', 'KNOWLEDGE_MANAGE', 'OPERATIONS_READ')
WHERE r.code = 'AGENT';

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r JOIN permissions p ON p.code = 'TICKET_READ'
WHERE r.code = 'REQUESTER';
