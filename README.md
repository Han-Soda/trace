# Trace

Turn your Google Maps Timeline into a short, calm video of where you went.

Trace is a small Android app: open your Timeline export, pick the dates, adjust a few
things while the preview plays, and export an MP4 to `Movies/Trace`. The options sit in five
tabs under the preview: Trip, Camera, Photos, Look and Video.

## What you can change

- **Dates**: day, week, month, year or everything, step back and forward, or pick dates in a
  calendar: one stretch of days, or any days you like. Days with travel are marked. Separate days
  aren't joined by a line; the video glides from one to the next. A stretch of days can also start
  and end at **exact times**, by the trip's local clock, so the video and its distance cover just
  the trip.
- **Camera**: *Lock on* (the default) keeps the trace's dot in the middle while the map moves
  under it. *Smooth* holds the map still while the dot crosses the frame, then glides on when the
  dot nears the edge. *Heading up* turns the map so the way ahead always points up, like a car's
  navigation; place names are hidden in it so they never turn upside down. All three zoom out for
  long trips and back in for short ones, and **Distance** sets how close they stay. **Camera lag**
  lets *Lock on* and *Heading up* trail the dot and catch up with it, like a game's camera; at 0%
  the dot stays dead centre. *Shots* holds a steady view of each part of the trip and glides to
  the next. *Whole route* keeps everything in view. **Zoom smoothness** sets how gently and how
  often the zoom changes, and **Pause at stops** lets the trace wait a moment at long stops; off,
  it never stops moving. **Start with the whole route** opens on the overview and flies in to
  where the trip starts. **Speed** is *Even*, the same pace on screen all the way, or *True to
  life*: walks take their time and drives rush by, as they did.
- **Route**: **Travel points** sets how many points of your history draw the route, from a few
  dozen for clean, simple lines up to every point. **Remove GPS errors** opens a map of every
  point: tap one, or two to select the stretch between them, and remove it. Likely errors are
  ringed and one tap away. Removals can be undone and are kept when you import a newer export.
- **Photos and videos**: add them from the gallery, or **Find from these days** and Trace looks
  through the gallery for the chosen dates and suggests an even spread of what it finds. Each
  comes up on the route where and when it was taken while the trace waits: as a large **Card**,
  standing **On the map**, small **In a corner** of the video, or as a **Print** left lying
  on the map. **Place and time** writes where and when under each one. A video plays up
  to 10 seconds, at its own frame rate and with its sound (**Sound from videos** turns that off);
  tap it to choose which part. **Each photo** sets how long a photo stays. Trace places them by
  the time they were taken, so ones without a date are left out.
- **Style**: Paper, Ink and Streets are free maps drawn on the device from OpenFreeMap, with
  streets, water, parks and place names; Light, Dark and Voyager come from CARTO and need a key.
  Map labels on or off.
- **Colour**: nine presets or any colour you pick, and thin, regular or bold lines. Travel
  points can be shown as dots.
- **Text**: a title (your own, or the dates), the running date and the distance travelled, each
  on or off.
- **Video**: 9:16, 4:5, 1:1 or 16:9, 6 to 60 seconds, 720p or 1080p, 30 or 60 fps.

You can also save the final overview as an image, and share either straight from the app. Trace
is in English or Russian, whatever the phone's language: **Settings → Language**.

## Getting your Timeline

On your phone: **Settings → Location → Timeline → Export Timeline data** (on some phones,
Timeline is under **Location services**). The **Export from Timeline** button on Trace's start
screen, and in its settings, opens the Timeline page, found the way the Settings app finds it,
or else Location settings, one tap away. When the export asks where to save, pick **Trace** from
the list of places: Trace imports the file as soon as you come back to it. You can also save it
anywhere and open or share it to Trace. Google offers no way for an app to fetch the Timeline by
itself, so this is as close to one tap as it gets.

Trace also reads the iPhone export, and from Google Takeout `Records.json` and the monthly
Semantic Location History files, including the ZIP archive itself. The imported history stays
in the app's private storage; nothing is uploaded. The only network traffic is map tiles, and
place names for photos. See the [privacy policy](PRIVACY.md).

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
time. The travelling cameras zoom to the scale of the current scene, pulling out before a flight
and diving in after landing. *Lock on* keeps the dot centred, rounding its path only enough that
the map doesn't jerk at corners; with lag, a critically damped spring chases the dot instead,
taking up to 1.2 seconds to catch up and never letting it get more than a quarter of the frame
ahead. *Smooth* holds a frame until the dot gets within a fifth of its edge, then glides on to
leave the dot a fifth behind the middle; *Heading up* averages the direction of travel over a
second or two and turns the map at most 100° a second. Every video ends by easing out to the whole
route, north up, and can open the same way in reverse, taking as long to fly in as any other move
that far. With *True to life* speed, each stretch takes its share of the time the trip spent
moving, leaving out stops and anything slower than a stroll, such as a phone left on a table.

## Building

Open this `trace` folder in Android Studio, or run:

```sh
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # parser, route and camera tests
./gradlew connectedDebugAndroidTest  # encodes a real video on a device or emulator
```

The `Trace` GitHub Actions workflow runs the same on every push and attaches the release APK and
the bundle for Google Play to the run, named after the commit they were built from, such as
`trace-c1d4b8d.apk` and `trace-c1d4b8d.aab`, with the R8 mapping. The version in **Settings ›
About** ends with the same commit, such as `1.2-c1d4b8d`, and the version code grows with every
run. With the Play upload key in the repository's secrets, `TRACE_UPLOAD_KEY` (the PKCS #12 file
in base64) and `TRACE_UPLOAD_PASSWORD`, both are signed with it. Without, they're signed with the
development key in `app/debug.keystore` (password `android`), so builds from any machine install
over each other: fine for trying out, not for Play. Locally, set `TRACE_KEYSTORE`,
`TRACE_KEYSTORE_PASSWORD`, `TRACE_KEY_ALIAS` and `TRACE_KEY_PASSWORD` to sign with your own key.

Run the workflow by hand (**Actions › Trace › Run workflow**) to make the bundle for Play. Such a
run stops if the upload key is missing or doesn't open, rather than sign with the development key,
and sums up at the top of its page which key signed the bundle, with a link to download it. Tick
*Also retake the store screenshots* to take them again, in English and Russian, into
`play-store/screenshots`. The rest of the store listing is in [`play-store`](play-store).

## Credits

Map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors. Free styles
from [OpenFreeMap](https://openfreemap.org) © [OpenMapTiles](https://openmaptiles.org); CARTO
styles © [CARTO](https://carto.com/attributions). Coastlines and lakes from
[Natural Earth](https://www.naturalearthdata.com/) (public domain); `tools/make_land_asset.py`
rebuilds `land.bin` from it. Icons from Material Symbols (Apache 2.0).
