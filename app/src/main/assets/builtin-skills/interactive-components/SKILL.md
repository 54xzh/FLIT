---
name: interactive-components
description: Create interactive components in chat.
---

# Interactive Components

Use components when the user needs to choose options, enter information, or adjust values.
Keep simple answers as text. Write visible labels in the user's language.

Output A2UI v0.9.1 JSON Lines inside a fenced block labeled `a2ui`, directly in your reply.
Use the supported catalogId supplied by the app. Each block describes one surface and must
start with `createSurface`, define an `id: "root"` component, and initialize input data.
Include a short explanation outside the block. Use clear, labeled submit buttons.

Input changes stay local. Only an explicit event submits user input back to this conversation.
To update a submitted interface, output a new complete block with the same surfaceId in
your next reply. Previous versions remain read-only. Do not output update-only blocks.

Read only the references needed for your task using `read_skill_file` with
`skill_name: "interactive-components"` and `source: "builtin"`:
- [Protocol](references/protocol.md): message format, lifecycle, and streaming rules.
- [Components](references/components.md): supported components, properties, and bindings.
- [Interactions](references/interactions.md): validation, submission, and follow-up replies.
- [Examples](references/examples.md): complete selection, form, and slider examples.
