# Tandem v4: multi-account AI workspace for Android

Run several AI accounts (Claude, ChatGPT, Gemini, Perplexity, DeepSeek, Grok, Copilot, Le Chat, or any site)
at the same time. Every screen has its own isolated login, so you never log out and back in.

## Get the APK (no Android Studio)
1. Create a GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Actions tab > **Build Android APK** > **Run workflow**.
3. When green, download **Tandem-APK**, unzip, install `app-debug.apk`.
   If the build fails, open the failed step and copy the red error text.

## Features
- **Isolated logins** per screen (Android WebView profiles, needs WebView 110+).
- **Layouts**: Focus (1), Split (2, drag the handle to resize), Trio (3), Grid (4). Rotates automatically.
- **Per-screen controls**: minimize, maximize/restore, reload, close. Long-press a header or a chip for more
  (rename, color, copy link, open in browser, sign out).
- **Dock** at the top: tap a chip to bring a screen forward. Spinning ring = answering, green check = finished.
- **Finished alerts**: notification when a response completes while the screen is hidden or the app is in the
  background. A background service keeps responses streaming after you leave the app.
- **Remembers** your screens, layout, divider position and the exact page you were on.
- **Downloads** work with your login (including generated files), voice chat mic, file upload.
- Up to 8 screens, 4 visible at once. Hidden screens keep running.

## Important notes
- "Finished" detection looks for the site's Stop button. It is built for Claude, ChatGPT, Gemini and Grok
  style UIs. If a site changes its UI or you add one that is not recognised (for example DeepSeek), update the
  selector list in `DETECT_JS` at the bottom of `MainActivity.kt`.
- "Continue with Google" is blocked inside most in-app browsers. Sign in with email and the emailed code.
- Back button: goes back in the focused screen, then restores a maximized screen, then sends the app to the
  background if something is still answering.
- This is a debug build for personal use. Using several accounts on one service is subject to that service's terms.
