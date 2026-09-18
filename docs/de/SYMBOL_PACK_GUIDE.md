# Eigene Symbole verwenden

[English](/custom-symbols) · [Hilfe](/de/support) · [TacMap](/de)

Verwende die Grafiken deiner Organisation auf eigenen Kartenebenen. Importiere ein Paket einmal, suche offline nach Symbolen und platziere sie neben deinen vorhandenen Markierungen.

**Voraussetzung:** TacMap 2.0.2 **Build 68 oder neuer** mit Unterstützung für eigene Symbole. Build 67 enthält diese Funktion noch nicht. Der neue Build wird für die Store-Veröffentlichung vorbereitet; ein hochgeladener Build ist nicht sofort für alle verfügbar.

## 1. Symbolpaket speichern

Ein TacMap-Symbolpaket ist eine **.json-Datei** mit Symbolen und Grafiken. Speichere sie auf dem iPhone oder iPad in „Dateien“, auf Android unter „Downloads“. Wenn der Browser Text anzeigt, verwende seine Funktion zum Herunterladen oder Speichern und behalte die Dateiendung `.json` bei.

Zum Ausprobieren: **894 Symbole für deutsche Behörden und Organisationen mit Sicherheitsaufgaben (BOS), 5,4 MB**, fertig zum Importieren:

<a href="/downloads/German-Emergency-Services.symbols.json" download="German-Emergency-Services.symbols.json">Deutsches BOS-Symbolpaket herunterladen (.json)</a>

Das Paket wurde aus [Taktische-Zeichen v2.0.0 von Jonas Koeritz und Mitwirkenden](https://github.com/jonas-koeritz/Taktische-Zeichen/releases/tag/v2.0.0) konvertiert. Die Grafiken der ursprünglichen Veröffentlichung stehen unter CC0-1.0. Quellenangabe sowie deutsche Kategorien und Symbolnamen bleiben erhalten. [Herkunft und Datei-Prüfsumme herunterladen](/downloads/German-Emergency-Services.provenance.json).

## 2. Eigene Ebene erstellen

Öffne **Ebenen und Beschriftungen**, erstelle eine neue Ebene und benenne sie, zum Beispiel „Feuerwehr“. Wähle sie als aktive Ebene, bevor du Markierungen platzierst. Du kannst auch eine vorhandene Ebene verwenden oder die Ebene einer Markierung im Editor ändern.

## 3. Auf deinem Gerät importieren

### Android

1. Öffne im Kartenmenü **Importieren / Exportieren**.
2. Tippe auf **Symbolpaket importieren …**.
3. Wähle die gespeicherte `.json`-Datei in der Dateiauswahl. Falls TacMap bei der Rückkehr zum Entsperren der Einsatzdaten auffordert, entsperre sie, um den Import abzuschließen.
4. Öffne den Symboleditor einer Markierung und wähle **Eigene Symbole**.

### iPhone und iPad

1. Öffne den Symboleditor einer Markierung.
2. Tippe auf **Symbolpaket importieren …**.
3. Wähle die gespeicherte `.json`-Datei in „Dateien“.
4. Wähle im Editor **Eigene Symbole**.

Das importierte Paket bleibt nach einem Neustart in deiner Bibliothek. Ist die Datei lokal gespeichert, funktionieren Import und Suche ohne Internet. Ein Cloud-Dateianbieter benötigt möglicherweise eine Verbindung, um die ausgewählte Datei herunterzuladen.

## 4. Suchen und platzieren

Suche nach Paket, Kategorie oder Symbolname und wähle eine Vorschau. Mehrere Suchwörter sind möglich; Großschreibung und Akzente spielen keine Rolle. Probiere im deutschen Paket **feuerwehr fuhr**, um beispielsweise **Führungsgruppe** und **Gruppenführer** zu finden.

Benenne die Markierung, prüfe ihre Ebene und speichere oder platziere sie. Die Grafiken behalten ihre ursprünglichen Farben und Proportionen. Die Sprachauswahl in TacMap ändert die Bedienelemente, nicht die Namen innerhalb deines Pakets.

## Markierungen im Team teilen

Eigene Markierungen enthalten ihre Grafik beim Teilen über die Einheitensynchronisierung oder beim Export als GeoJSON. Empfänger mit einer kompatiblen App-Version sehen sie, ohne das gesamte Paket zu importieren. Alle Beteiligten benötigen eine Version mit Unterstützung für eigene Symbole; ältere Versionen zeigen möglicherweise ein Ersatzsymbol.

Der Import eines Pakets teilt die Bibliothek nicht automatisch. Ein erneuter Import mit demselben Paketnamen ersetzt nach erfolgreicher Prüfung diesen Bibliothekseintrag. Bereits platzierte Markierungen behalten ihre ursprüngliche Grafik.

## Wenn der Import nicht klappt

- **Importoption fehlt:** Prüfe App-Version und Build. Benötigt wird Build 68 oder neuer mit Unterstützung für eigene Symbole.
- **Falsche Datei:** Wähle das vorbereitete `.json`-Paket. Ein heruntergeladenes GitHub-Repository, ein ZIP-Archiv oder eine einzelne SVG-Datei lässt sich nicht direkt importieren.
- **Einsatzdaten gesperrt oder Speicher voll:** Entsperre die Einsatzdaten, prüfe freien Speicher und versuche es erneut. Behalte die Originaldatei und lösche zur Fehlerbehebung keine App-Daten.
- **Ungültiges oder zu großes Paket:** Bitte den Ersteller um ein TacMap-kompatibles Paket. Pro Paket sind bis zu 1.000 Symbole und 16 MiB Dateigröße erlaubt; auch die PNG-Grafiken unterliegen Größenbeschränkungen.

## Eigene Grafiken vorbereiten

Bitte den Ersteller oder Administrator um ein TacMap-JSON-Paket aus PNG-Grafiken. Im Repository stehen ein Konverter für PNG-Ordner oder ZIP-Archive sowie eine [englische Anleitung zur Paketerstellung](https://github.com/JediBrooker/TacMap/blob/main/docs/CUSTOM_SYMBOL_PACKS.md#make-your-own-pack) bereit. SVG-Grafiken müssen zuerst in PNG umgewandelt werden.

## Speicherung und Datenschutz

TacMap speichert importierte Bibliotheken verschlüsselt im privaten Einsatzdatenspeicher. Grafiken werden als Daten behandelt: Der Import führt keine Skripte aus und lädt keine verknüpften Bilder nach. Ein Symbol oder eine Prüfsumme bestätigt weder den Urheber noch die fachliche Richtigkeit.

Die heruntergeladene Originaldatei liegt außerhalb dieser verschlüsselten Bibliothek. Bewusste GeoJSON-Exporte enthalten Namen und Grafiken im Klartext. Die Einheitensynchronisierung nutzt die bestehende verschlüsselte Übertragung von Einsatzdaten; die Metadaten-Grenzen des Relays beschreibt das [Bedrohungsmodell auf Englisch](/threat-model). Wähle Speicherort und Empfänger exportierter Dateien entsprechend.
