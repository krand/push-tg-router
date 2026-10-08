# Push Router UX prototype

Mobile notification router for Android. Synthetic notifications, Telegram identities, and bot setup only; no network calls or real forwarding. The HTML is a conversation visualization fragment, not an Android implementation.

Selected app icon: the round blue bell-and-arrow design, saved as `../assets/app-icon.png` and shown in the prototype header.

## Navigation

- Notifications: observed notifications with historical delivery status. Filters: All, Forwarded, Not forwarded. Open one to inspect it and create a route.
- Routes: app + notification category + optional case-insensitive title/body substring, with one or more paired destinations. Test samples before saving; enable or pause each rule.
- Pairings: invitation QR preview and same-phone Telegram entry point, recipient identity confirmation, editable labels, removal.
- Settings: notification access and custom bot token verification/replacement.

## Behavior decisions

- Android notifications are the input, not silent push payloads. Collection starts after notification access is granted; the list is not a full historical archive.
- Category means the source app's notification channel/category, not an inferred business event or guaranteed account identity. The implementation should store package name, Android profile, and channel ID; names are display labels.
- Default is no forwarding. Every configured condition must match. Forward to the union of destinations across matching enabled routes, once per destination.
- Editing a route affects future events only. Testing previews outcomes and sends nothing.
- A pairing invitation is a short-lived, single-use deep link encoded in a QR. Opening it and pressing Start proposes the Telegram identity; approval in the Android app activates the binding. Provide Open Telegram for same-phone use.
- Bind destinations to Telegram numeric user/chat IDs and bot identity. Labels/usernames are presentation only. Never authorize delivery from a label or username.
- Removing a pairing revokes delivery, removes it from all routes, cancels queued delivery to it, and pauses any rule left with no destinations. No fallback recipient.
- Replacing the bot invalidates pairings and pauses routes. Changing credentials for the same verified bot identity should preserve pairings in production.
- Pause suspends forwarding but keeps collection active. No automatic replay on resume. A production queue must check current authorization before every send.
- Skip unavailable sensitive content and notifications from this router to avoid loops. Handle updated/group notifications with event/destination deduplication; this prototype uses fixed samples.
- The prototype token field accepts only `demo`, clears entered values, and never persists tokens. Production must define encrypted credential storage and clearly identify whether the phone or a backend owns the token.

## Review path

1. Open a Gmail notification, create a route, select My Telegram, and test matching and nonmatching samples.
2. Add a pairing, simulate Start, inspect the identity, and confirm.
3. Rename the pairing and assign it to a route.
4. Remove a pairing and verify affected destinations and routes.
5. Open Settings, enter `demo`, verify it, and inspect the bot replacement confirmation.

## Open design decisions

- Should a single event reach multiple destinations, or should the product enforce exactly one? This draft supports explicit multiple selection.
- Should production store notification text locally, and for how long? Decide retention before implementation.
- Direct phone-to-Telegram versus backend delivery, including credential custody, retry ownership, and offline behavior.

Telegram deep links: https://core.telegram.org/bots/features#deep-linking
Android listener API: https://developer.android.com/reference/android/service/notification/NotificationListenerService
