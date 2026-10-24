-- Persisted address-book contacts: which GoChat users each person has in their phone contacts.
-- Written on contact sync; used to scope the story feed (see each other's stories).
CREATE TABLE IF NOT EXISTS auth.user_contacts (
    owner_id        UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    contact_user_id UUID        NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (owner_id, contact_user_id)
);

CREATE INDEX IF NOT EXISTS idx_user_contacts_owner ON auth.user_contacts(owner_id);
