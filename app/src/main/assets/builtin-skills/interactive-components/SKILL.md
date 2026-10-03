---
name: interactive-components
description: >-
  Create native interactive forms and local tools directly in chat using A2UI.
  Use when users need to choose options, enter structured information, adjust
  values, or interact with a calculator, filter, or scoring tool.
  Prefer plain text for simple answers and acknowledgments.
---

# Interactive Components

Use components when the user needs to choose options, enter information, adjust values,
or use a local calculator, filter, linked form, or scoring tool.
Keep simple answers as text. Write visible labels in the user's language.

## Basic format

Output A2UI v0.9.1 JSON Lines inside an `a2ui` fenced block directly in your reply:
one compact JSON object per line, with `version: "v0.9.1"` and exactly one message key.
Each block describes one surface. Use the app-supplied catalogId; never invent one.
For a new form, use this order:

1. `createSurface` with `surfaceId` and `catalogId`.
2. `updateDataModel` with the same `surfaceId`, `path: "/"`, and an object `value` containing initial fields.
3. `updateComponents` with the same `surfaceId` and a flat `components` array.

Every component has `id` and `component`. Reference children by ID; define an `id: "root"`
component, usually a Column. Card wrapping is optional. All child references must resolve
when the block finishes. For simple forms, use one updateComponents array, with root/layout
first and children in display order. Put a button's label before the button. The app previews
complete component objects while the array streams; separate small batches are not required.
Include a short explanation outside the block. App styling and spacing are automatic.

## Common components and bindings

| Component | Essential properties |
| --- | --- |
| Column / Row | `children`: array of component IDs |
| Text | `text`: string or `{ "path": "/field" }` |
| Button | `child`: label component ID; `variant`: primary, default, borderless; `action` |
| TextField | `label`; `value`: bound string; `variant`: shortText, longText, number, obscured |
| ChoicePicker | `label`; `options`: `{ "label": "…", "value": "…" }` objects; bound array `value`; `variant`: mutuallyExclusive or multipleSelection |
| CheckBox | `label`; `value`: bound boolean |
| Slider | `label`, numeric `min`/`max`; `value`: bound number |

Bind editable values with `{ "path": "/field" }` and initialize those fields before components.
TextField values are strings (also for number inputs); ChoicePicker values are arrays of
strings, even for a single selection. Keep the data model root an object, with named fields.
For a required field, add `checks` to that input component:
`[{ "condition": { "call": "required", "args": { "value": { "path": "/name" } } }, "message": "Enter your name" }]`.
Localize the message. The app rechecks fields on submission. Button checks are optional
for early disabling or extra conditions; read Components for advanced properties and checks.

## Submission and minimal example

An event button sends current values to the assistant:
`"action": { "event": { "name": "submit_preferences", "context": { "Travel mode": { "path": "/mode" } } } }`.
Use meaningful event names, localized context keys, and include every field needed for the answer.
Use clear, labeled action buttons. Purely local tools do not need a submit button;
add one only when assistant analysis is needed.

Replace `<supported catalogId>` with the app-supplied identifier and localize visible text:

```a2ui
{"version":"v0.9.1","createSurface":{"surfaceId":"preferences","catalogId":"<supported catalogId>"}}
{"version":"v0.9.1","updateDataModel":{"surfaceId":"preferences","path":"/","value":{"mode":["train"]}}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"preferences","components":[{"id":"root","component":"Column","children":["mode","submit"]},{"id":"mode","component":"ChoicePicker","label":"Travel mode","variant":"mutuallyExclusive","options":[{"label":"Train","value":"train"},{"label":"Car","value":"car"}],"value":{"path":"/mode"}},{"id":"submit-label","component":"Text","text":"Submit preferences"},{"id":"submit","component":"Button","child":"submit-label","variant":"primary","action":{"event":{"name":"submit_preferences","context":{"Travel mode":{"path":"/mode"}}}}}]}}
```

## Stable layout and follow-up replies

Keep the component tree stable and use only the components the task needs. Avoid unnecessary
temporary components for decoration, acknowledgments, success notices, or intermediate states.
Keep necessary result/feedback components present from the start with short initial text;
update their bound values instead of adding/removing components. Keep changing text concise
to reduce wrapping and height changes. Avoid placeholder interfaces that are later replaced,
or repeatedly redefining already emitted layouts. Use dynamic lists and tabs with substantially
different content heights only when needed. Do not invent height, visibility, or spacer properties.

Input changes and script results stay local. Only an explicit event submits user input back to this conversation.
Script tools submit once, then remain locally editable; later adjustments are not sent to the assistant.
Ordinary forms lock after submission. All previous versions remain read-only.
After submission, answer using the submitted values. Do not repeat the interface just to
confirm receipt or show its submitted state. Output another block only when the user needs
to make a further choice, enter more information, or adjust values. For a necessary update,
use a new complete block with the same surfaceId, latest data, and all components. Earlier
messages remain historical read-only versions. Never send just an update patch in a later
message; multiple update lines within the current complete block are allowed.

## Advanced references

The basics above are sufficient for ordinary forms; no reference reads are required.
For advanced tasks, read only the needed references using `read_skill_file` with
`skill_name: "interactive-components"` and `source: "builtin"`:

- [Protocol](references/protocol.md): message format, lifecycle, and streaming rules.
- [Components](references/components.md): supported components, properties, and bindings.
- [Interactions](references/interactions.md): validation, submission, and follow-up replies.
- [Examples](references/examples.md): complete selection, form, and slider examples.
- [Scripts](references/scripts.md): local QuickJS handlers, button actions, and automatic input updates.
- [Script examples](references/script-examples.md): complete budget, filtering, and scoring tools.
