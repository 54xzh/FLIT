# Examples

Replace `<supported catalogId>` with the app-provided identifier. Translate visible text and context keys to the user's language.

## Choose a travel mode

```a2ui
{"version":"v0.9.1","createSurface":{"surfaceId":"preferences","catalogId":"<supported catalogId>"}}
{"version":"v0.9.1","updateDataModel":{"surfaceId":"preferences","value":{"mode":["train"]}}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"preferences","components":[{"id":"root","component":"Card","child":"body"},{"id":"body","component":"Column","children":["mode","submit"]}]}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"preferences","components":[{"id":"mode","component":"ChoicePicker","label":"Travel mode","variant":"mutuallyExclusive","options":[{"label":"Train","value":"train"},{"label":"Car","value":"car"}],"value":{"path":"/mode"}}]}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"preferences","components":[{"id":"submit-label","component":"Text","text":"Submit preferences"},{"id":"submit","component":"Button","child":"submit-label","variant":"primary","action":{"event":{"name":"submit_preferences","context":{"Travel mode":{"path":"/mode"}}}}}]}}
```

## Collect a name and preferences

```a2ui
{"version":"v0.9.1","createSurface":{"surfaceId":"preferences","catalogId":"<supported catalogId>"}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"preferences","components":[{"id":"root","component":"Card","child":"body"},{"id":"body","component":"Column","children":["name","quiet","submit"]},{"id":"name","component":"TextField","label":"Name","value":{"path":"/name"}},{"id":"quiet","component":"CheckBox","label":"Prefer quiet places","value":{"path":"/quiet"}},{"id":"submit-label","component":"Text","text":"Submit preferences"},{"id":"submit","component":"Button","child":"submit-label","variant":"primary","action":{"event":{"name":"submit_preferences","context":{"Name":{"path":"/name"},"Quiet places":{"path":"/quiet"}}}}}]}}
{"version":"v0.9.1","updateDataModel":{"surfaceId":"preferences","value":{"name":"","quiet":true}}}
```

## Adjust a budget

```a2ui
{"version":"v0.9.1","createSurface":{"surfaceId":"preferences","catalogId":"<supported catalogId>"}}
{"version":"v0.9.1","updateComponents":{"surfaceId":"preferences","components":[{"id":"root","component":"Card","child":"body"},{"id":"body","component":"Column","children":["budget","submit"]},{"id":"budget","component":"Slider","label":"Budget","min":100,"max":5000,"value":{"path":"/budget"}},{"id":"submit-label","component":"Text","text":"Submit preferences"},{"id":"submit","component":"Button","child":"submit-label","variant":"primary","action":{"event":{"name":"submit_preferences","context":{"Budget":{"path":"/budget"}}}}}]}}
{"version":"v0.9.1","updateDataModel":{"surfaceId":"preferences","value":{"budget":1000}}}
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
{"version":"v0.9.1","updateComponents":{"surfaceId":"preferences","components":[{"id":"root","component":"Card","child":"body"},{"id":"body","component":"Column","children":["summary","budget","submit"]},{"id":"summary","component":"Text","text":"Budget updated. Adjust it again if needed."},{"id":"budget","component":"Slider","label":"Budget","min":100,"max":5000,"value":{"path":"/budget"}},{"id":"submit-label","component":"Text","text":"Update budget"},{"id":"submit","component":"Button","child":"submit-label","variant":"primary","action":{"event":{"name":"submit_preferences","context":{"Budget":{"path":"/budget"}}}}}]}}
{"version":"v0.9.1","updateDataModel":{"surfaceId":"preferences","value":{"budget":1500}}}
```
