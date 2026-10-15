# Pixel Agent - Android Accessibility + ChatGPT OAuth

Personal open-source Android prototype for Pixel 8 Pro. The app is local to your Android phone, and GitHub contains **only code**.

## Current release: v0.5 (experimental)
- v0.4 Chrome hardware smoke test: successfully confirmed on a real Pixel 8 Pro.
- OpenAI Sign in with ChatGPT (open-source dynamic client registration, OAuth PKCE S256, loopback callback on `127.0.0.1`).
- OpenAI ID token RSA signature validation against official JWKS, nonce, audience, issuer and expiry checks.
- OAuth scope `chatgpt.tokens.use.direct` required; encrypted token storage via Android Keystore (AES-256-GCM).
- Access-token refresh via rotating refresh token.
- Lists eligible model slugs from `https://api.openai.com/v1/models`.
- Calls `https://api.openai.com/v1/responses` with `store:false`, `stream:true`, and displays text after `response.completed`.
- One AI-guided, human-approved action: open `example.org` in Chrome, inspect a limited accessibility summary, ask the model BACK vs STOP, then ask for confirmation before executing BACK.
- No native OpenAI Computer Use, no bank or broker integrations, no background autonomy.

## Install
From GitHub **Actions > Build Android debug APK > latest successful run > Artifacts**, download `pixel-agent-debug-apk`, unzip and sideload `app-debug.apk` on Android.

In Pixel Agent, optionally enable **Pixel Agent** under Android Settings > Accessibility. On Android, sideloaded accessibility may require **App info > ... > Allow restricted settings**.

Tap **Continue with ChatGPT**. Consent is optional; if access isn't granted, the app cannot make AI calls. The browser redirects to an ephemeral listener on your **own phone** at `http://127.0.0.1:<port>/auth/callback`. Return to the application after Chrome displays confirmation. The loopback listener does not require a server or cloud hosting.

Tap **Test odpowiedzi OpenAI**. Only when that succeeds use **Test AI na Chrome**.

## Security
- Sensitive accounts/screens: do not use them with this prototype.
- Code is not a security-audited Android agent; review permissions and avoid password fields.
- Tokens are stored locally encrypted using the Android Keystore; never stored in GitHub.
- Signing out removes local tokens; remote authorization is controlled in ChatGPT settings.
- Debug APK is not release-signed for production distribution.
- Only invoke actions after human approval.
- Accessibility text from the Chrome test can be sent to OpenAI; use only public test pages.

## Official docs
- https://developers.openai.com/siwc/token-sharing-open-source/sign-in
- https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference
- https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations

## Development
GitHub Actions builds via Gradle 8.9 / Android Gradle Plugin 8.7.3 / SDK 35 / Java 17. App source in `app/src/main/java/pl/pixelagent/`.

This build is experimental, not yet verified against a real OAuth grant. The success of the Android compilation alone does not prove sign-in or inference works.


## v0.6 - Chrome multi-step preview
- User enters a goal in Pixel Agent; the app opens public Polish Wikipedia in Chrome.
- Agent reads a filtered Accessibility summary and chooses SCROLL_DOWN, SCROLL_UP, BACK or STOP.
- User must approve every physical action using an Accessibility overlay; STOP rejects the task.
- On approved actions the agent observes the screen again. Limit: 5 actions.
- v0.6 cannot click links, submit forms, write messages, access other apps, trade or make payments.
- OAuth and token storage unchanged since v0.5; app must be reinstalled if debug signing key changes.
- v0.6 must still be tested on the actual Pixel; compilation alone is not a hardware validation.
