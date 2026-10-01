# Separating SPC from the viewer

Suggestions for turning simplemodelview into a generic model viewer in which SPC REFS / GREFS is one data source. Nothing here is
implemented yet; it is a plan to pick from.

## Where SPC is today

| Layer | SPC-specific parts |
|---|---|
| `core/` (C++) | Only the grid constants: `SPC_GRID_REFS` / `SPC_GRID_GREFS` and their Lambert parameters (`core/src/grids.h`, `lambert_grid.h`). Blosc decoding, dtype conversion, the rasteriser and the globe are generic. |
| `shared/` (Kotlin) | Store URL patterns and cycle probing (`Cycles.kt`, `StoreKind`), the Zarr layout assumptions (`Repository.kt`), the product catalogue and titles (`Catalogue.kt`, `CuratedList.kt`), palettes (`Palettes.kt`), member names and paintball colours, the `Grid` enum. Zarr metadata parsing (`ZarrMeta.kt`), views, sectors, the globe and read-outs are generic. |
| `androidApp/` | The User-Agent text, the disclaimer / credit strings, the app title, the widget text, default product (`cref_max`), the sector list (US meso areas), the bundled US / Canada / Mexico line data. |
| Docs / CI | Names and prose in `README.md` and `docs/outline.md`. |

## Suggested steps, in order

### 1. A `Provider` interface (the main step)

Put everything SPC-specific behind one interface; the repository, view model and UI talk only to it.

```kotlin
interface Provider {
    val id: String                       // "spc"
    val title: String                    // "SPC REFS"
    val branding: Branding               // credit, disclaimer, About text, User-Agent suffix, contact policy
    val grids: List<GridSpec>            // columns, rows, projection, cell size, bounds
    val sectors: List<Sector>            // the areas offered in the menu

    suspend fun findRuns(now: Long, wanted: Int): List<Run>       // newest first
    suspend fun open(run: Run): RunData                            // products + times + members
    fun defaultProduct(products: List<Product>): Product
}
```

- `SpcProvider` holds today's `Cycle`, `StoreKind`, URL patterns, `Catalogue`, `Palettes` and the `.zgroup` probing.
- A `Run` replaces `Cycle` in the generic code (an id, a label, an init time).
- `Product` keeps its fields, but its `id` becomes provider-scoped (`spc:cref_max`) so favourites and saved places cannot collide between providers.
- Preferences are stored per provider (last product, sector, favourites).

### 2. Keep the core generic

- Blosc, Zarr chunk decoding, the rasteriser, the globe and the C API stay as they are.
- Grids become data a provider declares (columns, rows, cell size, Lambert parameters, lat/lon window) and are registered with the core at start-up (`spc_register_grid(spec)` returning an id), instead of the two compiled-in constants. The existing tests that check the REFS and GREFS grids against real store coordinates move to `SpcProvider`'s tests and keep their reference points.
- The Lambert projection with one standard parallel covers both SPC grids; other projections would be a new grid kind in the core.

### 3. Packages first, modules later

- Move SPC code into `spcrefs.spc` (or `simplemodelview.spc`) and leave the generic parts in the parent package. This is mechanical and keeps the Android build unchanged.
- Only once a second provider exists, split Gradle modules: `shared-core` (generic) and `provider-spc`. A module split before then adds build complexity for no gain.

### 4. Strings and branding come from the provider

- The About text, the disclaimer, the data credit, the share-image header and the widget text read from `provider.branding`.
- The app title and launcher label become provider-neutral ("simplemodelview"); the provider name shows in the header.
- The contact-email dialog text names the provider's server instead of "SPC's server".
- Keep the "not affiliated with or endorsed by ..." wording in the provider's branding so a new provider cannot ship without one.

### 5. Names, IDs and CI (done)

- **Done:** the application ID is `io.github.intelati26.simplemodelview` (a namespace the owner controls through GitHub, so no domain is claimed), the app package is `io.github.intelati26.simplemodelview`, the shared Kotlin package is `simplemodelview`, the Gradle project, JNI library (`libsimplemodelview_jni.so`), launcher label, preference file and theme are all `simplemodelview`. Doing it before any wider distribution matters because the application ID is the identity Android uses for updates.
- Still SPC-named on purpose: the core library and its C API (`spcrefs_core`, `spc_*`, `spcrefs_core.h`) and the SPC data code, which move behind the provider in steps 1-4.
- Workflows, artifacts and the APK use `simplemodelview`.
- Repository, README and outline: describe the project as a model viewer with SPC as the first provider.

## What does not need to change

- The line geometry and sector boxes are US-centred. They are data assets, so another provider can bring its own (the sector list is already a list).
- Time handling, cycle probing, HTTP caching, the loop / export code and the globe do not depend on SPC.
- The "no easy MRMS" finding stands: MRMS is GRIB2 (`.grib2.gz`), which would need a new decoder in the core, so it is a different kind of provider rather than another Zarr one. Other Zarr-based model stores are the realistic second providers.

## Risks and costs

- The `Provider` interface should be shaped by two real providers. Designing it from SPC alone risks baking in SPC's assumptions (cycles every 6 hours, one valid-time array per product, 10 members). Keep it small and change it when the second provider arrives.
- Registering grids at runtime adds a small amount of state to the C API; the existing core tests cover the numeric behaviour.
- Rough size: step 1 and the package move are about a day of mechanical refactoring with the existing tests as a safety net; steps 2 and 4 are smaller; the application ID rename is an hour but touches many files.

## Suggested order

1. Move SPC code into its own package (no behaviour change).
2. Introduce `Provider` / `Run` and make `SpcProvider` the only implementation.
3. Move strings and branding behind the provider.
4. Register grids with the core at start-up.
5. ~~Rename the application ID and package.~~ Done.
6. Add a second provider only when there is a real one to add.
