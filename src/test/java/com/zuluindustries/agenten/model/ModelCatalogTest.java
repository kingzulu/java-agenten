package com.zuluindustries.agenten.model;

import com.zuluindustries.agenten.support.Fixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelCatalogTest {

    @TempDir
    Path root;

    private ModelCatalog load(String yaml) {
        Fixtures.write(root.resolve(ModelCatalog.RELATIVE_PATH), yaml);
        return ModelCatalog.load(root);
    }

    private String error(String yaml) {
        return assertThrows(IllegalArgumentException.class, () -> load(yaml)).getMessage();
    }

    @Test
    void loadsProfilesPricesAndPeriods() {
        ModelCatalog catalog = load("""
                prices_as_of: 2026-10-18
                profiles:
                  schnell: {provider: openai, model: m}
                models:
                  m:
                    currency: USD
                    source: https://example.org
                    prices:
                      - valid_from: 2027-01-01
                        input: 2.0
                        output: 8.0
                      - input: 1.0
                        cached_input: 0.1
                        output: 4.0
                        long_context: {above_input_tokens: 200000, input: 3.0, output: 9.0}
                """);

        assertEquals(LocalDate.parse("2026-10-18"), catalog.pricesAsOf());
        assertEquals(new ModelProfile("openai", "m"), catalog.profiles().get("schnell"));
        ModelPricing.ModelPrice price = catalog.pricing().get("m");
        assertEquals(1.0, price.periodAt(LocalDate.parse("2026-12-31")).rates().input());
        assertEquals(0.1, price.periodAt(LocalDate.parse("2026-12-31")).rates().cachedInput());
        assertEquals(3.0, price.periodAt(LocalDate.parse("2026-12-31")).ratesFor(200_001).input());
        assertEquals(2.0, price.periodAt(LocalDate.parse("2027-01-01")).rates().input());
    }

    @Test
    void rejectsTyposAndMissingPrices() {
        assertTrue(error("""
                prices_as_of: 2026-10-18
                profiles: {p: {provider: openai, model: m}}
                models: {m: {currency: USD, prices: [{input: 1.0, cached_inptu: 0.1, output: 2.0}]}}
                """).contains("Unbekanntes Feld 'cached_inptu'"));
        assertTrue(error("""
                prices_as_of: 2026-10-18
                profiles: {p: {provider: openai, model: fehlt}}
                models: {m: {currency: USD, prices: [{input: 1.0, output: 2.0}]}}
                """).contains("keine Preise hinterlegt"));
        assertTrue(error("""
                prices_as_of: 2026-10-18
                profiles: {}
                models: {m: {currency: USD, prices: [{input: 1.0}]}}
                """).contains("output fehlt"));
        assertTrue(error("""
                prices_as_of: 2026-10-18
                profiles: {}
                models: {m: {currency: CHF, prices: [{input: 1.0, output: 2.0}]}}
                """).contains("Kostenwährung 'CHF'"));
        assertTrue(error("""
                prices_as_of: 2026-10-18
                profiles: {}
                models: {m: {currency: USD, prices: [{input: -1.0, output: 2.0}]}}
                """).contains("Ungültiger Preis"));
        assertTrue(error("""
                prices_as_of: 2026-10-18
                profiles: {}
                models: {m: {currency: USD, prices: [{input: 1.0, output: 2.0}, {input: 1.0, output: 2.0}]}}
                """).contains("eindeutige valid_from"));
        assertTrue(error("profiles: {}\nmodels: {}\n").contains("prices_as_of"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ModelCatalog.load(root.resolve("leer")))
                .getMessage().startsWith("Modellkatalog fehlt"));
    }

    @Test
    void shippedCatalogIsComplete() {
        ModelCatalog catalog = ModelCatalog.load(Path.of("").toAbsolutePath());

        for (ModelProfile profile : catalog.profiles().all().values()) {
            assertTrue(catalog.pricing().contains(profile.model()), profile.model());
        }
        assertEquals("gpt-5.4-mini", catalog.profiles().get("openai-mini").model());
    }
}
