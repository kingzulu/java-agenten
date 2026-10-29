package com.zuluindustries.agenten.tool;

/**
 * Providerunabhängige Sicherheitskategorien. Neue Tools müssen ihre Kategorie
 * angeben, sobald sie Daten schreiben, externe Systeme ansprechen oder löschen.
 */
public enum ToolCategory {
    WRITE,
    EXTERNAL,
    DELETE
}
