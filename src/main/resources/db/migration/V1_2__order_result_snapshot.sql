CREATE TABLE order_result_snapshot (
 order_id UUID PRIMARY KEY REFERENCES purchase_order(id) ON DELETE CASCADE,
 snapshot TEXT NOT NULL,
 charm_variant INTEGER NOT NULL DEFAULT 0 CHECK(charm_variant BETWEEN 0 AND 3),
 created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
