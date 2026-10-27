ALTER TABLE knowledge_articles
    ADD COLUMN review_requested_by BIGINT NULL AFTER source_ticket_no,
    ADD COLUMN review_requested_at DATETIME NULL AFTER review_requested_by,
    ADD COLUMN reviewed_by BIGINT NULL AFTER review_requested_at,
    ADD COLUMN reviewed_at DATETIME NULL AFTER reviewed_by,
    ADD COLUMN review_comment VARCHAR(1000) NULL AFTER reviewed_at,
    ADD COLUMN sensitive_data_checked TINYINT(1) NOT NULL DEFAULT 0 AFTER review_comment,
    ADD COLUMN scope_defined TINYINT(1) NOT NULL DEFAULT 0 AFTER sensitive_data_checked,
    ADD COLUMN verification_steps_complete TINYINT(1) NOT NULL DEFAULT 0 AFTER scope_defined,
    ADD COLUMN review_due_at DATETIME NULL AFTER verification_steps_complete,
    ADD INDEX idx_knowledge_review_queue (tenant_id, status, review_requested_at),
    ADD INDEX idx_knowledge_review_due (tenant_id, status, review_due_at);
