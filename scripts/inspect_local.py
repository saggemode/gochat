import psycopg2

dbs = [
    'auth-gochat-db', 'chat-gochat-db', 'authz-gochat-db', 'group-gochat-db',
    'story-gochat-db', 'call-gochat-db', 'channel-gochat-db', 'ai-gochat-db',
    'payment-gochat-db', 'social-gochat-db', 'miniapp-gochat-db', 'business-gochat-db',
    'gochat', 'media-gochat-db'
]

print("=== Inspecting Local Databases on localhost:5432 ===")
for db in dbs:
    try:
        conn = psycopg2.connect(f"postgres://postgres:123@localhost:5432/{db}?sslmode=disable", connect_timeout=3)
        cur = conn.cursor()
        cur.execute("""
            SELECT table_schema, table_name 
            FROM information_schema.tables 
            WHERE table_schema NOT IN ('information_schema', 'pg_catalog', 'pg_toast')
              AND table_type = 'BASE TABLE'
              AND table_name NOT IN ('schema_migrations', 'goose_db_version');
        """)
        tables = cur.fetchall()
        total_rows = 0
        table_counts = []
        for s, t in tables:
            try:
                cur.execute(f'SELECT count(*) FROM "{s}"."{t}";')
                cnt = cur.fetchone()[0]
                total_rows += cnt
                if cnt > 0:
                    table_counts.append(f"{s}.{t}: {cnt}")
            except Exception:
                pass
        print(f"[{db}] {len(tables)} tables, total rows: {total_rows}. Non-empty: {table_counts}")
        cur.close()
        conn.close()
    except Exception as e:
        print(f"[{db}] Connection error: {e}")
