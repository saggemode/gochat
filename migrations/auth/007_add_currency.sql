-- Add currency column to core.users for automatic regional currency assignment
ALTER TABLE core.users ADD COLUMN IF NOT EXISTS currency TEXT NOT NULL DEFAULT '';
