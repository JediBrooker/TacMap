# Presentation formatting policy

## First migration batch

`DisplayFormat` on each platform is the shared entry point for presentation numbers,
metric distances/areas and short chat times. Measurement HUD values, weather numbers,
waypoint/header elevations and chat timestamps now use it.

- Explicit app language: combine that language with the device locale's region.
  Native locale data decides the resulting separators and short-time conventions.
  If the region has no dedicated data, native locale fallback applies.
- Device language: use the current device/app locale directly for formatting.
  Text-resource fallback remains independent (unsupported app text falls back to English).
- Use the current device time zone. Format dates without changing the underlying instant.
- Preserve the existing units, precision and distance/area thresholds. Do not select
  imperial units merely because the region changes.
- Disable digit grouping for compact measurement displays. Keep requested trailing
  decimal places and native decimal separators. Use half-even rounding consistently.
- Non-finite numeric values display an unavailable dash.
- Resolve locale and formatters when values are displayed. Do not retain a formatter
  configured before the user changed language. Formatters are not shared mutable objects.

The explicit locale/time-zone arguments are useful for deterministic tests. Callers
normally omit them to follow the app preference and device region/time zone.

## Editor and scalar-input migration

Symbol width/height displays and drawing scale controls now use `DisplayFormat`,
including iOS slider accessibility values, presets and stroke-width labels.

Elevation creation/editing uses `DecimalInput` on both platforms where those
fields exist. A dot always means a decimal point (preserving existing draft
strings). The selected presentation locale's decimal separator is also accepted:
`123,5` is valid for German/Germany, while Swiss German uses a dot. Leading/trailing
whitespace, signs and scientific notation are supported. Group separators are
never removed; mixed separators, embedded spaces, unit suffixes, partial parses,
NaN and infinity are rejected. Empty elevation remains optional at the caller.

The field explicitly explains that thousands separators are not supported.
`1,234` in a comma-decimal locale means 1.234, never 1234; in a dot-decimal locale
it is rejected. `1.234` always means 1.234. This grammar is not used for coordinates.
Canonical stored double strings round-trip without display rounding.

## Data boundaries

Do not use presentation formatters for coordinates, MGRS/UTM, sync signatures,
cache keys, filenames, URLs, persistence or GeoJSON/GPX/MBTiles interchange. Their
existing canonical representations and typed values are outside this migration.
User-entered names and message bodies remain verbatim. The scalar elevation parser does not change coordinate input parsing or stored numeric types.

## Remaining phase 3 work

- Migrate remaining angle, duration, percentage and date displays after
  identifying which values are standards-defined rather than ordinary display numbers.
- Audit any remaining numeric input fields; the elevation policy above is now implemented.
- Test explicit system clock/calendar preferences and live device-region changes.
- Prototype Android system per-app language integration only with evidence that
  drafts, map position, imports, tracking and sync survive Activity recreation.
  The existing observable in-app resolver remains in use for now.
- Replace stored translated error strings with late-resolved message IDs/arguments.
- Expand the mixed-locale, lifecycle and data-integrity device matrix.

Passing formatter tests does not complete the full screen/layout or lifecycle review.
