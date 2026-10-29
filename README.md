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
mvn verify                      # alle Module: Tests und ausführbares JAR
java -jar agenten-app/target/agenten-0.1.0-SNAPSHOT.jar
```

Das Projekt besteht aus drei Maven-Modulen:

| Modul | Inhalt |
|---|---|
| `agenten-core` | Plattformkern ohne Fachlogik: Agenten, Tools, Skills, Provider, Runtime, Workflow-Engine, Builder, Coordinator, CLI |
| `agenten-monitor` | B2B-Verfügbarkeitsmonitor (Code, Bericht-Agent, Workflow, `--monitor`) |
| `agenten-app` | baut Kern und Fachmodule zu einem ausführbaren JAR zusammen |

Der Kern kennt keine Fachmodule. Ein Modul implementiert `PlatformExtension`
und meldet sich über `META-INF/services` an; siehe „Fachmodule“ unten. Wer ein
Modul nicht braucht, entfernt seine Abhängigkeit aus `agenten-app/pom.xml`.

Die Tests rufen keine externen Modelle auf.

## Kommandozeile

Die CLI entspricht `main.py`:

```bash
java -jar agenten-app/target/agenten-0.1.0-SNAPSHOT.jar "<frage>"                 # Standardassistent
java -jar agenten-app/target/agenten-0.1.0-SNAPSHOT.jar <agent-id> "<aufgabe>"    # Einmal-Modus
java -jar agenten-app/target/agenten-0.1.0-SNAPSHOT.jar <agent-id>                # interaktiver Modus
java -jar agenten-app/target/agenten-0.1.0-SNAPSHOT.jar --show-config <agent-id>
java -jar agenten-app/target/agenten-0.1.0-SNAPSHOT.jar coordinator '{"task": "...", "capabilities": ["summarize"]}'
java -jar agenten-app/target/agenten-0.1.0-SNAPSHOT.jar --workflow <key> '{"task": "..."}'
java -jar agenten-app/target/agenten-0.1.0-SNAPSHOT.jar --monitor                 # ein Lauf des Verfügbarkeitsmonitors
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

## Konfigurierbare Agenten

YAML-Agenten liegen in `agents/definitions/` und haben dasselbe Format wie im
Python-Projekt, sodass vorhandene Definitionen weiterverwendet werden können.
Die technische ID ist der Dateiname, `name` der Anzeigename, die Instructions
stehen in `description`. Unbekannte Felder werden ignoriert, ungültige Werte
mit einer Fehlermeldung abgelehnt.

Wirksame Konfigurationsbereiche:

- `model`: Profil, Temperatur, Ausgabelimit und Fallback-Profil
- `capabilities`: Fähigkeitskennungen als Liste von Texten
- `tools` und `skills`: Freigaben, Sperren und automatische Toolauswahl
- `execution`: Toolschritte, Lösungsversuche, Timeout, Retries und Fehlerverhalten
- `permissions`: Tool-, Schreib-, Lösch- und externe Zugriffe sowie Bestätigungspflichten
- `guardrails`: zusätzliche Regeln und verbotene Handlungen
- `context`: Systemkontext, lokale Wissensquellen und sitzungsbezogenes Gedächtnis
- `output`: Text- oder JSON-Ausgabe sowie optionale Schemavalidierung (`agents/schemas/<name>.json`,
  vollständiger JSON-Schema-Standard bis Draft 2020-12 einschließlich `format`)

Mitgeliefert sind `assistant`, `aufgaben` (zwei Skills mit Skill-Routing und
Fallback-Profil), `workflow_worker`, `verfuegbarkeits_bericht` sowie
`agent_builder`, `agent_creator_agent`, `prompt_agent`, `workflow_creator_agent`
und `coordinator` (siehe unten).

### Neue Agenten erzeugen

```bash
java -jar agenten-app/target/agenten-0.1.0-SNAPSHOT.jar agent_builder "Ein Agent, der Lieferantenangebote vergleicht"
```

Der `agent_builder` läuft über eine feste Steuerung, nicht als Modell mit
Schreibwerkzeug:

1. `prompt_agent` schreibt aus der Beschreibung einen Prompt mit neun Abschnitten.
2. `agent_creator_agent` baut daraus die YAML-Definition und übernimmt den Prompt
   unverändert als `description`. Er nutzt Modell und Ausführung des Builders.
3. `write_agent_definition` prüft und speichert das Ergebnis unter
   `agents/definitions/<id>.yaml`.

Beide inneren Agenten laufen ohne Tools und Berechtigungen. Gespeichert wird nur,
wenn die Definition deaktiviert ist (`enabled: false`), eine ID in snake_case hat,
den Prompt unverändert enthält und nur vorhandene Modellprofile, Tools, Skills,
Wissensquellen und Schemas nennt. Typische Formfehler der Modellausgabe
(Markdown-Codeblock, `fallback` statt `fallback_profile`, Guardrails als Liste,
`retries` usw.) werden korrigiert. Vorhandene Dateien werden nie überschrieben.
Der Builder speichert die Definition vollständig mit allen Standardwerten; er
braucht dafür `write_agent_definition` in `tools.allowed`, `write_operations: true`
und keine Bestätigungspflicht für `write`.

`agent_creator_agent` lässt sich auch direkt aufrufen. Dann wird seine
YAML-Ausgabe nach denselben Formprüfungen so gespeichert, wie das Modell sie
geliefert hat.

Die Tokens und Kosten aller Modellaufrufe werden zusammen ausgewiesen. Schlägt
das Speichern fehl, endet die Kommandozeile mit Exit-Code 1.

### Workflow-Gerüste erzeugen

```bash
java -jar agenten-app/target/agenten-0.1.0-SNAPSHOT.jar workflow_creator_agent \
  "Zuerst Bundesligaergebnisse lesen, dann nach Siegen und Unentschieden gruppieren und anschließend ausgeben."
```

Der `workflow_creator_agent` (ohne Tools) erzeugt aus den genannten Schritten ein
Workflow-YAML. Offene Schritte werden `action`-Schritte mit Platzhaltern
`todo_<schritt>`. `WorkflowTemplates` prüft das Ergebnis gegen die
Workflow-Definition und speichert es unter `workflows/definitions/<name>.yaml`
(Umlaute umgeschrieben, bei vorhandener Datei `_2`, `_3`, ...; nie überschreibend).
Ein Markdown-Codeblock wird entfernt, eine Schrittliste mit `id` in die
Objektform gebracht und ein fehlender technischer Endschritt `complete` ergänzt.
Das Gerüst muss `enabled: false` haben: Die Platzhalter-Actions müssen erst im
Code registriert werden, bevor der Workflow aktiviert wird.

### Coordinator

```bash
java -jar agenten-app/target/agenten-0.1.0-SNAPSHOT.jar coordinator '{"task": "Fasse den Text zusammen: ...", "capabilities": ["summarize"]}'
```

Der `coordinator` ist eine feste, sequenzielle Steuerung (kein Modell mit Tools,
kein paralleler Scheduler):

1. Kandidaten sind alle aktivierten Agenten, deren `capabilities` alle geforderten
   Fähigkeiten enthalten (exakte Übereinstimmung, Beschreibungen zählen nicht),
   nach ID sortiert. `coordinator`, `agent_builder`, `agent_creator_agent` und
   `prompt_agent` sind reserviert.
2. Jeder Kandidat läuft ohne Tools, Skills, Wissensquellen, Retries und Fallback.
   Das Ergebnis muss sein Ausgabeformat erfüllen; danach prüft ein werkzeugloser
   Aufruf (`prompt_agent` mit eigener Prüfanweisung), ob es die Aufgabe erfüllt.
   Diese Bewertung ist probabilistisch, kein fachlicher Beweis.
3. Akzeptiert keiner, erzeugt der Agent Builder im Entwurfsmodus eine Definition.
   Sie wird nicht gespeichert; Status `waiting_for_approval` liefert das YAML und
   einen SHA-256-Digest der normalisierten Definition.

Registriert wird ein Entwurf nur über `platform.coordinator().registerProposal(definition, digest)`
nach ausdrücklicher menschlicher Freigabe genau dieser Definition. Der Aufruf
prüft Modellprofile, Tools (nur vorhandene), Skills (keine) und Ressourcen,
überschreibt keine IDs und registriert nur im laufenden Prozess. Eine Freigabe
kommt nie aus einer Modellantwort oder der Coordinator-Eingabe.

Grenzen je Lauf (`Coordinator.Limits`): 6 Modellaufrufe, 32000 berichtete Tokens,
120 Sekunden. Ausgabetokens und Timeout werden auf das Restbudget gesetzt. Das
Tokenbudget ist eine Stoppschwelle, keine Abrechnungsgrenze. Tokens und Kosten
aller Aufrufe, auch fehlgeschlagener, werden je Modell und Währung ausgewiesen.
Verschachtelte Coordinator-Läufe werden blockiert; Workflow-Agentenschritte
lehnen den `coordinator` vor jedem Modellaufruf ab.

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
| `tool` | Tool-Schnittstelle, Sicherheitskategorien, Registry, Beispiel-Tools | `tools/registry.py`, `tools/tasks.py` |
| `skill` | SKILL.md-Loader, Freigabe, Routing-Tool `activate_skill` | `skills/` |
| `core` | Richtlinien, Instruktionen, Ausgabeprüfung, Agentic Loop, Fallback, Gesprächsverlauf | `core/` |
| `model` | Provider-Abstraktion, Modellkatalog (`config/models.yaml`), Kostenrechner | `models/`, `cost_calculator.py` |
| `workflow` | Workflow-Definitionen, Aktionen, Engine, Ablage, Gerüste des Workflow Creators | `workflows/` |
| `service` | Service-Manifeste und Registry | `platform_core/services.py` |
| `builder` | Agent Builder, Agent Creator, Prüfung und Speicherung erzeugter Definitionen | `agents/builder.py`, `creator.py`, `template_writer.py`, `tools/agent_definitions.py` |
| `coordinator` | Sequenzielle Delegation über Fähigkeiten, Entwurf und Freigabe neuer Agenten | `agents/coordinator.py` |
| `extension` | Schnittstelle für Fachmodule (`PlatformExtension`, `CliCommand`) | – (neu) |
| `cli` | Kommandozeile | `main.py` |

Diese Pakete liegen alle in `agenten-core`. Das Paket `monitor` (B2B-Verfügbarkeitsmonitor:
Konfiguration, Connectoren, Historie, Änderungserkennung) liegt im Modul `agenten-monitor`.

`Platform` verdrahtet alles (Composition Root) und lässt sich über den Builder
anpassen, etwa mit eigenen Tools, Providern, Fachmodulen oder einer anderen Workflow-Ablage.

### Fachmodule

Ein Fachmodul ist ein eigenes Maven-Modul, das nur von `agenten-core` abhängt und
`PlatformExtension` implementiert. Es kann beisteuern:

- Agenten- und Workflow-Definitionen als YAML aus seinen Ressourcen
  (`agentDefinitions()`, `workflowDefinitions()`); doppelte IDs mit dem
  Projektverzeichnis oder anderen Modulen sind ein Fehler,
- Workflow-Aktionen und andere Einrichtung nach dem Aufbau (`install(platform)`),
- Befehle der Kommandozeile (`commands()`, z. B. `--monitor`).

Angemeldet wird es mit einer Zeile in
`src/main/resources/META-INF/services/com.zuluindustries.agenten.extension.PlatformExtension`.
`Platform.builder(...)` findet alle Module auf dem Klassenpfad; mit
`.extensions(List.of(...))` lassen sie sich ausdrücklich festlegen, etwa in Tests.

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

## B2B-Verfügbarkeitsmonitor

Modul `agenten-monitor`; Code unter `agenten-monitor/src/main/java/.../monitor/`,
Bericht-Agent und Workflow als Ressourcen unter
`agenten-monitor/src/main/resources/com/zuluindustries/agenten/monitor/`. Die
Konfiguration `config/monitor.yaml` gehört zum Projektverzeichnis; fehlt sie,
bleibt der Monitor aus.

Prüft konfigurierte Waren auf mehreren B2B-Portalen, speichert die Ergebnisse
in SQLite und meldet Änderungen seit dem letzten Lauf. Abfragen, Speichern und
Vergleichen sind Code; ein Agent (`verfuegbarkeits_bericht`) schreibt nur dann
einen Bericht, wenn sich etwas geändert hat.

```
--monitor → Workflow b2b_verfuegbarkeit:
  pruefen (Aktion: alle Portale abfragen, vergleichen, speichern)
  → aenderungen? → bericht (Agent) → ende
```

### Konfiguration

Portale und Waren stehen in `config/monitor.yaml`. Jede Ware hat je Portal ihre
eigene Artikelnummer. Die Datei wird streng geprüft, Tippfehler in Feldnamen
sind Fehler.

```yaml
portals:
  grosshandel_a:
    name: Großhandel A
    connector: demo               # Zugriffsart, im Code registriert
    enabled: true
    delay_ms: 1000                # Pause zwischen zwei Abfragen dieses Portals
    credentials:                  # nur Namen von Umgebungsvariablen, nie Werte
      api_key: PORTAL_A_API_KEY
    settings: {}                  # Einstellungen des Connectors

products:
  - id: produkt_001
    name: Produkt A
    portals:
      grosshandel_a: {sku: "4711"}

notifications:
  on_stock_change: true           # wieder verfügbar / nicht mehr verfügbar
  on_price_change: true
  on_delivery_change: false
  on_check_failure: true
```

Mitgeliefert ist nur der Test-Connector `demo`, der keine echten Abfragen macht,
sondern den unter `settings.items` hinterlegten Bestand liefert. Echte Portale
bekommen je Zugriffsart (API, HTML-Seite, Exportdatei) einen eigenen Connector,
der `PortalConnector` implementiert und in einer `ConnectorRegistry` an
`new MonitorExtension(connectors)` übergeben wird.

### Ergebnisse

Jede Prüfung endet in einem von drei Zuständen: `verfügbar`, `nicht verfügbar`
oder `unbekannt` mit Fehlergrund. Eine fehlgeschlagene Abfrage zählt nie als
„nicht verfügbar“. Änderungen werden gegen das letzte bekannte Ergebnis
erkannt, sodass ein zwischenzeitlicher Fehler keine Scheinänderung erzeugt. Ein
neuer Abfragefehler wird einmal gemeldet. Die erste Prüfung einer Ware ist
keine Änderung. Preise werden exakt (als Dezimalzahl) gespeichert und
verglichen.

Die Historie liegt in `data/monitor.db` (änderbar über `monitor.database`).
Portale werden parallel abgefragt, die Waren eines Portals nacheinander.

### Zeitsteuerung

Der Monitor läuft nicht dauerhaft, sondern wird von außen gestartet, etwa per
cron alle 30 Minuten:

```
*/30 * * * * cd /pfad/zu/java-agenten && java -jar agenten-app/target/agenten-0.1.0-SNAPSHOT.jar --monitor >> data/monitor.log 2>&1
```

Unter macOS geht dasselbe mit einem launchd-Agenten (`StartInterval` 1800).
Scheitert der Bericht (etwa ohne API-Schlüssel), werden Übersicht und
Änderungen trotzdem ausgegeben und der Exit-Code ist 1.

## Stand der Portierung

Portiert ist der Plattformkern: Agentendefinitionen, Tool- und Skill-System,
Richtlinien und Berechtigungen, Agent-Runtime mit Fallback, die Provider OpenAI,
Anthropic und Scaleway, Kostenberechnung, Workflow-Engine und Service-Katalog,
Agent Builder, Workflow Creator und Coordinator.

Noch nicht portiert:

- Provider Gemini und lokales Modell (Profil `gemini-flash` meldet „Provider nicht verfügbar“)
- RAG-Quellen (`context.rag_sources` wird mit einer Fehlermeldung abgelehnt)
- Datenbankpersistenz (SQLite/Alembic), Nutzungsjournal, Kunden, Benutzer und Audit
- HTTP-API und Portal, Prüfberichte, Gutachten und Transkription
- Lotto-Lern-Tools; die Aufgaben-Tools speichern im Speicher statt in SQLite
