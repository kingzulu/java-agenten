package com.zuluindustries.agenten.model;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/** Minimale HTTP-Schnittstelle der Provideradapter; in Tests austauschbar. */
@FunctionalInterface
public interface HttpTransport {

    record Response(int status, String body) {
    }

    Response postJson(URI uri, Map<String, String> headers, String body, Duration timeout);

    /** Standardtransport auf Basis von {@link HttpClient}. */
    static HttpTransport jdk() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        return (uri, headers, body, timeout) -> {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (timeout != null) {
                builder.timeout(timeout);
            }
            headers.forEach(builder::header);
            try {
                HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                return new Response(response.statusCode(), response.body());
            } catch (IOException error) {
                throw new ProviderException("Provider konnte nicht erreicht werden: " + error.getMessage(), error);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new ProviderException("Provideraufruf wurde unterbrochen.", error);
            }
        };
    }
}
