package com.mmda.serverless.provider.simulators;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * O catálogo de {@code contracts/fixtures} precisa chegar ao zip da Lambda dos simuladores, que o lê do classpath, com
 * os mesmos bytes do contrato. Roda no {@code integration-test}, depois do {@code package}.
 */
class LambdaZipCatalogIT {

    private static final Path ZIP = Path.of("target/provider-simulators-lambda.zip");

    @Test
    void lambdaZipCarriesTheSameCatalogAsTheContract() throws IOException {
        assertThat(ZIP).exists();
        byte[] expected = Files.readAllBytes(Path.of("../../contracts/fixtures/catalog.json"));

        try (ZipFile zip = new ZipFile(ZIP.toFile())) {
            ZipEntry entry = zip.getEntry(LabCatalog.RESOURCE);
            assertThat(entry).as(LabCatalog.RESOURCE + " in " + ZIP).isNotNull();
            try (InputStream content = zip.getInputStream(entry)) {
                assertThat(content.readAllBytes()).isEqualTo(expected);
            }
        }
    }
}
