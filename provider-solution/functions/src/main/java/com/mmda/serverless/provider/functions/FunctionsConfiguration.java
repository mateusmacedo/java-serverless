package com.mmda.serverless.provider.functions;

import com.mmda.serverless.provider.domain.RetryPolicy;
import com.mmda.serverless.provider.domain.operations.DeadLetterPolicy;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.random.RandomGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class FunctionsConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    Sleeper sleeper() {
        return duration -> {
            try {
                Thread.sleep(duration);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during the retry backoff", exception);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    RandomGenerator jitter() {
        return RandomGenerator.getDefault();
    }

    /** WHY: o EMF é lido da saída padrão da Lambda, uma linha JSON por registro, fora do formato do log. */
    @Bean
    @ConditionalOnMissingBean
    Metrics.MetricsSink metricsSink() {
        return System.out::println;
    }

    @Bean
    DeadLetterPolicy deadLetterPolicy(OperationsProperties operations) {
        return new DeadLetterPolicy(operations.dlqMaxAttempts());
    }

    @Bean
    RetryPolicy retryPolicy(ProviderProperties properties) {
        return new RetryPolicy(properties.workerRetries(), Duration.ofSeconds(properties.retryBackoffMaxSeconds()));
    }

    /** WHY: o cliente não repete nada; o worker é o único dono do retry (desenho, §9). */
    @Bean
    HttpClient providerHttpClient(ProviderProperties properties) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.httpTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }
}
