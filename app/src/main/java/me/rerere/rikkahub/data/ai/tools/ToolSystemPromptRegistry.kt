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
    val affectedToolNames: List<String> = listOf(toolName),
    val injectedByToolName: String? = toolName,
)

object ToolSystemPromptRegistry {
    val definitions: List<ToolSystemPromptDefinition> = listOf(
        ToolSystemPromptDefinition(
            toolName = "search_web",
            group = ToolSystemPromptGroup.Search,
            defaultTemplate = SEARCH_WEB_SYSTEM_PROMPT_TEMPLATE,
        ),
        ToolSystemPromptDefinition(
            toolName = "scrape_web",
            group = ToolSystemPromptGroup.Search,
            defaultTemplate = SCRAPE_WEB_SYSTEM_PROMPT_TEMPLATE,
        ),
        ToolSystemPromptDefinition(
            toolName = "search_agent",
            group = ToolSystemPromptGroup.Search,
            defaultTemplate = SEARCH_AGENT_MAIN_TOOL_PROMPT_TEMPLATE,
        ),
        ToolSystemPromptDefinition(
            toolName = MEMORY_MANAGEMENT_TOOL_NAME,
            group = ToolSystemPromptGroup.Memory,
            defaultTemplate = MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE,
            variables = listOf(ToolSystemPromptVariable(MEMORY_CONTEXT_VARIABLE)),
            affectedToolNames = MEMORY_MANAGEMENT_AFFECTED_TOOL_NAMES,
            injectedByToolName = null,
        ),
        ToolSystemPromptDefinition(
            toolName = SESSION_MEMORY_MANAGEMENT_TOOL_NAME,
            group = ToolSystemPromptGroup.Memory,
            defaultTemplate = SESSION_MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE,
            variables = listOf(ToolSystemPromptVariable(SESSION_MEMORY_CONTEXT_VARIABLE)),
            affectedToolNames = SESSION_MEMORY_MANAGEMENT_AFFECTED_TOOL_NAMES,
            injectedByToolName = null,
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
            toolName = "get_time",
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
            toolName = SCHEDULED_TASKS_MANAGEMENT_TOOL_NAME,
            group = ToolSystemPromptGroup.ScheduledTasks,
            defaultTemplate = SCHEDULED_TASK_SYSTEM_PROMPT_TEMPLATE,
            affectedToolNames = SCHEDULED_TASKS_MANAGEMENT_AFFECTED_TOOL_NAMES,
            injectedByToolName = "list_scheduled_tasks",
        ),
        ToolSystemPromptDefinition(
            toolName = LOREBOOKS_MANAGEMENT_TOOL_NAME,
            group = ToolSystemPromptGroup.Lorebooks,
            defaultTemplate = LOREBOOK_SYSTEM_PROMPT_TEMPLATE,
            affectedToolNames = LOREBOOKS_MANAGEMENT_AFFECTED_TOOL_NAMES,
            injectedByToolName = "lorebooks_list_enabled",
        ),
        ToolSystemPromptDefinition(
            toolName = "ask_user",
            group = ToolSystemPromptGroup.UserInteraction,
            defaultTemplate = ASK_USER_SYSTEM_PROMPT_TEMPLATE,
        ),
    )

    private val definitionsByName = definitions.associateBy { it.toolName }
    private val definitionsByInjectedToolName = definitions
        .mapNotNull { definition ->
            definition.injectedByToolName?.let { toolName -> toolName to definition }
        }
        .toMap()

    fun get(toolName: String): ToolSystemPromptDefinition? = definitionsByName[toolName]

    fun getInjectedDefinition(toolName: String): ToolSystemPromptDefinition? = definitionsByInjectedToolName[toolName]
}

const val MEMORY_MANAGEMENT_TOOL_NAME = "memory_management"
const val SESSION_MEMORY_MANAGEMENT_TOOL_NAME = "session_memory_management"
const val SCHEDULED_TASKS_MANAGEMENT_TOOL_NAME = "scheduled_tasks_management"
const val LOREBOOKS_MANAGEMENT_TOOL_NAME = "lorebooks_management"
const val MEMORY_CONTEXT_VARIABLE = "memory_context"
const val SESSION_MEMORY_CONTEXT_VARIABLE = "session_memory_context"
const val SKILL_LIST_VARIABLE = "skill_list"
const val SKILL_NOTE_VARIABLE = "skill_note"
const val SCRIPTABLE_SKILL_LIST_VARIABLE = "scriptable_skill_list"
const val WORKSPACE_COMMON_RULES_VARIABLE = "workspace_common_rules"

val MEMORY_MANAGEMENT_AFFECTED_TOOL_NAMES = listOf(
    "create_memory",
    "edit_memory",
    "delete_memory",
)

val SESSION_MEMORY_MANAGEMENT_AFFECTED_TOOL_NAMES = listOf(
    "create_session_memory",
    "edit_session_memory",
    "delete_session_memory",
)

val SCHEDULED_TASKS_MANAGEMENT_AFFECTED_TOOL_NAMES = listOf(
    "list_scheduled_tasks",
    "create_scheduled_task",
    "update_scheduled_task",
    "delete_scheduled_task",
)

val LOREBOOKS_MANAGEMENT_AFFECTED_TOOL_NAMES = listOf(
    "lorebooks_list_enabled",
    "lorebooks_entry_list",
    "lorebooks_entry_create",
    "lorebooks_entry_update",
    "lorebooks_entry_delete",
    "lorebooks_history_list",
    "lorebooks_history_undo",
)

fun searchWebToolResultGuidance(includeProviderErrors: Boolean): String {
    val errorsNote = if (includeProviderErrors) {
        " The `errors` field lists providers that failed; do not cite provider errors as sources."
    } else {
        ""
    }

    return (
        "When citing facts or data from search results, add a citation marker after the sentence: " +
            "`[citation,domain](id)`, where `id` is the result item's id field. " +
            "If no search results are cited, do not add citation markers." +
            errorsNote
        )
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
""".trimIndent()

val SCRAPE_WEB_SYSTEM_PROMPT_TEMPLATE = """
    ## tool: scrape_web

    ### usage
    - You can use the scrape_web tool to scrape url for detailed content.
    - You can perform multiple scrape if needed.
    - For common problems, try not to use this tool unless the user requests it.
""".trimIndent()

val SEARCH_AGENT_MAIN_TOOL_PROMPT_TEMPLATE = """
    ## tool: search_agent

    ### usage
    - Use search_agent first for web search or webpage reading.
    - Use search_agent for latest/current information, time-sensitive facts, or to confirm facts from the web.
    - Use the app-provided current date as the recency reference when judging latest or recent information.
    - Put the full requirement into a single `task` with only necessary context.
    - Do not issue multiple `search_agent` calls in the same turn.
    - If URLs are already known, pass them in urls.
    - Use search_web or scrape_web directly only when you need raw results, full page content, or search_agent is unavailable.
    - When answering, only cite sources returned by search_agent.
""".trimIndent()

val MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE = """
    ## Memories
    {{memory_context}}

    ## Memory Tool
    You are a stateless large language model; you **cannot store memories** internally. To remember information, you must use **memory tools**.
    Memory tools allow you (the assistant) to store multiple pieces of information (records) to recall details across conversations.
    You can use the `create_memory`, `edit_memory`, and `delete_memory` tools to create, update, or delete memories.
    - If there is no relevant information in memory, call `create_memory` to create a new record.
    - If a relevant record already exists, call `edit_memory` to update it.
    - If a memory is outdated or no longer useful, call `delete_memory` to remove it.
    **Note:** You can only edit or delete **Core Memories** (which have an ID). Episodic Memories are read-only context.

    **Do not store sensitive information.** Sensitive information includes: ethnicity, religious beliefs, sexual orientation, political views, sexual life, criminal records, etc.
    During chats, act like a personal secretary and **proactively** record user-related information, including but not limited to:
    - Name/Nickname
    - Age/Gender/Hobbies
    - Plans/To-do items
""".trimIndent()

val SESSION_MEMORY_MANAGEMENT_SYSTEM_PROMPT_TEMPLATE = """
    ## Session Memories
    {{session_memory_context}}

    ## Session Memory Tool
    You can use `create_session_memory`, `edit_session_memory`, and `delete_session_memory` to manage details that should stay active in this conversation only.
    `create_session_memory` requires a `placement` value:
    - `SYSTEM_PROMPT_AFTER`: for stable session memories that are long, important, and unlikely to change often.
    - `BEFORE_LATEST_MESSAGE`: for short, temporary, frequently changing, or uncertain memories.
    When unsure, choose `BEFORE_LATEST_MESSAGE`.
    When editing a session memory, keep its current placement unless the updated memory clearly fits the other position better.
    Use session memory tools sparingly. Save a detail only when it is important for the rest of this conversation, such as settings, outlines, requirements, constraints, or important decisions.
    Do not save ordinary chat history, casual comments, temporary wording, guesses, or details already obvious from the latest user message.
    Prefer editing an existing session memory over creating a duplicate. Delete a session memory when it is wrong or no longer useful.
    Use long-term memory tools only for information that should help in future conversations. Use session memory tools for details that matter only in this conversation.
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
    - Use this tool sparingly. Ask the user only when missing information blocks the task, the choice would meaningfully change the result, or the action is risky or hard to undo.
    - For low-risk ambiguity, make a reasonable assumption, state it briefly, and continue.
    - Do not ask for information that can be inferred from the conversation, current context, or safe defaults.
    - If several details are truly needed, ask them together with the `questions` array. The user will answer them one by one.
    - Use the single `question` and `options` fields only when you have one question.
    - Provide 2 to 4 clear, distinct options for each question.
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

fun renderConfiguredToolSystemPrompt(
    settings: Settings,
    key: String,
    defaultTemplate: String,
    variables: Map<String, String> = emptyMap(),
): String {
    val template = settings.customToolSystemPrompts[key] ?: defaultTemplate
    return renderToolSystemPromptTemplate(
        template = template,
        variables = variables,
    )
}

fun Tool.renderConfiguredSystemPrompt(
    settings: Settings,
    model: Model,
    messages: List<UIMessage>,
): String {
    val injectedDefinition = ToolSystemPromptRegistry.getInjectedDefinition(name)
    return if (injectedDefinition != null && injectedDefinition.toolName != name) {
        renderConfiguredToolSystemPrompt(
            settings = settings,
            key = injectedDefinition.toolName,
            defaultTemplate = injectedDefinition.defaultTemplate,
            variables = systemPromptVariables(model, messages),
        )
    } else {
        val directDefinition = ToolSystemPromptRegistry.get(name)
        if (directDefinition != null) {
            renderConfiguredToolSystemPrompt(
                settings = settings,
                key = directDefinition.toolName,
                defaultTemplate = systemPrompt(model, messages),
                variables = systemPromptVariables(model, messages),
            )
        } else {
            systemPrompt(model, messages)
        }
    }
}
