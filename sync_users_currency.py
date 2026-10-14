import psycopg2

auth_dsn = 'postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/auth-gochat-db?sslmode=require'
conn_auth = psycopg2.connect(auth_dsn)
cur_auth = conn_auth.cursor()
cur_auth.execute("SELECT id, phone, country_code, display_name FROM users;")
auth_users = cur_auth.fetchall()
cur_auth.close()
conn_auth.close()

biz_dsn = 'postgres://neondb_owner:npg_GfPRWH95xneU@ep-dark-forest-a2188hsu-pooler.eu-central-1.aws.neon.tech/business-gochat-db?sslmode=require'
conn_biz = psycopg2.connect(biz_dsn)
cur_biz = conn_biz.cursor()

def derive_curr(phone, country_code):
    p = str(phone or "")
    c = str(country_code or "").upper()
    if p.startswith("+234") or p.startswith("234") or c == "NG":
        return "NGN", "NG"
    if p.startswith("+44") or c == "GB":
        return "GBP", "GB"
    if p.startswith("+254") or c == "KE":
        return "KES", "KE"
    if p.startswith("+233") or c == "GH":
        return "GHS", "GH"
    if p.startswith("+27") or c == "ZA":
        return "ZAR", "ZA"
    if p.startswith("+91") or c == "IN":
        return "INR", "IN"
    if p.startswith("+971") or c == "AE":
        return "AED", "AE"
    if c in ("DE", "FR", "IT", "ES"):
        return "EUR", c
    if c == "CA":
        return "CAD", "CA"
    if c == "AU":
        return "AUD", "AU"
    return "USD", c or "US"

for u in auth_users:
    uid, phone, country_code, name = u
    curr, country = derive_curr(phone, country_code)
    pin = uid.replace("-", "")[:6].upper()
    cur_biz.execute("""
        INSERT INTO core.users (id, phone, country_code, currency, display_name, password_hash, pin)
        VALUES (%s, %s, %s, %s, %s, '', %s)
        ON CONFLICT (id) DO UPDATE SET
            phone = EXCLUDED.phone,
            country_code = EXCLUDED.country_code,
            currency = EXCLUDED.currency,
            display_name = COALESCE(core.users.display_name, EXCLUDED.display_name);
    """, (uid, phone, country, curr, name, pin))

# Also update all products to have currency matching their owner's currency!
cur_biz.execute("""
    UPDATE business.products p
    SET currency = u.currency
    FROM core.users u
    WHERE p.owner_id = u.id AND u.currency IS NOT NULL AND u.currency != '';
""")
print("Products updated with owner currency count:", cur_biz.rowcount)

# Check products now
cur_biz.execute("SELECT id, name, price, currency, owner_id FROM business.products ORDER BY created_at DESC LIMIT 5;")
for r in cur_biz.fetchall():
    print(" Product:", r)

conn_biz.commit()
cur_biz.close()
conn_biz.close()
print("Sync completed successfully!")
