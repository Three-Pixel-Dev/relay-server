-- Whop integration: per-connection webhook identity and per-product delivery/plan ids.
-- Hibernate ddl-auto=update adds these in dev; run this where schema is managed manually.

ALTER TABLE relay_service_connection ADD COLUMN IF NOT EXISTS external_account_id VARCHAR(100);
ALTER TABLE relay_service_connection ADD COLUMN IF NOT EXISTS webhook_token VARCHAR(64);
ALTER TABLE relay_service_connection ADD COLUMN IF NOT EXISTS webhook_external_id VARCHAR(100);
ALTER TABLE relay_service_connection ADD COLUMN IF NOT EXISTS encrypted_webhook_secret TEXT;
CREATE UNIQUE INDEX IF NOT EXISTS ux_relay_service_connection_webhook_token
    ON relay_service_connection (webhook_token) WHERE webhook_token IS NOT NULL;

ALTER TABLE relay_whop_product ADD COLUMN IF NOT EXISTS external_plan_id VARCHAR(255);
ALTER TABLE relay_whop_product ADD COLUMN IF NOT EXISTS delivery_token VARCHAR(64);
CREATE UNIQUE INDEX IF NOT EXISTS ux_relay_whop_product_delivery_token
    ON relay_whop_product (delivery_token) WHERE delivery_token IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_relay_order_whop_external_id
    ON relay_order (source, external_id) WHERE external_id IS NOT NULL;
