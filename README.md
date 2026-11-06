# Marquee — an ad-free launcher for Google TV

Marquee replaces the Google TV home screen with one built around what you already pay for:

- **No ads, no sponsored rows, no rentals or purchases.** Every pick is on your Plex, included in a subscription you have, or genuinely free. Ad-supported tiers are never recommended.
- **Three home styles:** Dashboard (weather, calendar, watch-time cards), Cinematic (full-screen title with ad-free trailer previews), and Ambient (artwork, big clock, what's next). It can switch automatically by time of day and goes Ambient when idle. It also works as the system screensaver.
- **Plex-first recommendations:** unwatched, top-rated titles from your library, ranked alongside what's trending and top-rated on your services, with "Because you watched…" reasons from your Plex history.
- **Calendar with leave-now alerts:** reads your Google Calendar's private iCal link, checks live traffic, and shows a "time to leave" card over whatever is playing, with a QR code for directions on your phone.
- **TizenTube (or SmartTube) instead of YouTube:** your preferred YouTube app replaces YouTube everywhere, including the remote's YouTube button. "New from your subscriptions" opens there.
- **Ribbons that change over time:**
  - **Seasonal and holiday ribbons:** Halloween, Thanksgiving, Christmas, New Year's, Valentine's, awards season, May the 4th, summer, back to school and Friday the 13th.
  - **Weather ribbons:** rainy-night comfort picks and snow days.
  - **Time-of-week ribbons:** Friday movie night, easy Sunday, and short episodes before bed.
  - **Your Plex's own rotating hubs:** anything you promote to Home with "Manage Recommendations" always shows; Top Rated, Because you watched, More in a genre and By a director rotate daily.
  - **More rotating rows:** a daily collection spotlight and "New on your services".
  - **How seasonal picks are chosen:** your own Plex collections by name (e.g. "Halloween"), then library titles tagged on TMDB (e.g. *halloween*), then genres, then titles included with your services.
- **Plezy support:** play your Plex library in Plezy instead of the Plex app, using Plezy's `plezy://play` links. It can also open Plezy wherever the Plex app would open.
- **"What should we watch?"** A mood, length and type picker. It uses plain filters and ratings, no AI.

No AI is used anywhere in this version.

---

## Updates (automatic, from GitHub)

Marquee updates itself from this repo's **Releases**:

1. Bump `versionName` / `versionCode` in `app/build.gradle.kts`, commit, then tag and push: `git tag v0.14.0 && git push --tags`.
2. The **Release** workflow tests, builds and signs the APK (the signing key is the `MARQUEE_KEYSTORE_B64` secret, never committed) and publishes it as a release.
3. Each TV checks every few hours with its read-only token, verifies the APK is Marquee signed with the same key and newer, then installs it **while the TV is idle** (screen off or screensaver). Settings → Updates shows the status and has *Check now* / *Install now*.

Each TV needs, once: the repo name and a fine-grained, read-only token (this repo only, *Contents: Read*) on its phone setup page, and Marquee installed with itself as installer (`pm install -i app.marquee.tv`) so updates need no confirmation.

**Keep a backup of `marquee-release.jks`.** Without the same key, TVs can't accept updates.

## Adding another TV

Install as below, then copy your whole setup from an existing TV (Plex, keys, services, preferences):

```bash
curl -s http://<TV1-IP>:8765/s/<TV1-code>/export > marquee-settings.json
curl -s --data-binary @marquee-settings.json http://<TV2-IP>:8765/s/<TV2-code>/import
```

(Each TV's code is in its phone setup link: Settings → Accounts & setup.)

---

## 1. Install on your Google TV Streamer

1. On the TV: **Settings → System → About → Android TV OS build**. Click it 7 times to turn on Developer options.
2. **Settings → System → Developer options → USB debugging / Wireless debugging:** turn it on. Note the TV's IP address (**Settings → Network**).
3. On a computer with [Android platform-tools](https://developer.android.com/tools/releases/platform-tools):

```bash
adb connect <TV-IP>:5555          # accept the prompt on the TV
adb install -r marquee-0.2.0.apk
```

Alternatively, copy the APK to the TV with an app like "Send Files to TV" and open it with a file manager.

## 2. Grant the two permissions (one time)

Google TV hides these settings, so grant them with adb:

```bash
# Watch-time stats on the dashboard
adb shell appops set app.marquee.tv GET_USAGE_STATS allow

# Home-button takeover, YouTube redirect and leave-now alerts
adb shell settings put secure enabled_accessibility_services app.marquee.tv/.system.MarqueeAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

Marquee's accessibility service never reads screen content. It only notices which app came to the front.

Optional: make Marquee's Ambient view the screensaver:

```bash
adb shell settings put secure screensaver_components app.marquee.tv/.system.AmbientDreamService
```

## 3. Set up from your phone

Open Marquee → **Settings → Accounts & setup** and scan the QR code. Your phone and TV need to be on the same Wi-Fi. Then fill in:

| What | Where to get it | Needed for |
|---|---|---|
| Your services | tick the ones you pay for | everything |
| TMDB key (free) | themoviedb.org → Settings → API | trending, where to watch, artwork |
| OMDb key (optional, free tier) | omdbapi.com | IMDb and Rotten Tomatoes scores |
| City | type it | weather |
| Calendar iCal link | Google Calendar on a computer → Settings → your calendar → Integrate calendar → **Secret address in iCal format** | agenda and alerts |
| Home address + Google Routes API key | Google Cloud console → enable **Routes API** → create an API key | live-traffic "leave by" times |
| Google OAuth client (TVs and Limited Input devices) + secret | Google Cloud console → enable **YouTube Data API v3** → Credentials | "New from your subscriptions" |
| Preferred YouTube app | dropdown (TizenTube is detected automatically) | YouTube redirect |

Then, on the TV:

- **Link Plex:** go to Settings → Accounts & setup → Link Plex and enter the code at plex.tv/link.
- **Sign in to YouTube:** go to Settings → Accounts & setup → Sign in with Google and enter the code at google.com/device.

## 4. Using it

- **Plex player:** Settings → Home button & players → "Play my Plex library in". Choose Automatic, Plezy or Plex. Automatic uses Plezy when it's installed.
- **Home styles:** Settings → Home style. Turn on "Switch automatically" for Dashboard in the morning, Cinematic in the evening, and Ambient when idle.
- **Profiles:** the round avatar button switches profiles. Kid-safe profiles show only family titles and hide YouTube.
- **Trailer previews (Cinematic):** rest on a Plex title for about 2.5 seconds. Press ⏯ for sound, or OK for details.
- **Detail page:** the "Best way to watch" panel ranks your Plex copy first by picture and sound quality, then your included services.

---

## Known limits

- **Opening streaming titles:** Marquee opens the service's app and shows the title name. Jumping straight to a title inside Netflix and similar apps needs per-service IDs that TMDB doesn't provide. A Watchmode key would add this later.
- **Plex deep link:** playback in the Plex app uses a deep link. If your Plex app version ignores it, Marquee opens Plex instead.
- **Plezy links:** opening a title directly uses Plezy's `plezy://play` links. These are only in recent Plezy versions; older ones simply open the app.
- **Seasonal ribbons:** a ribbon only appears once it has at least four titles. With a small library and few services, some seasons may not show up.
- **Trailer previews:** these only auto-play from your Plex server, because they're guaranteed ad-free there. Other trailers open in your preferred YouTube app.
- **Home-button takeover:** this relies on the accessibility service. A future Google TV update could change how well that works. If it breaks, map the Home button with a button-remapping app as a fallback.
- **YouTube watch history:** the YouTube API doesn't share it, so the YouTube share of watch time comes from app usage instead.

## Building from source

Requirements: JDK 17+ and the Android SDK (platform 35).

```bash
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest      # calendar parser, seasonal ribbon and screenshot tests
./gradlew recordPaparazziDebug   # renders UI screenshots to app/src/test/snapshots
```

The release build is signed with a development key, `app/marquee-release.jks` (password `marquee-dev`). **Before you sell or distribute it, create your own key** and set `MARQUEE_STORE_PASSWORD` and `MARQUEE_KEY_PASSWORD`.

### Code map

```
app/src/main/java/app/marquee/tv/
  data/        Settings store, models, streaming-service catalog
  plex/        Plex PIN sign-in, server discovery, library, artwork, trailers
  tmdb/        Trending, discover-by-service, where-to-watch (no rent/buy), artwork, OMDb ratings
  youtube/     Google device sign-in, new uploads from subscriptions
  calendar/    iCal reader (time zones, recurrence, exceptions)
  traffic/     Google Routes, traffic-aware drive time
  stats/       Weekly watch time from Android usage stats
  recs/        Ranking (no AI), detail page "best way to watch", mood picker, search
  setup/       Phone setup web page + QR codes
  system/      Accessibility service (home takeover, YouTube redirect, alerts), screensaver, boot
  ui/          Compose for TV: Dashboard, Cinematic, Ambient, Detail, Tonight, Search, Settings
```

Fonts: Manrope and Instrument Serif, both under the SIL Open Font License (see `OFL-fonts.txt`).
