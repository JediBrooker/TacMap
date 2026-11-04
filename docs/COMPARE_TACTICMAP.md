# TacMap vs TacticMap

[Threat model](/threat-model) · [Privacy](/privacy) · [Help](/support) · [TacMap](/)

TacMap and TacticMap both put MGRS, offline maps and field markup on a phone. They make different trade-offs on pricing, offline coverage, file formats and accounts. This page sets them side by side so you can pick the one that fits how you work.

The TacticMap column describes their public store listing as of November 2026. We do not use their app day to day, and listings change, so check their current page before you decide. Nothing here is a claim about their internals.

## At a glance

| | TacMap | TacticMap (as of November 2026, from their public listing) |
|---|---|---|
| Price | Three-day trial, then one one-time unlock. No subscription. | Subscription. |
| Offline maps | Import PDF, GeoPDF and MBTiles for anywhere on Earth. No country restrictions. | Offline map coverage is tied to regions. |
| Your data | GeoJSON, KML and KMZ in and out. GPX track export. Opens in QGIS, Google Earth and ATAK. | Proprietary format. |
| Account, ads, analytics | None. No TacMap account, no ads, no behavioural analytics. | Account based. |

## Pay once

TacMap has a three-day free trial and then a single permanent unlock through the App Store or Google Play. There is no yearly renewal and no tier that locks features behind a higher plan. If you stop paying for a subscription app, you lose the app. A one-time unlock does not expire.

A purchase in one store does not carry across to the other store, so an iPhone unlock does not unlock an Android phone. See [Help](/support#purchases-and-recovery) for restoring a purchase.

## Offline anywhere

TacMap does not ship map regions. You bring your own: a GeoPDF from a national mapping agency, a scanned paper sheet saved as PDF, or an MBTiles pack you built yourself. The app renders them without a connection, with no limit on which country the sheet covers.

GeoPDFs are placed in the sheet's own projection and datum, so the MGRS grid lines up with the printed grid. A plain PDF with no georeference can be calibrated from three or more grid references. Once a map is in, you can generate offline tiles from it so it opens and zooms instantly. There is a step-by-step guide at [How to load a GeoPDF on iPhone](/guides/geopdf-on-iphone).

Online basemaps, elevation and weather exist too, but they are off on a fresh install and stay off until you turn them on.

## Open formats

Everything you draw in TacMap can leave it as GeoJSON, KML or KMZ. Recorded tracks export as GPX. The same formats import, so a boundary from QGIS, a KML from Google Earth or a mission package from ATAK lands on your map without a converter. If you stop using TacMap, your data is still readable by every GIS tool you own.

## No account

TacMap never asks you to sign up. There are no ads and no behavioural analytics. Mission data is encrypted in app-private storage on the device. Unit Sync rooms are opened with a join code you share yourself, not a login. The [threat model](/threat-model) spells out exactly what the relay sees when you use Unit Sync, and the [privacy policy](/privacy) lists every optional network request.

## Where TacticMap may suit you better

Be honest with yourself about what you need.

- If you want a hosted team account that someone else administers, an account-based app is set up for that. TacMap's team features run on a join code and a relay that stores encrypted blobs; there is no admin console and no user directory.
- If you prefer to download ready-made map regions rather than source your own GeoPDF or MBTiles, a region catalogue is less work up front.
- If your unit has already standardised on their format and workflow, switching has a cost regardless of which app is better on paper.

## Where TacMap suits you better

- You want to pay once and own the tool.
- You work somewhere no region catalogue covers, or you already have the exact sheet you need as a PDF.
- You need your drawings and symbols in open formats for QGIS, Google Earth or ATAK.
- You do not want an account, ads or analytics in a navigation app, and you want to read the [threat model](/threat-model) before you trust it.

## Get TacMap

Download on the [App Store](https://apps.apple.com/au/app/tacmap/id6773175573) or get it on [Google Play](https://play.google.com/store/apps/details?id=com.tacmap). Three days free, then one unlock.
