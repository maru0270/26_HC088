ALTER TABLE hazard_zones
    ADD COLUMN IF NOT EXISTS demo_batch_id VARCHAR(100);

CREATE INDEX IF NOT EXISTS index_hazard_zones_demo_batch
    ON hazard_zones(demo_batch_id)
    WHERE demo_batch_id IS NOT NULL;

COMMENT ON COLUMN hazard_zones.demo_batch_id IS
    'DB-only identifier for removable presentation/demo data; never shown in the rider UI.';
