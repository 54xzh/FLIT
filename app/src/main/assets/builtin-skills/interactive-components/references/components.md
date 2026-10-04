# Additional components and properties

## Media, dates, tabs, and modals

| Component | Properties |
| --- | --- |
| Image | `url`, optional `description`; `fit`: contain, cover, fill, none, scaleDown; `variant`: icon, avatar, smallFeature, mediumFeature, largeFeature, header |
| Video | `url`; opens media through a device handler |
| AudioPlayer | `url`, optional `description`; opens media through a device handler |
| DateTimeInput | bound ISO 8601 string `value`, optional `label`, boolean `enableDate`/`enableTime`, optional ISO `min`/`max`; initialize unset values with `""` |
| Tabs | `tabs`: array of `{ "title": "Label", "child": "component-id" }` |
| Modal | `trigger` and `content`: component IDs |
| Icon | `name`: a catalog icon name listed below |

## Layout and optional properties

Row and Column support `justify`: start, center, end, spaceBetween, spaceAround, spaceEvenly;
`align`: start, center, end, stretch. A child's `weight` must be a positive finite number.
List supports `children` (ID array or collection template), `direction`: vertical or horizontal,
and `align`: start, center, end, stretch. Row and Column can also use collection templates;
see [Script examples](script-examples.md) for the template shape and relative bindings.

Text supports `variant`: h1, h2, h3, h4, h5, caption, body.
ChoicePicker supports `displayStyle`: chips or checkbox.
All components can use `accessibility: { "label": "…", "description": "…" }`.

## Catalog icon names

Use these exact names:

```text
accountCircle, add, arrowBack, arrowForward, attachFile, calendarToday, call, camera,
check, close, delete, download, edit, event, error, fastForward, favorite, favoriteOff,
folder, help, home, info, locationOn, lock, lockOpen, mail, menu, moreVert, moreHoriz,
notificationsOff, notifications, pause, payment, person, phone, photo, play, print,
refresh, rewind, search, send, settings, share, shoppingCart, skipNext, skipPrevious,
star, starHalf, starOff, stop, upload, visibility, visibilityOff, volumeDown, volumeMute,
volumeOff, volumeUp, warning
```

## Formatting functions

Use `{ "call": "functionName", "args": {...} }` as a dynamic Text value.
Required arguments come first below; arguments marked optional may be omitted.

| `call` | `args` |
| --- | --- |
| formatString | string `value`, containing `${/path}` or named function expressions |
| formatNumber | numeric `value`; optional numeric `decimals`, boolean `grouping` |
| formatCurrency | numeric `value`, string `currency` (ISO 4217, e.g. USD); optional numeric `decimals` |
| formatDate | `value` (date/time), string `format` (Unicode TR35, e.g. yyyy-MM-dd or HH:mm) |
| pluralize | numeric `value`, string `other`; optional strings `zero`, `one`, `two`, `few`, `many` |

For example, a Text component's `text` may be:

```json
{"call":"formatString","args":{"value":"Hello ${/name}; total ${formatNumber(value:${/total}, decimals:2)}"}}
```

Function expressions use named arguments. A raw Text string does not interpolate data;
wrap it in formatString. To display a literal `${`, escape it as `\\${` in the JSON string.
Formatting functions return strings; validation functions return booleans and belong in
`checks.condition` as described in SKILL.md.
