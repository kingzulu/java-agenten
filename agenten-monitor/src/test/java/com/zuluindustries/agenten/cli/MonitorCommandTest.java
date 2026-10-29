package com.zuluindustries.agenten.cli;

import com.zuluindustries.agenten.Platform;
import com.zuluindustries.agenten.core.Settings;
import com.zuluindustries.agenten.model.ProviderRegistry;
import com.zuluindustries.agenten.support.Fixtures;
import com.zuluindustries.agenten.support.FakeProvider;
import com.zuluindustries.agenten.util.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code agenten --monitor} mit den mitgelieferten Definitionen in einem temporären Projektverzeichnis. */
class MonitorCommandTest {

    @TempDir
    Path home;

    final FakeProvider openai = new FakeProvider("openai");
    ByteArrayOutputStream out;
    ByteArrayOutputStream err;

    static final String CONFIG = """
            portals:
              lager:
                name: Lager
                connector: demo
                delay_ms: 0
                settings:
                  items:
                    "4711": {available: %s, price: "29.90", currency: EUR}
            products:
              - id: produkt_a
                name: Produkt A
                portals: {lager: {sku: "4711"}}
            """;

    @BeforeEach
    void copyDefinitions() throws IOException {
        Path repository = Path.of("").toAbsolutePath();
        // Bericht-Agent und Workflow bringt das Monitor-Modul selbst mit.
        Fixtures.write(home.resolve("config/models.yaml"), Files.readString(repository.resolve("config/models.yaml")));
    }

    private int run(String availability) {
        Fixtures.write(home.resolve("config/monitor.yaml"), CONFIG.formatted(availability));
        out = new ByteArrayOutputStream();
        err = new ByteArrayOutputStream();
        Environment environment = Environment.of(Map.of("AGENTEN_HOME", home.toString()));
        Platform platform = Platform.builder(Settings.load(environment), environment)
                .providers(new ProviderRegistry().register(openai))
                .build();
        return new Main().run(platform, new String[] {"--monitor"}, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void firstRunPrintsOverviewWithoutCallingTheAgent() {
        assertEquals(0, run("false"));

        assertTrue(out().contains("Produkt A  Lager   nicht verfügbar"), out());
        assertTrue(out().contains("Keine Änderungen seit dem letzten Lauf."));
        assertFalse(out().contains("Bericht:"));
        assertTrue(openai.requests.isEmpty());
        assertTrue(Files.isRegularFile(home.resolve("data/monitor.db")));
    }

    @Test
    void changeTriggersReportAgent() {
        run("false");
        openai.then(FakeProvider.text("Produkt A ist im Lager wieder verfügbar."));

        assertEquals(0, run("true"));

        String output = out();
        assertTrue(output.contains("- Produkt A bei Lager: wieder verfügbar (nicht verfügbar → verfügbar)"), output);
        assertTrue(output.contains("Bericht:\nProdukt A ist im Lager wieder verfügbar."), output);
        assertTrue(output.contains("Kosten: 0.000030 USD"), output);
        String prompt = openai.requests.getFirst().toString();
        assertTrue(prompt.contains("wieder verfügbar"), prompt);
    }

    @Test
    void overviewIsPrintedEvenIfReportFails() {
        run("false");
        openai.thenFail(new IllegalStateException("OPENAI_API_KEY ist nicht gesetzt."));
        openai.thenFail(new IllegalStateException("OPENAI_API_KEY ist nicht gesetzt."));

        assertEquals(1, run("true"));

        assertTrue(out().contains("wieder verfügbar"), out());
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("OPENAI_API_KEY"));
    }

    @Test
    void reportsMissingConfiguration() {
        Environment environment = Environment.of(Map.of("AGENTEN_HOME", home.toString()));
        Platform platform = Platform.builder(Settings.load(environment), environment).build();
        err = new ByteArrayOutputStream();

        assertEquals(1, new Main().run(platform, new String[] {"--monitor"}, System.out,
                new PrintStream(err, true, StandardCharsets.UTF_8)));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("config/monitor.yaml fehlt"));
    }
}
