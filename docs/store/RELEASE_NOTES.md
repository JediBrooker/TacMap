# Shipped store release notes

What each release told users in the App Store (What's New) and on Google Play
(release notes), English and German, exactly as committed in the release commit.
`docs/store/localizations/*.json` only holds the release being prepared, so copy
its `appStore.whatsNew` and `googlePlay.releaseNotes` here, newest first, once a
version ships. Counts are Unicode characters (App Store limit 4,000, Google Play
500). App Store Connect's English locale is `en-AU` and Play's is `en-AU` too;
both take the `en-US` text. Releases before 3.0.1 are in
[`../STORE_LISTING.md`](../STORE_LISTING.md).

## 3.0.4 (build 77)

Text on `release/3.0.4` (`docs/store/localizations/*.json`), not released yet: put the release commit and dates here when it ships. Both stores get 3.0.4 changes only: the App Store notes list the iOS fixes, the Google Play notes the Android ones. iOS 3.0.3 was still in App Review when these were written, so if 3.0.4 replaces it there, its notes need the 3.0.3 list back.

iOS: 3.0.3 was still in App Review when 3.0.4 was ready, so 3.0.4 replaced it there. iOS users go from 3.0.0 straight to 3.0.4, so the App Store notes carry the whole 3.0.1 to 3.0.4 iOS list; the two 3.0.4 items that only fixed 3.0.3 behaviour (packs refused by mistake, the leftover-import notice) are left out of them.

### 3.0.4: App Store, English (1,988 / 4,000)

```
TacMap 3.0.4: fixes and hardening

• App Lock and the privacy screen now also cover open panels. After the privacy screen briefly covers the app, for example when you open Control Center, the keyboard and VoiceOver come back where you were.
• Sturdier PDF reading, so a damaged PDF can't stop TacMap from opening.
• Fixed rare cases where updating from TacMap 2.x could hide or remove imported maps. If TacMap was closed while recovering your map library, maps are no longer listed twice afterwards.
• MBTiles packs that store their tiles as a view (for example from TileMill, MapTiler or tippecanoe) now open, and large packs open in the background.
• Sturdier MBTiles reading: a damaged or crafted pack can no longer make TacMap close at every launch, and more packs built to use too much memory are refused before they are opened.
• TacMap now tells you when an MBTiles map pack can't be opened. If your saved pack is missing, has changed or won't open, the map stays blank with a notice instead of loading an online map in its place. In Layers you can delete it, or try again if it just won't open.
• Choosing a map in Layers and deleting it before it opened no longer leaves the map blank.
• If the saved map library is missing, TacMap now says so instead of mentioning a recovery copy that doesn't exist. No map files are deleted.
• With mission data protected by Face ID or your passcode, work that finishes after you leave the app no longer asks for Face ID or stops Unit Sync. Map imports and offline tiles that finish while TacMap is locked are saved once you unlock, and unlocking while a map is still importing no longer removes it.
• Unit Sync: Undo no longer overwrites a teammate's newer change.
• Unit Sync: in older-style rooms (join codes starting with 2:), objects no longer disappear when a teammate on iPhone still uses TacMap 2, and the room now explains that members on different app versions can see different objects.
• Security hardening for the Unit Sync relay.
```

### 3.0.4: Google Play, English (401 / 500)

```
TacMap 3.0.4
• More MBTiles packs built to use too much memory are refused.
• A few valid MBTiles packs that were refused by mistake now open.
• If the saved map library is missing, TacMap now says so and tells you when map names and calibrations can be brought back.
• Choosing your saved MBTiles pack in Layers while it is still loading no longer shows the same notice twice when it can't be opened.
```

### 3.0.4: App Store, German (2,530 / 4,000)

```
TacMap 3.0.4: Korrekturen und mehr Sicherheit

• App-Sperre und Sichtschutz verdecken jetzt auch geöffnete Fenster. Hat der Sichtschutz die App kurz verdeckt, etwa beim Öffnen des Kontrollzentrums, kehren Tastatur und VoiceOver an die alte Stelle zurück.
• PDFs werden robuster gelesen, damit eine beschädigte PDF TacMap nicht mehr am Starten hindert.
• Seltene Fälle behoben, in denen ein Update von TacMap 2.x importierte Karten ausblenden oder entfernen konnte. Wurde TacMap beendet, während die Kartenbibliothek wiederhergestellt wurde, erscheinen Karten danach nicht mehr doppelt.
• MBTiles-Kartensätze, die ihre Kacheln als Ansicht (View) speichern, etwa aus TileMill, MapTiler oder tippecanoe, öffnen sich jetzt, und große Kartensätze öffnen sich im Hintergrund.
• MBTiles werden robuster gelesen: Ein beschädigter oder manipulierter Kartensatz kann TacMap nicht mehr bei jedem Start beenden, und weitere Kartensätze, die so gebaut sind, dass sie zu viel Speicher belegen, werden abgelehnt, bevor sie geöffnet werden.
• TacMap sagt dir jetzt, wenn sich ein MBTiles-Kartensatz nicht öffnen lässt. Fehlt dein gespeicherter Kartensatz, wurde er geändert oder öffnet er sich nicht, bleibt die Karte mit einem Hinweis leer, statt dass an seiner Stelle eine Onlinekarte geladen wird. Unter „Ebenen“ kannst du ihn löschen oder es erneut versuchen, wenn er sich nur nicht öffnen lässt.
• Wählst du unter „Ebenen“ eine Karte und löschst sie, bevor sie geöffnet ist, bleibt die Karte nicht mehr leer.
• Fehlt die gespeicherte Kartenbibliothek, sagt TacMap das jetzt, statt eine Wiederherstellungskopie zu erwähnen, die es nicht gibt. Es werden keine Kartendateien gelöscht.
• Sind Einsatzdaten mit Face ID oder deinem Code geschützt, fragen Vorgänge, die nach dem Verlassen der App enden, nicht mehr nach Face ID und stoppen die Einheitensynchronisierung nicht mehr. Kartenimporte und Offline-Kacheln, die fertig werden, während die App gesperrt ist, speichert TacMap nach dem Entsperren, und eine Karte, die beim Entsperren noch importiert wird, wird nicht mehr entfernt.
• Einheitensynchronisierung: „Rückgängig“ überschreibt keine neuere Änderung eines Teammitglieds mehr.
• Einheitensynchronisierung: In Räumen im alten Format (Beitrittscode beginnt mit 2:) verschwinden keine Objekte mehr, wenn ein Teammitglied auf dem iPhone noch TacMap 2 nutzt, und der Raum weist darauf hin, dass Mitglieder mit unterschiedlichen App-Versionen verschiedene Objekte sehen können.
• Mehr Sicherheit für das Relay der Einheitensynchronisierung.
```

### 3.0.4: Google Play, German (484 / 500)

```
TacMap 3.0.4
• Weitere MBTiles-Kartensätze, die so gebaut sind, dass sie zu viel Speicher belegen, werden abgelehnt.
• Einige irrtümlich abgelehnte MBTiles-Kartensätze öffnen sich jetzt.
• Fehlt die gespeicherte Kartenbibliothek, sagt TacMap das jetzt und nennt, wann sich Namen und Kalibrierungen zurückholen lassen.
• Wählst du unter „Ebenen“ deinen gespeicherten MBTiles-Kartensatz, während er noch geladen wird, kommt der Hinweis, dass er sich nicht öffnen lässt, nur noch einmal.
```

## 3.0.3 (build 76)

Text at `1960785f` (`docs/store/localizations/*.json`). Google Play released it on 2026-10-07 (the Play notes are 3.0.3 only). The App Store build was submitted for review the same day. iOS: App Review rejected 3.0.2 (75) on 2026-10-06 under guideline 2.3.10 because the App Store description mentioned Android, so iOS never shipped 3.0.1 or 3.0.2: the App Store notes below cover every iOS change since 3.0.0, and the App Store description no longer names other platforms (the Play description gets its cross-platform line back through `descriptionReplacements`).

### 3.0.3: App Store, English (1,846 / 4,000)

```
TacMap 3.0.3: fixes and hardening

• App Lock and the privacy screen now also cover open panels. After the privacy screen briefly covers the app, for example when you open Control Center, the keyboard and VoiceOver come back where you were.
• Sturdier PDF reading, so a damaged PDF can't stop TacMap from opening.
• Fixed rare cases where updating from TacMap 2.x could hide or remove imported maps. If TacMap was closed while recovering your map library, maps are no longer listed twice afterwards.
• MBTiles packs that store their tiles as a view (for example from TileMill, MapTiler or tippecanoe) now open, and large packs open in the background.
• Sturdier MBTiles reading: a damaged or crafted pack can no longer make TacMap close at every launch, and more packs built to use too much memory are refused before they are opened.
• TacMap now tells you when an MBTiles map pack can't be opened. If your saved pack is missing, has changed or won't open, the map stays blank with a notice instead of loading an online map in its place. In Layers you can delete it, or try again if it just won't open.
• Choosing a map in Layers and deleting it before it opened no longer leaves the map blank.
• With mission data protected by Face ID or your passcode, work that finishes after you leave the app no longer asks for Face ID or stops Unit Sync. Map imports and offline tiles that finish while TacMap is locked are saved once you unlock, and unlocking while a map is still importing no longer removes it.
• Unit Sync: Undo no longer overwrites a teammate's newer change.
• Unit Sync: in older-style rooms (join codes starting with 2:), objects no longer disappear when a teammate on iPhone still uses TacMap 2, and the room now explains that members on different app versions can see different objects.
• Security hardening for the Unit Sync relay.
```

### 3.0.3: Google Play, English (383 / 500)

```
TacMap 3.0.3
• TacMap now tells you when an MBTiles pack can't be opened, and no longer loads an online map in its place.
• More MBTiles packs built to use too much memory are refused.
• An MBTiles import interrupted by leaving the app now carries on.
• Recovering the map library works in more cases.
• Unit Sync: reconnecting no longer deletes an object a teammate has just edited.
```

### 3.0.3: App Store, German (2,357 / 4,000)

```
TacMap 3.0.3: Korrekturen und mehr Sicherheit

• App-Sperre und Sichtschutz verdecken jetzt auch geöffnete Fenster. Hat der Sichtschutz die App kurz verdeckt, etwa beim Öffnen des Kontrollzentrums, kehren Tastatur und VoiceOver an die alte Stelle zurück.
• PDFs werden robuster gelesen, damit eine beschädigte PDF TacMap nicht mehr am Starten hindert.
• Seltene Fälle behoben, in denen ein Update von TacMap 2.x importierte Karten ausblenden oder entfernen konnte. Wurde TacMap beendet, während die Kartenbibliothek wiederhergestellt wurde, erscheinen Karten danach nicht mehr doppelt.
• MBTiles-Kartensätze, die ihre Kacheln als Ansicht (View) speichern, etwa aus TileMill, MapTiler oder tippecanoe, öffnen sich jetzt, und große Kartensätze öffnen sich im Hintergrund.
• MBTiles werden robuster gelesen: Ein beschädigter oder manipulierter Kartensatz kann TacMap nicht mehr bei jedem Start beenden, und weitere Kartensätze, die so gebaut sind, dass sie zu viel Speicher belegen, werden abgelehnt, bevor sie geöffnet werden.
• TacMap sagt dir jetzt, wenn sich ein MBTiles-Kartensatz nicht öffnen lässt. Fehlt dein gespeicherter Kartensatz, wurde er geändert oder öffnet er sich nicht, bleibt die Karte mit einem Hinweis leer, statt dass an seiner Stelle eine Onlinekarte geladen wird. Unter „Ebenen“ kannst du ihn löschen oder es erneut versuchen, wenn er sich nur nicht öffnen lässt.
• Wählst du unter „Ebenen“ eine Karte und löschst sie, bevor sie geöffnet ist, bleibt die Karte nicht mehr leer.
• Sind Einsatzdaten mit Face ID oder deinem Code geschützt, fragen Vorgänge, die nach dem Verlassen der App enden, nicht mehr nach Face ID und stoppen die Einheitensynchronisierung nicht mehr. Kartenimporte und Offline-Kacheln, die fertig werden, während die App gesperrt ist, speichert TacMap nach dem Entsperren, und eine Karte, die beim Entsperren noch importiert wird, wird nicht mehr entfernt.
• Einheitensynchronisierung: „Rückgängig“ überschreibt keine neuere Änderung eines Teammitglieds mehr.
• Einheitensynchronisierung: In Räumen im alten Format (Beitrittscode beginnt mit 2:) verschwinden keine Objekte mehr, wenn ein Teammitglied auf dem iPhone noch TacMap 2 nutzt, und der Raum weist darauf hin, dass Mitglieder mit unterschiedlichen App-Versionen verschiedene Objekte sehen können.
• Mehr Sicherheit für das Relay der Einheitensynchronisierung.
```

### 3.0.3: Google Play, German (472 / 500)

```
TacMap 3.0.3
• TacMap meldet jetzt, wenn sich ein MBTiles-Kartensatz nicht öffnen lässt, und lädt an seiner Stelle keine Onlinekarte mehr.
• Weitere MBTiles-Kartensätze, die so gebaut sind, dass sie zu viel Speicher belegen, werden abgelehnt.
• Beim Verlassen der App unterbrochene MBTiles-Importe laufen weiter.
• Die Kartenbibliothek lässt sich in mehr Fällen wiederherstellen.
• Einheitensynchronisierung: Erneutes Verbinden löscht kein gerade bearbeitetes Objekt mehr.
```

## 3.0.2 (build 75)

Text at `828d342f` (`docs/store/localizations/*.json`). Google Play released it on 2026-10-06 (Android already had 3.0.1, so the Play notes are 3.0.2 only). The App Store build was submitted for review the same day and replaced the 3.0.1 build still in review, so iOS goes from 3.0.0 straight to 3.0.2 and its What's New repeats the 3.0.1 iOS fixes.

### 3.0.2: App Store, English (1,069 / 4,000)

```
TacMap 3.0.2: fixes and hardening

• App Lock and the privacy screen now also cover open panels.
• Sturdier PDF reading, so a damaged PDF can't stop TacMap from opening.
• Fixed rare cases where updating from TacMap 2.x could hide or remove imported maps.
• MBTiles packs that store their tiles as a view (for example from TileMill, MapTiler or tippecanoe) now open, and large packs open in the background.
• Sturdier MBTiles reading: a damaged or crafted pack can no longer make TacMap close at every launch. It is held back at the next start, and you can try it again later.
• With mission data protected by Face ID or your passcode, work that finishes after you leave the app no longer asks for Face ID or stops Unit Sync.
• Unit Sync: Undo no longer overwrites a teammate's newer change.
• Unit Sync: in older-style rooms (join codes starting with 2:), objects no longer disappear when a teammate on iPhone still uses TacMap 2, and the room now explains that members on different app versions can see different objects.
• Security hardening for the Unit Sync relay.
```

### 3.0.2: Google Play, English (464 / 500)

```
TacMap 3.0.2
• The Open button in Google Play no longer opens a second map screen.
• If storage runs out while updating from 2.x, your map names and calibrations are kept.
• Sturdier MBTiles reading: a pack that makes TacMap close is held back at the next start. Large packs open in the background.
• Older-style Unit Sync rooms (2: codes): your changes to objects you create reach teammates still on TacMap 2 for Android again, and a note explains mixed versions.
```

### 3.0.2: App Store, German (1,379 / 4,000)

```
TacMap 3.0.2: Korrekturen und mehr Sicherheit

• App-Sperre und Sichtschutz verdecken jetzt auch geöffnete Fenster.
• PDFs werden robuster gelesen, damit eine beschädigte PDF TacMap nicht mehr am Starten hindert.
• Seltene Fälle behoben, in denen ein Update von TacMap 2.x importierte Karten ausblenden oder entfernen konnte.
• MBTiles-Kartensätze, die ihre Kacheln als Ansicht (View) speichern, etwa aus TileMill, MapTiler oder tippecanoe, öffnen sich jetzt, und große Kartensätze öffnen sich im Hintergrund.
• MBTiles werden robuster gelesen: Ein beschädigter oder manipulierter Kartensatz kann TacMap nicht mehr bei jedem Start beenden. Er wird beim nächsten Start zurückgehalten, und du kannst es später erneut versuchen.
• Sind Einsatzdaten mit Face ID oder deinem Code geschützt, fragen Vorgänge, die nach dem Verlassen der App enden, nicht mehr nach Face ID und stoppen die Einheitensynchronisierung nicht mehr.
• Einheitensynchronisierung: „Rückgängig“ überschreibt keine neuere Änderung eines Teammitglieds mehr.
• Einheitensynchronisierung: In Räumen im alten Format (Beitrittscode beginnt mit 2:) verschwinden keine Objekte mehr, wenn ein Teammitglied auf dem iPhone noch TacMap 2 nutzt, und der Raum weist darauf hin, dass Mitglieder mit unterschiedlichen App-Versionen verschiedene Objekte sehen können.
• Mehr Sicherheit für das Relay der Einheitensynchronisierung.
```

### 3.0.2: Google Play, German (499 / 500)

```
TacMap 3.0.2
• „Öffnen“ in Google Play öffnet keinen zweiten Kartenbildschirm mehr.
• Geht bei einem Update von 2.x der Speicher aus, bleiben Kartennamen und Kalibrierungen erhalten.
• MBTiles werden robuster gelesen: Ein Kartensatz, der TacMap beendet, wird beim nächsten Start zurückgehalten. Große Kartensätze öffnen sich im Hintergrund.
• Räume im alten Format (2:): Änderungen an deinen Objekten erreichen Teammitglieder mit TacMap 2 für Android wieder, ein Hinweis erklärt gemischte Versionen.
```

## 3.0.1 (build 74)

Text at `ff25878` (`docs/store/localizations/*.json`). Released on Google Play. The iOS 3.0.1 build was replaced in review by 3.0.2 and never reached users, so the App Store text below was never shown; it is kept because it was submitted.

### 3.0.1: App Store, English (606 / 4,000)

```
TacMap 3.0.1: fixes and hardening

• App Lock and the privacy screen now also cover open panels.
• Sturdier PDF reading, so a damaged PDF can't stop TacMap from opening.
• MBTiles packs that store their tiles as a view (for example from TileMill or MapTiler) now open.
• Fixed rare cases where updating from TacMap 2.x could hide or remove imported maps.
• Unit Sync: Undo no longer overwrites a teammate's newer change.
• Unit Sync: in older-style rooms (join codes starting with 2:), objects no longer disappear when a teammate on iPhone still uses TacMap 2.
• Security hardening for the Unit Sync relay.
```

### 3.0.1: Google Play, English (355 / 500)

```
TacMap 3.0.1
• Opening TacMap from the Unit Sync notification could remove a recently imported map. Fixed.
• Fixed rare cases where updating from 2.x could hide or remove imported maps.
• MBTiles packs made with MapTiler or TileMill open again.
• Unit Sync no longer stops when it receives a malformed record.
• Security hardening for the Unit Sync relay.
```

### 3.0.1: App Store, German (790 / 4,000)

```
TacMap 3.0.1: Korrekturen und mehr Sicherheit

• App-Sperre und Sichtschutz verdecken jetzt auch geöffnete Fenster.
• PDFs werden robuster gelesen, damit eine beschädigte PDF TacMap nicht mehr am Starten hindert.
• MBTiles-Kartensätze, die ihre Kacheln als Ansicht (View) speichern, etwa aus TileMill oder MapTiler, öffnen sich jetzt.
• Seltene Fälle behoben, in denen ein Update von TacMap 2.x importierte Karten ausblenden oder entfernen konnte.
• Einheitensynchronisierung: „Rückgängig“ überschreibt keine neuere Änderung eines Teammitglieds mehr.
• Einheitensynchronisierung: In Räumen im alten Format (Beitrittscode beginnt mit 2:) verschwinden keine Objekte mehr, wenn ein Teammitglied auf dem iPhone noch TacMap 2 nutzt.
• Mehr Sicherheit für das Relay der Einheitensynchronisierung.
```

### 3.0.1: Google Play, German (453 / 500)

```
TacMap 3.0.1
• Das Öffnen über die Benachrichtigung der Einheitensynchronisierung konnte eine kürzlich importierte Karte entfernen. Behoben.
• Seltene Fälle behoben, in denen ein Update von 2.x importierte Karten ausblenden oder entfernen konnte.
• MBTiles-Kartensätze aus MapTiler oder TileMill öffnen sich wieder.
• Die Einheitensynchronisierung stoppt nicht mehr bei einem fehlerhaften Datensatz.
• Mehr Sicherheit für das Relay der Synchronisierung.
```
