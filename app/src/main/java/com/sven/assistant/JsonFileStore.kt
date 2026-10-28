package com.sven.assistant

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * Crash-safe JSON file: write to a temp file, fsync, keep the previous version as .bak, then rename.
 * A torn or corrupt main file falls back to the backup. Pure JVM so it can be unit-tested off-device.
 */
internal class JsonFileStore(private val file: File) {
    private val bak = File(file.path + ".bak")
    private val tmp = File(file.path + ".tmp")

    @Synchronized
    fun read(): JSONObject? {
        for (f in listOf(file, bak)) {
            try {
                if (f.exists()) {
                    val t = f.readText()
                    if (t.isNotBlank()) return JSONObject(t)
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    @Synchronized
    fun write(obj: JSONObject) {
        file.parentFile?.mkdirs()
        FileOutputStream(tmp).use { out ->
            out.write(obj.toString().toByteArray(Charsets.UTF_8))
            out.flush()
            try { out.fd.sync() } catch (_: Exception) {}
        }
        if (file.exists()) {
            try { file.copyTo(bak, overwrite = true) } catch (_: Exception) {}
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    @Synchronized
    fun delete() {
        file.delete(); bak.delete(); tmp.delete()
    }
}

/** Type-stable key list (org.json's keys() is a raw Iterator on some library versions). */
internal fun JSONObject.keyList(): List<String> {
    val out = ArrayList<String>()
    val it = this.keys()
    while (it.hasNext()) out.add(it.next().toString())
    return out
}
