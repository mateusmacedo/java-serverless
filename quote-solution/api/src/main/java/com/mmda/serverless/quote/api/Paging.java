package com.mmda.serverless.quote.api;

import java.util.List;

/** Tamanho de página comum às listagens com {@code cursor} e {@code limit} (§8 do desenho). */
final class Paging {

    static final String DEFAULT_LIMIT = "20";
    static final int MAX_LIMIT = 100;

    private Paging() {
    }

    /**
     * WHY: com {@code int} no {@code @QueryParam}, um valor não numérico vira {@code NotFoundException} no
     * Quarkus REST, e o contrato pede 400 para parâmetro inválido.
     */
    static int limit(String limit) {
        try {
            int size = Integer.parseInt(limit);
            if (size >= 1 && size <= MAX_LIMIT) {
                return size;
            }
        } catch (NumberFormatException invalid) {
            // NOTE: cai no mesmo erro de validação do valor fora da faixa
        }
        throw ApiException.validation(List.of(new ErrorResponse.FieldError("limit", "Use um valor de 1 a 100.")));
    }
}
