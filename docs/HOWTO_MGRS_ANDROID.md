# MGRS and UTM on Android without a subscription

[Help](/support) · [Privacy](/privacy) · [TacMap](/)

TacMap puts a live MGRS readout, a grid overlay, UTM and latitude/longitude on an Android phone with no subscription and no account. You try it free for three days and then unlock it once through Google Play. This page walks through the grid tools and where each one lives in the app. The iPhone app has the same tools under the same names.

## No account, no renewal

TacMap never asks you to sign up. There is no TacMap login, no ads and no behavioural analytics. Your waypoints, drawings and tracks are encrypted in app-private storage on the phone and are not uploaded anywhere unless you export them or join a Unit Sync room yourself.

Online basemaps, elevation and weather are off on a fresh install. Every grid tool below works with the phone in flight mode, on an imported map or on nothing at all.

## Read your position

The large readout at the top of the map is your current position in MGRS by default. Tap it to copy the coordinate. To change the format, open the map menu, choose **Settings, Privacy & OPSEC** and set **Coordinate display** to **MGRS**, **UTM** or **WGS84** (latitude and longitude). The choice survives relaunch.

Hold a finger still anywhere on the map for a second to get a menu for that spot, including **Copy Coordinates** for the grid reference under the crosshair.

## Turn on the grid overlay

Open **Layers and Labels** and switch on **MGRS Grid**. TacMap draws the 100 km square letters and the numbered grid lines over whatever map is active, online basemap or imported GeoPDF. The grid density follows the zoom. The lines are computed on the phone from the same test vectors on Android and iPhone, so both platforms draw the same grid.

On an imported GeoPDF the drawn grid should sit on the sheet's printed grid. If it is offset, see the datum section below.

## Enter a coordinate

The search field accepts a place name, a full MGRS reference, or a 4, 6, 8 or 10-figure local grid such as `LH 349 522`, as well as latitude and longitude. For a local grid TacMap uses the 100 km square you are currently in and centres the map on that square. Coordinate search works offline. Place-name search needs online lookups turned on and a connection.

When you edit a symbol or waypoint, its location field takes the same inputs, and **Set MGRS Location** moves it to a typed reference.

TacMap reads the latitude band and square letters strictly, so a typo in the zone or square is flagged rather than silently placed in the wrong square.

## Datum

Coordinates on screen and in exports are WGS84.

Imported GeoPDFs are placed in the sheet's own projection and datum, including NAD27, NAD83, GDA94, GDA2020, ETRS89, ED50 and OSGB36, so a sheet drawn on an older datum lands where it should rather than a few hundred metres off.

When you calibrate a plain PDF, **Datum printed on the map** lets you choose **WGS84**, **GDA94 / MGA94** or **GDA2020 / MGA2020**. Read the datum off the sheet's margin. The wrong datum can shift the map by 200 m or more. If the sheet does not say, choose **Not sure (use WGS84)**.

## Range rings

Hold a finger on the map and choose **Range Rings Here**, or open **Range Rings** from a placed symbol. Set the ring spacing, choose metres or kilometres, and the number of rings, up to ten with the outer ring at most 1000 km out.

The rings are added as dashed line drawings on the active layer. They sync to your unit, export with your other drawings, undo like any other drawing, and stay centred on the symbol if you move it.

## Measure in degrees or mils

**Measure From Here** on the long-press menu starts a distance, area and bearing measurement from that point. Bearings can be shown in degrees or NATO mils. **North Up** keeps north at the top with two-finger rotation available, or rotate the map with your heading using the phone compass.

## Night mode

Tap the moon button on the map to turn on **Night mode**. The whole screen, menus and dialogs included, turns red and dims to protect your night vision. The status bar is hidden while it is on. **Night mode brightness** sets how dim. Tap the moon again to turn it off.

## Keep the screen on

In **Settings, Privacy & OPSEC**, **Keep screen on** stops the phone locking while TacMap is open, for a mounted phone or one left on a table. It is off by default and it uses more battery. Lock the phone yourself before leaving it unattended.

## Lock the app

TacMap on Android has an optional **PIN** or **fingerprint or face unlock** app lock. Turn it on in **Settings, Privacy & OPSEC** if other people handle your phone.

## What is free and what the unlock adds

Opening maps and seeing your position, including the MGRS grid and coordinate readout on this page, is free with no time limit. The three-day trial adds every tool; when it ends, the tools (symbols, drawings, measuring, elevation, weather, tracks, GPX/KML/GeoJSON, TacMap Chat, Unit Sync and the Esri basemaps) need a one-time purchase through Google Play, which shows your local price, and the unlock is permanent. **Restore Purchases** brings it back on a new phone with the same Google account. A Play purchase does not unlock the iPhone app.

## Get TacMap

Get it on [Google Play](https://play.google.com/store/apps/details?id=com.tacmap) or download on the [App Store](https://apps.apple.com/au/app/tacmap/id6773175573). Three days free, then one unlock.
