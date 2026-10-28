# 7SVEN

**7SVEN is a local-first Android phone assistant by [Ujexa](https://github.com/Ujexa).**

It combines on-device language-model inference with Android tools and accessibility-based interaction to help carry out phone tasks from text or voice. The project is designed around local execution and explicit user control—not as a claim that every phone task is already autonomous or universally reliable.

> **Status:** v1.4.1 is a tested development build. The maintainer has exercised core commands on a physical Android phone, including flashlight on/off, volume adjustment, opening Settings without making changes, unknown-app handling, STOP, and repeated commands. This is not a standardized public benchmark or a claim of production-grade reliability.

## Current capabilities

- Chat-first text interface and voice interaction.
- Local inference through llama.cpp/JNI with a separately supplied GGUF model.
- Phone actions routed through deterministic tools and Android APIs where available.
- Accessibility-based UI interaction, safety checks, and an activity/audit log.
- Persistent assistant memory with search and explicit remember/forget flows.
- Multi-step task plans with step checkpoints, cancellation, and resume support.
- Supported phone utilities such as contact/calendar access, reminders, timers, and device status.
- Proactive reminders subject to Android background-execution and manufacturer battery policies.

Availability of individual functions depends on Android version, granted permissions, device settings, installed apps, and OEM restrictions.

## Important limitations

- **Not Jarvis-level autonomy.** 7SVEN is an actively developed phone assistant; it does not reliably understand or operate every app or every multi-step task.
- **No guaranteed universal app control.** Apps can block or expose incomplete accessibility information; screens and Android versions vary.
- **Communication actions may be drafts.** Calls, texts, email, and similar actions can require the user to review and press Send.
- **STOP behavior has limits.** Native model generation cannot always be interrupted mid-token; the current implementation may discard the visible answer and defer the next request until the engine is ready.
- **System alarms:** Android does not generally let an app inspect and delete every individual alarm set by other apps.
- **Background scheduling:** reminders and battery/charging checks may be delayed by Android or OEM battery management.
- **Local memory is not necessarily encrypted.** Do not store passwords, financial secrets, or highly sensitive information.
- **Hardware scope:** bundled native libraries target `arm64-v8a`; this is not a universal APK for all Android devices.

## Privacy model

Core inference is intended to run on-device. The model file is not included in this repository. Verify the final merged manifest and all transitive dependencies before each release; a local-first design should not substitute for inspecting the actual build.

## Build and setup

This repository uses a Termux/Android SDK build workflow. It may not be a turnkey Android Studio project: native libraries and model assets have separate provenance and setup requirements.

1. Install a compatible JDK 17, Android SDK (platform 34), Android NDK, Gradle 8.2, CMake, and `unzip`.
2. Place the required GGUF model at the location expected by the app's model-loading code. **Do not commit model weights**; follow the model's own license and distribution terms.
3. Verify the native libraries under `app/src/main/jniLibs/arm64-v8a/` and the wake-word assets expected by the service.
4. Build with the project-compatible Gradle setup:
   ```bash
   gradle --no-daemon assembleDebug
   ```
5. Install the debug APK on a test device and validate each action before using it with important data.

The Termux helper script is specific to the maintainer's existing environment. Read it before running it; it overlays source files and is not a generic installer.

## Permissions and user control

7SVEN may request microphone, notification, accessibility, contacts, calendar, exact-alarm, or related permissions depending on enabled features. Accessibility access is powerful. Grant it only if you understand that the assistant can inspect on-screen UI and perform actions within Android's accessibility APIs. Test using non-sensitive accounts and contacts first.

Do not rely on 7SVEN for emergency actions, financial transactions, safety-critical operations, or unattended messaging.

## Testing

The included JVM tests are useful regression checks, but they do not prove every Android integration works on every device. See [docs/TESTING.md](docs/TESTING.md) and preserve known limitations in release copy.

## Licensing

The MIT license applies only to original 7SVEN project material owned by Ujexa. Third-party libraries, native binaries, model weights, and assets are not automatically relicensed by it. Review [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) before redistributing binaries or models.
