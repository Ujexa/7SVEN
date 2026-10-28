# Third-party notices — verification required

This is an initial inventory, not a substitute for checking exact versions, build flags, source commits, and license files used to produce distributed binaries.

| Component | Where used | Distribution note |
|---|---|---|
| llama.cpp / ggml | Native libraries and JNI integration | Upstream is MIT-licensed. Include the applicable upstream copyright and license text for the exact version used to build binaries. |
| sherpa-onnx Android artifact | Gradle dependency, if present in the published source | Verify the exact artifact version's Apache-2.0 license and include required notices/attribution. |
| Qwen2.5 1.5B Instruct GGUF | Supplied separately; model intentionally excluded | Model weights have their own license and attribution terms. Verify the exact upstream model and conversion/quantization provenance before redistribution. |
| Wake-word model/assets | Installed separately in the maintainer workflow | Verify each model/asset's license and attribution requirements. |
| Android SDK/framework | Build/runtime platform | Subject to their own platform terms. |

## Before publishing a binary release

- [ ] Record exact llama.cpp commit/tag and build configuration.
- [ ] Include upstream license/copyright notices for bundled native libraries.
- [ ] Verify exact sherpa-onnx artifact license and notices.
- [ ] Identify the exact GGUF model source, license, quantization/conversion tool, and redistribution rights.
- [ ] Identify the exact wake-word model and tokenizer files and verify redistribution rights.
- [ ] Confirm no secrets, personal data, local logs, model files, APKs, signing keys, or private test data are committed.

The repository's MIT `LICENSE` covers only original project material owned by Ujexa. It does not override third-party terms.
