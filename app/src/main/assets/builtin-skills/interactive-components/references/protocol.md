# Protocol

Use version `v0.9.1` on every message. Use the catalogId supplied by the app;
it is an identifier, not a URL the app downloads. Never invent a catalog.

Emit one compact JSON object per line inside an `a2ui` fenced block.
One block owns one surface. During streaming, the app can preview complete component
objects within an unfinished updateComponents line. Incomplete objects remain buffered;
the full line is still validated when it finishes. Other messages wait for complete lines.
Use a stable, meaningful surfaceId to identify subsequent versions of the same interface.

1. `createSurface`: `{ "surfaceId": "preferences", "catalogId": "<supported catalogId>" }`.
2. `updateDataModel`: `{ "surfaceId": "preferences", "path": "/", "value": {...} }`.
3. `updateComponents`: `{ "surfaceId": "preferences", "components": [...] }`.

This is the recommended order for new forms, so bound values exist before inputs appear.
`deleteSurface`: `{ "surfaceId": "preferences" }` removes only this block's surface;
it is not a normal form-generation step.

Every envelope has exactly one of these message keys plus `version`.
Components are a flat list. Each has an `id`, a string `component` discriminator,
and component-specific properties. Reference children by their IDs. The root ID is `root`.
Children may arrive later during streaming, but all references must be resolved at completion.
For simple forms, use one updateComponents array with root/layout first and children in
display order. Put button label components before their buttons. Complete objects in the
array can appear before the line finishes; small batches are optional. Previewed inputs become editable once
the block is complete, and submission stays unavailable while the reply is generating.

`updateDataModel` replaces the value at its JSON Pointer path. Omit `value` to remove it.
Use `/` to replace the root object. Escape pointer segments with `~0` and `~1` as needed.

Only when further interaction is needed in a later reply, create a complete independent
surface using the same logical surfaceId. Include the latest data and all components.
Do not repeat a surface solely to confirm submission. Earlier messages remain read-only
historical versions. Never send only an update patch in a later message; multiple updates
inside the current complete block are allowed. Do not modify earlier message contents.
Do not emit Kotlin, HTML, custom JSON wrappers, or v0.8 message names.
JavaScript is allowed only in `createSurface.flitRuntime.code` with the supplied FLIT catalog;
read references/scripts.md before creating a local script tool.

The AndroidX renderer requires an object at the data model root. Put strings, numbers,
booleans, and arrays under named fields; bind inputs to those fields rather than the root.
