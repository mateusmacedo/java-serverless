package com.mmda.serverless.provider.functions;

/** Itens {@code OUTBOX_PENDING} que o {@code reconciler} encontrou, republicou ou não conseguiu publicar. */
public record ReconcileResult(int pending, int republished, int failed) {
}
