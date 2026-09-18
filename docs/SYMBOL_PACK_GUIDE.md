# Bring your own symbols

[Deutsch](/de/custom-symbols) · [Help](/support) · [TacMap](/)

Use your organisation’s artwork on your own map layers. Import a pack once, search its symbols offline, and place them alongside your existing markers.

**Version requirement:** TacMap 2.0.2 **build 68 or later** with custom symbol support. Build 67 does not include this feature. The new build is being prepared for store release; uploading a build does not make it immediately available to everyone.

## 1. Save a symbol pack

A TacMap pack is a **.json file** containing the symbols and their artwork. Save it to Files on iPhone or iPad, or Downloads on Android. If a browser opens the file as text, use its download or save-file option and keep the `.json` extension.

Try the **German emergency services pack: 894 symbols, 5.4 MB**, ready to import:

<a href="/downloads/German-Emergency-Services.symbols.json" download="German-Emergency-Services.symbols.json">Download the German emergency services pack (.json)</a>

It is converted from [Taktische-Zeichen v2.0.0 by Jonas Koeritz and contributors](https://github.com/jonas-koeritz/Taktische-Zeichen/releases/tag/v2.0.0). The upstream release artwork is CC0-1.0. Credits and German category/symbol names are retained in the pack. [Download provenance and file hash](/downloads/German-Emergency-Services.provenance.json).

## 2. Create your layer

Open **Layers and Labels**, add a new layer and give it a name, such as “Fire service”. Select it as the active layer before placing your markers. You can also use an existing layer or change a marker’s layer in its editor.

## 3. Import on your device

### Android

1. Open TacMap’s map menu and **Import / Export**.
2. Tap **Import Symbol Pack…**.
3. Select the saved `.json` file in the system file picker. If TacMap asks you to unlock your mission data when you return, unlock it to complete the import.
4. Open the marker symbol editor and choose **Custom Symbols**.

### iPhone and iPad

1. Open a marker’s symbol editor.
2. Tap **Import Symbol Pack…**.
3. Choose the saved `.json` file in Files.
4. Choose **Custom Symbols** in the editor.

The imported pack stays in your library after restarting the app. Once the file is saved locally, importing and searching need no internet connection. A cloud file provider may need a connection to download a selected file.

## 4. Search and place

Search by pack, category or symbol name, then choose a preview. You can enter several words; capital letters and accents do not matter. With the German pack, try **feuerwehr fuhr** to find matches such as **Führungsgruppe** and **Gruppenführer**.

Give the marker a name, check its layer and save or place it. Custom artwork keeps its original colours and proportions. The app’s English/German setting changes the controls; it does not translate names inside your pack.

## Share markers with your team

Placed custom markers carry their artwork when shared through Unit Sync or exported as GeoJSON. Recipients using a compatible app version can see them without importing the whole pack. Everyone needs a version with custom symbol support; older versions may show a fallback symbol.

Importing a pack does not automatically share the library. Reimporting a pack with the same name replaces that library entry after validation; markers you already placed keep their original artwork.

## If the pack will not import

- **No import option:** check your app version and build. You need build 68 or later with custom symbol support.
- **Wrong file:** choose the prepared `.json` pack. A GitHub repository download, ZIP archive or individual SVG cannot be imported directly.
- **Data locked or storage full:** unlock mission data, check free storage and retry. Keep your original file; do not delete app data to troubleshoot an import.
- **Invalid or oversized pack:** ask its author for a TacMap-compatible pack. The app accepts up to 1,000 symbols in one pack and a file up to 16 MiB, with bounded PNG artwork.

## Use your own artwork

Ask your pack author or administrator for a TacMap JSON pack made from PNG artwork. The repository includes a converter for folders or ZIPs of PNG images and [instructions for creating a pack](https://github.com/JediBrooker/TacMap/blob/main/docs/CUSTOM_SYMBOL_PACKS.md#make-your-own-pack). SVG artwork must first be converted to PNG.

## Storage and privacy

TacMap stores imported libraries encrypted in its private mission storage. Artwork is treated as data; importing it does not run scripts or fetch linked images. A symbol or content hash does not prove its author or operational accuracy.

The original downloaded file remains outside that encrypted library. Deliberate GeoJSON exports contain marker names and artwork in plaintext. Unit Sync uses the existing encrypted mission-sharing path, with the relay metadata limits described in the [threat model](/threat-model). Choose where you save and share exported files.
