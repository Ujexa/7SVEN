# 7SVEN

**7SVEN is a local-first Android phone assistant by [Ujexa](https://github.com/Ujexa).**

It combines on-device language-model inference with Android tools and accessibility-based interaction to help carry out phone tasks from text or voice. The project is designed around local execution and explicit user control—not as a claim that every phone task is already autonomous or universally reliable.

> **Status:** v1.4.1 is a tested development build. The maintainer has exercised core commands on a physical Android phone, including flashlight on/off, volume adjustment, opening Settings without making changes, unknown-app handling, STOP, and repeated commands. This is not yet a standardized public benchmark or a claim of production-grade reliability.

## Current capabilities

- Chat-first text interface and voice interaction, including the “Light Up” wake phrase setup from the earlier integration.
- Local inference through llama.cpp/JNI with a separately supplied GGUF model.
- Phone actions routed through deterministic tools and Android APIs where available.
- Accessibility-based UI interaction, with safety checks and an activity/audit log.
- Persistent assistant memory with search and explicit remember/forget flows.
- Multi-step task plans with step checkpoints, cancellation, and resume support.
- Phone utilities such as contact/calendar access, reminders, timers, battery/device status, and drafts for communication actions.
- Proactive reminders and scheduled checks subject to Android background-execution and manufacturer battery policies.

Availability of individual functions depends on Android version, granted permissions, device settings, installed apps, and OEM restrictions.

## Important limitations

- **Not Jarvis-level autonomy.** 7SVEN is an actively developed phone assistant; it does not reliably understand or operate every app or every multi-step task.
- **No guaranteed universal app control.** Apps can block or expose incomplete accessibility information; screens and Android versions vary.
- **Communication actions are drafts.** Calls, texts, email, and similar actions may require the user to review and press Send.
- **STOP behavior has limits.** Native model generation cannot always be interrupted mid-token; the current implementation can discard the visible answer and defer the next request until the engine is ready.
- **System alarms:** Android does not generally let an app inspect and delete every individual alarm set by other apps.
- **Background scheduling:** battery/charging checks and reminders may be delayed by Android or OEM battery management.
- **Memory storage:** local app-private storage is not the same as encrypted storage. Do not store passwords, financial secrets, or other highly sensitive information.
- **Hardware scope:** the packaged native libraries target `arm64-v8a`; this is not a universal APK for all Android devices.

## Privacy model

The app manifest intentionally omits the `INTERNET` permission in the current source, and core inference is intended to run on-device. The model file is not included in this repository. Verify the final merged manifest and all transitive dependencies before each release; a local-first design should not be treated as a substitute for inspecting the actual build.

## Build and setup

This repository is based on a Termux/Android SDK build workflow and includes the v1.4.1 source integration. It is **not yet a turnkey Android Studio project**: the native libraries and model assets have separate provenance and setup requirements, and the exact local build environment may need adjustment.

1. Install a compatible JDK 17, Android SDK (platform 34), Android NDK, Gradle 8.2, CMake, and `unzip`.
2. Place the required GGUF model at the location expected by the app's model-loading code. **Do not commit the model file**; follow the model's own license and distribution terms.
3. Verify all native libraries in `app/src/main/jniLibs/arm64-v8a/` and the wake-word assets expected by `WakeWordService`.
4. Build with the Android Gradle Plugin/Gradle versions specified by the project:
   ```bash
   gradle --no-daemon assembleDebug
   ```
5. Install the debug APK on a test device, grant only the permissions needed for the functions you intend to test, and validate each action before using it with important data.

The Termux script `APPLY-AND-BUILD-TERMUX.sh` is provided for the maintainer's existing `$HOME/7SVEN` setup. Read it before running it: it overlays source files and creates a backup of selected existing files. It is not intended as a generic installer for arbitrary projects.

## Permissions and user control

7SVEN may request microphone, notification, accessibility, contacts, calendar, exact-alarm, or related permissions depending on enabled features. Accessibility access is powerful. Grant it only if you understand that the assistant can inspect on-screen UI and perform actions within the limits of Android's accessibility APIs. Test using non-sensitive accounts and contacts first.

Do not rely on 7SVEN for emergency actions, financial transactions, safety-critical operations, or unattended messaging.

## Repository layout

- `app/src/main/java/com/sven/assistant/` — Android app, orchestration, tools, memory, tasks, scheduling, safety and UI.
- `app/src/main/cpp/` — JNI bridge and native build configuration.
- `app/src/main/jniLibs/arm64-v8a/` — prebuilt native runtime libraries.
- `tests/` — JVM tests included with the v1.4.1 source overlay.
- `docs/` — release, test and third-party-license notes.

## Testing

The included JVM tests are useful regression checks, but they do not prove every Android integration works on every device. Record device model, Android version, permissions, exact test steps, expected outcome and observed outcome when reporting a bug. See `docs/TESTING.md`.

## License

Original 7SVEN project code is offered under the MIT License in `LICENSE`, subject to the separate terms for third-party components documented in `THIRD_PARTY_NOTICES.md`. Third-party libraries, native binaries, model weights, and assets are not automatically relicensed by the project license. Review the notices and each upstream license before redistributing a build.
