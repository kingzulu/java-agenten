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

Nach jedem Lauf werden Tokenverbrauch und lokal berechnete Listenpreiskosten
ausgegeben.

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
- `output`: Text- oder JSON-Ausgabe sowie optionale Schemavalidierung (`agents/schemas/<name>.json`)

Mitgeliefert sind `assistant`, `aufgaben` (zwei Skills mit Skill-Routing und
Fallback-Profil) und `workflow_worker`.

## Architektur

| Paket | Aufgabe | Python-Gegenstück |
|---|---|---|
| `agent` | Agentendefinition, YAML-Loader, Registry | `agents/definition.py`, `loader.py`, `registry.py` |
| `tool` | Tool-Schnittstelle, Sicherheitskategorien, Registry, Beispiel-Tools | `tools/registry.py`, `tools/tasks.py` |
| `skill` | SKILL.md-Loader, Freigabe, Routing-Tool `activate_skill` | `skills/` |
| `core` | Richtlinien, Instruktionen, Ausgabeprüfung, Agentic Loop, Fallback, Gesprächsverlauf | `core/` |
| `model` | Provider-Abstraktion, Modellprofile, Preise, Kostenrechner | `models/`, `cost_calculator.py` |
| `workflow` | Workflow-Definitionen, Aktionen, Engine, Ablage | `workflows/` |
| `service` | Service-Manifeste und Registry | `platform_core/services.py` |
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
