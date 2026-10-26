ALTER TABLE tickets
    ADD COLUMN impact_scope VARCHAR(1000) NULL AFTER description,
    ADD COLUMN expected_outcome VARCHAR(1000) NULL AFTER impact_scope,
    ADD COLUMN attempted_actions TEXT NULL AFTER expected_outcome;
