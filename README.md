# Push Router

A native Android app that captures posted notifications and forwards matching events to explicitly paired Telegram accounts. Kotlin, Jetpack Compose, Android Keystore, and WorkManager. The selected bell-and-arrow icon appears in the app and launcher.

## Open and build

Open this folder in Android Studio. Use JDK 21 for Gradle, install Android SDK Platform 36 and SDK Platform-Tools through SDK Manager, and accept the normal Gradle sync. Android Studio creates `local.properties` with your SDK path; this file is machine-specific and ignored by Git. For command-line builds, set `JAVA_HOME` to your JDK 21 installation and set `ANDROID_HOME` to your SDK directory, or create `local.properties` with `sdk.dir=/absolute/path/to/your/android-sdk`.

```sh
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Minimum supported Android version is 8.0 (API 26). This project pins AGP 8.13.2, Kotlin 2.2.10, Gradle 8.13, and the Compose 2025.08.00 BOM to the available stable toolchain rather than upgrading the entire local SDK.

## Install on a phone

For development, install your locally built APK over USB:

1. Enable **Developer options → USB debugging** on the phone.
2. Connect it to your computer, unlock it, and approve the USB debugging prompt.
3. Add your SDK's `platform-tools` directory to your shell's `PATH`, or use the full path to `adb`.
4. From this project folder, run:

```sh
adb devices
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
```

`-d` selects the connected physical device rather than an emulator; connect only one phone. `-r` updates an existing installation while keeping app data, provided its signing key matches. You can also select the phone and click **Run** in Android Studio. See the [official ADB setup and installation guide](https://developer.android.com/tools/adb).

Alternatively, copy the APK to your phone and open it. If prompted, allow **Install unknown apps / Allow from this source** for the browser or file manager used to open it. Android may apply additional checks to this installation method; use the troubleshooting steps below before configuring the app.

### “App blocked to protect your device” during installation

Google Play Protect can block apps installed from browsers, messaging apps, or file managers when they request sensitive access, including notification access. Push Router needs notification access to collect the notifications you choose to route. This is a possible cause of the warning, not a guarantee that every warning has the same cause. See [Google's explanation of this block](https://developers.google.com/android/play-protect/warning-dev-guidance).

Try the USB development installation above first. For a trusted APK you built yourself on your own phone, you can also temporarily disable scanning:

1. Open **Play Store → profile picture → Play Protect → Settings (gear)**.
2. Turn off **Scan apps with Play Protect**.
3. Retry installing the APK.
4. Turn scanning back on afterward. Play Protect may flag the app again.

These steps reduce device protection while scanning is off and do not guarantee installation on every phone. Managed devices may enforce protection settings. See [Google's Play Protect settings instructions](https://support.google.com/pixelphone/answer/2812853?hl=en).

### “App was denied access” or “Restricted setting” when enabling notification access

Android can restrict sensitive settings for sideloaded apps even after installation succeeds. On Android 13 and newer, try:

1. Open the phone's **Settings → Apps → See all apps → Push Router** (the app info screen).
2. Tap **⋮** in the top-right corner and select **Allow restricted settings**.
3. Confirm with your PIN, pattern, or fingerprint if prompted.
4. Return to Push Router's **Settings → Open Android settings** and enable notification access again. The system may call it **Notification access** or **Notification read, reply & control**.

Allowing restricted settings unlocks the permission controls; you still need to grant notification access separately. Only do this for an app you trust, since notification access exposes notification content. See [Google's restricted settings instructions](https://support.google.com/android/answer/12623953?hl=en).

If the menu is missing or access remains denied, record your phone model, Android version, installation method, and the exact warning text or a screenshot when reporting the problem. Menu names and restrictions vary by manufacturer and device policy.

## First run

1. Open **Settings → Open Android settings** and enable notification access for Push Router. If Android denies access, follow [the restricted settings steps above](#app-was-denied-access-or-restricted-setting-when-enabling-notification-access).
2. Create a **dedicated Telegram bot** using @BotFather. Enter its token in Settings, verify it, then choose **Use this bot**. Do not reuse a bot owned by another polling client or an active webhook; this app does not remove existing webhooks.
3. In **Pairings**, enter a label and create an invitation. The recipient scans the QR or uses **Open Telegram on this phone**, then presses Start. Keep the pairing screen open. Inspect the Telegram identity and confirm it in Push Router.
4. Wait for a notification, open it, and choose **Route notifications like this**. Select its app/profile, category, optional text condition, and destination(s). Test matching against captured samples, then save.
5. Future matching notifications enter the local queue. WorkManager sends them when the phone has connectivity. Unmatched events remain **Not forwarded**.

No sample events or bot credentials are preloaded. Running builds or unit tests does not contact Telegram or forward any messages.

## Behavior

- All match conditions are combined. App package, Android profile, and notification channel ID are the source identifiers; names are display labels. An Android profile is not an account inside Gmail, a banking app, or another source app.
- Multiple matching routes produce one queued delivery per event/destination. Repeated callbacks with the same platform notification key and content are deduplicated while retained. A notification with changed content is a new event.
- Editing routes only affects future captures and authorization of queued messages. It never adds recipients to old events.
- Pairings use numeric Telegram user/chat IDs and verified bot ID. The invitation is random, single use, expires after five minutes, and needs on-device recipient approval. Only private chats are accepted. Duplicate pairings for the same bot/user are rejected.
- Removing a pairing cancels its queued deliveries, removes it from routes, and pauses rules without destinations. Replacing the bot disconnects pairings and pauses rules; rotating a token for the same bot preserves them. A send already in progress cannot be recalled.
- Global pause cancels queued deliveries but keeps collecting. Resume does not replay notifications received while paused.
- System group summaries and this app's own notifications are excluded. Official Telegram and Telegram X notifications are captured but cannot be routed, preventing bot receipts from creating a loop. Third-party Telegram clients are not supported as routing sources in this version.
- Hidden/unavailable Android content is skipped. Notification listeners see posted notifications, not silent push payloads or an archive from before access was granted.

## Storage and delivery

Configuration, token, notification text, and delivery history are stored together in an authenticated AES-GCM snapshot. The encryption key lives in Android Keystore; the snapshot lives in `noBackupFilesDir`. Cloud backup is disabled. Nothing stores the token in plaintext preferences, Gradle configuration, logs, or worker input data. The app uses HTTPS and does not follow API redirects.

History shows the last 24 hours, limited to 500 notifications. Expiry also drops corresponding queued deliveries. History is pruned on capture, startup, while pairing, and by hourly background work. Android can defer background work, so expired encrypted data is removed at the next cleanup rather than at an exact wall-clock deadline. **Clear history** removes it immediately and cancels those deliveries. The app is a single-device direct client, not a hosted relay or multi-device bot service.

Telegram's send API has no client idempotency key. Offline requests wait for connectivity. Explicit rate limits retry. If a connection fails after a request may have reached Telegram, delivery is marked **unconfirmed**, not blindly resent. A process that stops while sending is handled the same way. This avoids automatic duplicate sends but cannot guarantee end-to-end exactly-once delivery. Failed and uncertain results are visible in notification details.

## Project layout

- `app/`: Android UI, notification listener, encrypted persistence, Telegram client, background worker.
- `core/`: platform-independent routing, invitation validation, revocation, deduplication, and retention; unit tests.
- `prototypes/`: original interactive UX prototype and design notes.
- `assets/`: selected icon and original design alternatives.

## Verification

Verified on 2026-10-08: the debug APK builds, all 12 routing unit tests pass, and all 3 instrumentation tests pass on a fresh API 37 emulator. Instrumentation covers first launch/navigation, encrypted storage round trips without plaintext credentials, and rejection of tampered data. Android lint reports zero errors with nonblocking warnings. Live notification capture and Telegram delivery still require device testing with a test bot and recipient.

The routing tests cover profile/category/text matching, overlapping rules, duplicate callbacks, destination removal, rule edits, bot replacement versus rotation, pause/resume, invitation token/expiry/private-identity checks, history expiration, and interrupted-send recovery.

To verify capture and delivery on a device, follow the first-run steps with a test Telegram bot and test recipient. Post both matching and nonmatching notifications, repeat an unchanged notification, disconnect/reconnect the network, and remove a pairing before its queued event sends. OEM background restrictions and Android's redaction behavior need testing on the target phones.

## API references

- [Android notification listener](https://developer.android.com/reference/android/service/notification/NotificationListenerService)
- [WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started)
- [Telegram Bot API](https://core.telegram.org/bots/api)
- [Telegram deep links](https://core.telegram.org/bots/features#deep-linking)
