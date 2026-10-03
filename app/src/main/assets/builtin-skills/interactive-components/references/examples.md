# Examples

Replace `<supported catalogId>` with the app-provided identifier. Translate visible text and context keys to the user's language.
These simple forms initialize data before components, use a root Column, and need only
one updateComponents line. The name field demonstrates validation without duplicate button checks.

## Choose a travel mode

```a2ui
{"version":"v0.9.1","createSurface":{"surfaceId":"preferences","catalogId":"<supported catalogId>"}}
{"version":"v0.9.1","updateDataModel":{"surfaceId":"preferences","path":"/","value":{"mode":["train"]}}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"preferences","components":[{"id":"root","component":"Column","children":["mode","submit"]},{"id":"mode","component":"ChoicePicker","label":"Travel mode","variant":"mutuallyExclusive","options":[{"label":"Train","value":"train"},{"label":"Car","value":"car"}],"value":{"path":"/mode"}},{"id":"submit-label","component":"Text","text":"Submit preferences"},{"id":"submit","component":"Button","child":"submit-label","variant":"primary","action":{"event":{"name":"submit_preferences","context":{"Travel mode":{"path":"/mode"}}}}}]}}
```

## Collect a name and preferences

```a2ui
{"version":"v0.9.1","createSurface":{"surfaceId":"preferences","catalogId":"<supported catalogId>"}}
{"version":"v0.9.1","updateDataModel":{"surfaceId":"preferences","path":"/","value":{"name":"","quiet":true}}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"preferences","components":[{"id":"root","component":"Column","children":["name","quiet","submit"]},{"id":"name","component":"TextField","label":"Name","value":{"path":"/name"},"checks":[{"condition":{"call":"required","args":{"value":{"path":"/name"}}},"message":"Enter your name"}]},{"id":"quiet","component":"CheckBox","label":"Prefer quiet places","value":{"path":"/quiet"}},{"id":"submit-label","component":"Text","text":"Submit preferences"},{"id":"submit","component":"Button","child":"submit-label","variant":"primary","action":{"event":{"name":"submit_preferences","context":{"Name":{"path":"/name"},"Quiet places":{"path":"/quiet"}}}}}]}}
```

## Adjust a budget

```a2ui
{"version":"v0.9.1","createSurface":{"surfaceId":"preferences","catalogId":"<supported catalogId>"}}
{"version":"v0.9.1","updateDataModel":{"surfaceId":"preferences","path":"/","value":{"budget":1000}}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"preferences","components":[{"id":"root","component":"Column","children":["budget","submit"]},{"id":"budget","component":"Slider","label":"Budget","min":100,"max":5000,"value":{"path":"/budget"}},{"id":"submit-label","component":"Text","text":"Submit preferences"},{"id":"submit","component":"Button","child":"submit-label","variant":"primary","action":{"event":{"name":"submit_preferences","context":{"Budget":{"path":"/budget"}}}}}]}}
```

## Answer after submission

User submits `{ "Budget": 1500 }` for recommendations. Use that budget to answer in text.
Do not repeat the slider or generate a confirmation card.

## Follow-up when another interaction is needed

Only generate a follow-up card when another choice or adjustment is needed. For example,
the user explicitly asks to revise the submitted budget of 1500. Explain briefly, then
include a complete block with the same surfaceId and the submitted value as initial data:

```a2ui
{"version":"v0.9.1","createSurface":{"surfaceId":"preferences","catalogId":"<supported catalogId>"}}
{"version":"v0.9.1","updateDataModel":{"surfaceId":"preferences","path":"/","value":{"budget":1500}}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"preferences","components":[{"id":"root","component":"Column","children":["budget","submit"]},{"id":"budget","component":"Slider","label":"Budget","min":100,"max":5000,"value":{"path":"/budget"}},{"id":"submit-label","component":"Text","text":"Update budget"},{"id":"submit","component":"Button","child":"submit-label","variant":"primary","action":{"event":{"name":"submit_preferences","context":{"Budget":{"path":"/budget"}}}}}]}}
```
