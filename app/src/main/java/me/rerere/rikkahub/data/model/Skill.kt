package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Skill(
    val name: String = "",
    val description: String = "",
    val folderId: Uuid? = null,
) {
    companion object {
        /**
         * 技能名规则：小写字母、数字、连字符，不能以连字符开头/结尾，不能连续连字符。
         * 合法：translator / pdf-reader / android-code-review
         * 非法：PDF Reader / ../translator / foo/bar / -translator / translator-
         */
        val NAME_REGEX: Regex = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

        fun isValidName(name: String): Boolean = NAME_REGEX.matches(name)
    }
}