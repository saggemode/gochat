-- business/016_review_enhancements.sql
-- Adds image support and helpful upvotes to reviews

ALTER TABLE business.reviews
    ADD COLUMN IF NOT EXISTS image_urls TEXT[] DEFAULT '{}',
    ADD COLUMN IF NOT EXISTS helpful_count INT DEFAULT 0;

-- Optional: track which users found a review helpful to prevent double voting
CREATE TABLE IF NOT EXISTS business.review_helpful_votes (
    review_id UUID NOT NULL REFERENCES business.reviews(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES core.users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    PRIMARY KEY (review_id, user_id)
);
