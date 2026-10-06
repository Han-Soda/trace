# Trace

Turn your Google Maps Timeline into a short, calm video of where you went.

Trace is a small Android app: open your Timeline export, pick the dates, adjust a few
things while the preview plays, and export an MP4 to `Movies/Trace`.

## What you can change

- **Dates**: day, week, month, year or everything, step back and forward, or pick dates in a
  calendar: one stretch of days, or any days you like. Days with travel are marked. Separate days
  aren't joined by a line; the video glides from one to the next.
- **Camera**: *Follow* (the default) travels with the trace, zooming out for long trips and back
  in for short ones, so the video never stands still. *Shots* holds a steady view of each part of
  the trip and glides to the next. *Whole route* keeps everything in view. **Zoom smoothness**
  sets how gently and how often the zoom changes, and **Pause at stops** lets the trace wait a
  moment at long stops; off, it never stops moving.
- **Route**: **Travel points** sets how many points of your history draw the route, from a few
  dozen for clean, simple lines up to every point. **Remove GPS errors** opens a map of every
  point: tap one, or two to select the stretch between them, and remove it. Likely errors are
  ringed and one tap away. Removals can be undone and are kept when you import a newer export.
- **Style**: Paper, Ink and Streets are free maps drawn on the device from OpenFreeMap, with
  streets, water, parks and place names; Light, Dark and Voyager come from CARTO and need a key.
  Map labels on or off.
- **Colour**: nine presets or any colour you pick, and thin, regular or bold lines. Travel
  points can be shown as dots.
- **Text**: a title (your own, or the dates), the running date and the distance travelled, each
  on or off.
- **Video**: 9:16, 4:5, 1:1 or 16:9, 6 to 60 seconds, 720p or 1080p, 30 or 60 fps.

You can also save the final overview as an image, and share either straight from the app.

## Getting your Timeline

On your phone: **Settings → Location → Location services → Timeline → Export Timeline data**.
The **Open Timeline settings** button on Trace's start screen takes you there, or as close as
your phone allows. Open the saved `Timeline.json` in Trace, or share it to Trace.

Trace also reads the iPhone export, and from Google Takeout `Records.json` and the monthly
Semantic Location History files, including the ZIP archive itself. The imported history stays
in the app's private storage; nothing is uploaded. The only network traffic is map tiles.

## Maps

Paper, Ink and Streets are drawn on the phone from [OpenFreeMap](https://openfreemap.org)'s free
vector tiles, which need no key or account. Without a connection they fall back to bundled
coastlines.

CARTO's Light, Dark and Voyager maps need a free key. Without one, tiles carry an
"API KEY REQUIRED" watermark. Request a key at <https://carto.com/basemaps/apikey/> (free up to
5 million tiles a month), then paste it in **Settings → Map key** and tap **Test**.

## How the camera works

The route is cut into scenes at stops, at long jumps such as flights and at gaps between chosen
days. Neighbouring scenes merge while none of them would have to zoom out by more than the
smoothness allows. The dot moves at a steady pace across the screen, however far the camera is
zoomed, so a walk and a flight read at the same speed; flights and jumps between days take a set
time. The follow camera looks a little ahead of the dot along a smoothed path and zooms to the
scale of the current scene, pulling out before a flight and diving in after landing. Every video
ends by easing out to the whole route.

## Building

Open this `trace` folder in Android Studio, or run:

```sh
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # parser, route and camera tests
./gradlew connectedDebugAndroidTest  # encodes a real video on a device or emulator
```

The `Trace` GitHub Actions workflow runs the same on every push and attaches the APKs to the
run. APKs are signed with the development key in `app/debug.keystore` (password `android`), so
builds from any machine install over each other. To sign with your own key, set
`TRACE_KEYSTORE`, `TRACE_KEYSTORE_PASSWORD`, `TRACE_KEY_ALIAS` and `TRACE_KEY_PASSWORD`.

## Credits

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors. Free styles
from [OpenFreeMap](https://openfreemap.org) © [OpenMapTiles](https://openmaptiles.org); CARTO
styles © [CARTO](https://carto.com/attributions). Coastlines and lakes from
[Natural Earth](https://www.naturalearthdata.com/) (public domain); `tools/make_land_asset.py`
rebuilds `land.bin` from it. Icons from Material Symbols (Apache 2.0).
