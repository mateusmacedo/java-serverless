package com.mmda.serverless.provider.simulators;

import java.util.Map;

/**
 * Leitura tolerante do {@code risk} que a seguradora recebe, cópia do snapshot da quote. O veículo pode vir
 * resolvido ({@code risk.vehicle.ref} ou {@code risk.vehicle.id}) ou só como referência ({@code risk.vehicleRef}).
 */
final class RiskFields {

    private RiskFields() {
    }

    static String vehicleRef(Map<String, Object> risk) {
        if (risk == null) {
            return null;
        }
        if (risk.get("vehicle") instanceof Map<?, ?> vehicle) {
            for (String field : new String[] {"ref", "id", "vehicleRef"}) {
                if (vehicle.get(field) instanceof String value && !value.isBlank()) {
                    return value;
                }
            }
        }
        return risk.get("vehicleRef") instanceof String value && !value.isBlank() ? value : null;
    }

    static String usage(Map<String, Object> risk) {
        return risk != null && risk.get("usage") instanceof String value ? value : "PERSONAL";
    }

    /**
     * Código do perfil de cobertura: {@code risk.coverageProfile.code}, como o snapshot da quote o envia, ou
     * {@code risk.coverageProfile} só com o código. Sem perfil, o padrão do catálogo.
     */
    static String coverageProfile(Map<String, Object> risk) {
        Object profile = risk == null ? null : risk.get("coverageProfile");
        if (profile instanceof Map<?, ?> resolved && resolved.get("code") instanceof String code) {
            return code;
        }
        return profile instanceof String code ? code : LabCatalog.DEFAULT_COVERAGE_PROFILE;
    }
}
