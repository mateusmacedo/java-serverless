package com.mmda.serverless.sample.api;

import io.floci.testcontainers.FlociContainer;

/**
 * Um único Floci por execução dos testes de integração do módulo.
 * <p>
 * WHY: endpoint, região e credenciais vão para as propriedades de sistema que o AWS SDK lê nas
 * cadeias padrão. Assim os testes passam pela autoconfiguração real do módulo spring-aws, em vez de
 * clients montados à mão.
 */
abstract class FlociIntegrationTest {

    static final FlociContainer FLOCI = new FlociContainer(System.getProperty("floci.image", "floci/floci:2.4.0"));

    static {
        FLOCI.start();
        System.setProperty("aws.endpointUrl", FLOCI.getEndpoint());
        System.setProperty("aws.region", FLOCI.getRegion());
        System.setProperty("aws.accessKeyId", FLOCI.getAccessKey());
        System.setProperty("aws.secretAccessKey", FLOCI.getSecretKey());
    }
}
