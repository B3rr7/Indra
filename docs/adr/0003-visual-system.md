# ADR 0003 — the visual system: one accent per tool, one type scale, one shape ladder

Status: accepted, 2026-11-14.

## The problem

There were three separate problems that looked like one "the UI is plain" complaint.

1. **Every plugin screen looked identical.** All 36 rendered `PluginScaffold`, whose top bar was
   the theme's primary colour — cyan — and nothing else. The per-tool palette existed, but only on
   the Home and Tools cards (`getToolColor` in `HomeScreen.kt`), so the colour that identified a
   tool disappeared the moment you opened it.
2. **The accent palette was one hex per tool.** A single colour can tint an icon or a border, and
   that is all it was used for. It cannot fill a tile (it competes with the text) or a washed
   surface (it is invisible at the alpha needed for legibility).
3. **Type and shape were stock Material defaults.** Material's scale is built for marketing pages:
   24sp Normal headings that wrap inside a 360dp card, 0.5sp tracking on body text that reads as
   wide, and radii of 10/12/14/16/20/24 dp scattered across the tree.

## The decisions

**A tool's colour is a token, not a literal.** `ui/theme/Accent.kt` holds `Accent(base, deep)` and
one stable hue per plugin id. `deep` is derived, not hand-picked, so adding a tool is one line.
`onAccent` is computed from luminance rather than assumed, because the palette spans lime and
indigo. `soft`, `fill()`, `tile` and `edge()` are what a card, a chip, a pill and a console header
need so no screen invents an alpha.

**The frame publishes the accent.** `PluginScaffold(pluginId = "…")` provides it two ways:

- `LocalAccent`, a composition local, so a console, chip or status pill inside the screen picks up
  the tool's colour without a parameter threaded through 36 call trees (and without widening
  `LiveOutputCard`, which was already at detekt's parameter limit);
- an **overridden Material scheme** whose `primary`/`onPrimary` are the accent, so buttons,
  switches, sliders, progress bars and focused field outlines are the tool's colour with **zero**
  call-site changes.

The accent rule under the top bar is deliberate: a screen whose content is not tinted at all still
reads as this tool's screen.

**One type scale, one radius ladder.** `Typography.kt` is the hierarchy decision (tight semibold
headings, tracked semibold labels, near-zero body tracking, one step smaller than stock), and
`Theme.kt` exposes `IndraShapes` (10/14/18/22/26 dp) so a card is 18dp everywhere.

**The hero is the one gradient.** Cards stay flat (`Flat.kt`, elevation 0, hairline edge). The Home
hero is the identity surface, and a two-stop ramp between two container tones gives it depth
without the directional light source a card gradient would imply.

## Consequences

- A new plugin gets its colour by adding one `when` branch and passing `pluginId` to the frame.
  `AccentTest`-style coverage is currently by inspection; the palette is data, and a missing entry
  falls back to `Brand` rather than crashing.
- `getToolColor(id)` is now `accentFor(id).base` — kept as an accessor because Home and Tools call
  it, but the table lives in one place.
- Contrast is per-hue: `onAccent` picks black for light accents (lime, amber, mint) and white for
  the rest. A future palette entry must keep its `base` bright enough for a tile gradient; the
  `deep` derivation assumes it.
- Dark-only is unchanged and remains deliberate (see `Theme.kt`); the light scheme exists for a
  future appearance preference and is not exercised.

## Addendum: selectable palettes

The brand cyan that opened this ADR became its own complaint — with `primary` pinned to cyan, every
label that used it made the app read as one cyan surface. Two layers now coexist:

- **Palette** (`ui/theme/Palette.kt`): eight four-swatch sets (`Ember`, `Neon Nights`,
  `Forest Terminal`, `Ice Station`, `Solar Gold`, `Crimson Ops`, `Mint Circuit`, `Rose Quartz`), each
  deriving the sixteen Material roles it needs. `readableOn` measures WCAG contrast instead of
  assuming black-or-white text, because a mid-tone accent (gold, coral) sits on the wrong side of any
  single luminance threshold — `PaletteTest` requires ≥ 4.5:1 for every palette's filled surfaces.
- **Tool accent** (`Accent.kt`): unchanged. `PluginScaffold` still overrides `primary` with the
  tool's hue *on top of* the palette, which is why a plugin screen is still recognisably that tool.

Selecting is a preference (`SettingsRepository.setThemePalette`), published as a `StateFlow` so the
theme changes while the picker is on screen, and `IndraTheme` cross-fades the sixteen roles over
420 ms rather than swapping them in one frame. Home is the surface the picker previews: its page
glow, hero gradient and Home tab all come from the palette, while status colours stay semantic.

Motion added with the same pass: staggered entrance for Home's quick actions and tool grid,
press-scale on cards and pills, an animated discovery count with a breathing live dot, and a
cross-fade between destinations (180 ms in / 120 ms out) instead of a hard cut.
