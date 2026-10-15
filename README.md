# Pixel Agent v1.0

Open-source personal Android agent for Pixel 8 Pro. Built with Kotlin and Android AccessibilityService. Runs on Android, uses OpenAI Sign in with ChatGPT OAuth (PKCE), and does not require a cloud server or separate OpenAI API key if authorized account access remains available.

## Features
- Interactive AI control of approved Android apps (Chrome, YouTube, Maps, Gmail, Telegram, Instagram, WhatsApp, Google Messages, Files and Settings - only apps explicitly enabled by user).
- User enters a task. Agent reads visible accessibility nodes from **only enabled apps** and decides next action using OpenAI.
- Actions: TAP, TYPE, ENTER, SCROLL_DOWN, SCROLL_UP, BACK, HOME, OPEN_APP, OPEN_URL, WAIT, DONE.
- Explicit user approval for **every** physical action. For text entry, content is displayed before authorization.
- Revalidates target path, label, class, bounds and current package before clicking or typing.
- Reads new screen after each action. Stops on failed click, unavailable screen, unsupported action or denied approval.
- Up to 25 actions per task; can be stopped from the main screen or by denying an action.
- Stores OAuth credentials locally in Android Keystore encrypted storage; never uploads tokens to GitHub.
- Starts from user-chosen installed app; can switch only to checked app packages. Chrome HTTPS navigation requires consent.

## Privacy, safety and real limitations
- Text from the selected app screen **is sent to OpenAI for inference**. Enable only apps whose on-screen information you want to share with OpenAI; Gmail, messaging and social apps may show private information.
- Password fields and payment/broker/transfer targets are filtered or blocked. No guarantee that all harmful or irreversible operations are detected. Do not enable banking, financial, trading or highly sensitive apps.
- Each action needs user approval. This is *not* an unattended, always-on daemon.
- Some apps (especially games, secure apps and custom-drawn interfaces) do not expose actionable Accessibility trees, and Android can kill sessions in the background.
- Android AccessibilityService can observe and control extensive UI; only use builds and source you trust.
- Success of `performAction` does not always prove the goal completed; the agent re-observes the screen and may stop if a TAP had no observed effect.
- Not security audited. APK uses GitHub Actions debug signing key, which may change each build.
- Targeted only at personal sideloaded use. Store distribution may require additional accessibility policy steps.

## Installation
1. GitHub Actions -> most recent successful **Build Android debug APK** -> artifact `pixel-agent-debug-apk`.
2. Extract `app-debug.apk` and install it on your phone.
3. If the previous build's debug key differs, uninstall old Pixel Agent first (local OAuth state will be lost).
4. Settings -> Apps -> Pixel Agent -> (menu) Allow restricted settings (if prompted).
5. Enable Pixel Agent under Android Accessibility.
6. In the app, use **Continue with ChatGPT** and approve relevant OAuth scopes.
7. Check allowed app boxes, choose starting app, enter a task and press **URUCHOM AGENTA**.
8. Approve or STOP each proposed action on the screen. Stop is also available in the Pixel Agent app.

## Scope
This is an initial v1.0 source and APK release, not a guarantee of compatibility with all Android apps. ChatGPT OAuth model entitlements depend on account permissions and provider availability. No prompt can make blocked apps expose their content.

## Source
All Android sources in `app/src/main/`, the build workflow in `.github/workflows/android.yml`. Build settings: Android SDK 35, Java 17, Kotlin 2.0.21, AGP 8.7.3, Gradle 8.9.
