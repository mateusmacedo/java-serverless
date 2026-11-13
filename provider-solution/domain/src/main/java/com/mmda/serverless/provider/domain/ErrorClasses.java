package com.mmda.serverless.provider.domain;

/**
 * Códigos seguros de falha técnica: viram {@code errorClass} no enriquecimento e {@code reasonCode} no
 * {@code UNAVAILABLE} da seguradora. Nunca levam mensagem do fornecedor nem stack trace.
 */
public final class ErrorClasses {

    public static final String DEADLINE_EXCEEDED_BEFORE_CALL = "DEADLINE_EXCEEDED_BEFORE_CALL";
    public static final String QUOTA_EXHAUSTED = "QUOTA_EXHAUSTED";
    public static final String PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
    public static final String PROVIDER_NO_RESPONSE = "PROVIDER_NO_RESPONSE";
    public static final String PROVIDER_AUTHENTICATION_FAILED = "PROVIDER_AUTHENTICATION_FAILED";
    public static final String PROVIDER_REJECTED_REQUEST = "PROVIDER_REJECTED_REQUEST";
    public static final String INVALID_PAYLOAD = "INVALID_PAYLOAD";
    public static final String INVALID_PROVIDER_RESPONSE = "INVALID_PROVIDER_RESPONSE";
    public static final String UNDECLARED_NOT_FOUND = "UNDECLARED_NOT_FOUND";
    public static final String UNSUPPORTED_INSURER = "UNSUPPORTED_INSURER";
    /** Reentrega encontrou tentativa aberta: o pedido pode ter chegado ao fornecedor. */
    public static final String INTERRUPTED_ATTEMPT = "INTERRUPTED_ATTEMPT";
    /** Reentrega encontrou só falhas antes do envio, sem orçamento para repetir. */
    public static final String INTERRUPTED_BEFORE_CALL = "INTERRUPTED_BEFORE_CALL";

    private ErrorClasses() {
    }

    /** Classe de um status HTTP 5xx, como {@code HTTP_503}. */
    public static String http(int statusCode) {
        return "HTTP_" + statusCode;
    }
}
