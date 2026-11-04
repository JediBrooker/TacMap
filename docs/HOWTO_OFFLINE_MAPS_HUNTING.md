# Offline maps for hunting season

[Help](/support) · [Privacy](/privacy) · [TacMap](/)

Most hunting ground has no signal. TacMap is built to be loaded at home and used in the field with the phone in flight mode: your own maps, the property boundary, your stand and camp waypoints, and encrypted sharing with the rest of the party when you do have a bar or two. No subscription, no account.

Everything here applies to Android and iPhone.

## Before you leave: load the map

TacMap does not download map regions. You bring the map you want:

- **A GeoPDF** of the area. Government topo sheets are the usual source: USGS US Topo quads in the United States, state and national mapping agencies elsewhere. Import it with **Import / Export**, then **PDF Map**. The sheet is placed in its own projection and datum, so the MGRS grid lines up with the printed one.
- **A plain PDF or a scan of a paper map**, saved as PDF. Calibrate it from three or more grid references with **Calibrate / refine…** and TacMap tells you how good the fit is. A phone photo has to be saved as a PDF first; there is no image import.
- **An MBTiles pack** you built from aerial imagery or your own GIS data. Import it with **Import / Export**, then **Offline Tiles**.

The step-by-step is in [How to load a GeoPDF on iPhone](/guides/geopdf-on-iphone); the Android steps are the same.

Online basemaps are off on a fresh install. If you turn them on to scout the ground at home, they only draw what you look at while connected and are not downloaded for later. Use an imported map for the field.

## Generate offline tiles

Once the map is imported and calibrated, open **Layers and Labels**, find it under **Imported maps** and tap **Generate Offline Tiles…**. Pick a maximum zoom; each option shows the tile count, the size and roughly how long it takes. The PDF is kept.

Do this on a charger with the phone awake. In the field the map then opens and zooms instantly instead of rendering from the PDF each time.

## Import the property boundary

If the landowner, a lease, or a state land agency gave you the boundary as **KML**, **KMZ** or **GeoJSON**, import it with **Import / Export**: choose **KML / KMZ** or **GeoJSON** and pick the file. It arrives as drawings on your map, with layers preserved. Google Earth exports KML; QGIS exports GeoJSON.

If the boundary is a GPX file, convert it to KML or GeoJSON first in Google Earth or QGIS. TacMap exports GPX for recorded tracks but does not import GPX.

Turn boundary layers on and off in **Layers and Labels**. Hidden layers stay saved.

## Mark stands, camp and water

Hold a finger still on the map for a second and choose **Place Symbol Here**, or move the crosshair and use **Add Waypoint**. Give each one a name. There are points-of-interest and search-and-rescue symbol sets alongside the military ones, and you can organise waypoints into layers, for example one per block or one per hunter.

Hold on a spot and choose **Copy Coordinates** to get its grid reference, or **Range Rings Here** to draw distance rings around a stand. Rings are dashed drawings on the active layer and can be set in metres or kilometres.

Your position reads out live in MGRS, UTM or latitude and longitude, set under **Settings, Privacy & OPSEC**, **Coordinate display**. Measure distance and bearing from any point with **Measure From Here**.

## Record your route

Start track recording from the map menu and allow precise location. TacMap shows the recording status, and on Android an ongoing notification. Stop recording before you export. Tracks export as **GPX Track** from **Import / Export** and open in Garmin, Gaia GPS, QGIS and Google Earth. Waypoints and drawings export separately as GeoJSON, KML or KMZ with symbols.

## Share with your party

When the party has signal, in camp or in town, **Unit Sync** shares waypoints and drawings between phones, Android and iPhone alike, over an end-to-end encrypted channel. One person opens **Unit Sync**, chooses **Join / create room** and taps **Generate strong code**. Everyone else enters that join code, which starts with `3:`. Share the code in person or over a channel you trust. Anyone with the code is in the room.

Once joined, each member can choose **Share my location** so the others see their callsign, position and heading on the map. **TacMap Chat** sends encrypted text or reports to the whole room or a selected unit.

The relay cannot read mission content, but it does see who connected, when, from which IP address, and how much data moved. Read the [threat model](/threat-model) before you rely on it. There is no offline mailbox: a message sent while a member is out of coverage is not queued for them.

Out of coverage, nothing is lost. Edits stay saved on the phone and are reconciled when the room reconnects.

## Battery

- **Keep screen on** in **Settings, Privacy & OPSEC** stops the phone locking while TacMap is open. It is off by default and it uses more battery. Turn it on for a dash mount, not for a day in a stand.
- With Unit Sync, **Background Unit Sync location** is a separate switch that lets the phone keep sending its position with the screen off, on an interval you choose. Updates are best-effort and depend on the operating system and the connection. Leave it off if you do not need the party to track you.
- Flight mode stops the phone hunting for a tower, which is the biggest drain in a dead zone. Everything on this page except Unit Sync and Chat works in flight mode.

## Red night mode

Before first light, tap the moon button on the map. **Night mode** turns the entire screen red and dims it, menus included, so you keep your night vision walking in. **Night mode brightness** sets how dim. Tap the moon again at shooting light.

**Sun and Moon Here** on the long-press menu gives sunrise, sunset and moon times for that spot, computed offline. Check your local regulations for legal hours; TacMap shows the astronomy, not the law.

## Get TacMap

Download on the [App Store](https://apps.apple.com/au/app/tacmap/id6773175573) or get it on [Google Play](https://play.google.com/store/apps/details?id=com.tacmap). Three days free, then one unlock.
