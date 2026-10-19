import psycopg2
import sys

sys.stdout.reconfigure(encoding='utf-8')

db_dsns = {
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

print("==================================================")
print("  Starting Database Cleanup Across All Services  ")
print("==================================================")

for db_name, dsn in db_dsns.items():
    print(f"\n--- Cleaning Database: {db_name} ---")
    try:
        conn = psycopg2.connect(dsn)
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
            print(f"  No tables found in {db_name}.")
        else:
            table_list = [f'"{t[0]}"."{t[1]}"' for t in tables]
            print(f"  Found {len(tables)} tables: {', '.join([f'{t[0]}.{t[1]}' for t in tables])}")
            
            # Truncate all tables with CASCADE
            try:
                truncate_sql = f"TRUNCATE TABLE {', '.join(table_list)} CASCADE;"
                cur.execute(truncate_sql)
                print(f"  ✓ Truncated {len(tables)} tables in {db_name} successfully.")
            except Exception as te:
                print(f"  Notice during TRUNCATE: {te}. Trying individual DELETEs...")
                for t in tables:
                    try:
                        cur.execute(f'DELETE FROM "{t[0]}"."{t[1]}";')
                        print(f"    ✓ Deleted from {t[0]}.{t[1]}")
                    except Exception as de:
                        print(f"    ✗ Could not delete from {t[0]}.{t[1]}: {de}")

        cur.close()
        conn.close()
    except Exception as e:
        print(f"  ✗ Connection/cleanup failed for {db_name}: {e}")

print("\n==================================================")
print("  All Database Cleanup Completed Successfully!    ")
print("==================================================")
