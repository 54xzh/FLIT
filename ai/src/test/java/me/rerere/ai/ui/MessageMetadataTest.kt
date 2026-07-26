package me.rerere.ai.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 验证类型化 metadata 与旧版手写 JsonObject 数据的双向兼容
 */
class MessageMetadataTest {

    private fun reasoningWith(metadata: JsonObject?) = UIMessagePart.Reasoning(
        reasoning = "thinking...",
        metadata = metadata,
    )

    // ===== 读取旧数据(各 provider 旧代码写入的确切格式) =====

    @Test
    fun `parses legacy claude metadata`() {
        // 旧代码: buildJsonObject { put("signature", signature) }
        val part = reasoningWith(buildJsonObject { put("signature", "sig-abc") })
        assertEquals("sig-abc", part.metadataAs<ClaudeReasoningMetadata>()?.signature)
    }

    @Test
    fun `parses legacy google metadata with json null thought signature`() {
        val withValue = reasoningWith(buildJsonObject { put("thoughtSignature", "ts-1") })
        assertEquals("ts-1", withValue.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)

        val withNull = reasoningWith(buildJsonObject { put("thoughtSignature", null as String?) })
        assertNull(withNull.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
    }

    // ===== 容错 =====

    @Test
    fun `returns null when metadata is absent`() {
        assertNull(reasoningWith(null).metadataAs<ClaudeReasoningMetadata>())
    }

    @Test
    fun `cross provider metadata does not interfere`() {
        // 切换 provider 后, Google 写入的 metadata 被 Claude 解析: 不抛异常, signature 为 null
        val part = reasoningWith(buildJsonObject { put("thoughtSignature", "ts-1") })
        assertNull(part.metadataAs<ClaudeReasoningMetadata>()?.signature)
    }

    @Test
    fun `malformed metadata returns null instead of throwing`() {
        // 类型不匹配 (signature 是 object 而非 string)
        val part = reasoningWith(buildJsonObject {
            put("signature", buildJsonObject { put("nested", "value") })
        })
        assertNull(part.metadataAs<ClaudeReasoningMetadata>())
    }

    // ===== 写入格式与旧 key 保持一致(新写的数据可被旧式取值读取) =====

    @Test
    fun `written metadata uses legacy keys`() {
        val claude = ClaudeReasoningMetadata(signature = "sig").toMetadata()
        assertEquals("sig", claude["signature"]?.jsonPrimitive?.content)

        val google = GoogleThoughtMetadata(thoughtSignature = "ts").toMetadata()
        assertEquals("ts", google["thoughtSignature"]?.jsonPrimitive?.content)
    }

    @Test
    fun `null fields are omitted when writing`() {
        // explicitNulls = false: null 字段不写入, 不会出现 "thoughtSignature": null
        val metadata = GoogleThoughtMetadata(thoughtSignature = null).toMetadata()
        assertFalse(metadata.containsKey("thoughtSignature"))
    }

    @Test
    fun `metadata round trip via persistence is stable`() {
        // 模拟持久化: 部件序列化为 JSON 字符串再读回, metadata 不丢失不变形
        val json = Json { ignoreUnknownKeys = true }
        val part: UIMessagePart = reasoningWith(
            GoogleThoughtMetadata(thoughtSignature = "ts-1").toMetadata()
        )
        val restored = json.decodeFromString<UIMessagePart>(json.encodeToString(part))
        assertEquals("ts-1", restored.metadataAs<GoogleThoughtMetadata>()?.thoughtSignature)
    }

    @Test
    fun `legacy json null thought signature does not survive rewrite`() {
        // 旧数据含 JsonNull -> 解析 -> 重新写出: JsonNull 被清理而非保留
        val legacy = reasoningWith(buildJsonObject { put("thoughtSignature", JsonNull) })
        val rewritten = legacy.metadataAs<GoogleThoughtMetadata>()?.toMetadata()
        assertEquals(JsonObject(emptyMap()), rewritten)
    }
}
