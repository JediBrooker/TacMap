# TacMap — Datenschutzerklärung

*Stand der englischen Erklärung: 28. August 2026. Deutsche Übersetzung: 17. September 2026.*

*Gilt für TacMap unter iOS und Android.*

Diese Erklärung beschreibt, wie die von Christian Brooker veröffentlichte mobile Anwendung TacMap („App“) mit Informationen umgeht. TacMap hat keine vom Entwickler betriebenen Benutzerkonten, Werbung, Analyse- oder Fernübermittlungsdienste für Absturzberichte. Die App kann dennoch die unten beschriebenen Netzwerkanfragen stellen. Dadurch können IP-Adresse, Suchanfrage, betrachteter Kartenausschnitt, Store-Metadaten oder verschlüsselter Datenverkehr der Einheitensynchronisierung dem jeweiligen Dienstanbieter bekannt werden.

## 1. Informationen, die der Entwickler nicht erhebt

TacMap:

- verlangt kein TacMap-Konto, keine E-Mail-Adresse und kein Profil;
- enthält keine Werbung, Verhaltensanalyse, Werbekennungen oder vom Entwickler betriebene Telemetrie- bzw. Absturzübermittlung;
- verkauft oder vermietet keine personenbezogenen Informationen;
- liest weder Kontakte, Fotos, Mikrofon, Kamera, Kalender noch Bluetooth aus;
- liest Kompass- und Orientierungssensoren nur, wenn im Vordergrund die Ausrichtung nach Blickrichtung aktiv ist. Diese Werte drehen die Karte, bleiben im Arbeitsspeicher und werden weder gespeichert noch übertragen;
- lädt keine Einsatzinhalte in ein TacMap-Konto oder eine Entwicklerdatenbank hoch.

Apple und Google können Entwicklern freiwillig freigegebene Absturzdiagnosen des Betriebssystems nach ihren eigenen Einstellungen und Richtlinien bereitstellen. TacMap schreibt außerdem einen kurzen lokalen Absturzbericht. Er wird nur übertragen, wenn du ihn ausdrücklich exportierst.

## 2. Auf deinem Gerät gespeicherte Informationen

| Daten | Zweck und Speicherung |
| --- | --- |
| **Aktuelle GPS-Position** | Für Standortmarkierung, MGRS-/Koordinatenanzeige, Kartenzentrierung und optionale Live-Standortfreigabe. Bleibt im Arbeitsspeicher, sofern du keine Trackaufzeichnung startest. |
| **Kompassrichtung des Geräts** | Dreht die Karte nur bei aktiver Ausrichtung nach Blickrichtung im Vordergrund. Werte bleiben im Arbeitsspeicher und werden weder gespeichert noch übertragen. Die Richtung der Live-Standortfreigabe stammt dagegen aus dem mit der GPS-Position gemeldeten Bewegungskurs. |
| **Aufgezeichneter Track** | Eine ausdrücklich gestartete Aufzeichnung ergänzt genaue Koordinaten, Zeitstempel und verfügbare Höhendaten in einem verschlüsselten, privaten Trackprotokoll. Geschwindigkeit wird für die optionale Live-Standortfreigabe verwendet, aber nicht im Trackprotokoll gespeichert. Eine beendete Aufzeichnung bleibt verfügbar, bis du sie verwirfst. Ein Export erstellt eine separate GPX-Kopie. |
| **Wegpunkte, Symbole, Zeichnungen, Ebenen, Notizen und Stile** | Werden in verschlüsselten, privaten Einsatzdateien gespeichert und bleiben nach einem Neustart erhalten. |
| **TacMap-Chatverlauf und Schutz vor wiederholten Nachrichten** | Gesendete und empfangene Texte und Meldungen, ihr Empfängerkreis (Raum oder ausgewählte Einheit), lokaler Weiterleitungsstatus und Daten zum Schutz vor Wiederholungen werden in einer größenbegrenzten, verschlüsselten privaten Datei für den jeweiligen Synchronisierungsraum gespeichert. TacMap Chat v1 hat keine Zustell- oder Lesebestätigungen. |
| **Kalibrierungsdaten und Auswahl importierter Karten** | Werden in verschlüsselten privaten Dateien gespeichert. Daraus können Identität und geografische Abdeckung einer importierten Karte hervorgehen. |
| **Importierte PDF-/GeoPDF- und MBTiles-Karten** | Werden als ursprüngliche Dateidaten in den privaten App-Speicher kopiert. Sie unterliegen dem Dateischutz des Betriebssystems, werden aber nicht mit dem Einsatzdatenschlüssel von TacMap verschlüsselt. Die Dateifreigabe über iOS Dateien/Finder ist deaktiviert. Android verwendet die Systemdateiauswahl und verlangt keinen umfassenden Speicherzugriff. |
| **App-Einstellungen** | OPSEC-Schalter, Ebenensichtbarkeit, Kartenansicht und andere Bedienoberflächen-Einstellungen werden privat gespeichert. |
| **Kaufberechtigung** | Eine lokal überprüfte dauerhafte Freischaltung wird im iOS-Schlüsselbund oder in privaten Android-App-Einstellungen gespeichert, damit ein bekannter Käufer offline weiterarbeiten kann. |

TacMap verschlüsselt gespeicherte Einsatzdateien mit AES-256-GCM. Der Datenschlüssel wird durch den iOS-Schlüsselbund bzw. Android Keystore geschützt. Importierte Kartendateien und lokale Absturzberichte sind die beschriebenen Ausnahmen. Ein kompromittiertes oder entsperrtes Gerät, Exporte und optionale Netzwerkdienste bleiben gesonderte Risiken. Die Grenzen erläutert das veröffentlichte Bedrohungsmodell.

Das Löschen der App entfernt ihre privaten Dateien normalerweise nach den Regeln der jeweiligen Plattform. Exportierte Kopien, an andere Apps weitergegebene Dateien, Store-Transaktionsdaten und bei Dienstanbietern gespeicherte Daten werden gesondert verwaltet.

## 3. Netzwerkanfragen

### 3.1 Online-Karten — anfangs ausgeschaltet, unabhängig aktivierbar

Bei aktivierten **Online-Grundkartenkacheln** fordert der eigene Rasterkartenrenderer der App die angezeigten Kacheln bei Esri (Satelliten-, topografische und Karten im OpenStreetMap-Stil) oder OpenTopoMap an. Der Anbieter erhält deine IP-Adresse sowie Kachelkoordinaten und Zoomstufe. Daraus lassen sich Kartenausschnitt und Bewegung der Ansicht ableiten.

Beide Plattformen verwenden denselben eigenen Kartenrenderer. TacMap verwendet weder Apple Maps noch Google Maps zur Darstellung der Grundkarte. Diese Einstellung ist bei einer Neuinstallation ausgeschaltet; Updates erhalten deine gespeicherte Wahl. Lass sie ausgeschaltet und nutze importierte PDF-/GeoPDF- oder MBTiles-Karten, wenn der relevante Kartenausschnitt das Gerät nicht verlassen soll.

### 3.2 Online-Abfragen — anfangs ausgeschaltet, unabhängig aktivierbar

Bei aktivierten **Online-Abfragen**:

- erhält Open-Meteo die Koordinate der Kartenmitte für Höhe und Wetter (auf etwa 110 m vergröbert) oder ein Raster von 24 × 24 Koordinaten über dem sichtbaren Kartenausschnitt für die Gelände-Höhenkarte (auf etwa 11 m vergröbert);
- kann iOS einen eingegebenen Ortsnamen bzw. eine Adresse und die Kartenregion über `MKLocalSearch` an Apples Ortssuche senden;
- kann Android einen eingegebenen Ortsnamen bzw. eine Adresse und eine räumliche Suchpräferenz über den `Geocoder`-Anbieter des Geräts senden (auf Geräten mit Google-Diensten häufig Google oder der Gerätehersteller).

Bei einer Neuinstallation ist diese Einstellung ausgeschaltet; Updates erhalten deine Wahl. Du kannst sie jederzeit in den Datenschutz- und OPSEC-Einstellungen einschalten. Für einen Offline-Betrieb bleibt sie ausgeschaltet.

Suchen nach MGRS, Teilgitter, Breiten-/Längengrad, Wegpunkten, Zeichnungen, Typen, Notizen und Ebenen erfolgen auf dem Gerät. Eingaben, die wie Koordinaten aussehen, einschließlich fehlerhafter oder außerhalb des gültigen Bereichs liegender Koordinaten, werden keinem Ortsanbieter übermittelt.

### 3.3 Einheitensynchronisierung — optional

Die Einheitensynchronisierung bleibt aus, bis du einen Beitrittscode eingibst. Beim Beitritt wird eine WebSocket-Verbindung zum konfigurierten Relay geöffnet. Der Standarddienst wird auf Cloudflare betrieben; der quelloffene Relay kann selbst betrieben werden. Die Store-App verwaltet die Relay-Adresse und bietet dafür kein manuelles Eingabefeld.

Einsatzinhalte werden auf dem Gerät mit AES-256-GCM verschlüsselt. Synchronisierte Kartenobjekte, Standortfreigaben und Chatnachrichten an den **gesamten Raum** verwenden aus dem Beitrittscode abgeleitete Schlüssel. Jeder Inhaber dieses Codes kann deshalb an den Raum geteilte Inhalte entschlüsseln. Nachrichten an eine **ausgewählte Einheit** verwenden dagegen einen paarweisen Schlüssel aus den beiden ausgewählten aktiven Endgerätesitzungen. Der Relay, andere Raummitglieder und Codeinhaber außerhalb dieses Paars können sie nicht entschlüsseln. Protokollhülle und Steuerfelder gehören nicht zum verschlüsselten Einsatzinhalt.

Der Relay und seine Hosting- und Netzwerkanbieter können weiterhin Folgendes sehen oder verarbeiten:

- deine IP-Adresse, die Routing-Raumkennung und das beim WebSocket-Verbindungsaufbau übermittelte Autorisierungstoken; der Relay speichert dessen Hash;
- unverschlüsselte äußere Objekt-, Versions-, Typ-, Anfrage-, Bestätigungs- und Löschfelder;
- gemeinsame Raummitgliedschaften, Verbindungs- und Sitzungskennungen, öffentliche Akteursschlüssel sowie signierte Akteurs- und Sitzungsankündigungen;
- Chat-Absender, Sitzung und Schlüsselkennung, den Empfängerkreis sowie bei einer ausgewählten Einheit deren genaue Akteurs-, Sitzungs- und Schlüsselkennung;
- Verbindungszeiten sowie Zeitpunkt, Häufigkeit und Umfang von Nachrichten;
- für die Synchronisierung gespeicherte verschlüsselte Einsatzobjekte und Löschmarkierungen.

Verschlüsselte Einsatzobjekte und Akteursdatensätze können am Relay verbleiben, bis der Raum sieben Tage inaktiv war. Live-Positionen werden an verbundene Geräte weitergeleitet und nur als aktueller Sitzungszustand im Arbeitsspeicher gehalten. Chat-Schlüsselankündigungen und verschlüsselte Nachrichten werden nur an aktuell verbundene, Chat-fähige Sitzungen weitergeleitet und nicht im Raumspeicher des Relays abgelegt. Es gibt kein Offline-Chatpostfach. **Weitergeleitet** bzw. **An Raum gesendet** bestätigt nicht, dass ein Empfänger die Nachricht entschlüsselt, angezeigt oder gelesen hat. TacMap Chat v1 hat keine Zustell- oder Lesebestätigungen durch Endgeräte. Der angezeigte Online-Mitgliedsstatus stammt vom Relay. Signierte Sitzungsnachrichten bestätigen ihren Ursprung; ein nicht vertrauenswürdiger Relay kann Aktivitätsmeldungen aber verzögern, wiederholen oder unterdrücken. Der Status ist ein Hinweis, kein Beweis, dass eine Person gerade verbunden ist.

Beim Verlassen des Raums wird die Verbindung geschlossen und weiterer Synchronisierungsverkehr beendet. Der begrenzte lokale Chatverlauf bleibt verschlüsselt im privaten Speicher dieses Raums, bis die App-Daten gelöscht werden. Durch das Verlassen entsteht keine Relay-Kopie. Das Ausschalten von **Meinen Standort teilen** beendet Live-Positionssendungen, ohne den Raum zu verlassen.

**Einheitensynchronisierung im Hintergrund** ist auf iOS und Android ein separater, anfangs ausgeschalteter OPSEC-Schalter. Sind beide Standortschalter in einem beigetretenen v3-Raum aktiv, kann TacMap die authentifizierte Verbindung nach der Bildschirmsperre aufrechterhalten und verschlüsselte Positionen im gewählten, bestmöglichen Intervall senden: eine, fünf, fünfzehn, dreißig oder sechzig Minuten. iOS setzt die im Vordergrund gestartete Core-Location-Sitzung mit sichtbarer Hintergrund-Standortanzeige fort. Android verwendet einen Standort-Vordergrunddienst mit dauerhafter Benachrichtigung und verlangt kein `ACCESS_BACKGROUND_LOCATION`. Beide Plattformen ignorieren eingehende Einsatzdaten bei gesperrter oder im Hintergrund befindlicher App und verbinden sich nach deiner Rückkehr erneut, um einen überprüften Datenstand zu erhalten.

Ist beim Beitritt zu einem v3-Raum einer der Schalter ausgeschaltet, fragt TacMap vor dem Beitritt, ob beide aktiviert werden sollen. Bei Abbruch erfolgt kein Beitritt. Die signierte Gültigkeitsdauer einer zuletzt bekannten Position ist auf das gewählte Intervall zuzüglich Zustellspielraum begrenzt, höchstens 65 Minuten. Ältere bzw. nur im Vordergrund sendende Geräte behalten ein 45-Sekunden-Zeitfenster. Das Ausschalten eines Schalters verhindert Sendungen bei ausgeschaltetem Bildschirm und erneuert oder schließt die authentifizierte Sitzung, um die länger gültige Markierung zurückzuziehen.

### 3.4 App Store und Google Play

TacMap nutzt StoreKit bzw. Google Play Billing für den Testzeitraum und die einmalige Freischaltung. Die App kann die Kaufberechtigung beim Start und bei zeitlich begrenzten Prüfungen nach Rückkehr in den Vordergrund abgleichen. Beim Öffnen der Kaufansicht werden außerdem Produktinformationen und lokalisierte Preise geladen. Diese Kontakte sind unabhängig von den Schaltern für Online-Karten und Online-Abfragen.

Apple oder Google können das angemeldete Store-Konto, App-/Produktkennungen, Transaktions- bzw. Kauftokendaten, Geräte-/Dienstdaten, IP-Adresse, Zeitangaben und Diagnosen nach ihren Richtlinien verarbeiten. TacMap fügt Store-Anfragen keine Kartenkoordinaten, Tracks, Einsatzobjekte, importierten Karten, Rufzeichen, Synchronisierungsdaten oder Einsatzschlüssel hinzu. Es gibt keinen TacMap-Server zur Kaufprüfung.

## 4. Berechtigungen

Die Ausrichtung nach Blickrichtung liest den Gerätekompass nur, solange dieser Modus im Vordergrund aktiv ist. Dafür ist keine gesonderte Laufzeitberechtigung erforderlich. Bei verfügbarer Standortposition kann die App diese lokal zur Korrektur von magnetisch auf geografisch Nord verwenden. Ohne Position bleibt die Anzeige ausdrücklich als magnetisch Nord gekennzeichnet.

### iOS

- **Standort beim Verwenden der App** ermöglicht Live-Position, MGRS-Anzeige, optionale Standortfreigabe und eine von dir gestartete GPX-Aufzeichnung. TacMap fragt beim ersten Anzeigen der Karte, damit der Standort nach deiner Zustimmung sofort verfügbar ist. Der deklarierte Hintergrund-Standortmodus wird nur während einer ausdrücklich gestarteten Aufzeichnung oder bei aktiviertem Hintergrund-Synchronisierungsschalter und aktiver Standortfreigabe in einem v3-Raum genutzt. Die Systemanzeige für Hintergrundstandort bleibt sichtbar. Die App verlangt keine „Immer“-Berechtigung, kann die Sitzung nach Beendigung der App nicht neu starten und kein genaues Zeitintervall garantieren.
- **Face ID** wird nur angefordert, wenn du eine entsprechende App- oder Einsatzdatensperre aktivierst.
- Systemdateiauswahl und Teilen-Dialog erscheinen nur, wenn du einen Import oder Export wählst.

### Android

- **Genauer/ungefährer Standort** ermöglicht dieselben Karten- und Synchronisierungsfunktionen und wird beim ersten Anzeigen der Karte angefordert. Für GPS-Aufzeichnungen ist genauer Standortzugriff im Vordergrund erforderlich.
- **Vordergrunddienst (Standort)** setzt eine von dir gestartete Aufzeichnung oder ausdrücklich aktivierte v3-Standortfreigabe im Hintergrund bei minimierter App oder gesperrtem Bildschirm fort. Der Dienst zeigt eine dauerhafte Benachrichtigung für die aktive Funktion.
- **Benachrichtigungen** wird ab Android 13 für diese Dienstbenachrichtigung angefordert.
- **Internet/Netzwerkstatus** ermöglicht die beschriebenen optionalen Dienste und Store-Kontakte. **Abrechnung** ermöglicht die einmalige Freischaltung.

Android verlangt weder umfassenden Datei-/Medienspeicherzugriff noch `ACCESS_BACKGROUND_LOCATION`. Du kannst Berechtigungen in den Systemeinstellungen entziehen; die betroffene Funktion steht dann nicht zur Verfügung.

## 5. Von dir gestartete Importe und Exporte

Über die Systemdateiauswahl gewählte PDF-/GeoPDF- und MBTiles-Karten werden in den privaten App-Speicher kopiert, damit sie verfügbar bleiben. GeoJSON-, KML- und KMZ-Dateien werden dagegen mit dem begrenzten Zugriff der Dateiauswahl gelesen, in Einsatzobjekte umgewandelt und anschließend freigegeben. TacMap speichert die resultierenden Objekte verschlüsselt, nicht die Quelldatei.

**Alle Einsatzobjekte exportieren** erstellt eine GeoJSON-Datei mit Wegpunkten, Symbolen, Zeichnungen und Ebeneninformationen. Eine aufgezeichnete Route wird separat als GPX exportiert. GPX enthält für jeden aufgezeichneten Punkt einen Zeitstempel; GeoJSON enthält Erstellungszeiten von Einsatzobjekten und Ebenen, keine Zeitangaben für jeden einzelnen Stützpunkt. Dateien können genaue Koordinaten, Notizen und Höhen enthalten. TacMap schreibt sie lokal und öffnet die Systemoberfläche zum Teilen oder Speichern. Die App wählt kein Ziel und lädt die Dateien nicht selbst für dich hoch. Nach deiner Auswahl einer anderen App, eines Dienstes oder Empfängers gelten deren Datenschutz- und Aufbewahrungsregeln.

## 6. Kinder

TacMap richtet sich nicht an Kinder unter 13 Jahren und betreibt weder Benutzerkonten noch einen Werbedienst. Nutzt ein Kind einen optionalen Anbieter oder Plattform-Store, gelten dessen Richtlinien und die Kontoeinstellungen des Geräts.

## 7. Deine Möglichkeiten und Rechte

Du kannst Einsatzobjekte prüfen, bearbeiten, exportieren oder löschen, einen gespeicherten Track verwerfen, eine importierte Karte entfernen, die Einheitensynchronisierung verlassen, beide Online-Schalter ausschalten, Berechtigungen entziehen oder die App löschen. TacMap hat keine Entwicklerdatenbank mit deinen Einsatzinhalten, auf die sich ein Auskunfts- oder Löschersuchen beziehen könnte. Dienstanbieter können die oben beschriebenen begrenzten Metadaten halten. Wende dich für Anfragen zu diesen Daten an den jeweiligen Anbieter.

Datenschutz- und Verbraucherrechte unterscheiden sich je nach Rechtsordnung. Kontaktiere uns, wenn du diese Erklärung für unzutreffend hältst oder Unterstützung bei der Ermittlung des zuständigen Verantwortlichen bzw. Dienstanbieters benötigst.

## 8. Änderungen dieser Erklärung

Wesentliche Änderungen werden unter derselben öffentlichen URL mit aktualisiertem Datum veröffentlicht. Versionshinweise nennen Änderungen, die den Umgang mit Daten wesentlich verändern.

## 9. Kontakt und Quellcode

- **E-Mail:** christianbrooker@gmail.com
- **Fehlermeldungen:** <https://github.com/JediBrooker/TacMap/issues>
- **Quellcode:** <https://github.com/JediBrooker/TacMap>
- **Ausführliches Bedrohungsmodell (Englisch):** <https://tacmap.app/threat-model>

TacMap ist unter der MIT-Lizenz quelloffen. Der Quellcode kann geprüft werden. Das genaue Verhalten von Apple, Google, Esri, OpenTopoMap, Open-Meteo, Cloudflare, Netzbetreibern und Apps, mit denen du Daten teilst, richtet sich jedoch nach deren Systemen und Richtlinien.
