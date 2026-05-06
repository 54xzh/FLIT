package me.rerere.rikkahub.data.ai.tools

import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.datastore.Settings

enum class ToolSystemPromptGroup {
    Search,
    Memory,
    Local,
    Skills,
    Workspace,
    ScheduledTasks,
    Lorebooks,
    UserInteraction,
}

data class ToolSystemPromptVariable(
    val key: String,
)

data class ToolSystemPromptDefinition(
    val toolName: String,
    val group: ToolSystemPromptGroup,
    val defaultTemplate: String,
    val variables: List<ToolSystemPromptVariable> = emptyList(),
)

object ToolSystemPromptRegistry {
    val definitions: List<ToolSystemPromptDefinition> = listOf(
        ToolSystemPromptDefinition(
            toolName = "search_web",
            group = ToolSystemPromptGroup.Search,
            defaultTemplate = SEARCH_WEB_SYSTEM_PROMPT_TEMPLATE,
            variables = listOf(ToolSystemPromptVariable(SEARCH_RESULT_RULES_VARIABLE)),
        ),
        ToolSystemPromptDefinition(
            toolName = "scrape_web",
            group = ToolSystemPromptGroup.Search,
            defaultTemplate = SCRAPE_WEB_SYSTEM_PROMPT_TEMPLATE,
        ),
        ToolSystemPromptDefinition(
            toolName = "memory_search",
            group = ToolSystemPromptGroup.Memory,
            defaultTemplate = MEMORY_SEARCH_SYSTEM_PROMPT_TEMPLATE,
        ),
        ToolSystemPromptDefinition(
            toolName = "chat_search",
            group = ToolSystemPromptGroup.Memory,
            defaultTemplate = CHAT_SEARCH_SYSTEM_PROMPT_TEMPLATE,
        ),
        ToolSystemPromptDefinition(
            toolName = "eval_javascript",
            group = ToolSystemPromptGroup.Local,
            defaultTemplate = JAVASCRIPT_SYSTEM_PROMPT_TEMPLATE,
        ),
        ToolSystemPromptDefinition(
            toolName = "send_notification",
            group = ToolSystemPromptGroup.Local,
            defaultTemplate = "",
        ),
        ToolSystemPromptDefinition(
            toolName = "schedule_message",
            group = ToolSystemPromptGroup.Local,
            defaultTemplate = "",
        ),
        ToolSystemPromptDefinition(
            toolName = "get_notifications",
            group = ToolSystemPromptGroup.Local,
            defaultTemplate = "",
        ),
        ToolSystemPromptDefinition(
            toolName = "open_app",
            group = ToolSystemPromptGroup.Local,
            defaultTemplate = "",
        ),
        ToolSystemPromptDefinition(
            toolName = "set_alarm",
            group = ToolSystemPromptGroup.Local,
            defaultTemplate = "",
        ),
        ToolSystemPromptDefinition(
            toolName = "set_reminder",
            group = ToolSystemPromptGroup.Local,
            defaultTemplate = "",
        ),
        ToolSystemPromptDefinition(
            toolName = "read_skill_file",
            group = ToolSystemPromptGroup.Skills,
            defaultTemplate = READ_SKILL_FILE_SYSTEM_PROMPT_TEMPLATE,
            variables = listOf(
                ToolSystemPromptVariable(SKILL_LIST_VARIABLE),
                ToolSystemPromptVariable(SKILL_NOTE_VARIABLE),
            ),
        ),
        ToolSystemPromptDefinition(
            toolName = "run_skill_script",
            group = ToolSystemPromptGroup.Skills,
            defaultTemplate = RUN_SKILL_SCRIPT_SYSTEM_PROMPT_TEMPLATE,
            variables = listOf(ToolSystemPromptVariable(SCRIPTABLE_SKILL_LIST_VARIABLE)),
        ),
        ToolSystemPromptDefinition(
            toolName = "workspace_list",
            group = ToolSystemPromptGroup.Workspace,
            defaultTemplate = workspaceToolSystemPromptTemplate(
                toolName = "workspace_list",
                includeCommonRules = true,
            ),
            variables = listOf(ToolSystemPromptVariable(WORKSPACE_COMMON_RULES_VARIABLE)),
        ),
        ToolSystemPromptDefinition(
            toolName = "workspace_read_file",
            group = ToolSystemPromptGroup.Workspace,
            defaultTemplate = workspaceToolSystemPromptTemplate(
                toolName = "workspace_read_file",
                includeCommonRules = false,
            ),
            variables = listOf(ToolSystemPromptVariable(WORKSPACE_COMMON_RULES_VARIABLE)),
        ),
        ToolSystemPromptDefinition(
            toolName = "workspace_write_file",
            group = ToolSystemPromptGroup.Workspace,
            defaultTemplate = workspaceToolSystemPromptTemplate(
                toolName = "workspace_write_file",
                includeCommonRules = false,
            ),
            variables = listOf(ToolSystemPromptVariable(WORKSPACE_COMMON_RULES_VARIABLE)),
        ),
        ToolSystemPromptDefinition(
            toolName = "workspace_mkdir",
            group = ToolSystemPromptGroup.Workspace,
            defaultTemplate = workspaceToolSystemPromptTemplate(
                toolName = "workspace_mkdir",
                includeCommonRules = false,
            ),
            variables = listOf(ToolSystemPromptVariable(WORKSPACE_COMMON_RULES_VARIABLE)),
        ),
        ToolSystemPromptDefinition(
            toolName = "workspace_delete",
            group = ToolSystemPromptGroup.Workspace,
            defaultTemplate = workspaceToolSystemPromptTemplate(
                toolName = "workspace_delete",
                includeCommonRules = false,
            ),
            variables = listOf(ToolSystemPromptVariable(WORKSPACE_COMMON_RULES_VARIABLE)),
        ),
        ToolSystemPromptDefinition(
            toolName = "workspace_rename",
            group = ToolSystemPromptGroup.Workspace,
            defaultTemplate = workspaceToolSystemPromptTemplate(
                toolName = "workspace_rename",
                includeCommonRules = false,
            ),
            variables = listOf(ToolSystemPromptVariable(WORKSPACE_COMMON_RULES_VARIABLE)),
        ),
        ToolSystemPromptDefinition(
            toolName = "eval_python",
            group = ToolSystemPromptGroup.Workspace,
            defaultTemplate = EVAL_PYTHON_SYSTEM_PROMPT_TEMPLATE,
            variables = listOf(ToolSystemPromptVariable(WORKSPACE_COMMON_RULES_VARIABLE)),
        ),
        ToolSystemPromptDefinition(
            toolName = "list_scheduled_tasks",
            group = ToolSystemPromptGroup.ScheduledTasks,
            defaultTemplate = SCHEDULED_TASK_SYSTEM_PROMPT_TEMPLATE,
        ),
        ToolSystemPromptDefinition(
            toolName = "create_scheduled_task",
            group = ToolSystemPromptGroup.ScheduledTasks,
            defaultTemplate = "",
        ),
        ToolSystemPromptDefinition(
            toolName = "update_scheduled_task",
            group = ToolSystemPromptGroup.ScheduledTasks,
            defaultTemplate = "",
        ),
        ToolSystemPromptDefinition(
            toolName = "delete_scheduled_task",
            group = ToolSystemPromptGroup.ScheduledTasks,
            defaultTemplate = "",
        ),
        ToolSystemPromptDefinition(
            toolName = "lorebooks_list_enabled",
            group = ToolSystemPromptGroup.Lorebooks,
            defaultTemplate = LOREBOOK_SYSTEM_PROMPT_TEMPLATE,
        ),
        ToolSystemPromptDefinition(
            toolName = "lorebooks_entry_list",
            group = ToolSystemPromptGroup.Lorebooks,
            defaultTemplate = "",
        ),
        ToolSystemPromptDefinition(
            toolName = "lorebooks_entry_create",
            group = ToolSystemPromptGroup.Lorebooks,
            defaultTemplate = "",
        ),
        ToolSystemPromptDefinition(
            toolName = "lorebooks_entry_update",
            group = ToolSystemPromptGroup.Lorebooks,
            defaultTemplate = "",
        ),
        ToolSystemPromptDefinition(
            toolName = "lorebooks_entry_delete",
            group = ToolSystemPromptGroup.Lorebooks,
            defaultTemplate = "",
        ),
        ToolSystemPromptDefinition(
            toolName = "lorebooks_history_list",
            group = ToolSystemPromptGroup.Lorebooks,
            defaultTemplate = "",
        ),
        ToolSystemPromptDefinition(
            toolName = "lorebooks_history_undo",
            group = ToolSystemPromptGroup.Lorebooks,
            defaultTemplate = "",
        ),
        ToolSystemPromptDefinition(
            toolName = "ask_user",
            group = ToolSystemPromptGroup.UserInteraction,
            defaultTemplate = ASK_USER_SYSTEM_PROMPT_TEMPLATE,
        ),
    )

    private val definitionsByName = definitions.associateBy { it.toolName }

    fun get(toolName: String): ToolSystemPromptDefinition? = definitionsByName[toolName]
}

const val SEARCH_RESULT_RULES_VARIABLE = "search_result_rules"
const val SKILL_LIST_VARIABLE = "skill_list"
const val SKILL_NOTE_VARIABLE = "skill_note"
const val SCRIPTABLE_SKILL_LIST_VARIABLE = "scriptable_skill_list"
const val WORKSPACE_COMMON_RULES_VARIABLE = "workspace_common_rules"

fun buildSearchWebPromptVariables(
    messages: List<UIMessage>,
    includeProviderErrors: Boolean,
): Map<String, String> {
    val hasToolCall = messages.any { message ->
        message.getToolCalls().any { toolCall -> toolCall.toolName == "search_web" }
    }
    return mapOf(
        SEARCH_RESULT_RULES_VARIABLE to if (hasToolCall) {
            searchWebResultRules(includeProviderErrors)
        } else {
            ""
        }
    )
}

private fun searchWebResultRules(includeProviderErrors: Boolean): String {
    val errorsExample = if (includeProviderErrors) {
        """,
            "errors": [
                { "provider": "Tavily", "message": "error message" }
            ]"""
    } else {
        ""
    }

    return """
        ### result example
        ```json
        {
            "items": [
                {
                    "id": "random id in 6 characters",
                    "title": "Title",
                    "url": "https://example.com",
                    "text": "Some relevant snippets"
                }
            ]$errorsExample
        }
        ```

        ### citation
        After using the search tool, when replying to users, you need to add a reference format to the referenced search terms in the content.
        When citing facts or data from search results, you need to add a citation marker after the sentence: `[citation,domain](id of the search result)`.

        For example:
        ```
        The capital of France is Paris. [citation,example.com](id of the search result)

        The population of Paris is about 2.1 million. [citation,example.com](id of the search result) [citation,example2.com](id of the search result)
        ```

        If no search results are cited, you do not need to add a citation marker.
    """.trimIndent()
}

val JAVASCRIPT_SYSTEM_PROMPT_TEMPLATE = """
    ## tool: eval_javascript

    ### usage
    - Execute JavaScript code with QuickJS.
    - When using this tool for math that needs stable decimal output, format numbers explicitly, for example with `toFixed`.
""".trimIndent()

val MEMORY_SEARCH_SYSTEM_PROMPT_TEMPLATE = """
    ## tool: memory_search

    ### usage
    - Search saved memories when the user references something not provided in the current context, such as names, dates, or prior decisions.
    - Use this tool before guessing about saved memories.
    - Do not use this tool for the current conversation or general knowledge.

    ### query rules
    - Within one query string, spaces mean AND: every term must match.
    - Multiple query strings mean OR: any one query can match.
    - Wrap a phrase with double quotes to keep it as one term.
""".trimIndent()

val CHAT_SEARCH_SYSTEM_PROMPT_TEMPLATE = """
    ## tool: chat_search

    ### usage
    - Search past conversations when the user refers to a previous discussion, asks "did we talk about X", or needs context from an older conversation.
    - Do not use this tool for the current conversation. It is only for past conversations.

    ### query rules
    - Within one query string, spaces mean AND: every term must match.
    - Multiple query strings mean OR: any one query can match.
    - Wrap a phrase with double quotes to keep it as one term.
""".trimIndent()

val SEARCH_WEB_SYSTEM_PROMPT_TEMPLATE = """
    ## tool: search_web

    ### usage
    - You can use the search_web tool to search the internet for the latest news or to confirm some facts.
    - You can perform multiple search if needed
    - Generate keywords based on the user's question
    - Today is {{cur_date}}

    {{search_result_rules}}
""".trimIndent()

val SCRAPE_WEB_SYSTEM_PROMPT_TEMPLATE = """
    ## tool: scrape_web

    ### usage
    - You can use the scrape_web tool to scrape url for detailed content.
    - You can perform multiple scrape if needed.
    - For common problems, try not to use this tool unless the user requests it.
""".trimIndent()

val SCHEDULED_TASK_SYSTEM_PROMPT_TEMPLATE = """
    ## tool: scheduled tasks

    ### usage
    - Use scheduled task tools to list, create, update, or delete tasks that belong to the current assistant.
    - When updating a task, only provide fields that should change.

    ### repeat rules
    - `repeat_type` values are `once`, `daily`, `weekly`, `monthly`, and `interval`.
    - For `once`, `daily`, `weekly`, and `monthly`, provide `time_of_day` in `HH:mm`.
    - For `weekly`, provide `weekly_days` as `mon`, `tue`, `wed`, `thu`, `fri`, `sat`, or `sun`.
    - For `monthly`, provide `monthly_day` from 1 to 28, or -1 for the last day of month.
    - For `interval`, provide `interval_value` and `interval_unit` (`hours` or `days`).

    ### prompt template
    - Write `prompt_template` from the user's perspective, as if the user is sending a message to the assistant.
    - Good: "Please send me today's weather summary."
    - Bad: "Send the user a weather summary."
""".trimIndent()

val LOREBOOK_SYSTEM_PROMPT_TEMPLATE = """
    ## tool: lorebooks

    ### usage
    - Lorebook tools can only access lorebooks enabled for the current assistant in the current chat.
    - If you are unsure which lorebook to use, call `lorebooks_list_enabled` first.
    - `lorebooks_entry_update` is a patch tool. Only provide fields that should change.
    - Deleted entries can be recovered with `lorebooks_history_undo`.
    - Use history tools to inspect recent tool revisions or undo a mistaken change.
""".trimIndent()

val ASK_USER_SYSTEM_PROMPT_TEMPLATE = """
    ## tool: ask_user

    ### usage
    - Ask the user instead of guessing when intent is ambiguous, a decision has multiple valid paths, an action is irreversible, or needed information is only known by the user.
    - Do not proceed on your own when uncertain. Stop and ask.
    - You can ask multiple questions at once by providing the `questions` array. The user will answer them one by one.
    - Use the single `question` and `options` fields only when you have one question.
    - Provide 2 to 4 clear options for each question.
""".trimIndent()

val READ_SKILL_FILE_SYSTEM_PROMPT_TEMPLATE = """
    ## skill tools (skills list)

    ### skills
    {{skill_list}}

    ### note
    {{skill_note}}

    ## tool: read_skill_file

    ### rules
    - Always load a skill's SKILL.md before using it.
    - Never invent skill contents; use this tool to read files.
""".trimIndent()

val RUN_SKILL_SCRIPT_SYSTEM_PROMPT_TEMPLATE = """
    ## tool: run_skill_script

    ### rules
    - `skill_name` MUST be a skill marked `[script]` in the skills list (or pass `skill_id`).
    - `skill_name` is a Skill package name, NOT a workspace path. Do NOT use placeholders like "." or "/".
    - The script path must be under `scripts/` and end with `.py`.
    - Requires a user-authorized workspace folder.
    - Scripts run with the working directory set to the current conversation's workspace folder.
    - Prefer reading SKILL.md / script source via `read_skill_file` before running.
    - If the script is CLI-style (no run(input)), pass `argv` (e.g., ["--help"]) to run it.
""".trimIndent()

val WORKSPACE_COMMON_RULES_PROMPT = """
    ## workspace tools (common rules)

    ### scope
    - Operates only within the current conversation workspace directory under the user-authorized workspace root.
    - All paths are relative to the conversation workspace directory.

    ### path rules
    - Use relative paths with `/` separators (example: `folder/file.txt`).
    - Do NOT use absolute paths (no leading `/`) and do NOT use `..`.
    - Root directory is represented by an empty string "" when allowed by the tool (e.g. `workspace_list`).

    ### parameter naming
    - Use the exact parameter keys from the schema (usually snake_case, e.g. `max_entries`, `max_chars`).

    ### setup
    - If you see an error like "Workspace root is not set", ask the user to set the default root in Settings -> Skills, or authorize a root folder for this conversation in Work directory settings.
""".trimIndent()

fun workspaceToolSystemPromptTemplate(
    toolName: String,
    includeCommonRules: Boolean,
): String {
    val examples = when (toolName) {
        "workspace_list" -> """
            ### examples
            - List workspace root: {"path":"","recursive":false}
            - List a folder: {"path":"docs","recursive":true}
        """.trimIndent()

        "workspace_read_file" -> """
            ### examples
            - Read a file: {"path":"README.md"}
        """.trimIndent()

        "workspace_write_file" -> """
            ### examples
            - Write a file: {"path":"notes.txt","content":"hello"}
        """.trimIndent()

        "workspace_mkdir" -> """
            ### examples
            - Create a folder: {"path":"output","parents":true}
        """.trimIndent()

        "workspace_delete" -> """
            ### examples
            - Delete a file: {"path":"output/old.txt","recursive":false}
        """.trimIndent()

        "workspace_rename" -> """
            ### examples
            - Rename/move: {"from":"a.txt","to":"archive/a.txt","create_parents":true}
        """.trimIndent()

        else -> ""
    }

    return buildString {
        if (includeCommonRules) {
            appendLine("{{workspace_common_rules}}")
            appendLine()
        }
        appendLine("## tool: $toolName")
        if (examples.isNotBlank()) {
            appendLine()
            appendLine(examples)
        }
    }.trimEnd()
}

val EVAL_PYTHON_SYSTEM_PROMPT_TEMPLATE = """
    {{workspace_common_rules}}

    ## tool: eval_python

    ### execution
    - The Python code runs locally via Chaquopy.
    - Requires a user-authorized workspace folder.
    - The working directory is the current conversation workspace directory.
    - Prefer a `run(input: dict)` entrypoint and return JSON-serializable data.
    - Use print() for logs; stdout/stderr will be returned.
    - Avoid network access and avoid reading/writing files unless explicitly requested by the user.
""".trimIndent()

fun renderToolSystemPromptTemplate(
    template: String,
    variables: Map<String, String>,
): String {
    var result = template
    variables.forEach { (key, value) ->
        result = result
            .replace(oldValue = "{{$key}}", newValue = value, ignoreCase = true)
            .replace(oldValue = "{$key}", newValue = value, ignoreCase = true)
    }
    return result.trim()
}

fun Tool.renderConfiguredSystemPrompt(
    settings: Settings,
    model: Model,
    messages: List<UIMessage>,
): String {
    val customTemplate = settings.customToolSystemPrompts[name]
    return if (customTemplate != null) {
        renderToolSystemPromptTemplate(
            template = customTemplate,
            variables = systemPromptVariables(model, messages),
        )
    } else {
        systemPrompt(model, messages)
    }
}
