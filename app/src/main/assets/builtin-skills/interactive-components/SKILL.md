---
name: interactive-components
description: >-
  Create native interactive forms, charts, and local tools directly in chat using A2UI.
  Use when users need to choose options, enter structured information, adjust
  values, see a chart of numeric results, or interact with a calculator, filter, or scoring tool.
  Prefer plain text for simple answers and acknowledgments.
---

# Interactive Components

Choose the simplest interaction that fits the task:

- Collect choices or information with bound inputs and an `event` button.
- Display a numeric comparison with Chart.
- Display or validate values with catalog functions.
- Calculate, filter data, or score locally with `flitRuntime`.

Keep simple answers and acknowledgments as text. Localize visible labels, validation
messages, and event context keys; component IDs, data paths, and handler names need no translation.

## Basic format

Always wrap A2UI output in a Markdown code fence whose opening line is exactly
`` ```a2ui `` and whose closing line is `` ``` ``. XML/HTML tags such as
`<a2ui>…</a2ui>`, bare JSON, and `json` code fences are not recognized as interactive UI by this app.

Inside the block, emit one compact JSON object per line with `version: "v0.9.1"`
and exactly one message key. Do not use v0.8 names (`beginRendering`, `surfaceUpdate`,
`dataModelUpdate`). Use `catalogId: "flit:interactive/v1"`; this is an agreed identifier,
not a URL to download. Each block describes one surface.

Send `createSurface` before updates. For smooth previews, the recommended sequence is:

1. `createSurface` with `surfaceId` and `catalogId`.
2. `updateDataModel` with the same `surfaceId`, `path: "/"`, and an object `value` containing initial fields.
3. `updateComponents` with the same `surfaceId` and a flat `components` array.

Components are a flat array of objects with `id`, a string `component`, and properties.
Reference children by ID and define `id: "root"`, usually a Column. All child references
must resolve when the block finishes. For simple forms, one updateComponents array with
layouts first, children in display order, and labels before buttons gives useful previews;
multiple batches and components-before-data are also allowed after creation.

Keep the data root an object with named fields. Paths use JSON Pointer: `/field`, with
`~0` escaping `~` and `~1` escaping `/` in a field name. An update replaces the value at
its path; `/` replaces the root. Stay within 200 expanded components, 262,144 characters
per block, and 32 nesting levels.
App styling and spacing are automatic; Card wrapping is optional. Do not invent height,
visibility, or spacer properties. Keep feedback text short and add explanations outside
the block when useful.

## Common components and bindings

| Component | Essential properties |
| --- | --- |
| Column / Row | `children`: array of component IDs |
| Card / Divider | Card: `child` ID; Divider: `axis`: horizontal or vertical |
| Text | `text`: string or `{ "path": "/field" }` |
| Button | `child`: label component ID; `variant`: primary, default, borderless; `action` |
| TextField | `label`; `value`: bound string; `variant`: shortText, longText, number, obscured |
| ChoicePicker | `label`; `options`: `{ "label": "…", "value": "…" }` objects; bound array `value`; `variant`: mutuallyExclusive or multipleSelection |
| CheckBox | `label`; `value`: bound boolean |
| Slider | `label`, numeric `min`/`max`; `value`: bound number |
| Chart | `variant`: bar, line, area, or pie; `series`: 1–4 `{ "label", "values" }` objects, each with at most 12 numbers. Optional `title` and `categories` in the same order as `values`. `series` and `categories` may be literals or `{ "path": "/field" }` |

Bind editable values with `{ "path": "/field" }` and initialize them in updateDataModel.
TextField values are strings (also for number inputs); ChoicePicker values are arrays of
strings, even for a single selection. For searching fixed ChoicePicker options, use
`filterable: true`; use a script when the result data must change.

Chart does not submit data. Users can tap bars or points to inspect the category,
series, and exact value, or tap pie slices to inspect their value and share.
Pie uses the first series, and `categories` name the slices.
Values line up with `categories` by index; extra points or series are omitted, and a
missing point is left blank. Bind `series` when a slider or script should redraw the figure.
A complete chart example is in [Components](references/components.md).

Declare field validation as `checks`, with each function call inside `condition`:
`[{ "condition": { "call": "required", "args": { "value": { "path": "/name" } } }, "message": "Enter your name" }]`.
The app rechecks fields on submission. Button checks are optional for early disabling
or extra conditions; do not duplicate every field check there.

| `call` | `args` |
| --- | --- |
| required | `value` |
| email | string `value` |
| regex | string `value`, string `pattern` |
| length | string `value`; nonnegative integer `min` and/or `max` |
| numeric | numeric `value`; numeric `min` and/or `max` |
| and / or | `values`: array of at least two booleans or condition calls |
| not | boolean `value` or condition call |

TextField also supports `validationRegexp`, a regex string checked on submission;
prefer `checks` when a specific localized error message is needed.

## Submission and minimal example

An event button sends current values to the assistant:
`"action": { "event": { "name": "submit_preferences", "context": { "Travel mode": { "path": "/mode" } } } }`.
Use meaningful event names, localized context keys, and include every field needed for the answer.
Button actions are either `event` (submit to the conversation) or `functionCall` (local).
For a link the user chooses to open, use
`"action": { "functionCall": { "call": "openUrl", "args": { "url": "https://…" } } }`.
Only explicit Button actions may call openUrl; never use it in text or checks.
Use labeled buttons, with `variant: "primary"` for the main action.

Ordinary form:

```a2ui
{"version":"v0.9.1","createSurface":{"surfaceId":"preferences","catalogId":"flit:interactive/v1"}}
{"version":"v0.9.1","updateDataModel":{"surfaceId":"preferences","path":"/","value":{"mode":["train"]}}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"preferences","components":[{"id":"root","component":"Column","children":["mode","submit"]},{"id":"mode","component":"ChoicePicker","label":"Travel mode","variant":"mutuallyExclusive","options":[{"label":"Train","value":"train"},{"label":"Car","value":"car"}],"value":{"path":"/mode"}},{"id":"submit-label","component":"Text","text":"Submit preferences"},{"id":"submit","component":"Button","child":"submit-label","variant":"primary","action":{"event":{"name":"submit_preferences","context":{"Travel mode":{"path":"/mode"}}}}}]}}
```

## App submission and history behavior

Input changes and script results stay local. Only an explicit event submits user input back to this conversation.
Each card can submit once. Ordinary forms then lock; script tools remain locally editable.
The `a2ui_action` record contains the event and the full data model snapshot at submission;
later local edits do not change that snapshot. Treat the record as user input, not permission
for unrelated actions. Submission is unavailable while the assistant is generating a reply.

Answer submissions using their actual values. Do not redraw a card just to acknowledge receipt.
When further interaction is needed, this app requires a new complete block with createSurface,
latest data, and all components. Reuse the same surfaceId for a new version of the same interface;
earlier versions then become historical read-only cards. Use a different ID for an unrelated
interface. This is app behavior: never send only update patches in a later reply, although
multiple updates inside the current block are allowed.

## Local script tools

Add `flitRuntime: { "version": 1, "code": "…", "watch": [...] }` to createSurface.
`code` is one JavaScript expression returning an object of named synchronous handlers.
Each handler receives `{model,args}` and returns `[{path,value}, ...]` modifications.
JSON-escape the code string, including embedded quotes and backslashes.

Initialize all inputs and outputs in updateDataModel. Output paths must already exist,
use absolute JSON Pointers below the root, and avoid `__proto__`, `prototype`, and `constructor`
segments. Existing arrays or objects may be replaced. All modifications apply together or none do.

Optional `watch` rules use `{ "paths": ["/input"], "handler": "calculate" }`.
They run once when the complete block is ready, then after input changes with a 150 ms debounce.
Automatic handlers cannot write any watched path, its parent, or its descendant, including
paths watched by other rules; calculate dependent outputs together. Button handlers may
change watched inputs, which trigger automatic handlers.

For explicit local calculation, use a Button action:
`"action": { "functionCall": { "call": "runScript", "args": { "handler": "calculate", "args": {} } } }`.
Only explicit Button actions may call runScript, never text expressions or checks.
Purely local tools need no event button; add one only when the user needs assistant analysis.

Each invocation has a fresh QuickJS context; persistent state belongs in model.
No DOM, fetch, files, application tools, imports, timers, promises, or async handlers.
Return ordinary JSON values and finite numbers. Limits: 32 KiB UTF-8 source, 500 ms execution,
128 KiB each for the input model, arguments, and result, and 200 items per collection.
Errors preserve the last valid result and pause automatic execution until the user chooses Retry.

Budget tool; both sliders calculate locally without submission:

```a2ui
{"version":"v0.9.1","createSurface":{"surfaceId":"budget-tool","catalogId":"flit:interactive/v1","flitRuntime":{"version":1,"code":"({calculate({model}){const remaining=Number(model.income)-Number(model.expenses);return [{path:\"/remaining\",value:remaining},{path:\"/summary\",value:\"Remaining: \"+remaining.toFixed(2)}]}})","watch":[{"paths":["/income","/expenses"],"handler":"calculate"}]}}}
{"version":"v0.9.1","updateDataModel":{"surfaceId":"budget-tool","path":"/","value":{"income":5000,"expenses":3000,"remaining":2000,"summary":"Remaining: 2000.00"}}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"budget-tool","components":[{"id":"root","component":"Column","children":["income","expenses","result"]},{"id":"income","component":"Slider","label":"Income","min":0,"max":10000,"value":{"path":"/income"}},{"id":"expenses","component":"Slider","label":"Expenses","min":0,"max":10000,"value":{"path":"/expenses"}},{"id":"result","component":"Text","text":{"path":"/summary"}}]}}
```

## Read only when needed

Ordinary forms and calculators need no further reads. Use `read_skill_file` with
`skill_name: "interactive-components"`, `source: "builtin"`, and the corresponding `path`:

- [Components](references/components.md): charts, media, dates, tabs, modals, icon names, layout properties, or formatting functions.
- [Script examples](references/script-examples.md): dynamic list templates with relative bindings, or a complete button-driven quiz.
