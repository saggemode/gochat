import psycopg2

auth_dsn = 'postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/auth-gochat-db?sslmode=require'
conn = psycopg2.connect(auth_dsn)
cur = conn.cursor()
cur.execute("SELECT id, phone, country_code, display_name FROM users ORDER BY created_at DESC LIMIT 10;")
print("Auth users:")
for r in cur.fetchall():
    print(" ", r)
cur.close()
conn.close()

biz_dsn = 'postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/business-gochat-db?sslmode=require'
conn = psycopg2.connect(biz_dsn)
cur = conn.cursor()
cur.execute("SELECT id, phone, country_code, currency, display_name FROM core.users ORDER BY created_at DESC LIMIT 10;")
print("Biz users:")
for r in cur.fetchall():
    print(" ", r)
cur.close()
conn.close()
