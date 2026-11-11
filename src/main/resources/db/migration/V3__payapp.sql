-- Provider request IDs and verified notifications only; no phone numbers or API secrets.
CREATE TABLE payapp_payment (
 payment_id VARCHAR(80) PRIMARY KEY REFERENCES purchase_order(payment_id),
 order_id UUID NOT NULL UNIQUE REFERENCES purchase_order(id),
 callback_token VARCHAR(36) NOT NULL,
 mul_no VARCHAR(40) UNIQUE,
 pay_url TEXT,
 request_state VARCHAR(20) NOT NULL,
 status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
 paid_at TIMESTAMP WITH TIME ZONE,
 cancelled INTEGER NOT NULL DEFAULT 0,
 last_event_at TIMESTAMP WITH TIME ZONE
);
CREATE TABLE payapp_cancel_intent (
 id VARCHAR(80) PRIMARY KEY,
 payment_id VARCHAR(80) NOT NULL REFERENCES payapp_payment(payment_id),
 amount INTEGER NOT NULL CHECK(amount > 0),
 state VARCHAR(20) NOT NULL
);
CREATE TABLE payapp_cancellation (
 provider_id VARCHAR(40) PRIMARY KEY,
 payment_id VARCHAR(80) NOT NULL REFERENCES payapp_payment(payment_id),
 cancellation_id VARCHAR(80) NOT NULL UNIQUE,
 amount INTEGER NOT NULL CHECK(amount > 0)
);
