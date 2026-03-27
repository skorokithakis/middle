---
id: mid-twr7
status: closed
deps: []
links: []
created: 2026-03-27T00:09:27Z
type: task
priority: 2
assignee: Stavros Korokithakis
---
# Add battery-low system notification after sync

After each successful sync, if the battery voltage is below 3800 mV, show an Android system notification telling the user to charge the pendant. Debounce: don't show again within 6 hours of the last one.

Scope:
- MiddleApplication.kt: Register a new notification channel (e.g. ID 'middle_battery_low', importance HIGH) alongside the existing sync channel.
- Settings.kt: Add a lastBatteryNotificationTime Long property (default 0), same pattern as other persisted fields.
- SyncForegroundService.kt: After the voltage is read (around line 220-230), check if millivolts < 3800 and System.currentTimeMillis() - lastBatteryNotificationTime > 6 hours. If both true, post a notification on the new channel and persist the current time. Use a distinct notification ID (not 1, which is the foreground service notification).
- strings.xml: Add string resources for the channel name and notification text.

Non-goals:
- No in-app UI changes.
- No user-configurable threshold or debounce.
- No firmware changes.

## Acceptance Criteria

App compiles and installs with ./gradlew installDebug. Notification uses a separate HIGH-importance channel. Debounce is 6 hours.

