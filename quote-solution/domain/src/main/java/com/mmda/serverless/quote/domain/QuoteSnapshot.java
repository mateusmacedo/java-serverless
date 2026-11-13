package com.mmda.serverless.quote.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Snapshot imutável de uma versão: o pedido com as referências resolvidas no
 * catálogo da organização.
 * Os campos do pedido ({@code applicantRef}, {@code risk.vehicleRef},
 * {@code risk.principalDriverRef},
 * {@code coverageProfile}) ficam nos mesmos caminhos, para que os
 * {@code missingFields} apontem o campo
 * que o usuário corrige. Datas sem hora são {@code YYYY-MM-DD} (RN14).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
public record QuoteSnapshot(
                String product,
                String applicantRef,
                Party applicant,
                Risk risk,
                String coverageProfile,
                CoverageProfile coverage,
                String desiredStartDate) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Party(String id, String label) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Risk(
                        String vehicleRef,
                        Usage usage,
                        String principalDriverRef,
                        Vehicle vehicle,
                        Driver principalDriver) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Vehicle(String id, String label, String make, String model, int year, boolean salvage) {
        }

        /**
         * Condutor; {@code licenseDate} nulo indica habilitação não informada no
         * cadastro.
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Driver(String id, String label, String licenseDate) {
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record CoverageProfile(String code, String label, String summary, List<Coverage> coverages) {
        }

        /** Cobertura simulada; {@code insuredAmount} é decimal em string (RN12). */
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Coverage(String code, String label, String insuredAmount) {
        }
}
