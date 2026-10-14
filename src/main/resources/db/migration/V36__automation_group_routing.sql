ALTER TABLE ticket_automation_rules
    ADD COLUMN target_group_id BIGINT NULL AFTER target_assignee_id,
    ADD COLUMN assignment_strategy VARCHAR(24) NULL AFTER target_group_id;

CREATE INDEX idx_ticket_rules_target_group
    ON ticket_automation_rules (tenant_id, target_group_id, enabled);
