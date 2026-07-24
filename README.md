# Chintu AI

Chintu AI is an Urdu-first Android mobile assistant focused on reliable local command execution on Redmi Note 11 / Android 13 / HyperOS.

## Stable 3.3.2 architecture

- The always-listening speech and TTS engine runs in a dedicated `:voice` process so a slow Xiaomi binder cannot freeze the visible activity.
- Recognized commands are bridged back to the main process for Accessibility scrolling, typing, clicking, calls and app control.
- One SpeechRecognizer instance is reused with guarded cooldowns instead of rapid destroy/recreate loops.
- Urdu/Indian recognition, wake-word locking, command timeouts and status throttling are enabled.
- Contact lookup, direct calling, SMS, installed-app launching, Google/YouTube/weather/maps search, alarms, timers, torch, camera and settings remain available.
- No API key or secret is embedded in the app.

Financial transactions, password changes and other sensitive account actions are intentionally blocked.
