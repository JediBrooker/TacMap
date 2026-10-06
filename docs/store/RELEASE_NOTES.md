# Shipped store release notes

What each release told users in the App Store (What's New) and on Google Play
(release notes), English and German, exactly as committed in the release commit.
`docs/store/localizations/*.json` only holds the release being prepared, so copy
its `appStore.whatsNew` and `googlePlay.releaseNotes` here, newest first, once a
version ships. Counts are Unicode characters (App Store limit 4,000, Google Play
500). App Store Connect's English locale is `en-AU` and Play's is `en-AU` too;
both take the `en-US` text. Releases before 3.0.1 are in
[`../STORE_LISTING.md`](../STORE_LISTING.md).

## 3.0.3 (build 76)

Text on `release/3.0.3` (`docs/store/localizations/*.json`), not released yet: put the release commit and dates here when it ships. Both stores get 3.0.3 changes only: the App Store notes list the iOS fixes (iOS 3.0.2 was still in App Review when these were written, so if 3.0.3 replaces it there, its notes need the 3.0.2 list back), the Google Play notes the Android ones.

### 3.0.3: App Store, English (965 / 4,000)

```
TacMap 3.0.3: fixes and hardening

• TacMap now tells you when an MBTiles map pack can't be opened. If your saved pack is missing, has changed or won't open, the map stays blank with a notice instead of loading an online map in its place. In Layers you can delete it, or try again if it just won't open.
• Sturdier MBTiles reading: a pack built to use too much memory is now refused before it is opened.
• With mission data protected by Face ID or your passcode, map imports and offline tiles that finish while TacMap is locked are now saved once you unlock, and unlocking while a map is still importing no longer removes it.
• Choosing a map in Layers and deleting it before it opened no longer leaves the map blank.
• If TacMap was closed while recovering your map library, maps are no longer listed twice afterwards.
• After the privacy screen briefly covers the app, for example when you open Control Center, the keyboard and VoiceOver come back where you were.
```

### 3.0.3: Google Play, English (378 / 500)

```
TacMap 3.0.3
• TacMap now tells you when an MBTiles pack can't be opened, and no longer loads an online map in its place.
• MBTiles packs built to use too much memory are refused.
• An MBTiles import interrupted by leaving the app now carries on.
• Recovering the map library works in more cases.
• Unit Sync: reconnecting no longer deletes an object a teammate has just edited.
```

### 3.0.3: App Store, German (1,190 / 4,000)

```
TacMap 3.0.3: Korrekturen und mehr Sicherheit

• TacMap sagt dir jetzt, wenn sich ein MBTiles-Kartensatz nicht öffnen lässt. Fehlt dein gespeicherter Kartensatz, wurde er geändert oder öffnet er sich nicht, bleibt die Karte mit einem Hinweis leer, statt dass an seiner Stelle eine Onlinekarte geladen wird. Unter „Ebenen“ kannst du ihn löschen oder es erneut versuchen, wenn er sich nur nicht öffnen lässt.
• MBTiles werden robuster gelesen: Ein Kartensatz, der zu viel Speicher belegen würde, wird jetzt abgelehnt, bevor er geöffnet wird.
• Sind Einsatzdaten mit Face ID oder deinem Code geschützt, speichert TacMap Kartenimporte und Offline-Kacheln, die fertig werden, während die App gesperrt ist, jetzt nach dem Entsperren. Entsperrst du, während eine Karte noch importiert wird, wird sie nicht mehr entfernt.
• Wählst du unter „Ebenen“ eine Karte und löschst sie, bevor sie geöffnet ist, bleibt die Karte nicht mehr leer.
• Wurde TacMap beendet, während die Kartenbibliothek wiederhergestellt wurde, erscheinen Karten danach nicht mehr doppelt.
• Hat der Sichtschutz die App kurz verdeckt, etwa beim Öffnen des Kontrollzentrums, kehren Tastatur und VoiceOver an die alte Stelle zurück.
```

### 3.0.3: Google Play, German (441 / 500)

```
TacMap 3.0.3
• TacMap meldet jetzt, wenn sich ein MBTiles-Kartensatz nicht öffnen lässt, und lädt an seiner Stelle keine Onlinekarte mehr.
• MBTiles-Kartensätze, die zu viel Speicher bräuchten, werden abgelehnt.
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
