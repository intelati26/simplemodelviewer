# Roadmap: Simple mode, the outlook browser, and everything not finished

Written 2026-11-25 after v0.4.3 and the work since. Nothing here is a promise; it is the open list, in the order I would do it.
Status words: **built** (committed, in the app), **partial** (some of it works), **not started**.
`docs/ideas.md` has the older, looser list; this file replaces its "next" sections.

## 0. State of the code (not pushed unless marked)

| Item | Status |
|---|---|
| Release 0.4.3 (places, legend numbers, Compare, export, loop range bar) | released |
| Outlook page (map Days 1-8, discussion text), `63fd823` | built, **not pushed** |
| Loop range bar, `ac94d87` | pushed, not released (0.4.4 would carry it) |
| Compare Members grid, share dialog, 30 km score, `2dc5ad1` .. `1283a9c` | pushed, not released |
| Docs `eb70cdf` (ideas) | committed, not pushed |

## 1. The persona and the shape of the app

Persona (the owner, a few years ago): someone one step from The Weather Channel who sees an SPC outlook on TV and wants to look at SPC themselves.
Not a radar app (they have those). The edge is **SPC's own guidance and the models, explained**.

Decision to make: **one app with a Simple / Advanced switch** (recommended), not two apps. First run asks which one; Simple is the default.

### 1.1 Simple home, "Today" (sketched twice, not started)
Cards, top to bottom:
1. **SPC outlook at your place, in words.** "Slight risk (2 of 5)". Trigger default Slight and above (a setting, Marginal allowed). Names the **location's top hazard type** with its percent
   ("Hail is the top threat here, 15%") and the other two smaller. Day 1/2/3 switch. SPC's category names are kept; the 1-5 number is added.
2. **Chance at your place** (the family card):
   - **Thunder** from REFSCT in its three periods: next 1 h, 4 h, rest of the convective day (label them with clock times in local time, computed from the run's valid times).
   - **Severe, by type** from GREFS 4-h (and 24-h) tornado / wind / hail; **overall** = the SPC category, not a combined percent (hazards are not independent, so no invented sum).
   - **A line per row** (the owner uses about 25% on thunder and raises the alarm as it rises). Defaults for severe types come from SPC levels (tornado 2% / 5%), not 25%.
     One global default, optional per place (open question).
   - **"Rising"**: each row compared with the same period in the previous run ("up from 30%"). Needs a rule for what counts as a change (small wiggles should not show an arrow).
   - Headline turns red at the first row to cross its line and names it ("Thunder past your 25% line from about 4 pm").
   - **Model guidance, not a forecast** labeling, visible.
3. **Radar now with watches and warnings** (last-hour loop, centered on the saved place or location).
4. **How one member shows it** (optional second layer): the chosen REFS member as a 12-hour strip at the place (the app already saves a chosen member). Cost: about 5-12 MB for 12 hours, so it
   respects the Wi-Fi-only setting. "Best match" member (Compare scoring) only behind an explicit tap.
5. **Share a heads-up** for family: a picture plus a short text ("Thunder chance in Norman: 45% until 8 pm, up from 30%, past our 25% line"). Sent only when tapped.

Not in scope: background alerts or notifications (decided earlier: no location alerts). A "tell me when it crosses my line" notification is a separate decision.

### 1.2 What moves behind Advanced
Full MRMS catalog, REFS member viewer, Compare and the member ranking, globe, overlay tree, loop range bar, METAR density, storage and cache page, timings, GFS and NBM page controls.
Simple keeps about ten curated products with "All products" opening the full picker.

### 1.3 Work to do
- A `mode` setting (first-run choice, switch in the header), page list per mode. **Small.**
- The Today screen and its cards. **Medium**; the data readers mostly exist (outlook polygons, GREFS and REFSCT stores, places, outlook archive for the "previous run" comparison is NOT the same thing:
  the previous-run comparison needs the previous model cycle's REFSCT/GREFS, which `RefsRepository.findCycles` can open).
- Point reads from GREFS / REFSCT (coarse 40.6 km NCEP 212 grid, kilobytes per read). **Not started.**
- Check first: whether the app can read the outlook **per hazard at a point**. It reads the polygons and the categorical risk at a tap; the hazard breakdown ("top threat") is not confirmed.

## 2. The Outlook page (built, `63fd823`) and what is left on it

Built: map for Days 1-8 with the kinds each day has (categorical / tornado / wind / hail for Days 1-2, categorical and severe chance for Day 3, severe chance for 4-8), issue and valid times,
legend, saved places' risk, tap for the risk at a point, and the discussion text (SPC product via the Iowa Environmental Mesonet) with headline and Summary first.

Checked on the emulator: Day 1 with live data; the discussion expanding. **Not checked on the device:** Days 2-8, the kind chips, tap-for-risk, pinch zoom on that page.

Open:
- **Discussion to models link** (the owner's idea): find "12Z", a model name (REFS, HRRR), a parameter (SBCAPE, MUCAPE, SRH, STP) and a range ("250-500 J/kg") in the text and offer a chip that opens REFS on that product at
  that valid time, **zoomed to the area the sentence is about**. The catalog has sbcape, mucape, sbcinh, srh, shrmag, stp. The hard part is the area: turn "narrow corridor across eastern Oklahoma" into a box
  (a gazetteer of states and regions, or the nearest outlook polygon). Start with parameter + time only, add the area later.
- **Headline case**: the headline is capitalized by a word list (place names are guessed). Worth a gazetteer later.
- **Regions at risk list** ("Enhanced: eastern Oklahoma and western Arkansas") needs a rule for naming areas. Not started.
- **Browse past outlooks** by date (the archive and `ArchivedOutlook` exist). Not started.
- **Share** the outlook page as a picture. Not started.
- Outlook card on Today should open this page.
- The text arrives as plain text with no mark-up for bold or lists; long discussions are one long scroll. Consider collapsing by section.

## 3. Compare page

Built: reflectivity, side by side / overlay / difference / Members grid, on-screen scoring, 30 km neighborhood score, export (area, scores on or off, fixed table, small-print notes).
Open:
- **Long-press probe** (radar, shown member and difference at one point). Not started.
- **Hit / miss view** (both over 35 dBZ, model only, radar only). Not started.
- **Member strip** (a difference thumbnail per member). Partly covered by the Members grid with "Minus radar".
- **Rank row tap** to show that member's best hour. Not started.
- A visible note that matching radar at the start does not mean a better forecast later; the same member's score in the last two runs.
- **Newest run first** when its time axis has gaps (today it falls back one run).
- **Rain**: 1-h radar-only / Pass 1 against REFS 1-h QPF, then totals within the run.
- **Entry points** from the MRMS and REFS pages; landscape and tablet layout.
- "Minus radar" Members grid and the other layouts' exports were not tested after the latest changes.

## 4. Loops and the loop range bar

Built (`ac94d87`): one range bar on REFS, MRMS, NBM, GFS; Span menu; summary line; Play and the video use it.
Open:
- Tested on MRMS and REFS only; **NBM and GFS** not opened. The history loop on GFS ignores spans.
- Not saved between launches; the range is not highlighted on the time slider.
- The two handles sit near the screen edge, which fights Android's back gesture.
- Video size estimate with a custom range not checked.

## 5. Desktop program and the rain archive

- **Rain archive** (plan in `docs/mrms-rain-archive-desktop.md`): every finished UTC day kept in the quadtree form, no expiry. `RainDayStore` already works; retention and cap become optional.
- **Which desktop program** (the wX fork or a new Compose Desktop app) is undecided.
- **Backfill** from late 2020, resumable and rate-limited; looser tolerance (1 mm / 10%) to be measured on a real full day.
- **Two-pane MRMS vs REFS** (`docs/mrms-refs-compare-mobile.md`) is mobile-only, built as the Compare page.

## 6. Rainfall totals

- Hide the radar header and loop row while a total is shown.
- Favorites for the 3, 7, 14 and 30 day totals.
- A day older than 90 days is stored and expired in the same run (downloads every time).
- A 30-day total (time, size) and 90-day expiry with real old days are unchecked.
- Export of a total is built; the 3-day header line was shortened and not re-run after.

## 7. Globe and map

- The finer zoom picture on return to the map when most of CONUS is in view.
- "Map of this view" tested on MRMS only (REFS, NBM, GFS, Analysis share the code, not run).
- Globe drag: lines and temperature layer made faster on MRMS; the GFS temperature product and the polar map were not touched.

## 8. Places, legend, density, timings (small items)

- Places are one shared list (`PlacesStore`). Verified on GFS; a place saved there showing on another page was not opened.
- The legend now numbers each color (checked on MRMS reflectivity and rainfall, not REFS).
- METAR density: Normal / Dense / Densest, saved on the MRMS page only; export use not checked.
- "Log timings" setting writes stage times to Logcat; the readout shows the underlay and overlay stages.

## 9. Releases, CI and housekeeping

- Cut **0.4.4** to carry the loop range bar, Compare Members and export, the outlook page. (Version bump + `release` workflow, as done for 0.4.1 to 0.4.3.)
- iOS: not touched since v0.3.2; the new code is Android-only (`defineLatLonGrid` and the compare/rain code are not on iOS).
- Sync the wX fork (`~/Claude/wx`, branch `refs-mrms`).
- NOMADS `spc_post` feeds: the owner is revisiting in about two weeks; do not build yet (`docs/nomads-spc-post.md`).
- Step arrows on the MRMS, NBM and GFS pages were only compiled, never run.
- The `core-tests` and `ios` workflows are only triggered by core changes; not run for the recent app-only pushes.

## 10. Order I would do it in

1. Push `63fd823` and `eb70cdf`; release 0.4.4.
2. Simple/Advanced switch plus the curated Simple product list (small, reuses screens).
3. Check hazard-by-hazard point reads from the outlook and GREFS; then the Today "Chance at your place" card (thunder, severe by type, the line, rising).
4. Outlook page: test Days 2-8 on the device; then the discussion-to-REFS links (parameter and time first, area second).
5. Today home assembly: outlook card, chance card, radar card, share heads-up. The member strip last.
6. Then the open Compare and loop polish.
