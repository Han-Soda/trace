# Trace

Turn your Google Maps Timeline into a short, calm video of where you went.

Trace is a small Android app: open your Timeline export, pick the dates, adjust a few
things while the preview plays, and export an MP4 to `Movies/Trace`.

## What you can change

- **Dates**: day, week, month, year or everything, step back and forward, or pick any range.
- **Zoom smoothness**: how often and how quickly the camera reframes. Higher values merge
  nearby moments into fewer shots and slow the moves between them.
- **Travel points**: how many points of your history draw the route, from a few dozen for
  clean, simple lines up to every point.
- **Style**: Light, Dark and Voyager maps from CARTO, or Paper and Ink, which draw coastlines
  on the device and need no network or key. Map labels on or off.
- **Colour**: nine presets or any colour you pick, and thin, regular or bold lines. Travel
  points can be shown as dots.
- **Text**: a title (your own, or the dates), the running date and the distance travelled, each
  on or off.
- **Video**: 9:16, 4:5, 1:1 or 16:9, 6 to 60 seconds, 720p or 1080p, 30 or 60 fps.

You can also save the final overview as an image, and share either straight from the app.

## Getting your Timeline

In Google Maps on your phone: **Your Timeline → ⋮ → Location & privacy settings → Export
Timeline data**. Open the saved `Timeline.json` in Trace, or share it to Trace.

Trace also reads the iPhone export, and from Google Takeout `Records.json` and the monthly
Semantic Location History files, including the ZIP archive itself. The imported history stays
in the app's private storage; nothing is uploaded. The only network traffic is map tiles.

## CARTO map key

CARTO's Light, Dark and Voyager maps need a free key. Without one, tiles carry an
"API KEY REQUIRED" watermark. Request a key at <https://carto.com/basemaps/apikey/> (free up to
5 million tiles a month), then paste it in **Settings → Map key** and tap **Test**. Paper and
Ink never need a key.

## How the camera works

The route is cut into shots at stops and at long jumps such as flights. Neighbouring shots merge
while none of them would have to zoom out by more than the smoothness allows. Within a shot the
camera holds still and the dot moves at a steady pace across the screen. Between shots the dot
waits while the camera eases to the next framing, pulling out before a flight and diving in after
landing. The video ends by easing out to the whole route.

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

Map tiles © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors ©
[CARTO](https://carto.com/attributions). Coastlines and lakes from
[Natural Earth](https://www.naturalearthdata.com/) (public domain); `tools/make_land_asset.py`
rebuilds `land.bin` from it. Icons from Material Symbols (Apache 2.0).
