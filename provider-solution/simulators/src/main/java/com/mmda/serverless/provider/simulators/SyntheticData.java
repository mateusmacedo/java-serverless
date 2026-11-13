package com.mmda.serverless.provider.simulators;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dados sintéticos e determinísticos pela referência, para o que o catálogo do
 * laboratório não tem (valor de
 * mercado, sinistros e prêmio) e para referências fora dele, como as dos testes
 * da provider: a mesma referência
 * sempre gera o mesmo perfil e a mesma oferta.
 */
final class SyntheticData {

    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(19[5-9]\\d|20[0-4]\\d)(?!\\d)");
    private static final List<String[]> MODELS = List.of(
            new String[] { "Fiat", "Argo" },
            new String[] { "Volkswagen", "Polo" },
            new String[] { "Chevrolet", "Onix" },
            new String[] { "Toyota", "Corolla" },
            new String[] { "Hyundai", "HB20" },
            new String[] { "Honda", "Civic" });

    private SyntheticData() {
    }

    /**
     * Referência que o provedor declara não conhecer: contém {@code unknown} ou
     * {@code not-found}.
     */
    static boolean isUnknown(String ref) {
        String normalized = ref.toLowerCase(Locale.ROOT);
        return normalized.contains("unknown") || normalized.contains("not-found");
    }

    static VehicleData vehicle(String ref) {
        long hash = hash(ref);
        String[] model = MODELS.get((int) Math.floorMod(hash, MODELS.size()));
        Matcher year = YEAR.matcher(ref);
        int modelYear = year.find() ? Integer.parseInt(year.group(1)) : 2012 + (int) Math.floorMod(hash >> 8, 12);
        BigDecimal marketValue = BigDecimal.valueOf(35_000 + Math.floorMod(hash >> 16, 90) * 1_000L)
                .setScale(2, RoundingMode.UNNECESSARY);
        return new VehicleData(ref, model[0], model[1], modelYear, marketValue);
    }

    static DriverData driver(String ref) {
        long hash = hash(ref);
        return new DriverData(ref, 1 + (int) Math.floorMod(hash, 30), (int) Math.floorMod(hash >> 8, 3));
    }

    static BigDecimal basePremium(String vehicleRef, String usage) {
        BigDecimal base = BigDecimal.valueOf(1_800 + Math.floorMod(hash(vehicleRef), 1_500)).setScale(2,
                RoundingMode.UNNECESSARY);
        return "COMMERCIAL".equals(usage) ? base.multiply(new BigDecimal("1.35")).setScale(2, RoundingMode.HALF_UP)
                : base;
    }

    static String shortCode(String value) {
        return HexFormat.of().formatHex(sha256(value)).substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static long hash(String value) {
        byte[] digest = sha256(value);
        long result = 0;
        for (int index = 0; index < Long.BYTES; index++) {
            result = (result << 8) | (digest[index] & 0xff);
        }
        return result;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    record VehicleData(String ref, String make, String model, int modelYear, BigDecimal marketValue) {
    }

    record DriverData(String ref, int licenseYears, int claimsLast5Years) {
    }
}
