package com.mmda.serverless.quote.e2e;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

/**
 * Parâmetros do stage (§12 do desenho) lidos do bloco {@code params} do
 * {@code serverless.yml} da quote, para o
 * e2e esperar exatamente o orçamento que a máquina de estado tem, e não um
 * número próprio.
 */
record StageParameters(int enrichmentTimeoutSeconds, int insurerTimeoutSeconds, int mapConcurrency,
        int stepsMarginSeconds, int retryAfterSeconds) {

    static StageParameters load(Path serverlessYml, String stage) {
        Map<String, Object> values = valuesOf(serverlessYml, stage);
        return new StageParameters(
                number(values, "enrichmentTimeoutSeconds"),
                number(values, "insurerTimeoutSeconds"),
                number(values, "mapConcurrency"),
                number(values, "stepsMarginSeconds"),
                number(values, "retryAfterSeconds"));
    }

    /**
     * Parâmetro numérico de outro {@code serverless.yml} no stage, como a quota de
     * um provedor na provider (T21).
     */
    static int number(Path serverlessYml, String stage, String name) {
        return number(valuesOf(serverlessYml, stage), name);
    }

    /**
     * Orçamento sem esperas externas: a versão termina nos passos simples (T23,
     * T24).
     */
    Duration withoutExternalWaits() {
        return Duration.ofSeconds(stepsMarginSeconds);
    }

    /**
     * Invariante do §9 para {@code insurers} seguradoras: enriquecimento, rodadas
     * do Map e margem dos passos.
     */
    Duration withInsurers(int insurers) {
        int rounds = (insurers + mapConcurrency - 1) / mapConcurrency;
        return Duration
                .ofSeconds(enrichmentTimeoutSeconds + (long) rounds * insurerTimeoutSeconds + stepsMarginSeconds);
    }

    Duration pollInterval() {
        return Duration.ofSeconds(retryAfterSeconds);
    }

    private static Map<String, Object> valuesOf(Path serverlessYml, String stage) {
        Map<String, Object> params = paramsOf(serverlessYml);
        Map<String, Object> values = new HashMap<>(section(params, "default"));
        values.putAll(section(params, stage));
        return values;
    }

    // WHY: o resto do arquivo usa tags do CloudFormation (!Ref, !GetAtt) que o
    // SnakeYAML não conhece; o bloco
    // params é YAML simples
    @SuppressWarnings("unchecked")
    private static Map<String, Object> paramsOf(Path serverlessYml) {
        List<String> block = new ArrayList<>();
        boolean inside = false;
        try {
            for (String line : Files.readAllLines(serverlessYml)) {
                boolean topLevel = !line.isBlank() && !Character.isWhitespace(line.charAt(0)) && !line.startsWith("#");
                if (topLevel) {
                    inside = line.startsWith("params:");
                }
                if (inside) {
                    block.add(line);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("cannot read " + serverlessYml, exception);
        }
        Map<String, Object> document = new Yaml().load(String.join("\n", block));
        return (Map<String, Object>) document.get("params");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<String, Object> params, String name) {
        Object section = params.get(name);
        return section instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static int number(Map<String, Object> values, String name) {
        Object value = values.get(name);
        if (value == null) {
            throw new IllegalStateException("serverless.yml has no param " + name);
        }
        return Integer.parseInt(value.toString());
    }
}
