package com.mmda.serverless.quote.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;

/**
 * O catálogo de {@code contracts/fixtures} precisa chegar ao zip da Lambda (§10
 * do desenho): a API o lê
 * do classpath. Roda no {@code integration-test}, depois do {@code package}.
 */
class LambdaZipCatalogIT {

    private static final Path ZIP = Path.of("target/quote-api-lambda.zip");

    @Test
    void lambdaZipCarriesTheSameCatalogAsTheContract() throws IOException {
        assertThat(ZIP).exists();
        byte[] expected = Files.readAllBytes(Path.of("../../contracts/fixtures/catalog.json"));

        assertThat(findCatalog()).isEqualTo(expected);
    }

    private static byte[] findCatalog() throws IOException {
        try (ZipFile zip = new ZipFile(ZIP.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(Catalog.RESOURCE)) {
                    try (InputStream content = zip.getInputStream(entry)) {
                        return content.readAllBytes();
                    }
                }
                if (entry.getName().endsWith(".jar")) {
                    byte[] nested = findInJar(zip.getInputStream(entry));
                    if (nested != null) {
                        return nested;
                    }
                }
            }
        }
        throw new AssertionError(Catalog.RESOURCE + " not found in " + ZIP);
    }

    private static byte[] findInJar(InputStream jar) throws IOException {
        try (ZipInputStream entries = new ZipInputStream(jar)) {
            for (ZipEntry entry = entries.getNextEntry(); entry != null; entry = entries.getNextEntry()) {
                if (entry.getName().equals(Catalog.RESOURCE)) {
                    return entries.readAllBytes();
                }
            }
        }
        return null;
    }
}
