ALTER TABLE knowledge_articles
    ADD COLUMN source_ticket_id BIGINT NULL AFTER author_id,
    ADD COLUMN source_ticket_no VARCHAR(32) NULL AFTER source_ticket_id,
    ADD UNIQUE KEY uk_knowledge_tenant_source_ticket (tenant_id, source_ticket_id),
    ADD INDEX idx_knowledge_source_ticket_no (tenant_id, source_ticket_no);
