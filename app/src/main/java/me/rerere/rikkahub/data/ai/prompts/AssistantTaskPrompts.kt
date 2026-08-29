package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_BACKGROUND_PROMPT = """
    You are {assistant_name}. You are running in the background to check in on the user. Be casual and friendly.
    
    Recent chat history:
    {{history}}

    Memory Summary:
    {{memory_summary}}
    
    Relevant Memories:
    {{memories}}
    {last_notification_info}
    
    Do you want to send a spontaneous notification to the user right now?
    Consider the context. Only send if:
    - It's genuinely helpful or relevant
    - You have a good reason (explain it in the "reason" field)
    - It's not repetitive or annoying
    
    Output JSON format:
    {
        "send": true/false,
        "reason": "Why you want to send this notification",
        "title": "Notification Title",
        "content": "Notification Content"
    }
""".trimIndent()

internal val DEFAULT_MEMORY_CONSOLIDATION_PROMPT = """
    Analyze the following conversation and create a "Memory Episode".
    
    **Language**: Detect the primary language of the conversation (prioritize the user's messages; if mixed, follow the most recent user message). Write the "summary" in that language.
    
    {context_section}
    1. **Summary**: Concise summary of what happened (under 100 words).
    2. **Significance**: Rate the emotional impact or importance of this conversation from 1-10 (10 = life-changing, 1 = trivial).
    
    Conversation:
    {messages_text}
    
    Output JSON format (return only JSON, no extra text):
    {
        "summary": "...",
        "significance": 5
    }
""".trimIndent()

internal val DEFAULT_CONTEXT_SUMMARY_PROMPT = """
    You are updating a rolling conversation summary used to compress chat context.
    
    {previous_summary_section}
    **New Messages ({messages_count} messages):**
    {messages_text}
    
    Create an updated summary that:
    - Preserves important context from earlier messages
    - Incorporates key new information
    - Captures decisions, pending tasks, and user preferences
    - Stays under 500 words
    
    Updated Summary:
""".trimIndent()
