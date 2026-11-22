# Mobile compare view: MRMS against the REFS members at the current time

Status: proposal, Android app only (not the desktop program). Nothing is built.

First version is **reflectivity only**: the least ambiguous pairing (no Pass 2 lag, no accumulation windows,
no unit differences). Rain comes after.

## Purpose: which member has the initial conditions best

The question the screen answers: **right now, which REFS member's analysis (and first hours) best matches what
the radar shows?** Side by side, overlay and difference are the way to look at it; a per-member score and ranking
is the answer.

- Compare each member at its earliest frames (f00, f01, f02, f03 of the newest run) against MRMS at the same
  valid times. Initial conditions show up where the model has not yet drifted, so the lead range is short by design.
- **Ranked list** of members, best first, with the score and a small difference thumbnail. Tap a member to make it
  the displayed REFS pane. "Best member" is the default pick.
- **Scores** (on the shared grid, MRMS max-pooled to 3 km, evaluated inside the current view or a chosen area, with
  CONUS as the default):
  - Critical success index and bias at 20, 35 and 45 dBZ (hits, misses, false alarms).
  - Neighborhood score (fractions skill score, about 15 and 45 km radius) so a storm placed 10 km off is not scored
    as a complete miss.
  - Mean absolute dBZ error where either field is at least 10 dBZ.
  The ranking uses one headline (default FSS at 35 dBZ, 45 km) and shows the others as a detail sheet.
- **Trend:** the score at f00 to f03, and the same member's score in the last 3 cycles, so one lucky frame does not
  crown a member. Cycles are fetched only for the same valid times already in use (older runs' f00 to f03 are
  small, cached chunks).
- **Caveat shown in the UI:** matching the radar at the start does not guarantee a better forecast later; the
  ranking is about initial conditions only, and the lead-time trend makes that visible.
- Fairness guards: skip the ranking (say why) when too little of the area has echoes (under about 0.5 % of cells
  at 20 dBZ), and score only cells inside MRMS coverage.

## Modes

One screen, one camera, three ways to see the same valid time:

1. **Side by side** (stacked in portrait, side by side in landscape/tablet): MRMS left or top, REFS right or bottom.
2. **Overlay**: MRMS reflectivity drawn over the REFS field in one pane, with an opacity slider and a
   swap (which one is on top). Fast to flick between with a tap on the legend chip.
3. **Difference**: REFS minus MRMS in dBZ on the shared grid, diverging colormap centered on 0, with
   a "no data in either" transparent. Also a hit/miss view (both above 30 dBZ, model only, radar only).

## The REFS side: members of the most current run

- Run: the newest REFS cycle that actually has the valid-time frame (check the chunk exists, else fall back
  one cycle). Label `run 18Z f03 · valid 21:00Z · 6 min from MRMS`.
- Field: the existing Member Viewer reflectivity (a 4-D `member, time, y, x` product, one member at a time
  through `readFrame(store, product, timeIndex, memberIndex)`), plus the ensemble summaries already in the app
  (the "all members" paintball bit mask, and the mean or max if the cycle has them).
- Member picker as today; a "step member" pair of arrows so the difference can be paged member by member.
- Member ranking (the purpose, see above) needs every member's chunk for the f00 to f03 times: about 10 members
  x 4 times x 0.4 to 1 MB. Fetch in the background with a progress bar, cache in the existing frame cache, and show
  the list as scores arrive. Thumbnails of each member's difference are the small multiples.
- Valid time is chosen from the MRMS frame (newest, or the one being stepped to); forecast hour is the nearest
  one the run has. REFS valid times are hourly or sub-hourly, MRMS every 2 minutes, so a mismatch of up to
  about 30 minutes is normal and shown, not hidden.

## The MRMS side

Composite reflectivity (the existing MRMS reflectivity product), most recent frame, same colormap and dBZ scale as
REFS reflectivity so overlay and difference are honest. MRMS "no coverage" stays transparent, never 0 dBZ.

## One grid, one camera

- First pane's Lambert grid is the shared grid; the other source reprojects with `FrameTarget.Onto(grid)`.
  The difference is computed on that grid in the native core (new small call: subtract two float grids with
  NaN-if-either-missing), not in Kotlin per pixel.
- One camera owner (the compare screen). Panes only render; gestures go to the owner. No pane-to-pane
  syncing, so no jitter.
- Resolution mismatch: MRMS about 1 km against REFS 3 km. Pool MRMS to the REFS grid for the difference and the
  hit/miss view (max pooling so small storms are not erased); the plain MRMS pane and the overlay can show full
  detail.

## Time and "now"

- "Now" = newest MRMS frame. Step arrows move the shared valid time (MRMS 2 minutes, snapped to REFS hours
  when stepping the model). Lock toggle unlinks the two sides to compare a model hour against a different radar time.
- Poll for a new MRMS frame every 2 minutes only while the screen is visible; new REFS cycle through the existing
  new-run check.

## Phone ergonomics

- Probe: long-press reads the value in every shown source (`radar 47 dBZ · member 3 38 dBZ · diff −9`).
  Pan stays on drag; the probe appears after the long-press delay and does not move the map.
- Controls in one sheet: mode, opacity, member, valid time. Legend shows the active mode's scale.
- Landscape and tablets get side by side with the sheet as a side column.

## Costs and failure handling

- Roughly two frame loads per step (one MRMS, one REFS member) in the first version; all-members difference
  multiplies the REFS side, so it is off by default and bounded.
- Existing HTTP cache and the loop-frame cache apply; compare mode adds none of its own at first.
- If one side fails or is stale, that pane says why (`REFS 21Z not out yet`, `MRMS stale 14 min`) and
  the other still draws; difference and overlay disable themselves instead of showing a wrong picture.

## Implementation steps

0. Scoring first (it is the purpose): pure Kotlin or core code that, given two float grids, returns CSI, bias and
   FSS at thresholds, with unit tests on hand-made grids (perfect, shifted by n cells, empty, all-missing).
1. Core: float-grid difference with missing handling, plus the hit/miss classification (tests with small grids).
2. `CompareViewModel` owning one MRMS loader and one REFS loader, the valid-time matching rule, and the shared
   grid/camera. Pure Kotlin matching logic in `shared` with unit tests (cycle fallback, nearest forecast hour,
   offset label).
3. Compose screen: stacked and side-by-side panes, then the overlay (opacity, swap), then the difference.
4. Member picker and step-member; probe; legend per mode.
5. Run on the emulator against live data: a quiet day (empty fields), a convective day (the useful case), and
   a missing-cycle case.
6. After reflectivity works, add 1 h rain (MRMS radar-only or Pass 1 against REFS 1 h QPF), then totals.

## Open questions

- Which REFS reflectivity field is the member product: simulated composite reflectivity per member? (The
  catalog's Member Viewer bucket has the names; confirm before building.)
- Is the all-members summary a mean, a probability of 40 dBZ, or the paintball mask? The mask gives no single
  number to difference, so the difference mode would use one member or the ensemble mean only.
- Entry point: a new page in the page list, or a button on the MRMS and REFS pages that opens compare at the
  current sector and time?
