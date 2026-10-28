# Testing and release evidence

## Device smoke tests reported for v1.4.1

The maintainer reported testing these on a physical Android phone:

| Test | Reported result |
|---|---|
| Turn flashlight on/off | Passed |
| Set volume to 40% | Passed |
| Open Settings without changing anything | Passed |
| Unknown app / unsupported target | Failure reported appropriately |
| STOP control | Passed |
| Repeat a command | Passed |

These are reported smoke-test results, not independently reproduced results or a complete benchmark. Record device model, Android version, permissions, and exact build before making formal test claims.

## Before public release

- [ ] Clean build from a fresh checkout using documented dependencies.
- [ ] Install on a clean test profile/device.
- [ ] Test in airplane mode after setup; confirm no unexpected network access.
- [ ] Test missing-permission flows and retry after granting permissions.
- [ ] Test accessibility disabled/enabled, screen lock, app backgrounding, process kill, and reboot.
- [ ] Test STOP while waiting, generating, executing a tool, and speaking.
- [ ] Test reminders across reboot, timezone changes, and battery restrictions.
- [ ] Test memory persistence, deletion, and corrupted-data recovery.
- [ ] Verify high-risk actions require appropriate user confirmation.
- [ ] Review APK manifest, native libraries, dependency licenses, model and wake-word licenses.
- [ ] Capture screenshots from the actual release build.
- [ ] Record SHA-256 hashes for published APK and source archive.

## Known limits to preserve in release copy

- No claim of Jarvis-level capability or universal app support.
- Accessibility actions can fail when apps expose incomplete UI trees or Android blocks automation.
- Communication actions may remain drafts and require user confirmation.
- Model generation may not stop immediately at the native engine level.
- Background reminders can be delayed by Android/OEM battery management.
- Local memory is app-private but not encrypted by default.
