package me.rerere.rikkahub.data.localai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeManifestTest {
    private val hash = "a".repeat(64)
    private fun manifest(engine: String = "litert-lm", version: String = "0.16.1", path: String = "lib/liblitertlm_jni.so") =
        """{"engine":"$engine","version":"$version","abi":"arm64-v8a","files":[{"path":"$path","sha256":"$hash"}]}"""

    @Test
    fun `accepts pinned LiteRT and legacy GGUF manifests`() {
        assertEquals(LocalRuntimePackage.LITERT_LM, parseRuntimeManifest(manifest()).runtimePackage)
        val gguf = manifest("llama.cpp", "1.0.0", "lib/libflit_local_llama.so")
        assertEquals(LocalRuntimePackage.GGUF, parseRuntimeManifest(gguf).runtimePackage)
        assertEquals(LocalRuntimePackage.GGUF, parseRuntimeManifest(gguf.replace("\"engine\":\"llama.cpp\",", "")).runtimePackage)
    }

    @Test
    fun `rejects mismatched LiteRT version and missing native library`() {
        assertThrows(IllegalStateException::class.java) { parseRuntimeManifest(manifest(version = "0.17.1")) }
        assertThrows(IllegalStateException::class.java) { parseRuntimeManifest(manifest(path = "lib/other.so")) }
    }

    @Test
    fun `rejects traversal in versions and file paths`() {
        assertThrows(IllegalArgumentException::class.java) { parseRuntimeManifest(manifest(version = "..")) }
        assertThrows(IllegalArgumentException::class.java) { parseRuntimeManifest(manifest(path = "../liblitertlm_jni.so")) }
        assertThrows(IllegalArgumentException::class.java) { parseRuntimeManifest(manifest(path = "/lib/liblitertlm_jni.so")) }
    }

    @Test
    fun `rejects malformed field types and duplicate files`() {
        assertThrows(IllegalArgumentException::class.java) {
            parseRuntimeManifest(manifest().replace("\"engine\":\"litert-lm\"", "\"engine\":{}"))
        }
        assertThrows(IllegalStateException::class.java) {
            parseRuntimeManifest(manifest().replace("\"version\":\"0.16.1\"", "\"version\":[]"))
        }
        assertThrows(IllegalStateException::class.java) {
            parseRuntimeManifest(manifest().replace("]}", ",{" + "\"path\":\"lib/liblitertlm_jni.so\",\"sha256\":\"$hash\"}]}"))
        }
    }
}
