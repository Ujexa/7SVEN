# Third-party notices — release verification required

This repository includes or references third-party components. The notices below are an initial inventory, **not a substitute for checking the exact versions, build flags, source commits, and license files used to produce the distributed binaries**.

| Component | Where used | License / distribution note |
|---|---|---|
| llama.cpp / ggml | Prebuilt native libraries under `app/src/main/jniLibs/arm64-v8a/` and JNI integration | Upstream project is MIT-licensed. Include the applicable upstream copyright and license text for the exact version used to build the binaries. |
| sherpa-onnx Android artifact `com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v1.13.8` | Gradle dependency | Upstream project is distributed under Apache-2.0; retain required notices and license text from the exact artifact. |
| Qwen2.5 1.5B Instruct GGUF model | Supplied separately by the user; model is intentionally not included here | Model weights have their own license and attribution terms. Verify the exact upstream model and conversion/quantization provenance before redistribution. Do not assume the app's MIT license covers model weights. |
| Wake-word model/assets | Installed separately in the maintainer workflow | Verify each model/asset's license and required attribution before redistribution. |
| Android SDK / Android framework | Build/runtime platform | Subject to their own platform terms. |

## Before publishing a binary release

- [ ] Record exact llama.cpp commit/tag and build configuration.
- [ ] Add the exact upstream MIT license and copyright notice for the bundled llama.cpp/ggml binaries.
- [ ] Verify the exact sherpa-onnx artifact's Apache-2.0 license and include its required NOTICE/attribution if applicable.
- [ ] Identify the exact GGUF model source, license, quantization/conversion tool, and whether redistribution is permitted.
- [ ] Identify the exact wake-word model and tokenizer files and verify redistribution rights.
- [ ] Generate a dependency inventory/SBOM if practical.
- [ ] Confirm no secrets, personal data, local logs, model files, APKs, signing keys, or private test data are committed.

The repository's MIT `LICENSE` covers only original project material owned by Ujexa. It does not override third-party terms.
