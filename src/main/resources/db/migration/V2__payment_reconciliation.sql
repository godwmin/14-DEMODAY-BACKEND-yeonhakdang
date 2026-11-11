ALTER TABLE purchase_order ADD COLUMN sync_revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE purchase_order ADD COLUMN next_sync_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX order_sync_due_idx ON purchase_order(status,next_sync_at,created_at);
CREATE INDEX entitlement_pending_idx ON entitlement(status,order_id);
