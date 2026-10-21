# Tandem v5.1: multi-account AI workspace for Android

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

## v5.1 changes
- **App lock** (Menu > App lock): asks for your phone PIN, pattern or fingerprint when you open Tandem or return after 30 seconds. Also blocks screenshots and hides content in Recent apps.
- **Finish detector**: long-press a screen header > Finish detector to give any site its Stop-button selector so finished alerts work everywhere.
- **Crash report**: if the app ever crashes, Menu > Copy last crash report.

## v5.0 changes
- **Dark and light themes** (Menu > Theme: follow system, dark or light). Whole app, dialogs and status bar.
- **Ask all open screens**: type one question and send it to every visible AI at once (beta; uses each site's chat box).
- Three page identities (mobile Chrome, desktop Chrome, original WebView) tried automatically if a page loads blank.
- Each screen header shows the site name.

## v4.1 changes
- **Logins are kept.** The X button now only closes the screen (frees memory). The account and its login stay saved
  and the screen stays in the top bar; tap it to reopen on the same page. Accounts are deleted only from
  Settings (gear icon) > Accounts > Delete account.
- Logins are written to disk when you leave the app, after every page load, and on close, so swiping the app away no longer loses them.
- **Blank white screen fixes (all sites):** no more rounded clipping around the WebView, pages load only after the
  screen has a real size, no fade animation, the "X-Requested-With" app header is removed, and a blank-page
  detector reloads, then switches mobile/desktop identity, then shows a message with Retry.
- Load errors now show a message instead of a white page. Long-press a screen header > **Copy diagnostics**.

## Important notes
- "Finished" detection looks for the site's Stop button. It is built for Claude, ChatGPT, Gemini and Grok
  style UIs. If a site changes its UI or you add one that is not recognised (for example DeepSeek), update the
  selector list in `DETECT_JS` at the bottom of `MainActivity.kt`.
- "Continue with Google" is blocked inside most in-app browsers. Sign in with email and the emailed code.
- Back button: goes back in the focused screen, then restores a maximized screen, then sends the app to the
  background if something is still answering.
- This is a debug build for personal use. Using several accounts on one service is subject to that service's terms.
