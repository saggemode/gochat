-- business/018_drop_user_fk_constraints.sql
-- Drop foreign key constraints on tables in the business schema that reference core.users
-- In our multi-database microservice setup, users are stored in auth-gochat-db,
-- making cross-database FK references to core.users fail on inserts (e.g., reviews, helpful votes, followers).

DO $$
DECLARE
    _rec RECORD;
BEGIN
    FOR _rec IN
        SELECT con.conname, rel.relname
          FROM pg_constraint con
          JOIN pg_class     rel ON rel.oid = con.conrelid
          JOIN pg_namespace nsp ON nsp.oid = rel.relnamespace
          JOIN pg_class     confrel ON confrel.oid = con.confrelid
          JOIN pg_namespace confnsp ON confnsp.oid = confrel.relnamespace
         WHERE nsp.nspname = 'business'
           AND confnsp.nspname = 'core'
           AND confrel.relname = 'users'
           AND con.contype = 'f'
    LOOP
        EXECUTE format('ALTER TABLE business.%I DROP CONSTRAINT IF EXISTS %I', _rec.relname, _rec.conname);
    END LOOP;
END $$;
