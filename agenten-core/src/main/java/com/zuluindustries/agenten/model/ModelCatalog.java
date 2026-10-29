package com.zuluindustries.agenten.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.zuluindustries.agenten.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Modellprofile und Listenpreise aus {@code config/models.yaml}. Die Datei wird
 * streng geprüft: Unbekannte Felder, fehlende Preise oder Profile ohne Preis sind
 * Fehler, damit sich kein Tippfehler unbemerkt auf die Kosten auswirkt.
 *
 * @param pricesAsOf Datum, an dem die Preise zuletzt mit den Anbieterseiten abgeglichen wurden
 */
public record ModelCatalog(ModelProfiles profiles, ModelPricing pricing, LocalDate pricesAsOf) {

    public static final String RELATIVE_PATH = "config/models.yaml";

    private static final Set<String> ROOT_FIELDS = Set.of("prices_as_of", "profiles", "models");
    private static final Set<String> PROFILE_FIELDS = Set.of("provider", "model");
    private static final Set<String> MODEL_FIELDS = Set.of("currency", "source", "prices");
    private static final Set<String> PERIOD_FIELDS = Set.of("valid_from", "input", "cached_input", "cache_write",
            "output", "long_context");
    private static final Set<String> LONG_CONTEXT_FIELDS = Set.of("above_input_tokens", "input", "cached_input",
            "cache_write", "output");

    public ModelCatalog {
        for (Map.Entry<String, ModelProfile> profile : profiles.all().entrySet()) {
            if (!pricing.contains(profile.getValue().model())) {
                throw new IllegalArgumentException("Modellprofil '" + profile.getKey() + "' verweist auf Modell '"
                        + profile.getValue().model() + "', für das keine Preise hinterlegt sind.");
            }
        }
    }

    /** Lädt {@code config/models.yaml} unterhalb des Projektverzeichnisses. */
    public static ModelCatalog load(Path projectRoot) {
        Path path = projectRoot.resolve(RELATIVE_PATH);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Modellkatalog fehlt: " + path.toAbsolutePath().normalize());
        }
        try {
            return parse(Json.DEFINITIONS_YAML.readTree(path.toFile()));
        } catch (IOException | IllegalArgumentException error) {
            throw new IllegalArgumentException("Ungültiger Modellkatalog '" + path + "': " + error.getMessage(), error);
        }
    }

    static ModelCatalog parse(JsonNode root) {
        object(root, "Wurzel", ROOT_FIELDS);
        LocalDate asOf = date(root.get("prices_as_of"), "prices_as_of");

        Map<String, ModelPrice> prices = new LinkedHashMap<>();
        JsonNode models = object(root.get("models"), "models", null);
        for (Iterator<Map.Entry<String, JsonNode>> it = models.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            prices.put(entry.getKey(), modelPrice(entry.getKey(), entry.getValue()));
        }

        Map<String, ModelProfile> profiles = new LinkedHashMap<>();
        JsonNode profileNodes = object(root.get("profiles"), "profiles", null);
        for (Iterator<Map.Entry<String, JsonNode>> it = profileNodes.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String where = "profiles." + entry.getKey();
            JsonNode node = object(entry.getValue(), where, PROFILE_FIELDS);
            profiles.put(entry.getKey(), new ModelProfile(text(node.get("provider"), where + ".provider"),
                    text(node.get("model"), where + ".model")));
        }

        return new ModelCatalog(new ModelProfiles(profiles), new ModelPricing(convert(prices)), asOf);
    }

    private record ModelPrice(String currency, String source, List<ModelPricing.Period> periods) {
    }

    private static Map<String, ModelPricing.ModelPrice> convert(Map<String, ModelPrice> prices) {
        Map<String, ModelPricing.ModelPrice> result = new LinkedHashMap<>();
        prices.forEach((model, price) -> {
            try {
                result.put(model, new ModelPricing.ModelPrice(price.currency(), price.source(), price.periods()));
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("models." + model + ": " + error.getMessage(), error);
            }
        });
        return result;
    }

    private static ModelPrice modelPrice(String model, JsonNode node) {
        String where = "models." + model;
        object(node, where, MODEL_FIELDS);
        String currency = text(node.get("currency"), where + ".currency");
        String source = node.hasNonNull("source") ? text(node.get("source"), where + ".source") : null;
        JsonNode periods = node.get("prices");
        if (periods == null || !periods.isArray() || periods.isEmpty()) {
            throw new IllegalArgumentException(where + ".prices muss eine nicht leere Liste sein.");
        }
        List<ModelPricing.Period> result = new ArrayList<>();
        for (int i = 0; i < periods.size(); i++) {
            String periodWhere = where + ".prices[" + i + "]";
            JsonNode period = object(periods.get(i), periodWhere, PERIOD_FIELDS);
            LocalDate validFrom = period.hasNonNull("valid_from")
                    ? date(period.get("valid_from"), periodWhere + ".valid_from") : null;
            ModelPricing.LongContext longContext = null;
            if (period.hasNonNull("long_context")) {
                String lcWhere = periodWhere + ".long_context";
                JsonNode lc = object(period.get("long_context"), lcWhere, LONG_CONTEXT_FIELDS);
                JsonNode threshold = lc.get("above_input_tokens");
                if (threshold == null || !threshold.canConvertToLong()) {
                    throw new IllegalArgumentException(lcWhere + ".above_input_tokens fehlt oder ist keine Zahl.");
                }
                longContext = new ModelPricing.LongContext(threshold.asLong(), rates(lc, lcWhere));
            }
            result.add(new ModelPricing.Period(validFrom, rates(period, periodWhere), longContext));
        }
        return new ModelPrice(currency, source, result);
    }

    private static ModelPricing.Rates rates(JsonNode node, String where) {
        try {
            return new ModelPricing.Rates(number(node.get("input"), where + ".input"),
                    optionalNumber(node.get("cached_input"), where + ".cached_input"),
                    optionalNumber(node.get("cache_write"), where + ".cache_write"),
                    number(node.get("output"), where + ".output"));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(where + ": " + error.getMessage(), error);
        }
    }

    private static JsonNode object(JsonNode node, String where, Set<String> allowed) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(where + " muss ein Objekt sein.");
        }
        if (allowed != null) {
            for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
                String name = names.next();
                if (!allowed.contains(name)) {
                    throw new IllegalArgumentException("Unbekanntes Feld '" + name + "' in " + where + ".");
                }
            }
        }
        return node;
    }

    private static String text(JsonNode node, String where) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(where + " fehlt oder ist kein Text.");
        }
        return node.asText();
    }

    private static double number(JsonNode node, String where) {
        if (node == null || !node.isNumber()) {
            throw new IllegalArgumentException(where + " fehlt oder ist keine Zahl.");
        }
        return node.asDouble();
    }

    private static Double optionalNumber(JsonNode node, String where) {
        return node == null || node.isNull() ? null : number(node, where);
    }

    private static LocalDate date(JsonNode node, String where) {
        try {
            return LocalDate.parse(text(node, where));
        } catch (DateTimeParseException error) {
            throw new IllegalArgumentException(where + " ist kein Datum der Form JJJJ-MM-TT.", error);
        }
    }
}
