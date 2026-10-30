-- Migration 027: Nudge log for abandoned-cart / wishlist reminders
-- Tracks the last time each user was nudged for a given kind ("cart", "wishlist")
-- so the periodic reminder job never spams the same person. One row per
-- (user, kind); the job upserts last_sent_at when it sends a reminder.

CREATE TABLE IF NOT EXISTS business.nudge_log (
    user_id      UUID        NOT NULL,
    kind         VARCHAR(20) NOT NULL,
    last_sent_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (user_id, kind)
);
