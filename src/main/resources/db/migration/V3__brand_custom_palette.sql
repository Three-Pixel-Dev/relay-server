-- Custom AI-generated brand palette & cover directions on relay_brand
ALTER TABLE relay_brand ADD COLUMN IF NOT EXISTS palette_json JSONB;
ALTER TABLE relay_brand ADD COLUMN IF NOT EXISTS cover_directions_json JSONB;

