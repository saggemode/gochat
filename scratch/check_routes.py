import re
from collections import defaultdict

with open('services/gateway/main.go', 'r', encoding='utf-8') as f:
    lines = f.readlines()

routes = []
for i, line in enumerate(lines):
    m = re.search(r'([a-zA-Z0-9_]+)\.(GET|POST|PUT|DELETE|PATCH)\("([^"]+)"', line)
    if m:
        grp, method, path = m.group(1), m.group(2), m.group(3)
        routes.append((i+1, grp, method, path, line.strip()))

print(f"Total routes found: {len(routes)}")

# Note: api and authRequired share the same base prefix (/api/v1)
# marketplace under api has /api/v1/marketplace
# channels under api has /api/v1/channels
# miniapps under api has /api/v1/miniapps
# r has prefix /

full_paths = []
for line_no, grp, method, path, full in routes:
    if grp == 'r':
        base = ""
    elif grp in ('api', 'authRequired'):
        base = "/api/v1"
    elif grp == 'marketplace':
        base = "/api/v1/marketplace"
    elif grp == 'channels':
        base = "/api/v1/channels"
    elif grp == 'miniapps':
        base = "/api/v1/miniapps"
    else:
        base = f"UNKNOWN({grp})"
    
    full_path = base + path
    full_paths.append((line_no, grp, method, full_path, full))

grouped = defaultdict(list)
for line_no, grp, method, fp, full in full_paths:
    grouped[(method, fp)].append((line_no, grp, full))

duplicates = {k: v for k, v in grouped.items() if len(v) > 1}
if duplicates:
    print("DUPLICATE ROUTES FOUND:")
    for (method, fp), instances in duplicates.items():
        print(f"  {method} {fp}:")
        for line_no, grp, full in instances:
            print(f"    Line {line_no} [{grp}]: {full}")
else:
    print("NO DUPLICATES FOUND!")
