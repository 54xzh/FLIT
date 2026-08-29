package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_INCREMENTAL_MEMORY_SUMMARY_PROMPT = """
    You maintain a long-term memory summary for an AI assistant.

    Current date:
    {current_date}

    Previous memory summary:
    <previous_summary>
    {previous_summary}
    </previous_summary>

    New memories added since the previous successful update:
    <new_memories>
    {new_memories}
    </new_memories>

    Update the previous summary using the new memories and return the complete updated summary.

    Requirements:
    - Preserve useful information from the previous summary unless a newer memory replaces it.
    - Use memory timestamps to judge whether an event is recent, ongoing, or historical.
    - Judge relevance from the content and timestamps.
    - When memories conflict, prefer the newer explicit memory.
    - If the conflict cannot be resolved, omit the information.
    - Include only information supported by the provided memories.
    - Use Markdown headings and bullet points.
    - Omit empty sections.
    - Do not include an introduction, explanation, or closing paragraph.
    - Write in the language used by the user. If languages are mixed, prefer the language of the newer memories.
    - Keep the summary between 400 and 800 words.

    Useful sections may include:
    - Personal Profile
    - Preferences
    - Long-term Goals
    - Current Focus
    - Recent Important Events
    - Important People and Relationships

    Return only the complete Markdown summary.
""".trimIndent()

internal val DEFAULT_FULL_MEMORY_SUMMARY_PROMPT = """
    You maintain a long-term memory summary for an AI assistant.

    Current date:
    {current_date}

    Previous memory summary:
    <previous_summary>
    {previous_summary}
    </previous_summary>

    Current complete memory library:
    <all_memories>
    {all_memories}
    </all_memories>

    Rebuild the complete memory summary from the current memory library.

    Requirements:
    - Use the previous summary only to preserve useful wording and continuity.
    - Do not retain information supported only by the previous summary.
    - Use memory timestamps to judge whether an event is recent, ongoing, or historical.
    - Judge relevance from the content and timestamps.
    - When memories conflict, prefer the newer explicit memory.
    - If the conflict cannot be resolved, omit the information.
    - Include only information supported by the current memory library.
    - Use Markdown headings and bullet points.
    - Omit empty sections.
    - Do not include an introduction, explanation, or closing paragraph.
    - Write in the language used by the user. If languages are mixed, prefer the language of the newer memories.
    - Keep the summary between 400 and 800 words.

    Useful sections may include:
    - Personal Profile
    - Preferences
    - Long-term Goals
    - Current Focus
    - Recent Important Events
    - Important People and Relationships

    Return only the complete Markdown summary.
""".trimIndent()

internal val DEFAULT_REBUILD_MEMORY_SUMMARY_PROMPT = """
    You maintain a long-term memory summary for an AI assistant.

    Current date:
    {current_date}

    Current complete memory library:
    <all_memories>
    {all_memories}
    </all_memories>

    Rebuild the complete memory summary from the current memory library.

    Requirements:
    - Do not use or rely on any previous memory summary.
    - Use memory timestamps to judge whether an event is recent, ongoing, or historical.
    - Judge relevance from the content and timestamps.
    - When memories conflict, prefer the newer explicit memory.
    - If the conflict cannot be resolved, omit the information.
    - Include only information supported by the current memory library.
    - Use Markdown headings and bullet points.
    - Omit empty sections.
    - Do not include an introduction, explanation, or closing paragraph.
    - Write in the language used by the user. If languages are mixed, prefer the language of the newer memories.
    - Keep the summary between 400 and 800 words.

    Useful sections may include:
    - Personal Profile
    - Preferences
    - Long-term Goals
    - Current Focus
    - Recent Important Events
    - Important People and Relationships

    Return only the complete Markdown summary.
""".trimIndent()
