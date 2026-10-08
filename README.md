# Push Router

Forward the Android notifications you care about to Telegram. Choose an app, notification category, and optional text filter, then send matching notifications to one or more confirmed recipients. Unmatched notifications stay on your phone.

Push Router runs directly on your phone, queues deliveries while offline, and keeps configuration and notification history encrypted locally. Built with Kotlin, Jetpack Compose, Android Keystore, and WorkManager. Requires Android 8.0 or newer.

## Screenshots

The four main screens in light mode, before setup.

| Notifications | Routes | Pairings | Settings |
| --- | --- | --- | --- |
| <img src="assets/screenshots/notifications.png" alt="Notifications screen before notification access is enabled" width="220"> | <img src="assets/screenshots/routes.png" alt="Routes screen before creating the first routing rule" width="220"> | <img src="assets/screenshots/pairings.png" alt="Pairings screen before configuring a Telegram bot" width="220"> | <img src="assets/screenshots/settings.png" alt="Settings screen with notification access, Telegram bot configuration, and encrypted history controls" width="220"> |

## Build

Install Android Studio, JDK 21, Android SDK Platform 36, and Android SDK Platform-Tools. Open this folder in Android Studio, select JDK 21 as the Gradle JDK, and sync the project.

For command-line builds, set `JAVA_HOME` to your JDK 21 installation and `ANDROID_HOME` to your Android SDK directory. Alternatively, set the SDK path in the ignored `local.properties` file:

```properties
sdk.dir=/absolute/path/to/your/android-sdk
```

Build the debug APK:

```sh
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Run unit tests and Android lint:

```sh
./gradlew :core:test :app:lintDebug
```

## Install

Enable **Developer options → USB debugging** on your phone. Connect it over USB, unlock it, and approve the debugging prompt. Add the SDK's `platform-tools` directory to your `PATH`, then run:

```sh
adb devices
adb -d install -r app/build/outputs/apk/debug/app-debug.apk
```

Connect only one physical phone. `-d` selects it instead of an emulator; `-r` updates an existing installation while preserving data if the signing key matches. You can also select the phone and click **Run** in Android Studio.

Alternatively, copy the APK to your phone and open it, allowing **Install unknown apps** for the browser or file manager if prompted.

### Initial setup

1. In Push Router, open **Settings → Open Android settings** and enable notification access.
2. Create a dedicated Telegram bot with **@BotFather**. Enter its token in Settings, select **Verify token**, then **Use this bot**. Use a bot without another polling client or active webhook.
3. In **Pairings**, create an invitation. Have the recipient scan the QR code and press **Start** in Telegram. Keep the pairing screen open, check the recipient's identity, and confirm the pairing.
4. Open a captured notification and select **Route notifications like this**. Choose the matching conditions and destination(s), then save. Future matching notifications are forwarded when the phone has connectivity.

If Android blocks notification access for the sideloaded app, open **Android Settings → Apps → Push Router → ⋮ → Allow restricted settings**, confirm, then enable notification access again. Menu names vary by phone.
