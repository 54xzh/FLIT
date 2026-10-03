package me.rerere.rikkahub.service.interactive;

import me.rerere.rikkahub.service.interactive.IInteractiveScriptCallback;

oneway interface IInteractiveScriptService {
    void execute(long requestId, String code, String handler, String model, String args, IInteractiveScriptCallback callback);
    void abort(long requestId);
}
