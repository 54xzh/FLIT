# Components

The app supports the AndroidX Material 3 Basic Catalog. App colors, typography, card shapes,
button press feedback, and motion are supplied by the host; do not specify arbitrary styling.
Cards have transparent backgrounds and no borders so their contents blend into the chat.
They add 8 dp vertical padding and no horizontal inset. A root Column needs no Card wrapper.
The host spaces vertical children by 16 dp and horizontal
children by 12 dp for the default arrangements; do not insert blank spacer components.

| Component | Main properties |
| --- | --- |
| Text | `text` (string or path); `variant`: h1, h2, h3, h4, h5, caption, body |
| Icon | `name` (catalog icon name) |
| Image | `url`, `description`; `fit`: contain, cover, fill, none, scaleDown; `variant`: icon, avatar, smallFeature, mediumFeature, largeFeature, header |
| Video | `url` |
| AudioPlayer | `url`, `description` |
| Row / Column | `children` (ID array), `align`, `justify`; child `weight` |
| List | `children` (ID array or catalog collection template) |
| Card | `child` (one component ID) |
| Tabs | `tabs`: array of `{ "title": "Label", "child": "component-id" }` |
| Modal | `trigger` and `content`: component IDs |
| Divider | `axis`: horizontal or vertical |
| Button | `child`, `variant`: default, primary, borderless; `action` |
| TextField | `label`, `value`; `variant`: shortText, longText, number, obscured |
| CheckBox | `label`, boolean `value` |
| ChoicePicker | `options`: label/value objects; array `value`; mutuallyExclusive or multipleSelection variant |
| Slider | `label`, numeric `min`, `max`, `value` |
| DateTimeInput | `label`, ISO-formatted `value`, boolean `enableDate`/`enableTime`, optional ISO `min`/`max` |

Prefer simple columns, text fields, checkboxes, choices, and sliders; add Card only when needed.
Keep the component tree stable. Initialize necessary result and feedback components with
short text, then update their values. Avoid unnecessary temporary components, repeated layout
replacement, and large text-height changes. Use changing list lengths or tabs with very
different heights only when the task needs them. Do not invent height or visibility properties.
Row and Column support `justify`: start, center, end, spaceBetween, spaceAround, spaceEvenly;
`align`: start, center, end, stretch. List uses `direction`: vertical or horizontal and
`align`: start, center, end, stretch. `weight` belongs to the child component and must be a positive finite number.
ChoicePicker optionally uses `displayStyle`: chips or checkbox, and boolean `filterable`.
All components can use `accessibility: { "label": "…", "description": "…" }`.
Icon names include add, check, close, search, settings, favorite, home, menu, person,
arrowBack, arrowForward, info, warning, error, and delete.

For dynamic child collections use `children: { "componentId": "item", "path": "/items" }`.
The template's data context is the corresponding array element; relative paths bind within it.
Static child ID arrays are simpler and preferred. Limits: 200 expanded components,
262,144 characters per block, and 32 nesting levels. Keep interfaces small and avoid
unnecessary nesting; count expanded collection items, not just template definitions.

Bind editable values using `{ "path": "/field" }`; initialize them with updateDataModel.
TextField uses strings, CheckBox booleans, ChoicePicker arrays of selected strings, and
Slider numbers. Unbound literal values are useful for labels, not editable fields.

Use `checks` with a `condition` function call and a localized `message`, for example:
`{ "condition": { "call": "required", "args": { "value": { "path": "/name" } } },
"message": "Enter your name" }`.
Declare necessary validation on the fields. The app rechecks field conditions against current
values before accepting a submission. Button checks are optional for early disabling or
additional submit conditions; do not mechanically duplicate every field check.
Functions include validation, formatting, string operations, and explicit URL opening.
The FLIT catalog also supports `runScript` as an explicit Button action, when the surface
declares flitRuntime. It cannot be used inside text expressions or checks. Read scripts.md.
