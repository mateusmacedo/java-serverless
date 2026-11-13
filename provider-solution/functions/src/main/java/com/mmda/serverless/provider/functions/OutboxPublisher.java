package com.mmda.serverless.provider.functions;

import java.time.Clock;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.eventbridge.model.PutEventsRequestEntry;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResponse;
import software.amazon.awssdk.services.eventbridge.model.PutEventsResultEntry;

/**
 * Publicação de um item do outbox no barramento da plataforma, comum à relay e ao republicador (§7). A
 * entrada do {@code PutEvents} é conferida uma a uma; publicar de novo não tem efeito, porque o
 * {@code eventId} vai no {@code detail} e os consumidores descartam a repetição.
 */
@Component
class OutboxPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final Set<String> TRANSIENT_ENTRY_ERRORS =
            Set.of("ThrottlingException", "InternalFailure", "InternalException", "ServiceUnavailable");

    private final EventBridgeClient eventBridge;
    private final OutboxStore outbox;
    private final ProviderProperties properties;
    private final Clock clock;

    OutboxPublisher(EventBridgeClient eventBridge, OutboxStore outbox, ProviderProperties properties, Clock clock) {
        this.eventBridge = eventBridge;
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    enum Result {
        PUBLISHED,
        /** Vale repetir: o registro volta em {@code batchItemFailures}. */
        TRANSIENT_FAILURE,
        /** Não vale repetir: grava {@code attempts} e {@code lastError} e o item continua pendente. */
        PERMANENT_FAILURE
    }

    Result publish(OutboxEntry entry) {
        PutOutcome outcome = put(entry);
        if (outcome.published()) {
            return markPublished(entry);
        }
        if (outcome.retryable()) {
            LOGGER.warn("Transient failure publishing event {}: {}", entry.eventId(), outcome.error());
            return Result.TRANSIENT_FAILURE;
        }
        LOGGER.error("Permanent failure publishing event {}: {}", entry.eventId(), outcome.error());
        try {
            outbox.recordFailure(entry.eventId(), outcome.error(), clock.instant());
            return Result.PERMANENT_FAILURE;
        } catch (SdkException exception) {
            LOGGER.warn("Could not record the failure of event {}", entry.eventId(), exception);
            return Result.TRANSIENT_FAILURE;
        }
    }

    private PutOutcome put(OutboxEntry entry) {
        PutEventsRequestEntry request = PutEventsRequestEntry.builder()
                .eventBusName(properties.eventBusName())
                .source(properties.eventSource())
                .detailType(entry.detailType())
                .detail(entry.detail())
                .build();
        PutEventsResponse response;
        try {
            response = eventBridge.putEvents(builder -> builder.entries(request));
        } catch (SdkClientException exception) {
            return PutOutcome.failed(true, exception.getClass().getSimpleName());
        } catch (AwsServiceException exception) {
            String code = exception.awsErrorDetails() == null
                    ? exception.getClass().getSimpleName()
                    : exception.awsErrorDetails().errorCode();
            return PutOutcome.failed(exception.statusCode() >= 500 || exception.isThrottlingException(), code);
        }
        if (response.failedEntryCount() == null || response.failedEntryCount() == 0) {
            return PutOutcome.PUBLISHED;
        }
        PutEventsResultEntry failed = response.entries().getFirst();
        return PutOutcome.failed(TRANSIENT_ENTRY_ERRORS.contains(failed.errorCode()),
                failed.errorCode() + ": " + failed.errorMessage());
    }

    private Result markPublished(OutboxEntry entry) {
        try {
            outbox.markPublished(entry.eventId(), clock.instant());
            return Result.PUBLISHED;
        } catch (SdkException exception) {
            LOGGER.warn("Event {} was published but not marked", entry.eventId(), exception);
            return Result.TRANSIENT_FAILURE;
        }
    }

    private record PutOutcome(boolean published, boolean retryable, String error) {

        static final PutOutcome PUBLISHED = new PutOutcome(true, false, null);

        static PutOutcome failed(boolean retryable, String error) {
            return new PutOutcome(false, retryable, error);
        }
    }
}
