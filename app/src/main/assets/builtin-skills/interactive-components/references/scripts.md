# Local script tools

Use catalogId `flit:interactive/v1`. Keep the normal v0.9.1 envelopes and native components.
Add `flitRuntime` inside createSurface:

```json
{"version":1,"code":"({calculate({model}){return [{path:'/result',value:model.amount*2}]}})","watch":[{"paths":["/amount"],"handler":"calculate"}]}
```

`code` is one JavaScript expression returning an object of named handler functions.
Each synchronous handler receives `{model,args}` and returns an array of `{path,value}`
modifications. Initialize all input and output paths in updateDataModel. Paths must already
exist, point below the root, and use absolute JSON Pointers (`~0` and `~1` escape segments).
Replacing an existing array or object is allowed. Do not use prototype-related path names.

Button action:
`{"functionCall":{"call":"runScript","args":{"handler":"calculate","args":{}}}}`.
Only explicit Button actions can call runScript. Never use it inside text, interpolation,
formatting, checks, or other render-time expressions.

Optional watch rules declare the input paths and handler. Rules execute once after the
complete block is ready and again after those inputs change (150 ms debounce). Do not
write any watched path, its parent, or its descendant from an automatic handler. This
also prohibits chains between watch rules; calculate dependent outputs together instead.
Button handlers may change watched inputs, which then trigger automatic calculations.

All state belongs in model. Each invocation has a fresh QuickJS context. No browser DOM,
fetch, files, application tools, imports, timers, promises, async handlers, or persistent
global variables are provided. Use finite numbers and ordinary JSON values only.
Return all modifications together; invalid results never apply partially.

Keep calculations short and bounded. Limits: 500 ms execution, 32 KiB UTF-8 source,
128 KiB input model/arguments/result, 200 items per collection, 32 nesting levels.
Keep tools small. Errors preserve the last valid result and pause automatic execution
until the user chooses Retry. Purely local tools do not send conversation messages.

Initialize all data before components. Keep results and necessary feedback in components
that are present from the start, with concise initial text; update their bound values.
Avoid temporary status components and large text-height changes on every input edit.
Use dynamic list filtering only when needed, with a small result set to limit height changes.

Use an event button only when the user needs assistant analysis. Each card submits once.
After submission, local editing and calculations continue, but the assistant receives only
the submission snapshot. A newer complete surface with the same logical surfaceId makes
the old card read-only. Do not repeat a tool merely to acknowledge its submission.

See script-examples.md for three complete independent surfaces.
