# 7SVEN v0.8.1 — local wake-word integration

This patch adds a foreground microphone service using sherpa-onnx's Android Kotlin keyword-spotting API and the official English GigaSpeech KWS model. The script downloads the official model archive and resolves the sherpa-onnx runtime dependency from JitPack before building.

## Wake-word behavior
- Open 7SVEN and choose Control.
- Grant microphone permission and enable “Enable local wake-word detection (Light Up)”.
- Android displays a persistent notification while listening.
- Say “Light Up”; the service detects it locally and opens 7SVEN voice capture.
- Disable the checkbox or tap Stop listening in the notification to stop.

## Constraints
- The initial supported phrase is “Light Up”, because the model's English keyword list and tokenization must match the model. The text field is not yet a true arbitrary phrase generator.
- The first build requires internet access to fetch the official model and resolve the sherpa-onnx dependency. Subsequent inference is local and does not send audio to cloud services.
- The service must be started while the app is visible and microphone permission must be granted. Android may still apply manufacturer-specific background/battery limits.
- Build success and device microphone behavior must be verified on the phone; this bundle has not been compiled on the user's Termux installation.
