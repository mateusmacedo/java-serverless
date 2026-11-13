package com.mmda.serverless.provider.domain.insurer;

/**
 * Status terminal que a provider publica por seguradora. {@code TIMED_OUT} é
 * decidido pela quote.
 */
public enum InsurerResultStatus {
    QUOTED,
    DECLINED,
    NEEDS_INFORMATION,
    UNAVAILABLE
}
