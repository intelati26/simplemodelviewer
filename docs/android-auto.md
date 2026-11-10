# Android Auto (weather category)

The phone app also contains a car app, `RadarCarService`, built with the Car App Library (`androidx.car.app:app`). It is declared in the **points of
interest** category (it was the beta weather category first, which the host may not list), in which an app may draw its own map (the radar) on the car
display and show a short content pane beside it. It needs the `MAP_TEMPLATES` and `ACCESS_SURFACE` permissions, both in the manifest.

## Status

This is an **initial sketch**. It compiles in CI but has not been run on a car or on the Desktop Head Unit, so the host may reject a template
or a button that the library accepts at build time.

The weather category is in **beta** (Play's testing tracks only), which is why the app moved to the points-of-interest category. Check the Play Console's car app policies for that category before planning a launch.

## What it does now

- One screen (`RadarCarScreen`): the newest MRMS scan fills the map surface (drawn by the same `MapRenderer` as the phone's MRMS page, through
  `CarRadar`), with a pane beside it (product, scan time and age, sector, a "not for safety decisions" line).
- Buttons: previous / next product (composite reflectivity, 60-minute rotation track, hail size, VIL), next sector (CONUS and the meso sectors),
  Refresh. The scan is looked for again every two minutes while the screen is showing.
- Still pictures only: no loop. A moving picture does not belong on a driver's display, and the car app quality rules for weather apps are strict
  about distraction.

## Why the app may not show in the car

Nothing is logged when Android Auto hides a car app. The manifest asks for car API level 1 only and the radar screen needs 7 (map template): on an older host
the app starts and says "Update Android Auto" instead of vanishing. Sideloaded apps also need "Unknown sources" turned on in Android Auto's developer settings.
If the radar screen is rejected by the host in this category, the fallback is the POI-native `PlaceListMapTemplate`.

## Not done yet

- Overlays (warnings and watches, the SPC outlook, LightningCast) and contours on the car map.
- A sector around the car's location (needs the location permission and the library's location API).
- Pan and zoom (`SurfaceCallback.onScroll` / `onScale` and a `MapController` action strip).
- A release host allowlist review (`createHostValidator` allows any host in debug builds and the library's sample list in release builds).
- The Play Console side: declaring the app as a car app in the weather category and the car app quality review.

## Trying it

1. Install a debug build on a phone with Android Auto, enable developer mode in Android Auto's settings, and start the head unit server from its menu.
2. On a computer, `adb forward tcp:5277 tcp:5277` and run the Desktop Head Unit (from the Android SDK's `extras/google/auto`).
3. Open "simplemodelview" from the launcher on the head unit.

## Files

`RadarCarService.kt` (service, host validator and the info screen), `RadarCarScreen.kt` (template and surface drawing), `CarRadar.kt` (loading and drawing the scan),
`res/xml/automotive_app_desc.xml`, and the service, permission (`androidx.car.app.MAP_TEMPLATES`) and meta-data in `AndroidManifest.xml`.
