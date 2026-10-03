---
name: interactive-components
description: Create interactive components in chat.
---

# Interactive Components

Use components when the user needs to choose options, enter information, adjust values,
or use a local calculator, filter, linked form, or scoring tool.
Keep simple answers as text. Write visible labels in the user's language.

Output A2UI v0.9.1 JSON Lines inside a fenced block labeled `a2ui`, directly in your reply.
Use the supported catalogId supplied by the app. Each block describes one surface and must
start with `createSurface`, define an `id: "root"` component, and initialize input data.
Include a short explanation outside the block. Use clear, labeled action buttons.
Purely local tools do not need a submit button. Add one only when assistant analysis is needed.
Emit the root and layout early, then add components in small batches in visual order.
Initialize bound data early so inputs can appear as their descriptions arrive.

Input changes and script results stay local. Only an explicit event submits user input back to this conversation.
Script tools submit once, then remain locally editable; later adjustments are not sent to the assistant.
Ordinary forms lock after submission. All previous versions remain read-only.
After submission, answer using the submitted values. Do not repeat the interface just to
confirm receipt or show its submitted state. Output another block only when the user needs
to make a further choice, enter more information, or adjust values. For a necessary update,
use a new complete block with the same surfaceId. Previous versions remain read-only.
Do not output update-only blocks.

Read only the references needed for your task using `read_skill_file` with
`skill_name: "interactive-components"` and `source: "builtin"`:
- [Protocol](references/protocol.md): message format, lifecycle, and streaming rules.
- [Components](references/components.md): supported components, properties, and bindings.
- [Interactions](references/interactions.md): validation, submission, and follow-up replies.
- [Examples](references/examples.md): complete selection, form, and slider examples.
- [Scripts](references/scripts.md): local QuickJS handlers, button actions, and automatic input updates.
- [Script examples](references/script-examples.md): complete budget, filtering, and scoring tools.
