package com.mmda.serverless.provider.domain.insurer;

import com.mmda.serverless.provider.domain.Decimals;
import com.mmda.serverless.provider.domain.Timestamps;
import java.time.DateTimeException;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Oferta normalizada, o {@code offer} do {@code InsurerResultRecorded} quando
 * {@code QUOTED}. Valores
 * monetários são strings decimais com a moeda em {@code currency} (RN12). Os
 * formatos são os do schema
 * {@code Offer} do AsyncAPI da provider, que a quote lê sem validar de novo.
 *
 * @param rawResponseRef chave, no bucket de payloads da provider, da resposta
 *                       bruta da seguradora
 */
public record Offer(
        String externalOfferId,
        String insurerName,
        String premiumTotal,
        String currency,
        List<Installment> installments,
        List<Coverage> coverages,
        List<Deductible> deductibles,
        String validUntil,
        String rawResponseRef) {

    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public Offer {
        Objects.requireNonNull(externalOfferId, "externalOfferId");
        Objects.requireNonNull(insurerName, "insurerName");
        Decimals.requireDecimal(premiumTotal, "premiumTotal");
        if (currency == null || !CURRENCY.matcher(currency).matches()) {
            throw new IllegalArgumentException("currency is not an ISO 4217 code: " + currency);
        }
        requireDateTime(validUntil);
        installments = installments == null ? List.of() : List.copyOf(installments);
        coverages = coverages == null ? List.of() : List.copyOf(coverages);
        deductibles = deductibles == null ? List.of() : List.copyOf(deductibles);
    }

    private static void requireDateTime(String validUntil) {
        Objects.requireNonNull(validUntil, "validUntil");
        try {
            Timestamps.parse(validUntil);
        } catch (DateTimeException invalid) {
            throw new IllegalArgumentException("validUntil is not a date-time: " + validUntil, invalid);
        }
    }

    public Offer withRawResponseRef(String reference) {
        return new Offer(externalOfferId, insurerName, premiumTotal, currency, installments, coverages,
                deductibles, validUntil, reference);
    }

    /** Opção de parcelamento: {@code count} parcelas de {@code amount}. */
    public record Installment(int count, String amount) {

        public Installment {
            if (count < 1) {
                throw new IllegalArgumentException("installments.count must be positive: " + count);
            }
            Decimals.requireDecimal(amount, "installments.amount");
        }
    }

    /**
     * Cobertura por código normalizado; {@code insuredAmount} ausente significa não
     * informado, não zero. O
     * {@code label} é obrigatório porque a API de produto o exige em cada cobertura
     * da oferta.
     */
    public record Coverage(String code, String label, String insuredAmount) {

        public Coverage {
            Objects.requireNonNull(code, "coverages.code");
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("coverages.label is required");
            }
            if (insuredAmount != null) {
                Decimals.requireDecimal(insuredAmount, "coverages.insuredAmount");
            }
        }
    }

    public record Deductible(String coverageCode, String amount) {

        public Deductible {
            Objects.requireNonNull(coverageCode, "deductibles.coverageCode");
            Decimals.requireDecimal(amount, "deductibles.amount");
        }
    }
}
