# 7SVEN v1.4.1 — release notes draft

**Status:** tested development build; public release candidate pending clean-build, packaging, and third-party-license verification.

## Highlights
- Chat-first UI redesign with adaptive light/dark appearance.
- Persistent memory with search and remember/forget flows.
- Multi-step task plans with checkpoints, cancellation and resume support.
- Phone tools for supported contacts/calendar/device actions.
- Persistent reminders and schedules with proactive behavior.
- Natural follow-ups, voice interaction and STOP controls.
- Safety checks and local activity/audit logging.

## Limitations
- Device and app compatibility varies; accessibility-based control is not universal.
- Calls, texts and email actions may produce drafts that the user must review and send.
- Native generation is not guaranteed to stop immediately mid-token.
- Android and manufacturer battery policies can delay scheduled behavior.
- Memory is stored locally but is not encrypted by default.
- This release has not been validated against a formal 30-task or 100-task benchmark.

## Release checklist
Do not publish this as a production-ready universal assistant until the clean-build, device verification, license review, and privacy checks in `docs/TESTING.md` and `THIRD_PARTY_NOTICES.md` are complete.
