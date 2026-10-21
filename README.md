# Agenten (Java)

Java-Portierung des Kerns der Agentenplattform aus dem Python-Projekt
`agenten`. Die Plattform bleibt ein modularer Monolith (ADR 001): Agenten,
Modelle, Tools und Skills sind getrennt, die Runtime steuert den Ablauf, und
Services registrieren ihre Agenten und Pipelines explizit (ADR 002).

Leitplanken wie im Original: so viel Determinismus wie möglich, so viel KI wie
nötig; nur relevante Tools an das Modell senden; Kosten sind ein
Architekturmerkmal.

## Voraussetzungen und Build

- Java 25 (aktuelles LTS)
- Maven 3.9+

```bash
mvn verify                      # Tests und ausführbares JAR
java -jar target/agenten-0.1.0-SNAPSHOT.jar
```

Die Tests rufen keine externen Modelle auf.

## Kommandozeile

Die CLI entspricht `main.py`:

```bash
java -jar target/agenten-0.1.0-SNAPSHOT.jar "<frage>"                 # Standardassistent
java -jar target/agenten-0.1.0-SNAPSHOT.jar <agent-id> "<aufgabe>"    # Einmal-Modus
java -jar target/agenten-0.1.0-SNAPSHOT.jar <agent-id>                # interaktiver Modus
java -jar target/agenten-0.1.0-SNAPSHOT.jar --show-config <agent-id>
java -jar target/agenten-0.1.0-SNAPSHOT.jar --workflow <key> '{"task": "..."}'
```

Nach jedem Lauf werden Tokenverbrauch, lokal berechnete Listenpreiskosten und der
Preisstand ausgegeben.

### Umgebungsvariablen

Eine `.env` im Projektverzeichnis wird gelesen; gesetzte Variablen haben Vorrang.

| Variable | Bedeutung | Standard |
|---|---|---|
| `AGENTEN_HOME` | Verzeichnis mit `agents/`, `skills/`, `workflows/` | aktuelles Verzeichnis |
| `OPENAI_API_KEY` | OpenAI Responses API | – |
| `ANTHROPIC_API_KEY` | Anthropic Messages API | – |
| `SCW_SECRET_KEY` | Scaleway Generative APIs | – |
| `DEFAULT_MODEL_PROFILE` | Profil ohne Agentenangabe | `openai-mini` |
| `MAX_AGENT_STEPS` | Standardgrenze der Toolschritte | `5` |
| `AGENT_TIMEZONE` | Zeitzone des Zeitkontexts | `Europe/Berlin` |
| `LOG_LEVEL` | `DEBUG`, `INFO`, `WARNING`, `ERROR` | `INFO` |
| `SCHWARM_WORKSPACE` | Git-Checkout, in dem der Code-Schwarm arbeitet | – |
| `SCHWARM_MAVEN` | Maven-Aufruf des Code-Schwarms, z. B. `./mvnw` oder `mvn -o` | `mvn` |
| `SCHWARM_TEST_TIMEOUT_SECONDS` | Zeitlimit eines Testlaufs | `600` |

## Konfigurierbare Agenten

YAML-Agenten liegen in `agents/definitions/` und haben dasselbe Format wie im
Python-Projekt, sodass vorhandene Definitionen weiterverwendet werden können.
Die technische ID ist der Dateiname, `name` der Anzeigename, die Instructions
stehen in `description`. Unbekannte Felder werden ignoriert, ungültige Werte
mit einer Fehlermeldung abgelehnt.

Wirksame Konfigurationsbereiche:

- `model`: Profil, Temperatur, Ausgabelimit und Fallback-Profil
- `tools` und `skills`: Freigaben, Sperren und automatische Toolauswahl
- `execution`: Toolschritte, Lösungsversuche, Timeout, Retries und Fehlerverhalten
- `permissions`: Tool-, Schreib-, Lösch- und externe Zugriffe sowie Bestätigungspflichten
- `guardrails`: zusätzliche Regeln und verbotene Handlungen
- `context`: Systemkontext, lokale Wissensquellen und sitzungsbezogenes Gedächtnis
- `output`: Text- oder JSON-Ausgabe sowie optionale Schemavalidierung (`agents/schemas/<name>.json`,
  vollständiger JSON-Schema-Standard bis Draft 2020-12 einschließlich `format`)

Mitgeliefert sind `assistant`, `aufgaben` (zwei Skills mit Skill-Routing und
Fallback-Profil), `workflow_worker` und die vier Agenten des Code-Schwarms
(`code_planer`, `code_testautor`, `code_entwickler`, `code_reviewer`).

## Modelle und Preise

Modellprofile und Listenpreise stehen in `config/models.yaml`, nicht im Code. Ein
Agent wählt sein Profil in seiner YAML-Datei unter `model.profile`.

```yaml
prices_as_of: 2026-10-18          # Datum des letzten Abgleichs mit den Anbieterseiten

profiles:
  openai-mini: {provider: openai, model: gpt-5.4-mini}

models:
  gpt-5.4-mini:
    currency: USD                  # USD oder EUR
    source: https://developers.openai.com/api/docs/models/gpt-5.4-mini
    prices:                        # je 1 Mio. Tokens
      - input: 0.75
        cached_input: 0.075        # Cache-Treffer
        output: 4.50
      - valid_from: 2027-01-01     # angekündigte Preisänderung
        input: 0.90
        output: 5.40
```

Weitere Felder: `cache_write` für Cache-Schreibvorgänge und `long_context` mit
`above_input_tokens` für höhere Preise bei großem Input.

So bleiben die Kosten korrekt:

- Jeder Modellaufruf wird einzeln mit dem Preis seines Modells berechnet. Nach einem
  Fallback zählen auch die Aufrufe des fehlgeschlagenen Versuchs mit.
- Cache-Treffer (OpenAI `cached_tokens`, Anthropic `cache_read_input_tokens`) und
  Cache-Schreibvorgänge werden mit ihrem eigenen Preis berechnet.
- Maßgeblich ist die am Abrechnungstag (UTC) gültige Preisperiode.
- Die Datei wird beim Start streng geprüft: Unbekannte Felder, fehlende Preise und
  Profile ohne Preis sind Fehler. Ohne Preis werden keine Kosten von 0 angezeigt,
  sondern eine Fehlermeldung.
- Ist `prices_as_of` älter als 90 Tage, weist die Kommandozeile auf einen Abgleich hin.

Nicht berücksichtigt sind Steuern, Rabatte, Freikontingente (etwa die ersten
1 Mio. Tokens bei Scaleway) und Zuschläge für regionale Endpunkte.

## Architektur

| Paket | Aufgabe | Python-Gegenstück |
|---|---|---|
| `agent` | Agentendefinition, YAML-Loader, Registry | `agents/definition.py`, `loader.py`, `registry.py` |
| `tool` | Tool-Schnittstelle, Sicherheitskategorien, Registry, Beispiel-Tools, Datei-Tools (`tool.workspace`) | `tools/registry.py`, `tools/tasks.py` |
| `skill` | SKILL.md-Loader, Freigabe, Routing-Tool `activate_skill` | `skills/` |
| `core` | Richtlinien, Instruktionen, Ausgabeprüfung, Agentic Loop, Fallback, Gesprächsverlauf | `core/` |
| `model` | Provider-Abstraktion, Modellkatalog (`config/models.yaml`), Kostenrechner | `models/`, `cost_calculator.py` |
| `workflow` | Workflow-Definitionen, Aktionen, Engine, Ablage | `workflows/` |
| `service` | Service-Manifeste und Registry; `service.code` ist der Code-Schwarm | `platform_core/services.py` |
| `cli` | Kommandozeile | `main.py` |

`Platform` verdrahtet alles (Composition Root) und lässt sich über den Builder
anpassen, etwa mit eigenen Tools, Providern oder einer anderen Workflow-Ablage.

### Agentic Loop

`AgentRuntime` baut die Instruktionen (Basisregeln, Agentenanweisungen,
Zeitkontext, Systemkontext, Wissensquellen, Guardrails, Ausgabevorgaben), sendet
nur die freigegebenen Tools und prüft jeden Toolaufruf vor der Ausführung:
Registrierung, Freigabe, Sperre, Lösch-/Extern-/Schreibkategorie und
Bestätigungspflicht. Mit mehreren Skills muss das Modell zuerst genau einen Skill
über `activate_skill` wählen; danach erhält es nur dessen Tools. Ungültige
Ausgaben führen im Modus `iterative` zu einem Korrekturversuch. Jeder Modellaufruf
wird mit `call_index`, `step`, `call_type` und Tokens erfasst; auch eine nach dem
Zeitlimit eingetroffene Antwort wird als `late_response` verbucht.

`AgentRunner` versucht das konfigurierte Profil und danach das Fallback-Profil.
Ohne `stop_on_error` wird ein Fehler als Ergebnis mit `Agentenfehler: ...`
zurückgegeben statt geworfen.

### Provider

| Provider | Umsetzung |
|---|---|
| `openai` | Responses API, Folgeaufrufe über `previous_response_id` |
| `anthropic` | Messages API, Verlauf wird in der Antwort mitgeführt; Temperatur wird wie beim aktuellen SDK nicht gesendet |
| `scaleway` | Chat Completions, nur freigegebene Modelle, ohne Tools |

Die Adapter verwenden `java.net.http` und Jackson, keine Provider-SDKs.

### Tools hinzufügen

Tools werden explizit registriert und deklarieren ihre Sicherheitskategorien:

```java
ToolRegistry tools = new ToolRegistry().register(FunctionTool.of(
        "wetter", "Liefert das Wetter.", Map.of("type", "object", "properties", Map.of()),
        arguments -> Map.of("temperatur", 21), ToolCategory.EXTERNAL));
Platform platform = Platform.builder(settings, environment).tools(tools).build();
```

## Workflow-Engine

Workflows liegen als YAML unter `workflows/definitions/` (Format wie im
Python-Projekt). Unterstützte Schritte: `agent`, `approval`, `input`,
`condition`, `transform`, `action`, `wait`, `subworkflow`, `end`, inklusive
Pfadauflösung mit `$input...`, aller Bedingungsoperatoren, `result_mapping`,
flüchtiger Ein- und Ausgaben (`persist_input`/`persist_output`) sowie Grenzen
für Schritte, Laufzeit und Kosten.

Aktionen werden im Code registriert, YAML führt keine beliebigen Funktionen aus:

```java
platform.actions().register("store_result", (context, parameters) -> Map.of("ok", true));
WorkflowRun run = platform.workflows().start("review_and_approve", Map.of("task", "Prüfe X"));
platform.workflows().continueWorkflow(run.runId, true, null, false);
```

Läufe werden über `WorkflowRunRepository` gespeichert. Enthalten ist eine
speicherbasierte Ablage, die sich wie eine Datenbank verhält (flüchtige Daten
gehen beim erneuten Laden verloren).

## Code-Schwarm

Der Service `code_schwarm` lässt vier Agenten Java-Code in einem Maven-Projekt
entwickeln. Ob der Code taugt, entscheidet `mvn test`, nicht ein Modell; am Ende
gibt ein Mensch frei.

| Schritt | Wer | Was |
|---|---|---|
| `plan` | `code_planer` (claude-sonnet) | liest den Code, schreibt `.schwarm/plan.md` |
| `tests_schreiben` | `code_testautor` (claude-sonnet) | schreibt JUnit-Tests zum Plan, bevor es Code gibt |
| `entwickeln_n` | `code_entwickler` (claude-sonnet) | setzt den Plan um; ab Runde 2 mit der Maven-Ausgabe als Eingabe |
| `testen_n`, `pruefen_n` | Aktion `run_maven_tests` | `mvn -B test`; rot führt zurück zum Entwickler, höchstens 3 Runden |
| `diff` | Aktion `workspace_diff` | `git diff HEAD` plus neue Dateien |
| `review` | `code_reviewer` (claude-haiku) | prüft den Diff, empfiehlt freigeben oder überarbeiten |
| `freigabe` | Mensch | Freigabeschritt der Workflow-Engine |

```bash
git clone <projekt> /pfad/zum/checkout
export SCHWARM_WORKSPACE=/pfad/zum/checkout
java -jar target/agenten-0.1.0-SNAPSHOT.jar --workflow code_entwicklung '{"task": "Füge eine Methode ... hinzu"}'
cd /pfad/zum/checkout && git diff     # Ergebnis prüfen, selbst committen oder verwerfen
```

Der Lauf endet im Status `waiting_for_approval` mit dem Bericht des Reviewers.
Die Kommandozeile setzt Läufe nicht fort; geprüft und übernommen wird direkt im
Checkout. Nach drei roten Runden schlägt der Lauf fehl, der Zwischenstand bleibt
im Checkout. Budget: höchstens 3 USD und 60 Minuten je Lauf (`limits` im Workflow).

Leitplanken:

- Die Agenten haben nur `list_files`, `read_file` und `write_file` (der Reviewer
  nur lesend). Pfade müssen im Arbeitsverzeichnis bleiben; `..`, absolute Pfade,
  `.git` und Symlinks nach außen werden abgelehnt. Ein Löschtool gibt es nicht.
- Agenten führen keine Befehle aus. Maven und Git startet nur der Workflow.
- `mvn test` führt modellgeschriebenen Code aus. Den Schwarm daher nur auf einem
  eigenen Checkout laufen lassen, nie auf diesem Repository, und für echten
  Einsatz in einem Container ohne Netz und ohne Zugangsdaten.
- Es wird nichts committet oder gepusht.

## Stand der Portierung

Portiert ist der Plattformkern: Agentendefinitionen, Tool- und Skill-System,
Richtlinien und Berechtigungen, Agent-Runtime mit Fallback, die Provider OpenAI,
Anthropic und Scaleway, Kostenberechnung, Workflow-Engine und Service-Katalog.

Noch nicht portiert:

- Provider Gemini und lokales Modell (Profil `gemini-flash` meldet „Provider nicht verfügbar“)
- RAG-Quellen (`context.rag_sources` wird mit einer Fehlermeldung abgelehnt)
- Agent Builder, Agent Creator und Workflow Creator
- Datenbankpersistenz (SQLite/Alembic), Nutzungsjournal, Kunden, Benutzer und Audit
- HTTP-API und Portal, Prüfberichte, Gutachten und Transkription
- Lotto-Lern-Tools; die Aufgaben-Tools speichern im Speicher statt in SQLite
