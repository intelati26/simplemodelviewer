# Why a 60-frame loop video is several MB, and what shrinks it

Measured 2026-10-02 on the whole-US composite reflectivity (ensemble maximum), 24 clean frames rendered by the core, and on the emulator.

## What the pictures are made of
- Only **28%** of a frame is data (the rest is the map background and lines).
- **31%** of all pixels, and **92% of the data area**, change between consecutive frames. Video codecs already store the static map for
  almost nothing, so making it transparent or "removing" it would not help (H.264 has no alpha anyway); the cost is the weather data.

## Formats tried (same 24 frames, MB per 60 frames)
| Format | 1280 px |
|---|---|
| H.264 Baseline, 4 Mbps, keyframe every 4 frames (the old app setting) | 7.9 |
| H.264 High, CRF 30, keyframe every 64 | 7.9 |
| H.265, CRF 30 | 8.4 |
| AV1 (SVT), CRF 40 | 6.4 |
| Animated WebP, lossy q40 / q60 / q80 | 7.6 / 9.4 / 12.1 |
| Animated WebP, lossless (palette) | 10.2 |

Animated WebP is **not** smaller than H.264 for this content, even lossless, so there is no format switch that fixes it. (AV1 is ~20%
smaller but has no hardware encoder on most phones and is slow in software.)

## What does shrink it (H.264 High, CRF 30)
| Width | MB per 60 frames |
|---|---|
| 1280 | 7.9 |
| 960 | 4.4 |
| 800 | 3.0 |
| 640 | 1.9 |

Resolution is the lever: the data is 3 km pixels, so 960 or 800 px wide loses little. Also changed: one keyframe every 5 s instead of every 1 s
(16 of 64 frames were keyframes), variable bitrate, High profile when the device offers it.

## In the app
Share > Loop video size: **Small** (800 px, about 2.8 MB for 57 steps), **Medium** (960 px, about 4.5 MB, default), **Large** (1280 px, about
7 MB), with an estimate on each chip. Verified on the emulator: Small 2.83 MB, Medium 4.55 MB (was 7.57 MB), 4 keyframes in 64 instead of 16.
Hardware encoders on phones usually offer High profile, so files should be smaller still.

## Not done / ideas
- Fewer frames (every 2nd step) does not help per frame of output, but halves the file for the same time span; could be an option.
- Lowering the frame rate below 4 fps for slow-moving products.
