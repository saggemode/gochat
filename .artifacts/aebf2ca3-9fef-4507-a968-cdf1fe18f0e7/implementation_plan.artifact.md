# Fix Android Project Configuration to Build Full GoChat App

Redirect the Android build system from the template root app to the full Kotlin implementation located in the `GoChat_Kotlin` directory.

## Proposed Changes

### Build Configuration

#### [MODIFY] [libs.versions.toml](file:///C:/Users/hi/Desktop/whatsapp_golang/gradle/libs.versions.toml)
Replace the root version catalog with the full version catalog from `GoChat_Kotlin/gradle/libs.versions.toml` to support all necessary dependencies (Firebase, Room, Retrofit, etc.).

#### [MODIFY] [build.gradle.kts](file:///C:/Users/hi/Desktop/whatsapp_golang/build.gradle.kts)
Update the top-level build file to include necessary plugins (KSP, Google Services) required by the full app.

#### [MODIFY] [settings.gradle.kts](file:///C:/Users/hi/Desktop/whatsapp_golang/settings.gradle.kts)
Redirect the `:app` module definition to point to the `GoChat_Kotlin/app` directory.

## Verification Plan

### Automated Tests
- Run `gradlew :app:assembleDebug` to verify the full app compiles.

### Manual Verification
- Deploy the app using the IDE and verify the Chat List screen (the "full code") appears instead of the First/Second Fragment template.
