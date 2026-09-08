-- business/017_store_followers.sql
-- Enables following store system

CREATE TABLE IF NOT EXISTS business.store_followers (
    store_id UUID NOT NULL REFERENCES business.business_profiles(user_id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (store_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_store_followers_user ON business.store_followers(user_id);
