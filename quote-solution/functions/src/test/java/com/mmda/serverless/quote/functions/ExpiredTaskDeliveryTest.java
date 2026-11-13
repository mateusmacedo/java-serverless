package com.mmda.serverless.quote.functions;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * Saídas de {@code TaskSucceeded} no formato do histórico do Step Functions
 * (conferido no Floci): o resultado
 * gravado chega à execução pelo {@code SendTaskSuccess} ou pelo passo de
 * timeout que o lê da tabela (§8).
 */
class ExpiredTaskDeliveryTest {

        private static final ObjectMapper JSON = new ObjectMapper();
        private static final String INSURER_PAYLOAD = "{\"insurerId\":\"beta\",\"status\":\"QUOTED\",\"reasonCode\":null,\"offerId\":\"of_9b25\"}";
        private static final String ENRICHMENT_PAYLOAD = "{\"resultType\":\"FOUND\",\"evidenceId\":\"ev_3f7c\","
                        + "\"queryId\":\"q_c571\",\"reuseDecision\":\"HIT\",\"policyVersion\":null}";

        @Test
        void resultSentByTheSendTaskSuccessIsDelivered() throws Exception {
                assertThat(WaiterReconciler.carries(tree(INSURER_PAYLOAD), tree(INSURER_PAYLOAD))).isTrue();
                assertThat(WaiterReconciler.carries(tree(ENRICHMENT_PAYLOAD), tree(ENRICHMENT_PAYLOAD))).isTrue();
        }

        @Test
        void timeoutStepThatReadTheRecordedResultDeliversIt() throws Exception {
                String insurerStep = "{\"ExecutedVersion\":\"$LATEST\",\"Payload\":{\"insurerId\":\"beta\","
                                + "\"status\":\"QUOTED\",\"error\":\"States.Timeout\"},\"StatusCode\":200}";
                String enrichmentStep = "{\"ExecutedVersion\":\"$LATEST\",\"Payload\":{\"resultType\":\"FOUND\","
                                + "\"evidenceId\":\"ev_3f7c\",\"queryId\":\"q_c571\",\"reuseDecision\":\"HIT\"},\"StatusCode\":200}";

                assertThat(WaiterReconciler.carries(tree(insurerStep), tree(INSURER_PAYLOAD))).isTrue();
                assertThat(WaiterReconciler.carries(tree(enrichmentStep), tree(ENRICHMENT_PAYLOAD))).isTrue();
        }

        @Test
        void timeoutStepWithoutTheResultOrAnotherTaskDoesNotDeliverIt() throws Exception {
                String timedOut = "{\"ExecutedVersion\":\"$LATEST\",\"Payload\":{\"insurerId\":\"beta\","
                                + "\"status\":\"TIMED_OUT\",\"error\":\"States.Timeout\"},\"StatusCode\":200}";
                String otherInsurer = "{\"insurerId\":\"alpha\",\"status\":\"QUOTED\",\"reasonCode\":null}";
                String enrichmentFailed = "{\"ExecutedVersion\":\"$LATEST\",\"Payload\":{\"resultType\":\"FAILED\","
                                + "\"errorClass\":\"STEP_TIMEOUT\"},\"StatusCode\":200}";
                String consolidation = "{\"ExecutedVersion\":\"$LATEST\",\"Payload\":{\"outcome\":\"PARTIAL\"},"
                                + "\"StatusCode\":200}";

                assertThat(WaiterReconciler.carries(tree(timedOut), tree(INSURER_PAYLOAD))).isFalse();
                assertThat(WaiterReconciler.carries(tree(otherInsurer), tree(INSURER_PAYLOAD))).isFalse();
                assertThat(WaiterReconciler.carries(tree(consolidation), tree(INSURER_PAYLOAD))).isFalse();
                assertThat(WaiterReconciler.carries(tree(enrichmentFailed), tree(ENRICHMENT_PAYLOAD))).isFalse();
                assertThat(WaiterReconciler.carries(tree("\"text\""), tree(ENRICHMENT_PAYLOAD))).isFalse();
        }

        private static JsonNode tree(String json) throws Exception {
                return JSON.readTree(json);
        }
}
