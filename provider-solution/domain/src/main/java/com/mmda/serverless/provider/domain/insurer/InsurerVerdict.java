package com.mmda.serverless.provider.domain.insurer;

import java.util.List;

/** Classificação de uma chamada a seguradora (desenho, §9). */
public sealed interface InsurerVerdict {

    /** Oferta normalizada, ainda sem {@code rawResponseRef}. */
    record Quoted(Offer offer) implements InsurerVerdict {
    }

    /** Recusa comercial: resultado válido, sem retry (RF09). */
    record Declined(String reasonCode) implements InsurerVerdict {
    }

    record NeedsInformation(String reasonCode, List<MissingField> missingFields) implements InsurerVerdict {

        public NeedsInformation {
            missingFields = List.copyOf(missingFields);
        }
    }

    /** Seguradora assíncrona aceitou o pedido; o resultado chega por callback (M3). */
    record Accepted(String providerRequestId) implements InsurerVerdict {
    }

    /** Erro que permite repetir com segurança; esgotado o orçamento, vira {@code UNAVAILABLE}. */
    record Transient(String errorClass) implements InsurerVerdict {
    }

    /** Falha técnica definitiva: {@code UNAVAILABLE} sem retry. */
    record Unavailable(String reasonCode) implements InsurerVerdict {
    }

    /** Pedido possivelmente enviado sem resposta: a consulta fica {@code UNKNOWN} e nunca é repetida. */
    record Unknown(String errorClass) implements InsurerVerdict {
    }
}
