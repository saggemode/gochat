-- Migration: 015_auto_reply_business_hours.sql
-- Purpose: Add business hours scheduling configuration to auto_replies

ALTER TABLE business.auto_replies
    ADD COLUMN IF NOT EXISTS schedule_type VARCHAR(30) DEFAULT 'always',
    ADD COLUMN IF NOT EXISTS timezone VARCHAR(60) DEFAULT 'UTC',
    ADD COLUMN IF NOT EXISTS days_of_week INT[] DEFAULT ARRAY[1,2,3,4,5],
    ADD COLUMN IF NOT EXISTS start_time VARCHAR(10) DEFAULT '09:00',
    ADD COLUMN IF NOT EXISTS end_time VARCHAR(10) DEFAULT '17:00';
