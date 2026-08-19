-- ============================================================================
-- auth/002_add_pin.sql — Add BBM-style PIN to users table
-- ============================================================================
SET search_path TO core, auth, public;

-- Add PIN column to core.users
ALTER TABLE core.users ADD COLUMN IF NOT EXISTS pin TEXT;

-- Generate unique PINs for existing users
DO $$
DECLARE
    user_record RECORD;
    new_pin TEXT;
    max_attempts INT := 100;
    attempt INT;
BEGIN
    FOR user_record IN SELECT id FROM core.users WHERE pin IS NULL OR pin = '' LOOP
        attempt := 0;
        LOOP
            attempt := attempt + 1;
            IF attempt > max_attempts THEN
                RAISE EXCEPTION 'Failed to generate unique PIN for user % after % attempts', user_record.id, max_attempts;
            END IF;
            
            -- Generate random 8-character alphanumeric PIN (A-Z, 0-9)
            new_pin := upper(substring(encode(gen_random_bytes(4), 'hex'), 1, 8));
            
            -- Check if PIN is unique
            IF NOT EXISTS (SELECT 1 FROM core.users WHERE pin = new_pin) THEN
                UPDATE core.users SET pin = new_pin WHERE id = user_record.id;
                EXIT;
            END IF;
        END LOOP;
    END LOOP;
END $$;

-- Add unique constraint on PIN
ALTER TABLE core.users ADD CONSTRAINT core_users_pin_unique UNIQUE (pin);

-- Create index for快速 PIN lookups
CREATE INDEX IF NOT EXISTS idx_core_users_pin ON core.users(pin);
