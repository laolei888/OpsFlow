CREATE TABLE IF NOT EXISTS cmdb_assets (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    asset_no VARCHAR(40) NOT NULL,
    name VARCHAR(120) NOT NULL,
    asset_type VARCHAR(32) NOT NULL,
    environment VARCHAR(32) NOT NULL DEFAULT 'PRODUCTION',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    criticality VARCHAR(16) NOT NULL DEFAULT 'MEDIUM',
    owner_id BIGINT NULL,
    business_system VARCHAR(120) NULL,
    location VARCHAR(120) NULL,
    description VARCHAR(1000) NULL,
    metadata_json TEXT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    UNIQUE KEY uk_cmdb_assets_tenant_no (tenant_id, asset_no),
    INDEX idx_cmdb_assets_tenant_type_status (tenant_id, asset_type, status),
    INDEX idx_cmdb_assets_tenant_criticality (tenant_id, criticality),
    INDEX idx_cmdb_assets_owner (tenant_id, owner_id)
);

CREATE TABLE IF NOT EXISTS cmdb_asset_relations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    source_asset_id BIGINT NOT NULL,
    target_asset_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL,
    description VARCHAR(500) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    UNIQUE KEY uk_cmdb_relation (tenant_id, source_asset_id, target_asset_id, relation_type),
    INDEX idx_cmdb_relation_source (tenant_id, source_asset_id),
    INDEX idx_cmdb_relation_target (tenant_id, target_asset_id)
);

CREATE TABLE IF NOT EXISTS ticket_asset_links (
    tenant_id BIGINT NOT NULL,
    ticket_id BIGINT NOT NULL,
    asset_id BIGINT NOT NULL,
    link_type VARCHAR(32) NOT NULL DEFAULT 'AFFECTED',
    note VARCHAR(500) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (tenant_id, ticket_id, asset_id),
    INDEX idx_ticket_asset_links_ticket (tenant_id, ticket_id),
    INDEX idx_ticket_asset_links_asset (tenant_id, asset_id)
);
