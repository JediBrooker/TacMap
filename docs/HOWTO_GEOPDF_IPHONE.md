# How to load a GeoPDF on iPhone (offline)

[Help](/support) · [Privacy](/privacy) · [TacMap](/)

A GeoPDF is a normal PDF with the map's projection and corner coordinates embedded. TacMap reads that georeference and places the sheet so the MGRS grid lines up with the printed grid. Once it is imported the map works with no signal, and you can generate offline tiles from it so it opens and zooms instantly.

This guide is for iPhone and iPad. The steps on Android are the same apart from where the file lives.

## 1. Get a GeoPDF

Any single-sheet GeoPDF works. Common sources:

- **USGS topo** (United States): US Topo and historical quads from the USGS topoView and the National Map are GeoPDFs. Download the sheet, not the whole collection.
- **State and national mapping agencies**: many publish their topographic series as GeoPDF. In Australia, Geoscience Australia and the state agencies do. The sheet's margin tells you the datum, usually GDA94 or GDA2020 there, NAD83 or NAD27 in the US.
- **Vendor PDFs**: maps sold for Avenza-style apps are usually GeoPDFs too. If you bought the PDF you can import it; TacMap does not read a vendor's in-app store.

TacMap also imports plain PDFs with no georeference, and MBTiles tile packs. A photo or scan of a paper map has to be saved as a PDF first. Phone photos come out as JPEG or HEIC, and TacMap has no image import, so use the Files app, Photos' print-to-PDF or any scanner app to turn it into a PDF before you start.

Keep sheets under the import limit the app reports if you hit it, and import one sheet per file. A multi-page PDF is fine: TacMap asks which page has the map.

## 2. Get the file onto the phone

Any route into the Files app works:

- **AirDrop** from a Mac. The PDF lands in Files under Downloads.
- **Save to Files** from Safari, Mail or Messages using the share sheet.
- **iCloud Drive** or any other Files location you can browse.

TacMap does not register as a PDF handler, so "Open in TacMap" is not on the share sheet. You pick the file from inside TacMap in the next step.

## 3. Import it into TacMap

1. Open the map menu and choose **Add a map** (the first row).
2. Pick the GeoPDF in the Files picker. The same row takes a plain PDF or an MBTiles pack.
3. TacMap reads the page, finds the georeference and saves the map into its own private storage. The original stays where it was.

If the PDF has several pages and none has a usable georeference, TacMap asks you to choose the page with the map. If it is password protected, remove the password and import again.

The map is now the active basemap. Imported maps are listed under **Imported maps** in **Layers and Labels**, with a status line: **GeoPDF** means the embedded georeference was used.

## 4. Check the grid lines up

Open **Layers and Labels** and turn on **MGRS Grid**. TacMap draws its own grid over the sheet. On a correctly placed GeoPDF, the drawn grid sits on the printed grid lines. Zoom in on a grid intersection to check.

If they are offset by a constant amount, the sheet's datum is probably not what the PDF says it is. You can override the embedded georeference: open the map's entry under Imported maps and choose **Calibrate / refine…**, then set **Datum printed on the map** to what the margin says. **Use the PDF's own georeferencing** puts the embedded placement back.

If the PDF's georeference uses a projection TacMap cannot read, the status line says **Georeferencing unreadable – tap to calibrate**. Calibrate it as a plain PDF (step 6).

## 5. Generate offline tiles

A GeoPDF is already usable offline, but TacMap renders it from the PDF each time, which is slower on a large sheet. Offline tiles pre-render the map once.

1. Open **Layers and Labels** and find the map under **Imported maps**.
2. Tap **Generate Offline Tiles…**.
3. Pick a maximum zoom. Each option shows the tile count, size and roughly how long it takes.
4. Confirm. The PDF is kept, and the map stays on screen while tiles are generated.

When it finishes the status line reads **Offline tiles**. **Remove Offline Tiles** frees the space again. Deleting the map removes the file, its calibration and any tiles made from it.

Do this at home on a charger. Tiles cannot be generated after the fact without the phone.

## 6. Calibrate a plain PDF

If the PDF has no georeference, the status line reads **Not georeferenced – tap to calibrate**. Calibration tells TacMap where three or more known points on the sheet are in the real world.

1. Tap the map's entry and choose **Calibrate / refine…**.
2. Set **Datum printed on the map**. Read it from the sheet's margin or legend. The wrong datum can shift the map by 200 m or more. Choose **Not sure (use WGS84)** if the sheet does not say.
3. Place the crosshair on a grid intersection or a clearly marked feature and tap **Add point**. Type the reference as MGRS, UTM or latitude and longitude. For the first point enter the full reference with zone and 100 km square, for example `56H LH 349 522`.
4. Add at least three points, spread across the sheet, one near each corner where you can.
5. Add a fourth point. With only three, TacMap cannot tell if one of them is a typo. With four or more it reports the fit as an RMS error and a Good, Fair or Poor grade, and flags the point that disagrees.
6. Tap **Done**. The status line reads **Calibrated** with the RMS error.

Grid intersections are the most precise points. The British National Grid and other national grids cannot be typed; enter latitude and longitude for those sheets instead. Polar UPS references are not supported.

You can leave calibration part way through and keep the points on the device to finish later. Once calibrated, the PDF can be tiled exactly like a GeoPDF.

## Working offline with it

Online basemaps and lookups are off on a fresh install, so nothing is fetched in the background. Your position reads out in MGRS, UTM or latitude and longitude. The imported map, your waypoints, drawings and recorded tracks all stay on the device in encrypted app-private storage.

Imported maps are not included in iCloud or iTunes backups. Keep the source PDF somewhere safe so you can import it again on a new phone.

## Get TacMap

Download on the [App Store](https://apps.apple.com/au/app/tacmap/id6773175573) or get it on [Google Play](https://play.google.com/store/apps/details?id=com.tacmap). Three days free, then one unlock.
