# Components

The app supports the AndroidX Material 3 Basic Catalog. App colors, typography, card shapes,
button press feedback, and motion are supplied by the host; do not specify arbitrary styling.

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

Prefer simple cards, columns, text fields, checkboxes, choices, and sliders.
Row and Column support `justify`: start, center, end, spaceBetween, spaceAround, spaceEvenly;
`align`: start, center, end, stretch. List uses `direction`: vertical or horizontal and
`align`: start, center, end, stretch. `weight` belongs to the child component and must be a positive finite number.
ChoicePicker optionally uses `displayStyle`: chips or checkbox, and boolean `filterable`.
All components can use `accessibility: { "label": "…", "description": "…" }`.
Icon names include add, check, close, search, settings, favorite, home, menu, person,
arrowBack, arrowForward, info, warning, error, and delete.

For dynamic child collections use `children: { "componentId": "item", "path": "/items" }`.
The template's data context is the corresponding array element; relative paths bind within it.
Static child ID arrays are simpler and preferred. Keep the expanded interface under 200
components, descriptions under 256 KiB, and nesting under 32 levels.

Bind editable values using `{ "path": "/field" }`; initialize them with updateDataModel.
TextField uses strings, CheckBox booleans, ChoicePicker arrays of selected strings, and
Slider numbers. Unbound literal values are useful for labels, not editable fields.

Use `checks` with a `condition` function call and a localized `message`, for example:
`{ "condition": { "call": "required", "args": { "value": { "path": "/name" } } },
"message": "Enter your name" }`.
Repeat relevant checks on the submit button so invalid forms cannot be submitted.
Functions are limited to the catalog's registered basic functions: validation, formatting,
string operations, and explicit URL opening. No arbitrary script execution is available.
