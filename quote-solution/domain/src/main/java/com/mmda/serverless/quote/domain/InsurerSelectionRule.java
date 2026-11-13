package com.mmda.serverless.quote.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Regra de seleção de seguradoras {@code sel-1}, aplicada com os dados
 * enriquecidos (PRD §9, passo 4):
 * veículo anterior a 2000 não tem seguradora; uso comercial só cota na alpha;
 * os demais cotam na alpha e
 * na beta. A lista é limitada a {@code maxInsurers}, parte da invariante de
 * orçamento (§9 do desenho).
 */
public final class InsurerSelectionRule {

    public static final String VERSION = "sel-1";
    static final int MIN_VEHICLE_YEAR = 2000;

    private InsurerSelectionRule() {
    }

    /**
     * Aplica a regra à versão com o perfil da evidência {@code FOUND}: o uso vem do
     * snapshot, que é o pedido, e
     * o ano do veículo vem do perfil enriquecido ({@code vehicle.year}). Sem o ano
     * no perfil, vale o do snapshot.
     *
     * @param enrichedProfile o {@code result} do {@code EnrichmentResolved}, como a
     *                        evidência usada o guarda
     */
    public static InsurerSelection select(QuoteSnapshot snapshot, JsonNode enrichedProfile, int maxInsurers) {
        JsonNode year = enrichedProfile == null ? null : enrichedProfile.at("/vehicle/year");
        int vehicleYear = year != null && year.canConvertToInt() ? year.asInt() : snapshot.risk().vehicle().year();
        return select(snapshot.risk().usage(), vehicleYear, maxInsurers);
    }

    /**
     * @param vehicleYear ano do veículo segundo o enriquecimento, ou o do snapshot
     *                    quando o perfil
     *                    enriquecido não o informa
     */
    public static InsurerSelection select(Usage usage, int vehicleYear, int maxInsurers) {
        if (maxInsurers < 1) {
            throw new IllegalArgumentException("maxInsurers must be positive: " + maxInsurers);
        }
        List<String> eligible;
        if (vehicleYear < MIN_VEHICLE_YEAR) {
            eligible = List.of();
        } else if (usage == Usage.COMMERCIAL) {
            eligible = List.of(Insurers.ALPHA);
        } else {
            eligible = List.of(Insurers.ALPHA, Insurers.BETA);
        }
        return new InsurerSelection(eligible.stream().limit(maxInsurers).toList(), VERSION);
    }

    public record InsurerSelection(List<String> insurerIds, String ruleVersion) {

        public InsurerSelection {
            insurerIds = List.copyOf(insurerIds);
        }
    }
}
