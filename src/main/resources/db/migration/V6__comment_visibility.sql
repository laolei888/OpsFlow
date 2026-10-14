ALTER TABLE ticket_comments
    ADD COLUMN visibility VARCHAR(16) NOT NULL DEFAULT 'PUBLIC' AFTER content;

CREATE INDEX idx_ticket_comments_visibility
    ON ticket_comments (tenant_id, ticket_id, visibility, created_at);