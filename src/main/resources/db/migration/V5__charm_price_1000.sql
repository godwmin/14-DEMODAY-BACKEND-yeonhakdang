-- Existing orders retain their purchase-time amount; only new orders use the new price.
UPDATE product SET price=1000 WHERE code='charm';
