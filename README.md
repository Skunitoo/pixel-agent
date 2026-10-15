# Pixel Agent v1.2.1 - touch and performance hotfix

Private Android agent for Pixel 8 Pro, using AccessibilityService and ChatGPT OAuth. Source and CI are publicly reviewable. This is a sideloaded experimental build, not a production-grade computer-use agent.

## What changed since v1.2
The user reported that v1.2 blocked taps where the status banner appeared, caused major phone UI lag, and did not bring Telegram to the foreground when requested.

- **The full-width floating status strip is removed entirely.** Only the compact ✦ launcher, the focusable composer while being edited, and a small STOP AI control while working can overlay other applications. Final errors are shown using short system toasts.
- **App discovery no longer scans every installed app on the UI thread.** A one-time background thread builds a cached list; model planning reads that fast cached list without PackageManager enumeration.
- **The input composer releases keyboard focus and removes its overlay synchronously** before the agent starts. It does not re-add the launcher while launching a task.
- **Explicit instructions to message Folk/Telegram navigate to Telegram first**, even when ✦ was activated in a different app. Without explicit routing, the agent starts where the user invoked it.
- After opening Telegram or ChatGPT, **the agent verifies the foreground app** before planning further actions. It stops with a visible error if Android blocks the transition.
- A pending Telegram SEND still executes at most once and is **never blindly retried**. If a task requests ChatGPT afterward, the agent attempts to open it and verifies the transition.

## Install and use
1. GitHub Actions -> latest green **Build Android debug APK** -> download `pixel-agent-debug-apk` -> unzip `app-debug.apk`.
2. Sideload APK. If Android rejects it due to a changed debug signature, uninstall older Pixel Agent first and log in again.
3. Enable Pixel Agent under Settings -> Accessibility and log in to ChatGPT once.
4. In an eligible app, press ✦, type a task and press Uruchom.
5. The composer disappears; the agent proceeds. **STOP AI** can interrupt anytime. To disable all overlays, turn off Pixel Agent's Accessibility access in Android settings.

## Privacy and limitations
- The model sees accessibility text from the active application. Never activate on sensitive or private screens without considering what is being sent to OpenAI.
- Heuristics exclude common banking, payment, broker, password and other sensitive apps, but **do not guarantee exclusion of every risky or sensitive app**.
- Android (including recent releases) may restrict programmatic activity launches from a background service. The application now reports a failed transition instead of pretending it succeeded.
- Some Android apps lack accessible buttons and labels, so universal app control is not guaranteed.
- Tokens are stored locally via Android Keystore; they are not included in source.
- CI compilation verifies syntax/buildability; it does not certify touchscreen or cross-app behavior on actual hardware.
- This is not a persistent 24/7 automation daemon.

## Earlier verified milestones
- v0.4 Pixel Chrome gesture completed.
- v0.5 OAuth and model decision reported working.
- v0.6 two-step scrolling reported working.
- v1.1 Folk Telegram message was observed sent; the agent incorrectly stopped on uncertain send verification.
- v1.2 built but caused UI blocking/jank in user testing.
- v1.2.1 is the release targeting those faults; end-to-end Pixel hardware behavior needs a real device to confirm.

## Build tooling
Android SDK 35, Java 17, AGP 8.7.3, Kotlin 2.0.21, Gradle 8.9. GitHub Actions workflow in `.github/workflows/android.yml`.
