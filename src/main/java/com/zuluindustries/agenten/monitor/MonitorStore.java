package com.zuluindustries.agenten.monitor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Historie der Prüfergebnisse in SQLite. Preise werden als Text gespeichert,
 * damit sie exakt erhalten bleiben.
 */
public final class MonitorStore {

    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS availability_checks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                run_id TEXT NOT NULL,
                checked_at TEXT NOT NULL,
                product_id TEXT NOT NULL,
                portal TEXT NOT NULL,
                sku TEXT NOT NULL,
                availability TEXT NOT NULL,
                quantity INTEGER,
                price TEXT,
                currency TEXT,
                delivery TEXT,
                error TEXT
            )""";
    private static final String INDEX = """
            CREATE INDEX IF NOT EXISTS availability_checks_lookup
                ON availability_checks (product_id, portal, sku, id)""";
    private static final String COLUMNS = "product_id, portal, sku, availability, quantity, price, currency, delivery, "
            + "error, checked_at";

    private final Path database;

    public MonitorStore(Path database) {
        this.database = database;
    }

    public Path database() {
        return database;
    }

    /** Letztes gespeichertes Ergebnis einer Ware in einem Portal mit dieser Artikelnummer. */
    public CheckResult last(String productId, String portal, String sku) {
        return query("", productId, portal, sku);
    }

    /** Letztes Ergebnis mit bekannter Verfügbarkeit. */
    public CheckResult lastKnown(String productId, String portal, String sku) {
        return query(" AND availability <> 'unknown'", productId, portal, sku);
    }

    /** Speichert alle Ergebnisse eines Laufs in einer Transaktion. */
    public void save(String runId, List<CheckResult> results) {
        try (Connection connection = open()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO availability_checks (run_id, "
                    + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (CheckResult result : results) {
                    insert.setString(1, runId);
                    insert.setString(2, result.productId());
                    insert.setString(3, result.portal());
                    insert.setString(4, result.sku());
                    insert.setString(5, result.availability().key());
                    if (result.quantity() == null) {
                        insert.setNull(6, Types.INTEGER);
                    } else {
                        insert.setInt(6, result.quantity());
                    }
                    insert.setString(7, result.price() == null ? null : result.price().toPlainString());
                    insert.setString(8, result.currency());
                    insert.setString(9, result.delivery());
                    insert.setString(10, result.error());
                    insert.setString(11, result.checkedAt().toString());
                    insert.addBatch();
                }
                insert.executeBatch();
                connection.commit();
            } catch (SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Prüfergebnisse konnten nicht gespeichert werden: " + error.getMessage(), error);
        }
    }

    /** Alle Ergebnisse eines Laufs in Speicherreihenfolge. */
    public List<CheckResult> results(String runId) {
        try (Connection connection = open();
             PreparedStatement select = connection.prepareStatement(
                     "SELECT " + COLUMNS + " FROM availability_checks WHERE run_id = ? ORDER BY id")) {
            select.setString(1, runId);
            List<CheckResult> results = new ArrayList<>();
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    results.add(read(rows));
                }
            }
            return results;
        } catch (SQLException error) {
            throw new IllegalStateException("Prüfergebnisse konnten nicht gelesen werden: " + error.getMessage(), error);
        }
    }

    private CheckResult query(String filter, String productId, String portal, String sku) {
        try (Connection connection = open();
             PreparedStatement select = connection.prepareStatement("SELECT " + COLUMNS
                     + " FROM availability_checks WHERE product_id = ? AND portal = ? AND sku = ?" + filter
                     + " ORDER BY id DESC LIMIT 1")) {
            select.setString(1, productId);
            select.setString(2, portal);
            select.setString(3, sku);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? read(rows) : null;
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Historie konnte nicht gelesen werden: " + error.getMessage(), error);
        }
    }

    private static CheckResult read(ResultSet rows) throws SQLException {
        int quantity = rows.getInt("quantity");
        boolean noQuantity = rows.wasNull();
        String price = rows.getString("price");
        return new CheckResult(rows.getString("product_id"), rows.getString("portal"), rows.getString("sku"),
                Availability.fromKey(rows.getString("availability")), noQuantity ? null : quantity,
                price == null ? null : new BigDecimal(price), rows.getString("currency"), rows.getString("delivery"),
                rows.getString("error"), Instant.parse(rows.getString("checked_at")));
    }

    private Connection open() throws SQLException {
        Path parent = database.toAbsolutePath().getParent();
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException error) {
            throw new UncheckedIOException("Verzeichnis der Datenbank kann nicht angelegt werden: " + parent, error);
        }
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
        try (Statement statement = connection.createStatement()) {
            statement.execute(SCHEMA);
            statement.execute(INDEX);
        } catch (SQLException error) {
            connection.close();
            throw error;
        }
        return connection;
    }
}
