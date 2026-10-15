# Pixel Agent - Android hardware smoke test

Private-purpose Android AccessibilityService prototype for Pixel 8 Pro.

## Current scope (v0.4 test)
- Opens `https://example.org` in Chrome
- Locally reads the active Chrome accessibility tree (no screenshots, no network requests)
- Shows a system Accessibility overlay requesting explicit approval to perform Back
- Returns to Pixel Agent after approval; decline does nothing

**This build deliberately contains NO OpenAI authentication or autonomous LLM agent.**
This is an isolated hardware permission / interface test before OAuth and model integration.

## Build
GitHub Actions -> Android APK -> latest run -> Artifacts -> `pixel-agent-debug-apk`.
Sideload the APK on your phone and enable Pixel Agent in Settings > Accessibility.
Android may initially block sideloaded accessibility services: Settings > Apps > Pixel Agent > three dots > Allow restricted settings; then retry Accessibility.
Only enable this service if you trust the installed APK and its source. Turn it off when testing finishes.

Android SDK 35, Gradle 8.9, Java 17, Kotlin 2.0.21, AGP 8.7.3.
No tokens or secrets are stored in this repository.
