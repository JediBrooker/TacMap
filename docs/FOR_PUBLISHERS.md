# For map publishers

[Help](/support) · [Privacy](/privacy) · [TacMap](/)

This page is for anyone who publishes GeoPDF maps: forest and parks agencies, state mapping offices, trail associations, and the people who make hunting and fishing maps. If you have been selling or giving away GeoPDFs through the Avenza Map Store, you need a new "how to use this map on your phone" line for your product pages.

Your GeoPDFs work unchanged in TacMap.

Avenza's own [publisher FAQ](https://store.avenza.com/pages/avenza-map-store-publisher-faq) states that the Map Store is no longer accepting new publishers or new map uploads and that the store will close. No closing date is given there. TacMap is a different kind of app: it imports plain GeoPDF, PDF and MBTiles files directly, with no store in the middle. Your users pay once for the app, need no account, and use the map offline anywhere.

## What you need to do

Nothing to your files. A GeoPDF that opens in Avenza Maps, QGIS or Acrobat opens in TacMap.

Host the GeoPDF yourself. A download link on your own site, a Dropbox or Google Drive link, or a QR code printed on the paper sheet all work. Then point your users at our [GeoPDF guide](/guides/geopdf-on-iphone) so they know how to get the file into the app.

There is no TacMap store, no revenue share, no publisher account and no fee to you. You keep selling or giving away the file however you already do.

## What your users get

- **Import from the phone's own files.** On iPhone and iPad they save the file anywhere in the Files app (AirDrop, Save to Files from Safari or Mail, iCloud Drive) and pick it from inside TacMap under **Import / Export**. On Android they pick it with the system file picker. The original file stays where it was.
- **The sheet is placed in its own projection and datum.** TacMap reads the embedded georeference and draws its MGRS grid over the map, so the drawn grid sits on your printed grid. Users can check this by turning on **MGRS Grid** and zooming in on an intersection.
- **Offline, with no region limits.** The imported map works with no signal. Users can generate offline tiles from it once so a large sheet opens and zooms instantly.
- **Their data leaves in open formats.** Waypoints, symbols and drawings export as GeoJSON, KML or KMZ, and recorded tracks as GPX, for QGIS, Google Earth and ATAK.
- **Position readout in MGRS, UTM or latitude and longitude**, with a grid overlay, range rings, measurement in degrees or mils, and a red night mode.
- **Free to view, for good.** Opening your GeoPDF and seeing your position on it costs nothing, with no time limit and no TacMap account. The field tools (symbols, measuring, tracks, sharing) have a three-day trial and then one unlock, with no subscription. The App Store or Google Play shows the local price. This meets agency rules that forbid listing apps that require payment or a login to use the map.

The step-by-step guide for iPhone is at [/guides/geopdf-on-iphone](/guides/geopdf-on-iphone). The steps on Android are the same apart from where the file lives; the grid and datum tools are covered in [/guides/mgrs-on-android](/guides/mgrs-on-android).

## Paste this on your product page

Copy as is, or edit to taste:

```
Use this map offline on your phone: import the GeoPDF into TacMap (iOS and Android).
Free to view, no account. Guide: https://tacmap.app/guides/geopdf-on-iphone
```

One-line version:

```
Works offline in TacMap (iOS and Android), free to view, no account: https://tacmap.app/guides/geopdf-on-iphone
```

Link the guide rather than a store page so the user sees how to import before they install the app.

## Badge

You can use the TacMap app icon next to the snippet. It is at [https://tacmap.app/assets/brand/app-icon-512.png](/assets/brand/app-icon-512.png). Use the alt text "Works with TacMap" and link it to the guide above.

```
<a href="https://tacmap.app/guides/geopdf-on-iphone"><img src="https://tacmap.app/assets/brand/app-icon-512.png" width="64" height="64" alt="Works with TacMap"></a>
```

If you want a dedicated "Works with TacMap" badge in a particular size or style, email us and we will make one.

## Files that work

- **GeoPDF.** A PDF with the georeference embedded, as exported by QGIS, ArcGIS, Global Mapper, TerraGo and the USGS tools. TacMap reads the embedded projection and datum. The datums it knows are WGS84, NAD83, NAD27 (including the CONUS, Alaska and Canada variants), GDA94, GDA2020, ETRS89, ED50, OSGB36, Tokyo, CH1903, NTF and Pulkovo 1942. If a sheet uses a projection TacMap cannot read, the user can calibrate it by hand from the printed grid.
- **Plain PDF.** A PDF with no georeference, including a scan of a paper map. The user calibrates it from three or more known points (MGRS, UTM or latitude and longitude) and the app reports how well they fit. If you publish plain PDFs, printing the datum in the margin helps here.
- **MBTiles.** Raster tile packs import as a basemap.

Publish one sheet per file. A multi-page PDF is fine, TacMap asks which page holds the map. If a sheet is larger than the app can import it says so on import, so test your biggest sheet on a phone before you link it.

## For your staff and volunteers

If your rangers, volunteers or field staff need the app, email [christianbrooker@gmail.com](mailto:christianbrooker@gmail.com) and ask for promo codes. Tell us roughly how many and which platform.

If you would like to be listed on this page as a publisher whose maps work in TacMap, say so in the same email.

## Get TacMap

Download on the [App Store](https://apps.apple.com/au/app/tacmap/id6773175573) or get it on [Google Play](https://play.google.com/store/apps/details?id=com.tacmap). Three days free, then one unlock.
