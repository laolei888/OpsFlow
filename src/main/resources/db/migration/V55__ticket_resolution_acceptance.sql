ALTER TABLE tickets
    ADD COLUMN resolution_code VARCHAR(32) NULL AFTER attempted_actions,
    ADD COLUMN resolution_summary TEXT NULL AFTER resolution_code,
    ADD COLUMN verification_result VARCHAR(2000) NULL AFTER resolution_summary,
    ADD COLUMN resolution_submitted_at DATETIME NULL AFTER verification_result,
    ADD COLUMN confirmed_at DATETIME NULL AFTER resolution_submitted_at;
