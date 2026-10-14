CREATE TABLE status_page_components (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    slug VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000),
    asset_id BIGINT,
    status VARCHAR(24) NOT NULL DEFAULT 'OPERATIONAL',
    status_message VARCHAR(300),
    display_order INT NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_status_page_component_slug UNIQUE (tenant_id, slug),
    CONSTRAINT uk_status_page_component_name UNIQUE (tenant_id, name),
    KEY idx_status_page_component_public (tenant_id, enabled, display_order)
);

CREATE TABLE status_page_incidents (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    public_no VARCHAR(32) NOT NULL,
    source_incident_id BIGINT,
    title VARCHAR(160) NOT NULL,
    summary VARCHAR(2000) NOT NULL,
    impact VARCHAR(16) NOT NULL,
    status VARCHAR(24) NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    created_by BIGINT NOT NULL,
    published_by BIGINT,
    published_at DATETIME,
    resolved_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_status_page_incident_no UNIQUE (tenant_id, public_no),
    KEY idx_status_page_incident_feed (tenant_id, published, status, published_at),
    KEY idx_status_page_incident_source (tenant_id, source_incident_id)
);

CREATE TABLE status_page_incident_components (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    status_incident_id BIGINT NOT NULL,
    component_id BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    CONSTRAINT uk_status_page_incident_component UNIQUE (tenant_id, status_incident_id, component_id),
    KEY idx_status_page_component_incidents (tenant_id, component_id, status_incident_id)
);

CREATE TABLE status_page_updates (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    status_incident_id BIGINT NOT NULL,
    status VARCHAR(24) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    KEY idx_status_page_update_timeline (tenant_id, status_incident_id, created_at, id)
);
