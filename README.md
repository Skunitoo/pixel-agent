# Pixel Agent v1.2 - floating Android AI agent

Personal, sideloaded AI controller for Pixel 8 Pro. Native Kotlin and Android AccessibilityService. OpenAI Sign in with ChatGPT OAuth; no separate server needed.

## Usage
1. Install APK from GitHub Actions, enable Pixel Agent in Android Accessibility settings, and sign in to ChatGPT once from the app.
2. Open any eligible installed application, such as Telegram, Chrome, Maps, YouTube or ChatGPT.
3. Touch the floating **✦** bubble and describe the entire task. **There is no app chooser.**
4. The agent starts in the app that was visible when the overlay was opened, and can change to other installed apps with `OPEN_APP`. Its package inventory is discovered from Android PackageManager.
5. During execution a red **STOP AI** button is always visible. The task ends at DONE, on an error, or after 30 actions.

## Fixed versus v1.1
- The prior build repeatedly stopped on "send not confirmed" after Telegram visibly sent the text, which blocked further navigation.
- v1.2 treats sending to the identified **Folk bot chat** as a single attempt. It **never retries a possibly sent message**.
- If the task requires ChatGPT after messaging Folk, the runtime launches installed `com.openai.chatgpt` and resumes planning rather than treating Telegram's composer state as a permanent blocker.
- Sending a message to a different person or chat still requires confirmation.
- Both the floating overlay and main setup screen no longer ask users to select an app. The foreground app at invocation determines the starting point.

## Safety and privacy
- Text accessible on the phone's active application may be transmitted to OpenAI for inference. Do not run on confidential screens.
- Payment, banking, broker, wallet, password manager, authenticator, health and some government applications are heuristically excluded. Heuristics cannot provide a security guarantee.
- Autopilot may still propose high-impact actions; human approval or blocking remains for actions considered sensitive. Only allow personal automation on information you are authorized to access.
- Android's `QUERY_ALL_PACKAGES` is used to discover installed apps. This version is intended solely for personal sideloading; Play Store distribution would require a separate eligibility and privacy policy review.
- You can stop anytime through the red floating STOP AI button. Android can terminate the app's accessibility service in the background.
- It is not a secure persistent daemon and not compatible with every Android application; protected and custom-drawn interfaces can restrict accessibility nodes.
- Tokens are stored locally using Android Keystore. No credentials belong in GitHub.
- A GitHub Actions debug signing key may differ between builds, which can require uninstalling the previous version and logging in again.

## What is verified
- v0.4 Chrome Accessibility gesture verified on a real Pixel 8 Pro.
- v0.5 ChatGPT OAuth and model reply reported working by user.
- v0.6 basic multi-step scroll completed on user device.
- v1.0-v1.1 are compiled and v1.1 user demonstrated a Telegram send and task stopping on send verification.
- v1.2 must successfully compile in GitHub Actions; this alone does **not** prove end-to-end behavior on Android. Avoid claiming runtime hardware verification before actually observed.

## Build
GitHub Actions -> latest green "Build Android debug APK" -> artifact `pixel-agent-debug-apk` -> unzip `app-debug.apk`. SDK 35, Kotlin 2.0.21, Gradle 8.9, AGP 8.7.3, Java 17.
