-- Add country_code column to core.users for IP-based country detection (WhatsApp/Telegram style).
ALTER TABLE core.users ADD COLUMN IF NOT EXISTS country_code TEXT NOT NULL DEFAULT '';
