package com.zuluindustries.agenten.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.zuluindustries.agenten.util.Json;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

/** Gemeinsame Hilfen für HTTP-basierte Provideradapter. */
public final class HttpProviderSupport {

    private HttpProviderSupport() {
    }

    public static JsonNode post(HttpTransport transport, String providerLabel, URI uri,
                                Map<String, String> headers, Object body, Duration timeout) {
        HttpTransport.Response response = transport.postJson(uri, headers, Json.write(body), timeout);
        if (response.status() < 200 || response.status() >= 300) {
            throw new ProviderException(providerLabel + " antwortete mit HTTP " + response.status() + ": "
                    + abbreviate(response.body(), 500));
        }
        try {
            return Json.readTree(response.body());
        } catch (RuntimeException error) {
            throw new ProviderException(providerLabel + " lieferte keine gültige JSON-Antwort.", error);
        }
    }

    public static String requireKey(Function<String, String> environment, String variable) {
        String key = environment.apply(variable);
        if (key == null || key.isBlank()) {
            throw new ProviderException(variable + " ist nicht gesetzt.");
        }
        return key;
    }

    private static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
