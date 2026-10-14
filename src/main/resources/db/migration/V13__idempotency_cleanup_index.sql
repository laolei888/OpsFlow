CREATE INDEX idx_ticket_idempotency_created_at
    ON ticket_idempotency_keys (created_at);
