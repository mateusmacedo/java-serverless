package com.mmda.serverless.quote.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mmda.serverless.quote.domain.QuoteSnapshot;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/**
 * Catálogo de fixtures do laboratório, lido do classpath ({@code contracts/fixtures/catalog.json}
 * empacotado no zip). Referências só se resolvem dentro da organização do usuário.
 */
@Singleton
public class Catalog {

    static final String RESOURCE = "fixtures/catalog.json";

    private final CatalogDocument document;

    Catalog(ObjectMapper objectMapper) {
        try (InputStream json = Thread.currentThread().getContextClassLoader().getResourceAsStream(RESOURCE)) {
            if (json == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the classpath");
            }
            document = objectMapper.readValue(json, CatalogDocument.class);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public Optional<Organization> organization(String organizationId) {
        return document.organizations().stream().filter(org -> org.id().equals(organizationId)).findFirst();
    }

    public List<QuoteSnapshot.CoverageProfile> coverageProfiles() {
        return document.coverageProfiles();
    }

    public Optional<QuoteSnapshot.CoverageProfile> coverageProfile(String code) {
        return document.coverageProfiles().stream().filter(profile -> profile.code().equals(code)).findFirst();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CatalogDocument(List<QuoteSnapshot.CoverageProfile> coverageProfiles, List<Organization> organizations) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Organization(
            String id,
            String label,
            List<QuoteSnapshot.Party> customers,
            List<QuoteSnapshot.Vehicle> vehicles,
            List<QuoteSnapshot.Driver> drivers) {

        public Optional<QuoteSnapshot.Party> customer(String id) {
            return customers.stream().filter(customer -> customer.id().equals(id)).findFirst();
        }

        public Optional<QuoteSnapshot.Vehicle> vehicle(String id) {
            return vehicles.stream().filter(vehicle -> vehicle.id().equals(id)).findFirst();
        }

        public Optional<QuoteSnapshot.Driver> driver(String id) {
            return drivers.stream().filter(driver -> driver.id().equals(id)).findFirst();
        }
    }
}
