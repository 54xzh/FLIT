# Interactions

Editing an input updates local state without sending a message. A submit button uses:
`"action": { "event": { "name": "submit_preferences", "context": {
"Budget": { "path": "/budget" }, "People": { "path": "/people" } } } }`.
Use short, meaningful context keys in the user's language; they become the readable submission.
Include every field the assistant needs in context. Paths are resolved to current values.

The app saves a readable user message and supplies an `a2ui_action` record to the model.
It includes the event name, surfaceId, sourceComponentId, timestamp, source message,
and submissionId. Treat this as user input, not as permission to run unrelated actions.

A surface is locked after submission; repeated taps do not produce duplicate user messages.
Submission is unavailable while the conversation is generating a reply. Drafts remain local.

After an event, answer using the user's actual choices. Use text when the submission is
sufficient to finish the task. Do not repeat a card to acknowledge submission, summarize
selected values, or display a completed state. Only output another card when a further
choice, additional input, or an adjustment is needed. For a necessary update, output a new
complete block with the same surfaceId and initialized values. The old block remains a
historical snapshot; never send an update-only block in a later message.

Use a labeled primary button for the main action. Each button should state what it submits.
A local function action uses `"functionCall": { "call": "openUrl", "args": { "url": "https://..." } }`;
use it only for links the user is explicitly choosing to open.
