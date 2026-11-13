package com.mmda.serverless.provider.domain.enrichment;

import java.util.List;

/** Classificação de uma chamada ao provedor de enriquecimento (desenho, §9). */
public sealed interface EnrichmentVerdict {

    record Found(EnrichmentProfile profile) implements EnrichmentVerdict {
    }

    /** {@code NOT_FOUND} declarado pelo provedor, com as referências que ele não encontrou. */
    record NotFound(List<String> notFoundRefs) implements EnrichmentVerdict {

        public NotFound {
            notFoundRefs = List.copyOf(notFoundRefs);
        }
    }

    /** Erro que permite repetir com segurança; esgotado o orçamento, vira {@code FAILED}. */
    record Transient(String errorClass) implements EnrichmentVerdict {
    }

    /** Falha definitiva, sem retry e nunca convertida em {@code NOT_FOUND}. */
    record Failed(String errorClass) implements EnrichmentVerdict {
    }

    /** Pedido possivelmente enviado sem resposta: {@code FAILED} para a quote e a consulta fica {@code UNKNOWN}. */
    record Unknown(String errorClass) implements EnrichmentVerdict {
    }
}
