package me.rerere.rikkahub.service.interactive;

oneway interface IInteractiveScriptCallback {
    void complete(long requestId, String result, String error);
}
