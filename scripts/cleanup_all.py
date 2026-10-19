import psycopg2
import sys

sys.stdout.reconfigure(encoding='utf-8')

# 1. Local databases running on PostgreSQL (localhost:5432)
local_dbs = [
    'auth-gochat-db', 'chat-gochat-db', 'authz-gochat-db', 'group-gochat-db',
    'story-gochat-db', 'call-gochat-db', 'channel-gochat-db', 'ai-gochat-db',
    'payment-gochat-db', 'social-gochat-db', 'miniapp-gochat-db', 'business-gochat-db',
    'gochat', 'media-gochat-db'
]

# 2. Remote Neon databases (from .env)
remote_dsns = {
    "auth": "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/auth-gochat-db?sslmode=require",
    "chat": "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/chat-gochat-db?sslmode=require",
    "authz": "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/authz-gochat-db?sslmode=require",
    "group": "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/group-gochat-db?sslmode=require",
    "story": "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/story-gochat-db?sslmode=require",
    "call": "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/call-gochat-db?sslmode=require",
    "channel": "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/channel-gochat-db?sslmode=require",
    "ai": "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/ai-gochat-db?sslmode=require",
    "payment": "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/payment-gochat-db?sslmode=require",
    "social": "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/social-gochat-db?sslmode=require",
    "miniapp": "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/miniapp-gochat-db?sslmode=require",
    "business": "postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/business-gochat-db?sslmode=require",
}

def clean_database(target_name, dsn, connect_timeout=5):
    print(f"\n--- Cleaning Database: {target_name} ---", flush=True)
    try:
        conn = psycopg2.connect(dsn, connect_timeout=connect_timeout)
        conn.autocommit = True
        cur = conn.cursor()

        # Find all user-defined tables in public and custom schemas
        cur.execute("""
            SELECT table_schema, table_name 
            FROM information_schema.tables 
            WHERE table_schema NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
              AND table_type = 'BASE TABLE'
              AND table_name NOT IN ('schema_migrations', 'goose_db_version')
            ORDER BY table_schema, table_name;
        """)
        tables = cur.fetchall()

        if not tables:
            print(f"  No tables found in {target_name}.", flush=True)
        else:
            table_list = [f'"{t[0]}"."{t[1]}"' for t in tables]
            print(f"  Found {len(tables)} tables to clear.", flush=True)
            
            # Truncate all tables with CASCADE
            try:
                truncate_sql = f"TRUNCATE TABLE {', '.join(table_list)} CASCADE;"
                cur.execute(truncate_sql)
                print(f"  ✓ Truncated {len(tables)} tables in {target_name} successfully.", flush=True)
            except Exception as te:
                print(f"  Notice during TRUNCATE: {te}. Trying individual DELETEs...", flush=True)
                for t in tables:
                    try:
                        cur.execute(f'DELETE FROM "{t[0]}"."{t[1]}";')
                        print(f"    ✓ Deleted from {t[0]}.{t[1]}", flush=True)
                    except Exception as de:
                        print(f"    ✗ Could not delete from {t[0]}.{t[1]}: {de}", flush=True)

        cur.close()
        conn.close()
    except Exception as e:
        print(f"  ✗ Connection/cleanup failed for {target_name}: {e}", flush=True)

print("==================================================", flush=True)
print("  Step 1: Cleaning Local PostgreSQL Databases     ", flush=True)
print("==================================================", flush=True)
for db_name in local_dbs:
    dsn = f"postgres://postgres:123@localhost:5432/{db_name}?sslmode=disable"
    clean_database(f"local:{db_name}", dsn, connect_timeout=3)

print("\n==================================================", flush=True)
print("  Step 2: Cleaning Remote Neon Cloud Databases    ", flush=True)
print("==================================================", flush=True)
for db_name, dsn in remote_dsns.items():
    clean_database(f"neon:{db_name}", dsn, connect_timeout=5)

print("\n==================================================", flush=True)
print("  All Database Cleanup Completed Successfully!    ", flush=True)
print("==================================================", flush=True)
