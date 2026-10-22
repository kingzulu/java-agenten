package com.zuluindustries.agenten.monitor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ergebnis eines Monitor-Laufs: alle Prüfergebnisse und die zu meldenden Änderungen.
 */
public record MonitorReport(String runId, Instant checkedAt, List<CheckResult> results, List<Change> changes,
                            MonitorConfig config) {

    public MonitorReport {
        results = List.copyOf(results);
        changes = List.copyOf(changes);
    }

    public long count(Availability availability) {
        return results.stream().filter(result -> result.availability() == availability).count();
    }

    /** Darstellung als Workflow-Ergebnis; Preise als Text, damit sie exakt bleiben. */
    public Map<String, Object> toMap() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("geprueft", results.size());
        summary.put("verfuegbar", count(Availability.AVAILABLE));
        summary.put("nicht_verfuegbar", count(Availability.UNAVAILABLE));
        summary.put("unbekannt", count(Availability.UNKNOWN));

        List<Map<String, Object>> changeList = new ArrayList<>();
        for (Change change : changes) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("art", change.type().key());
            item.put("beschreibung", change.type().label());
            item.put("ware", productName(change.productId()));
            item.put("portal", portalName(change.portal()));
            item.put("vorher", change.before());
            item.put("nachher", change.after());
            changeList.add(item);
        }

        List<Map<String, Object>> resultList = new ArrayList<>();
        for (CheckResult result : results) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("ware_id", result.productId());
            item.put("ware", productName(result.productId()));
            item.put("portal", portalName(result.portal()));
            item.put("artikelnummer", result.sku());
            item.put("verfuegbarkeit", result.availability().key());
            item.put("menge", result.quantity());
            item.put("preis", result.price() == null ? null : result.price().toPlainString());
            item.put("waehrung", result.currency());
            item.put("lieferzeit", result.delivery());
            item.put("fehler", result.error());
            resultList.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("run_id", runId);
        data.put("geprueft_am", checkedAt.toString());
        data.put("zusammenfassung", summary);
        data.put("has_changes", !changes.isEmpty());
        data.put("aenderungen", changeList);
        data.put("ergebnisse", resultList);
        data.put("tabelle", table());
        return data;
    }

    /** Übersicht als Texttabelle. */
    public String table() {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Ware", "Portal", "Verfügbarkeit", "Menge", "Preis", "Lieferzeit"});
        for (CheckResult result : results) {
            String availability = result.known() ? result.availability().label()
                    : result.availability().label() + " (" + result.error() + ")";
            rows.add(new String[] {
                    productName(result.productId()),
                    portalName(result.portal()),
                    availability,
                    result.quantity() == null ? "–" : result.quantity().toString(),
                    ChangeDetector.formatPrice(result.price(), result.currency()),
                    result.delivery() == null ? "–" : result.delivery()});
        }
        int[] widths = new int[rows.getFirst().length];
        for (String[] row : rows) {
            for (int i = 0; i < row.length; i++) {
                widths[i] = Math.max(widths[i], row[i].length());
            }
        }
        StringBuilder text = new StringBuilder();
        for (int r = 0; r < rows.size(); r++) {
            String[] row = rows.get(r);
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < row.length; i++) {
                line.append(i == 0 ? "" : "  ").append(row[i]).append(" ".repeat(widths[i] - row[i].length()));
            }
            text.append(line.toString().stripTrailing()).append('\n');
            if (r == 0) {
                int total = 0;
                for (int width : widths) {
                    total += width;
                }
                text.append("-".repeat(total + 2 * (widths.length - 1))).append('\n');
            }
        }
        return text.toString();
    }

    private String productName(String productId) {
        return config.products().stream().filter(product -> product.id().equals(productId))
                .map(MonitorConfig.ProductConfig::name).findFirst().orElse(productId);
    }

    private String portalName(String portal) {
        MonitorConfig.PortalConfig config = this.config.portals().get(portal);
        return config == null ? portal : config.displayName(portal);
    }
}
