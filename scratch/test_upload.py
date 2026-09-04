import requests
import json

# Login as a test user to get token
base_url = "https://gochat-kvpj.onrender.com"
# First check if there's any user in auth DB to login or register a temporary test user
register_res = requests.post(
    f"{base_url}/api/v1/auth/register",
    json={
        "display_name": "Upload Tester",
        "phone": "+19998887776",
        "password": "Password123!"
    },
    timeout=10
)

print("Register status:", register_res.status_code, register_res.text[:200])

if register_res.status_code in (200, 201):
    token = register_res.json().get("token") or register_res.json().get("access_token")
else:
    # Try login
    login_res = requests.post(
        f"{base_url}/api/v1/auth/login",
        json={
            "phone": "+19998887776",
            "password": "Password123!"
        },
        timeout=10
    )
    print("Login status:", login_res.status_code, login_res.text[:200])
    token = login_res.json().get("token") or login_res.json().get("access_token")

print("Got token:", token[:20] if token else None)

if token:
    # Try upload 1x1 jpeg
    headers = {"Authorization": f"Bearer {token}"}
    files = {
        "file": ("test.jpg", b"\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x01\x00`\x00`\x00\x00\xff\xdb\x00C\x00\x08\x06\x06\x07\x06\x05\x08\x07\x07\x07\t\t\x08\n\x0c\x14\r\x0c\x0b\x0b\x0c\x19\x12\x13\x0f\x14\x1d\x1a\x1f\x1e\x1d\x1a\x1c\x1c $.' \",#\x1c\x1c(7),01444\x1f'9=82<.342\xff\xc0\x00\x0b\x08\x00\x01\x00\x01\x01\x01\x11\x00\xff\xc4\x00\x1f\x00\x00\x01\x05\x01\x01\x01\x01\x01\x01\x00\x00\x00\x00\x00\x00\x00\x00\x01\x02\x03\x04\x05\x06\x07\x08\t\n\x0b\xff\xda\x00\x08\x01\x01\x00\x00?\x00\xbf\x00\xff\xd9", "image/jpeg")
    }
    upload_res = requests.post(f"{base_url}/api/v1/media/upload", headers=headers, files=files, timeout=15)
    print("Upload status:", upload_res.status_code, upload_res.text)
