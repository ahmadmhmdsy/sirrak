# Store graphics (Play / F-Droid)

Launch-ready bitmap assets for the store listings, generated from data instead of being drawn by hand.
The Arabic text is rendered by a real shaping engine, not by a per-letter font API, and every colour,
font, copy line and coordinate comes from [`brand.json`](brand.json).

| Asset | Size | Purpose | Play requirement |
| --- | --- | --- | --- |
| [`icon-512.png`](icon-512.png) | 512 × 512 | App icon for the listing | 512 × 512 PNG, no alpha needed |
| [`feature-graphic-1024x500.png`](feature-graphic-1024x500.png) | 1024 × 500 | Feature graphic / hero image | 1024 × 500 PNG or JPEG |

`manifest.json` records the renderer version, the brand source hash and the size/bytes/SHA-256 of each
output. It is committed together with the PNGs so the files are auditable and reproducible.

## How it is generated

```powershell
# any machine with Chrome or Edge; no Android device or SDK needed
./scripts/store-graphics.ps1
```

The script:

1. reads `brand.json` (palette roles, font files, copy keys, icon mark paths, layout);
2. embeds the shipped Amiri / IBM Plex Sans Arabic TTFs from `core/design` as data URIs — the fonts
   are **never installed on the host**, so the output does not depend on a developer's font folder;
3. renders an HTML page with the shipped adaptive-icon vector paths inline as SVG;
4. screenshots it with headless Chrome (`--headless=new --screenshot --window-size=…`), which shapes
   Arabic with HarfBuzz/Bidi exactly like a browser, and writes the PNG;
5. rejects any output whose pixel size is not the advertised one and writes `manifest.json`.

Intermediate HTML and the throwaway browser profile live in `store/graphics/.render/` (git-ignored).

## Where the data comes from

- **Palette** — the shipped Girih & Ink light theme pack in
  `core/design/src/main/java/app/sirrak/core/design/ThemePacks.kt`.
- **Mark** — the shipped adaptive-icon vector `app/src/main/res/drawable/ic_launcher_foreground.xml`
  (same two paths, imported here so the store icon cannot drift from the launcher icon).
- **Copy** — the shipped `app_name` and the chat empty-state strings `empty_promise` /
  `empty_privacy`, plus `store/listing/*/name.txt` for the English/Latin wordmark.

No string, colour or coordinate is duplicated in the renderer itself; change the app token or the
shipped string, then re-run the script. If you forget, `StoreGraphicsBrandTest` fails.

## Guardrails and honest limits

- `StoreGraphicsBrandTest` (JVM) fails when the palette no longer matches `ThemePacks.default.light`,
  when the mark paths differ from the launcher vector, when the copy drifts from the shipped strings,
  or when a PNG/manifest hash does not match the files on disk.
- The icon is the launcher identity, not a redrawn logo: a square ivory canvas with the lapis girih
  mark at the same proportion as the adaptive icon. Play applies its own mask/rounding, so no baked-in
  rounded corners are used.
- The feature graphic keeps its content inside the central safe area (roughly y 50–450) because Play
  may crop the graphic on some surfaces; the outer margins are decorative.
- These are static assets: they are not pixel-compared by the on-device screenshot gate, and the
  Arabic shaping is verified by rendering with HarfBuzz, not by an eyeball-only claim.

## Regenerating after a brand change

1. Update `core/design/.../ThemePacks.kt`, the launcher vector, or the shipped strings.
2. Run `./scripts/store-graphics.ps1` (re-run `StoreGraphicsBrandTest` mentally: it will fail first if
   `brand.json` itself needs new copy/layout data — that is the intended forcing function).
3. Commit `brand.json`, both PNGs and `manifest.json` together.
