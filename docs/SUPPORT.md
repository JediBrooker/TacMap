# TacMap — Help

[Deutsch](/de/support) · [Privacy](/privacy) · [TacMap](/)

## Change language

Open the map menu and **Settings, Privacy & OPSEC**. Under **Language**, choose **English**, **Deutsch** or **Device language**. The choice survives relaunch. Unsupported device languages fall back to English. Your own names, notes and messages, and external map/place names, remain unchanged.

System prompts, file pickers, keyboards and purchase dialogs follow the platform or store language rules. On Android, use TacMap's in-app picker; an explicit app choice overrides Device language. Changing language does not change measurement units, coordinate format or time zone.

## Start offline

Online basemaps and lookups are off on a fresh installation. Use **Import / Export** to import PDF/GeoPDF maps or MBTiles tiles. Calibrate PDFs without georeferencing using known control points and check the reported residual. Choose your map and overlays in **Layers and Labels**.

Coordinate and mission-object search works locally. Place search, weather, elevation and online maps need the relevant online settings and a connection.

## Import custom symbol packs

Follow the [custom symbol pack guide](/custom-symbols) to create a layer, import on Android or iPhone/iPad, and search offline. It includes a ready-to-import German emergency services pack with 894 symbols. Requires 2.0.2 build 68 or later with custom symbol support; see the guide for availability.

## Add and export field data

Move the crosshair to your target and use **Symbology** or the add control. Use **Drawings** for lines, areas and freehand work; organise objects into layers. Your saved names are not renamed when you switch languages.

Decimal fields accept the selected language's decimal separator and a decimal point. Avoid ambiguous thousands separators. Follow each coordinate field's format guidance.

Start track recording explicitly from the map menu and grant precise location access. The app shows recording status; Android also uses an ongoing notification. Stop recording before exporting GPX. **Export All Mission Objects** produces GeoJSON for symbols, waypoints, drawings and layers. Tracks export separately as GPX. Exported files may contain precise positions, notes and timestamps. You choose the destination in the system interface.

## Unit Sync and Chat

Use a strong join code beginning with `3:` and share it only with intended participants. TacMap manages the relay address; there is no user-editable relay field. Location sharing and background location sharing are separate controls; joining a v3 room may ask for consent to enable them.

In Chat, **Entire room** addresses every currently chat-ready unit; **Selected unit** addresses the selected live session. Check the scope before sending. “Routed” confirms relay acceptance, not delivery or reading. The relay does not keep an offline Chat mailbox. Background update intervals are best-effort and depend on the operating system, location reception and connection. Relay-reported online status is not proof of a person's presence.

## Purchases and recovery

TacMap has a three-day trial and a one-time permanent unlock, with no subscription. Use **Restore Purchases** in the purchase screen if entitlement is missing, with the same store account used to purchase. The store supplies local prices and payment options. A purchase in one store does not imply cross-platform entitlement.

For storage failures, keep your data and source file, check free storage and follow the retry guidance. Partial-import errors identify what was already saved. Do not delete app data to fix a translation or entitlement problem; doing so can remove local mission data. Unlock the device or the relevant protection setting when mission data is locked.

## Contact

Email: [christianbrooker@gmail.com](mailto:christianbrooker@gmail.com)

Report problems: [GitHub Issues](https://github.com/JediBrooker/TacMap/issues)

Include platform, app version, selected language and reproduction steps. Do not send join codes, keys, private mission content or unredacted locations. Crash reports are sent only when you export and share them yourself.
