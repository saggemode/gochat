import psycopg2

biz_dsn = 'postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/business-gochat-db?sslmode=require'
conn = psycopg2.connect(biz_dsn)
cur = conn.cursor()
cur.execute("SELECT id, phone, country_code, currency FROM core.users WHERE id = '63b0c524-0373-49dc-b66b-18125ef00d70';")
print('User:', cur.fetchall())
cur.close()
conn.close()
