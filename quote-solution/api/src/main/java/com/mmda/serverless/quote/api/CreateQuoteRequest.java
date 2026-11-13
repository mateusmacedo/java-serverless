package com.mmda.serverless.quote.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.mmda.serverless.quote.domain.QuoteSnapshot;
import com.mmda.serverless.quote.domain.Usage;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Corpo do {@code POST /v1/quotes}, validado campo a campo para devolver todos os {@code fieldErrors} de
 * uma vez. WHY: o body é lido como JSON bruto porque o {@code requestHash} da idempotência é calculado
 * sobre ele; campos desconhecidos são rejeitados para que o hash não dependa de campos ignorados.
 */
record CreateQuoteRequest(
        String applicantRef,
        String vehicleRef,
        Usage usage,
        String principalDriverRef,
        String coverageProfile,
        String desiredStartDate) {

    static final String PRODUCT = "AUTO";
    private static final Set<String> FIELDS = Set.of("product", "applicantRef", "risk", "coverageProfile",
            "desiredStartDate");
    private static final Set<String> RISK_FIELDS = Set.of("vehicleRef", "usage", "principalDriverRef");
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final int MAX_REF_LENGTH = 128;

    static CreateQuoteRequest parse(JsonNode body) {
        requireObject(body);
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        CreateQuoteRequest request = parseFields(body, Set.of(), errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return request;
    }

    static void requireObject(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw ApiException.validation(List.of(new ErrorResponse.FieldError("$", "O corpo deve ser um objeto JSON.")));
        }
    }

    /**
     * Campos do pedido, acumulando os erros em {@code errors}; {@code extraFields} são os campos que outro corpo
     * acrescenta, como o {@code expectedVersion} da nova versão, e que esta leitura não rejeita.
     */
    static CreateQuoteRequest parseFields(JsonNode body, Set<String> extraFields, List<ErrorResponse.FieldError> errors) {
        Set<String> known = new HashSet<>(FIELDS);
        known.addAll(extraFields);
        rejectUnknown(body, known, "", errors);
        String product = text(body, "product", "product", errors);
        if (product != null && !PRODUCT.equals(product)) {
            errors.add(new ErrorResponse.FieldError("product", "Produto não suportado; use AUTO."));
        }
        String applicantRef = reference(body, "applicantRef", "applicantRef", errors);
        JsonNode risk = body.get("risk");
        String vehicleRef = null;
        String principalDriverRef = null;
        Usage usage = null;
        if (risk == null || !risk.isObject()) {
            errors.add(new ErrorResponse.FieldError("risk", "Campo obrigatório."));
        } else {
            rejectUnknown(risk, RISK_FIELDS, "risk.", errors);
            vehicleRef = reference(risk, "vehicleRef", "risk.vehicleRef", errors);
            principalDriverRef = reference(risk, "principalDriverRef", "risk.principalDriverRef", errors);
            String usageText = text(risk, "usage", "risk.usage", errors);
            if (usageText != null) {
                try {
                    usage = Usage.valueOf(usageText);
                } catch (IllegalArgumentException invalid) {
                    errors.add(new ErrorResponse.FieldError("risk.usage", "Use PERSONAL ou COMMERCIAL."));
                }
            }
        }
        String coverageProfile = reference(body, "coverageProfile", "coverageProfile", errors);
        String desiredStartDate = text(body, "desiredStartDate", "desiredStartDate", errors);
        if (desiredStartDate != null && !isCivilDate(desiredStartDate)) {
            errors.add(new ErrorResponse.FieldError("desiredStartDate", "Use uma data válida no formato YYYY-MM-DD."));
        }
        return new CreateQuoteRequest(applicantRef, vehicleRef, usage, principalDriverRef, coverageProfile,
                desiredStartDate);
    }

    /** Resolve as referências no catálogo da organização; referência desconhecida é erro de campo. */
    QuoteSnapshot resolve(Catalog.Organization organization, Catalog catalog) {
        List<ErrorResponse.FieldError> errors = new ArrayList<>();
        var applicant = organization == null ? null : organization.customer(applicantRef).orElse(null);
        var vehicle = organization == null ? null : organization.vehicle(vehicleRef).orElse(null);
        var driver = organization == null ? null : organization.driver(principalDriverRef).orElse(null);
        var coverage = catalog.coverageProfile(coverageProfile).orElse(null);
        unknownReference(applicant, "applicantRef", errors);
        unknownReference(vehicle, "risk.vehicleRef", errors);
        unknownReference(driver, "risk.principalDriverRef", errors);
        unknownReference(coverage, "coverageProfile", errors);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        return new QuoteSnapshot(PRODUCT, applicantRef, applicant,
                new QuoteSnapshot.Risk(vehicleRef, usage, principalDriverRef, vehicle, driver),
                coverageProfile, coverage, desiredStartDate);
    }

    private static void unknownReference(Object resolved, String path, List<ErrorResponse.FieldError> errors) {
        if (resolved == null) {
            errors.add(new ErrorResponse.FieldError(path, "Referência não encontrada no catálogo da organização."));
        }
    }

    private static void rejectUnknown(JsonNode node, Set<String> known, String prefix,
            List<ErrorResponse.FieldError> errors) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!known.contains(name)) {
                errors.add(new ErrorResponse.FieldError(prefix + name, "Campo não reconhecido."));
            }
        }
    }

    private static String text(JsonNode node, String field, String path, List<ErrorResponse.FieldError> errors) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            errors.add(new ErrorResponse.FieldError(path, "Campo obrigatório."));
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            errors.add(new ErrorResponse.FieldError(path, "Informe um texto não vazio."));
            return null;
        }
        return value.textValue();
    }

    private static String reference(JsonNode node, String field, String path, List<ErrorResponse.FieldError> errors) {
        String value = text(node, field, path, errors);
        if (value != null && value.length() > MAX_REF_LENGTH) {
            errors.add(new ErrorResponse.FieldError(path, "Referência longa demais."));
            return null;
        }
        return value;
    }

    private static boolean isCivilDate(String value) {
        if (!DATE.matcher(value).matches()) {
            return false;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeParseException invalid) {
            return false;
        }
    }
}
