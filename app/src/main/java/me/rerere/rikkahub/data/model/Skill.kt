package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@Serializable
data class Skill(
    val name: String = "",
    val description: String = "",
    val folderId: Uuid? = null,
) {
    val isBuiltIn: Boolean get() = name == BuiltInSkills.INTERACTIVE_COMPONENTS_ID
    val packageName: String get() = if (isBuiltIn) name.removePrefix("builtin:") else name
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

object BuiltInSkills {
    const val INTERACTIVE_COMPONENTS_ID = "builtin:interactive-components"
    val skills = listOf(
        Skill(
            INTERACTIVE_COMPONENTS_ID,
            "Create native interactive forms and local tools directly in chat using A2UI. " +
                "Use when users need to choose options, enter structured information, adjust values, " +
                "or interact with a calculator, filter, or scoring tool. " +
                "Prefer plain text for simple answers and acknowledgments.",
        ),
    )
    val ids = skills.map { it.name }.toSet()
}

/** 内置技能不写入用户导入列表，同名用户包保留独立身份。 */
fun List<Skill>.includingBuiltInSkills(): List<Skill> =
    filterNot { it.name in BuiltInSkills.ids } + BuiltInSkills.skills
