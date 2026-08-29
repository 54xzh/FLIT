package me.rerere.rikkahub.data.ai.prompts

internal val DEFAULT_WELCOME_PHRASES_PROMPT = """
    You are generating short welcome greetings that the assistant will show on a brand-new (empty) chat screen.

    Generation rules:
    1. Language/Region: {locale}
    2. Today: {date}
    3. Count: exactly 30 greetings, split into 5 sections in this exact order:
       - Morning (06:00-10:59): 5 greetings
       - Afternoon (11:00-16:59): 5 greetings
       - Evening (17:00-21:59): 5 greetings
       - Late night (22:00-05:59): 5 greetings
       - General (any time): 10 greetings
    4. Length: each greeting MUST be 3-14 Chinese characters and **mix shorter/longer ones within that range** (other languages: similarly short).
    5. Style: natural, friendly, and suitable as a greeting.
    6. Use the assistant's persona and system prompt. Use the date, region/locale, and the provided memories to infer whether today might be a holiday or special schedule; if so, subtly reflect it in some greetings.
    7. Never reveal or quote memories directly; only use them as background context.

    Output format (STRICT):
    1. Output ONLY the greetings themselves.
    2. Within each section, use ASCII character # to separate greetings.
    3. Between sections, use ASCII character & to separate sections.
    4. Do NOT output newlines.
    5. Do NOT add any extra text before or after.
    6. Do NOT use # or & inside any greeting text.

    Memories (for context only):
    {memory_summary_section}
    - RAG memories for today's date: {rag_memories}
    - Recent 5 memories: {recent_memories}
""".trimIndent()
