package com.sven.assistant

internal class NativeEngine {
    external fun loadModel(fd: Int): String
    external fun generate(prompt: String, maxTokens: Int): String
    external fun unloadModel()

    companion object {
        init {
            System.loadLibrary("7sven_jni")
        }
    }
}
