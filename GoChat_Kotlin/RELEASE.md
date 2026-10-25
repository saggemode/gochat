# Releasing GoChat to Google Play (AAB)

Google Play requires an **Android App Bundle (.aab)**, not an APK. Google then generates
per-device downloads (so each user only gets their screen density + CPU ABI — much smaller than
the ~68 MB all-in-one debug APK).

## 1. Create your upload keystore (once)
Run this once and keep the `.jks` file + passwords safe (if you lose them you can't update the app):

```bash
keytool -genkeypair -v -keystore upload-keystore.jks -keyalg RSA -keysize 2048 \
  -validity 10000 -alias upload
```
Put `upload-keystore.jks` somewhere outside the repo (or in the project root — it's git-ignored).

## 2. Configure signing (git-ignored)
Copy the template and fill in your values:

```bash
cp GoChat_Kotlin/keystore.properties.example GoChat_Kotlin/keystore.properties
```
`keystore.properties` (never committed):
```
storeFile=../upload-keystore.jks
storePassword=••••••
keyAlias=upload
keyPassword=••••••
```

## 3. Bump the version for each release
In `app/build.gradle.kts` `defaultConfig`:
- `versionCode` must increase every upload (1 → 2 → 3 …).
- `versionName` is the human label ("1.0", "1.1", …).

## 4. Build the signed bundle
```bash
cd GoChat_Kotlin
./gradlew :app:bundleRelease
```
Output: `app/build/outputs/bundle/release/app-release.aab`

## 5. Upload
Play Console → your app → **Production** (or Internal testing first) → upload the `.aab`.
Enable **Play App Signing** when prompted (recommended) — Google manages the final signing key;
your keystore here is just the *upload* key.

## Notes
- Without `keystore.properties`, `bundleRelease` still builds but produces an **unsigned** bundle
  (fine for inspection, not for Play upload). Debug builds are unaffected.
- The debug APK (`:app:assembleDebug`) is still what you side-load for testing.
