-- business/017_store_followers.sql
-- Enables following store system

CREATE TABLE IF NOT EXISTS business.store_followers (
    store_id UUID NOT NULL REFERENCES business.business_profiles(user_id) ON DELETE CASCADE,
    user_id UUID NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (store_id, user_id)
);

-- Drop ANY foreign-key constraint on the user_id column (regardless of name)
DO $$
DECLARE
    _con TEXT;
BEGIN
    FOR _con IN
        SELECT con.conname
          FROM pg_constraint con
          JOIN pg_class     rel ON rel.oid = con.conrelid
          JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
          JOIN pg_attribute att ON att.attrelid = con.conrelid
                               AND att.attnum = ANY(con.conkey)
         WHERE nsp.nspname   = 'business'
           AND rel.relname   = 'store_followers'
           AND att.attname   = 'user_id'
           AND con.contype   = 'f'
    LOOP
        EXECUTE format('ALTER TABLE business.store_followers DROP CONSTRAINT %I', _con);
    END LOOP;
END $$;

CREATE INDEX IF NOT EXISTS idx_store_followers_user ON business.store_followers(user_id);
