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
}
