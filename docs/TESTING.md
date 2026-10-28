# Testing and release evidence

## Device smoke tests reported for v1.4.1

The maintainer reported testing the following on a physical Android phone:

| Test | Reported result |
|---|---|
| Turn flashlight on | Passed |
| Turn flashlight off | Passed |
| Set volume to 40% | Passed |
| Open Settings without changing anything | Passed |
| Request an unknown app / unsupported target | Correctly reported failure |
| STOP control | Passed |
| Repeat a command | Passed |

These are reported smoke-test results, not independently reproduced results or a complete benchmark. Device model, OS build, app permissions, and exact app build should be recorded before publishing formal test claims.

## Before public release

- [ ] Clean build from a fresh checkout using documented dependencies.
- [ ] Install on a clean test profile/device.
- [ ] Test in airplane mode after setup; confirm no unexpected network access.
- [ ] Test missing-permission flows and retry after granting permissions.
- [ ] Test accessibility disabled/enabled, screen lock, app backgrounding, process kill and reboot.
- [ ] Test STOP while waiting, generating, executing a tool, and speaking.
- [ ] Test reminders across reboot, timezone changes and battery restrictions.
- [ ] Test memory persistence, deletion and corrupted data recovery.
- [ ] Verify all high-risk actions require appropriate user confirmation.
- [ ] Review APK manifest, bundled native libraries, dependency licenses, model and wake-word licenses.
- [ ] Capture screenshots from the actual release build; do not use mockups as product screenshots.
- [ ] Record SHA-256 hashes for published APK and source archive.

## Known limits to preserve in release copy

- No claim of Jarvis-level capability or universal app support.
- Some accessibility actions can fail when apps expose incomplete UI trees or Android blocks automation.
- Communication actions may remain drafts and require user confirmation.
- Model generation cancellation is not a hard, immediate interruption at the native engine level.
- Background reminders can be delayed by Android/OEM battery management.
- Local memory is app-private but not encrypted by default.
