CREATE TABLE oncall_schedules (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    timezone VARCHAR(64) NOT NULL DEFAULT 'Asia/Shanghai',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_oncall_schedule_tenant_name UNIQUE (tenant_id, name),
    KEY idx_oncall_schedule_tenant_enabled (tenant_id, enabled)
);

CREATE TABLE oncall_shifts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    schedule_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    start_at DATETIME NOT NULL,
    end_at DATETIME NOT NULL,
    is_primary BOOLEAN NOT NULL DEFAULT TRUE,
    handoff_note VARCHAR(1000),
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    KEY idx_oncall_shift_active (tenant_id, schedule_id, is_primary, start_at, end_at),
    KEY idx_oncall_shift_user_time (tenant_id, user_id, start_at, end_at)
);

CREATE TABLE oncall_escalation_policies (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    minimum_severity VARCHAR(16) NOT NULL DEFAULT 'SEV2',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_by BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_oncall_policy_tenant_name UNIQUE (tenant_id, name),
    KEY idx_oncall_policy_tenant_enabled (tenant_id, enabled)
);

CREATE TABLE oncall_escalation_steps (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    policy_id BIGINT NOT NULL,
    step_no INT NOT NULL,
    delay_minutes INT NOT NULL DEFAULT 0,
    target_type VARCHAR(16) NOT NULL,
    schedule_id BIGINT,
    target_user_id BIGINT,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_oncall_policy_step UNIQUE (policy_id, step_no),
    KEY idx_oncall_step_policy (tenant_id, policy_id, step_no)
);

CREATE TABLE oncall_escalation_events (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    incident_id BIGINT NOT NULL,
    policy_id BIGINT NOT NULL,
    incident_no VARCHAR(64) NOT NULL,
    incident_title VARCHAR(160) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    current_step INT NOT NULL DEFAULT 0,
    next_escalate_at DATETIME,
    last_notified_at DATETIME,
    last_error VARCHAR(500),
    resolved_at DATETIME,
    created_at DATETIME NOT NULL,
    updated_at DATETIME NOT NULL,
    CONSTRAINT uk_oncall_event_incident_policy UNIQUE (tenant_id, incident_id, policy_id),
    KEY idx_oncall_event_due (status, next_escalate_at),
    KEY idx_oncall_event_tenant_incident (tenant_id, incident_id, created_at)
);

CREATE TABLE oncall_escalation_deliveries (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    tenant_id BIGINT NOT NULL,
    escalation_event_id BIGINT NOT NULL,
    step_no INT NOT NULL,
    recipient_id BIGINT NOT NULL,
    target_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    detail VARCHAR(500),
    created_at DATETIME NOT NULL,
    CONSTRAINT uk_oncall_delivery_recipient UNIQUE (escalation_event_id, step_no, recipient_id),
    KEY idx_oncall_delivery_event (tenant_id, escalation_event_id, created_at)
);
