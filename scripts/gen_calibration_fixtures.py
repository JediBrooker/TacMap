#!/usr/bin/env python3
"""Shared WP4 / WP5 fixtures: calibration entry, the fit report, import limits and lifecycle tables.

Writes (default: the repo's testdata/):
  calibration_input.json       CoordinateInputParser cases (contract s4 + s5 resolution)
  calibration_fit_report.json  CalibrationFitReport cases, entry checks, camera anchor, capture (s5-s7)
  import_limits.json           every s3 constant, import errors -> message keys, ImportDecision
                               vectors, library entry states and the calibration lifecycle tables

The binding spec is plans/WP4-calibration-lifecycle-shared_contract.md. Numbers come from PROJ
(pyproj) + numpy through scripts/gen_test_geopdfs.py's Ref (same datum table, same TM), MGRS
letters from the plain AA-scheme math plus the NGA mgrs-java/mgrs-ios row -> northing rule (add
2,000 km blocks until it clears the band's bottom 100 km square). Nothing here reads app code.

  python3 scripts/gen_calibration_fixtures.py             # rewrites the three files in testdata/
  python3 scripts/gen_calibration_fixtures.py --out DIR   # somewhere else

Needs the same venv as gen_test_geopdfs.py (pyproj + numpy). No mgrs package: it isn't needed,
the letter math is 30 lines and the NGA northing rule is spelled out below.

Deterministic on purpose: no clocks, noise comes from a fixed LCG, every input is rounded to
what the json says BEFORE anything is computed from it, so a platform reading the json gets
the same numbers.
"""

import argparse
import base64
import math
import os
import re
import sqlite3
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import gen_test_geopdfs as G  # noqa: E402  (shared datum table, Ref, sheets, json writer)

CONTRACT = os.path.join(REPO, "plans", "WP4-calibration-lifecycle-shared_contract.md")

# ----------------------------------------------------------------------------
# contract s3 constants. import_limits.json is written straight from these
# ----------------------------------------------------------------------------

MiB = 1024 * 1024
GiB = 1024 * MiB
PT_M = 0.0254 / 72.0                # metres of paper per pdf point, the 0.00035277778 in s3

CALIBRATION = {
    "maxCalibrationPoints": 50,
    "undoDepth": 50,
    "maxDrafts": 16,
    "gpsMaxAccuracyM": 20.0,
    "degenerateEigenRatio": 0.02,
    "spreadMinFraction": 0.25,
    "toleranceFloorM": 10.0,
    "toleranceDiagonalFraction": 0.0005,
    "gradeFairToleranceMultiple": 3.0,
    "implausibleAnisotropy": 1.5,
    "plausibleScaleMin": 1000.0,
    "plausibleScaleMax": 5000000.0,
    "offEarthLatDeg": 85.0,
    "outlierMinPoints": 5,
    "entryWarnSigma": 3.0,
    "entryWarnFloorM": 50.0,
    "nextCornerInsetFraction": 0.1,
    "residualLineMinScreenPt": 6.0,
    "moveToCrosshairMinScreenPt": 4.0,
    "markerHitRadiusPt": 24.0,
    "zoomHintScreenPtPerPagePt": 1.0,
    "cameraZoomMin": 2.0,
    "cameraZoomMax": 22.0,
    "cameraJacobianStepPt": 1.0,
    "transitionPrefetchDeadlineMs": 400,
    "metresPerPagePointAtUnitScale": PT_M,
    "provisionalScaleDenominator": 50000.0,
    "maxInputUtf16Units": 64,
}

IMPORT = {
    "pdfMaxBytes": 512 * MiB,
    "mbtilesMaxBytes": 4 * GiB,
    "maxPages": 500,
    "georefScanPages": 50,
    "pageSideMinPt": 36.0,
    "pageSideMaxPt": 14400.0,
    "parseTimeoutMs": 30000,
    "freeSpaceMarginBytes": 64 * MiB,
    "maxLibraryEntries": 100,
    "progressHudDelayMs": 300,
    "thumbnailWidthPt": 120.0,
    "thumbnailCacheEntries": 24,
    "cancelCopyGranularityBytes": 1 * MiB,
}

C = CALIBRATION

# ellipsoids whose sheets carry the old AL MGRS lettering (contract s4.3), MGRS refused on them
AL_ELLIPSOIDS = ("Clarke1866", "Clarke1880IGN", "Bessel1841")

BANDS = G.BANDS                     # C..X without I and O
ROWS = G.LETTERS_ROW                # A..V without I and O
NO_GZD = {(32, "X"), (34, "X"), (36, "X")}   # Svalbard: these grid zones don't exist

CORNERS = ("topLeft", "topRight", "bottomRight", "bottomLeft")
CORNER_KEYS = {"topLeft": "calibration_next_corner_top_left", "topRight": "calibration_next_corner_top_right",
               "bottomRight": "calibration_next_corner_bottom_right",
               "bottomLeft": "calibration_next_corner_bottom_left"}
PARSE_ERROR_KEYS = {
    "empty": None,
    "unrecognised": "calibration_err_unrecognised",
    "tooCoarse": "calibration_err_too_coarse",
    "unequalDigits": "calibration_err_odd_digits",
    "needsFullReference": "calibration_err_needs_full",
    "invalidSquare": "calibration_err_square",
    "bandMismatch": "calibration_err_band",
    "polarUnsupported": "calibration_err_polar",
    "outOfRange": "calibration_err_range",
    "oldLettering": "calibration_err_old_lettering",
}

# every message key a fixture names, checked against the contract's copy table at the end
USED_KEYS = set()


def key(k):
    USED_KEYS.add(k)
    return k


def msg(k, **args):
    return {"key": key(k), "args": args}


# ----------------------------------------------------------------------------
# rounding (same as pdf_georef.json) + a boring deterministic noise source
# ----------------------------------------------------------------------------

rdeg, rm, rpt, rsig = G.rdeg, G.rm, G.rpt, G.rsig


def rzoom(v):
    return round(float(v), 9)


class Lcg:
    """glibc style LCG, plenty for 'some noise' and it never changes under us"""

    def __init__(self, seed):
        self.x = seed & 0x7FFFFFFF

    def u(self):
        self.x = (1103515245 * self.x + 12345) & 0x7FFFFFFF
        return self.x / 2147483648.0 - 0.5          # [-0.5, 0.5)


def wrap180(lon):
    return ((lon + 180.0) % 360.0) - 180.0


# ----------------------------------------------------------------------------
# geo on top of G.Ref (PROJ). TM forward refuses > 90 deg from the CM like both apps do
# ----------------------------------------------------------------------------

class Geo:
    def __init__(self):
        self.ref = G.Ref()
        self.np = self.ref.np
        self._geod = self.ref.pyproj.Geod(ellps="WGS84")

    def fwd(self, datum, zone, south, lat, lon):
        if abs(wrap180(lon - (6 * zone - 183))) > 90.0:
            return None
        return self.ref.fwd(G.UTM(zone, south), datum, lat, lon)

    def inv(self, datum, zone, south, E, N):
        return self.ref.inv(G.UTM(zone, south), datum, E, N)

    def to_wgs84(self, datum, lat, lon):
        return self.ref.to_wgs84(datum, lat, lon)

    def from_wgs84(self, datum, lat, lon):
        return self.ref.from_wgs84(datum, lat, lon)

    def dist(self, lat1, lon1, lat2, lon2):
        """WGS84 geodesic metres (Karney). iOS CLLocation / Android Location.distanceBetween agree to mm"""
        return abs(float(self._geod.inv(lon1, lat1, lon2, lat2)[2]))

    # georefs in pdf_georef.json shape: {crs, datum, affine, crop}
    def g_to_wgs84(self, g, x, y):
        (lat, lon), _ = self.ref.to_wgs84_page(g, x, y)
        return lat, lon

    def g_to_page(self, g, lat, lon):
        return self.ref.to_page(g, lat, lon)


GEO = None


# ----------------------------------------------------------------------------
# MGRS letters + the NGA northing rule
# ----------------------------------------------------------------------------

def col_letters(zone):
    return G.LETTERS_COL[(zone - 1) % 3]


def row_letters(zone):
    return ROWS[5:] + ROWS[:5] if zone % 2 == 0 else ROWS


def band_index(lat):
    """None outside the MGRS bands (-80..84)"""
    if lat < -80.0 or lat > 84.0:
        return None
    return min(19, int(math.floor((lat + 80.0) / 8.0)))


def band_of(lat):
    i = band_index(lat)
    return None if i is None else BANDS[i]


def mgrs_zone(lat, lon):
    """MGRS grid zone of a point, Norway + Svalbard exceptions included (it's what's printed on the sheet)"""
    lon = wrap180(lon)
    z = int(math.floor((lon + 180.0) / 6.0)) + 1
    if 56.0 <= lat < 64.0 and 3.0 <= lon < 12.0:
        z = 32
    if 72.0 <= lat <= 84.0 and lon >= 0.0:
        if lon < 9.0:
            z = 31
        elif lon < 21.0:
            z = 33
        elif lon < 33.0:
            z = 35
        elif lon < 42.0:
            z = 37
    return min(z, 60)


NBAND = {}


def band_bottom_northing(band):
    """NGA MGRS.utmNorthing(): floor to 100 km of the UTM northing of (band south edge, lon 0) on WGS84"""
    if band not in NBAND:
        lat = -80.0 + 8.0 * BANDS.index(band)
        E, N = GEO.ref.fwd(G.UTM(31, lat < 0), "WGS84", lat, 0.0)
        frac = N % 100000.0
        assert lat == 0.0 or 1.0 < frac < 99999.0, "band %s bottom sits on a 100 km line, floor is fragile" % band
        NBAND[band] = math.floor(N / 100000.0) * 100000.0
    return NBAND[band]


def mgrs_en(zone, band, col, row, e_in, n_in):
    """MGRS.parse().toUTM(): never through lat/lon"""
    E = (col_letters(zone).index(col) + 1) * 100000.0 + e_in
    n100 = row_letters(zone).index(row) * 100000.0
    nb = band_bottom_northing(band)
    n2m = 0.0
    while n2m + n100 + n_in < nb:
        n2m += 2000000.0
    return E, n2m + n100 + n_in


def square_of(zone, E, N):
    ci = int(E // 100000.0) - 1
    if not 0 <= ci < 8:
        return None
    return col_letters(zone)[ci] + row_letters(zone)[int(N // 100000.0) % 20]


def mgrs_text(datum, lat, lon, digits=10, spaced=True, zone=None):
    """format a sheet-datum lat/lon as MGRS the way it's printed (truncated, not rounded)"""
    zone = zone or mgrs_zone(lat, lon)
    south = lat < 0
    E, N = GEO.fwd(datum, zone, south, lat, lon)
    half = digits // 2
    unit = 10 ** (5 - half)
    e = int(math.floor(E % 100000.0 / unit))
    n = int(math.floor(N % 100000.0 / unit))
    sq = square_of(zone, E, N)
    if spaced:
        return "%d%s %s %0*d %0*d" % (zone, band_of(lat), sq, half, e, half, n)
    return "%d%s%s%0*d%0*d" % (zone, band_of(lat), sq, half, e, half, n)


# ----------------------------------------------------------------------------
# the reference parser (contract s4). The fixture is this function's output plus asserts
# ----------------------------------------------------------------------------

def normalise(raw):
    s = "".join(" " if ord(c) in G.WHITE_SPACE else c for c in raw)
    s = s.replace("−", "-")
    s = s.replace("′", "'").replace("’", "'")
    s = s.replace("″", '"').replace("”", '"').replace("''", '"')
    s = re.sub(" +", " ", s).strip(" ")
    return s


def utf16_len(s):
    return len(s.encode("utf-16-le")) // 2


FULL = re.compile(r"([0-9]{1,2}) ?([A-Z]) ?([A-Z]) ?([A-Z])(?: ?([0-9]+))?(?: ([0-9]+))?")
UPS = re.compile(r"([ABYZ]) ?([A-Z]) ?([A-Z])(?: ?([0-9]+))?(?: ([0-9]+))?")
SHORT_SQ = re.compile(r"([A-Z]) ?([A-Z])(?: ?([0-9]+))?(?: ([0-9]+))?")
SHORT_DIG = re.compile(r"([0-9]+)(?: ([0-9]+))?")
UTM_RE = re.compile(r"([0-9]{1,2}) ?([A-Z]) ([0-9]+)(?: ?ME)? ([0-9]+)(?: ?MN)?")
UTM_WORDS = re.compile(r"([0-9]{1,2}) (NORTH|SOUTH) ([0-9]+)(?: ?ME)? ([0-9]+)(?: ?MN)?")


class ParseError(Exception):
    def __init__(self, code, **args):
        super().__init__(code)
        self.code, self.args_ = code, args


def digit_rule(g1, g2, kind):
    """(e_in, n_in, total) in metres from the digit groups, or a ParseError"""
    if g1 is None:
        g1 = ""
    if g2 is not None and len(g1) != len(g2):
        raise ParseError("unequalDigits")
    digits = g1 + (g2 or "")
    total = len(digits)
    if total % 2:
        raise ParseError("unequalDigits")
    if total in (0, 2):
        raise ParseError("tooCoarse")
    if total > 10:
        raise ParseError("unrecognised")
    if kind == "feature" and total == 4:
        raise ParseError("tooCoarse")
    half = total // 2
    unit = 10 ** (5 - half)
    return int(digits[:half]) * unit, int(digits[half:]) * unit, total


def grid_ok(zone, band, col, row, e_in, n_in, total, ctx, completed=None, completed_point=None):
    datum = ctx["datumId"]
    E, N = mgrs_en(zone, band, col, row, e_in, n_in)
    south = band < "N"
    lat_sw = GEO.inv(datum, zone, south, E, N)[0]
    bi = band_index(lat_sw)
    if bi is None:
        raise ParseError("polarUnsupported")
    if abs(bi - BANDS.index(band)) > 1:
        raise ParseError("bandMismatch", band=band)
    cell = 10 ** (5 - total // 2)
    kind = ctx["kind"] if total < 10 else "intersection"
    off = cell / 2.0 if kind == "feature" else 0.0
    lat, lon = GEO.inv(datum, zone, south, E + off, N + off)
    half = total // 2
    canonical = "%d%s %s%s %05d %05d" % (zone, band, col, row, int(E % 100000), int(N % 100000))
    messages = [msg("calibration_reads_as", text=canonical)]
    if kind == "feature":
        messages.append(msg("calibration_cell_feature", sizeM=float(cell), halfM=cell / 2.0))
    else:
        messages.append(msg("calibration_cell_intersection", sizeM=float(cell)))
    if completed == "map":
        messages.append(msg("calibration_completed_from_map"))
    elif completed == "point":
        messages.append(msg("calibration_completed_from", number=completed_point))
    return dict(source="mgrs", zone=zone, south=south, band=band, square=col + row, easting=E, northing=N,
                digits=total, cellSizeM=float(cell), effectiveKind=kind, kindSegment=half in (2, 3, 4),
                lat=None, lon=None, completedFrom=completed, completedFromPoint=completed_point,
                canonical=canonical, resolved=(lat, lon), messages=messages)


def parse_full(m, ctx, al):
    zone, band, col, row, g1, g2 = int(m.group(1)), m.group(2), m.group(3), m.group(4), m.group(5), m.group(6)
    if not 1 <= zone <= 60 or band not in BANDS or (zone, band) in NO_GZD:
        raise ParseError("unrecognised")
    if al:
        raise ParseError("oldLettering", datumId=ctx["datumId"])
    if col not in col_letters(zone) or row not in ROWS:
        raise ParseError("invalidSquare", square=col + row, zone=zone)
    e_in, n_in, total = digit_rule(g1, g2, ctx["kind"])
    return grid_ok(zone, band, col, row, e_in, n_in, total, ctx)


def parse_short(col, row, g1, g2, ctx, al):
    datum = ctx["datumId"]
    if al:
        raise ParseError("oldLettering", datumId=datum)
    pred, anchor = ctx.get("predicted"), ctx.get("gridAnchor")
    if pred is None and anchor is None:
        raise ParseError("needsFullReference")
    if pred is not None:
        plat, plon = GEO.from_wgs84(datum, pred["lat"], pred["lon"])
        zone = mgrs_zone(plat, plon)
        south = plat < 0
        band = band_of(plat)
        if band is None:
            raise ParseError("polarUnsupported")
        if col is not None and (col not in col_letters(zone) or row not in ROWS):
            raise ParseError("invalidSquare", square=col + row, zone=zone)
        e_in, n_in, total = digit_rule(g1, g2, ctx["kind"])
        if col is not None:
            return grid_ok(zone, band, col, row, e_in, n_in, total, ctx, "map")
        # digits only: the predicted square and its 8 neighbours, nearest resolved point wins
        Ep, Np = GEO.fwd(datum, zone, south, plat, plon)
        cell = 10 ** (5 - total // 2)
        kind = ctx["kind"] if total < 10 else "intersection"
        off = cell / 2.0 if kind == "feature" else 0.0
        e0, n0 = math.floor(Ep / 1e5) * 1e5, math.floor(Np / 1e5) * 1e5
        best = None
        for dn in (-1, 0, 1):
            for de in (-1, 0, 1):
                E, N = e0 + de * 1e5 + e_in, n0 + dn * 1e5 + n_in
                if not (100000.0 <= E < 900000.0 and 0.0 <= N <= 10000000.0):
                    continue
                d = math.hypot(E + off - Ep, N + off - Np)
                if best is None or d < best[0]:
                    best = (d, E, N)
        _, E, N = best
        sq = square_of(zone, E, N)
        band_c = band_of(GEO.inv(datum, zone, south, E, N)[0])
        if band_c is None:
            raise ParseError("polarUnsupported")
        out = grid_ok(zone, band_c, sq[0], sq[1], e_in, n_in, total, ctx, "map")
        assert (out["easting"], out["northing"]) == (E, N), "canonical must re-read to the chosen candidate"
        return out
    zone, band = anchor["zone"], anchor["band"]
    if col is None:
        col, row = anchor["square"][0], anchor["square"][1]
    elif col not in col_letters(zone) or row not in ROWS:
        raise ParseError("invalidSquare", square=col + row, zone=zone)
    e_in, n_in, total = digit_rule(g1, g2, ctx["kind"])
    return grid_ok(zone, band, col, row, e_in, n_in, total, ctx, "point", anchor["fromPoint"])


def utm_ok(zone, south, E, N, ctx):
    datum = ctx["datumId"]
    lat, lon = GEO.inv(datum, zone, south, E, N)
    if lat < -80.0 or lat > 84.0:
        raise ParseError("polarUnsupported")
    band = band_of(lat)
    canonical = "UTM %d%s %d %d" % (zone, band, E, N)
    return dict(source="utm", zone=zone, south=south, band=band, square=None, easting=float(E), northing=float(N),
                digits=None, cellSizeM=None, effectiveKind=None, kindSegment=False, lat=None, lon=None,
                completedFrom=None, completedFromPoint=None, canonical=canonical, resolved=(lat, lon),
                messages=[msg("calibration_reads_as", text=canonical)])


def parse_utm(u, ctx):
    m = UTM_RE.fullmatch(u)
    words = None
    if not m:
        m = UTM_WORDS.fullmatch(u)
        if not m:
            return None
        words = m.group(2)
    zone, E, N = int(m.group(1)), int(m.group(3)), int(m.group(4))
    if not 1 <= zone <= 60 or not (100000 <= E <= 900000) or not (0 <= N <= 10000000):
        raise ParseError("unrecognised")
    if words:
        return utm_ok(zone, south=(words == "SOUTH"), E=E, N=N, ctx=ctx)
    letter = m.group(2)
    if letter not in BANDS:
        raise ParseError("unrecognised")
    south_band = letter < "N"
    lat = GEO.inv(ctx["datumId"], zone, south_band, E, N)[0]
    lo = -80.0 + 8.0 * BANDS.index(letter)
    hi = lo + (12.0 if letter == "X" else 8.0)
    if lo - 0.5 <= lat <= hi + 0.5:
        return utm_ok(zone, south_band, E, N, ctx)
    if letter in ("N", "S"):
        # our own readout writes the hemisphere there, '56S 334000mE 6250000mN' is southern
        return utm_ok(zone, letter == "S", E, N, ctx)
    raise ParseError("bandMismatch", band=letter)


def latlon_ok(lat, lon):
    if abs(lat) > 90.0 or abs(lon) > 180.0:
        raise ParseError("outOfRange")
    canonical = "%.5f° %s, %.5f° %s" % (abs(lat), "N" if lat >= 0 else "S", abs(lon), "E" if lon >= 0 else "W")
    for v in (lat, lon):
        f = abs(v) * 1e5 % 1.0
        assert abs(f - 0.5) > 1e-6, "%r sits on a rounding tie, Java and C format it differently" % v
    return dict(source="latLon", zone=None, south=None, band=None, square=None, easting=None, northing=None,
                digits=None, cellSizeM=None, effectiveKind=None, kindSegment=False, lat=lat, lon=lon,
                completedFrom=None, completedFromPoint=None, canonical=canonical, resolved=(lat, lon),
                messages=[msg("calibration_reads_as", text=canonical)])


def assign(vals):
    """vals = [(sign, value, letter|None)] x2 -> (lat, lon) or ParseError"""
    letters = [v[2] for v in vals]
    if any(letters) and not all(letters):
        raise ParseError("unrecognised")
    if all(letters):
        if any(v[0] for v in vals):
            raise ParseError("unrecognised")          # -33.8 S: sign and letter together, which one?
        kinds = ["lat" if l in "NS" else "lon" for l in letters]
        if sorted(kinds) != ["lat", "lon"]:
            raise ParseError("unrecognised")
        out = {}
        for (_, v, l), k in zip(vals, kinds):
            out[k] = -v if l in "SW" else v
        return out["lat"], out["lon"]
    (s1, v1, _), (s2, v2, _) = vals
    return (-v1 if s1 == "-" else v1), (-v2 if s2 == "-" else v2)


def parse_dd(u):
    modes = (("dot", r"[0-9]+(?:\.[0-9]+)?", r"(?: ?[,;] ?| )"),
             ("semicolon", r"[0-9]+(?:[.,][0-9]+)?", r" ?; ?"),
             ("comma", r"[0-9]+,[0-9]+", r" "))
    for _, num, sep in modes:
        nl = r"([+-]?)(%s) ?°?" % num
        suf = r"([+-]?)(%s) ?°? ?([NSEWO])" % num
        pre = r"([NSEWO]) ?([+-]?)(%s) ?°?" % num
        for style, val in (("none", nl), ("suffix", suf), ("prefix", pre)):
            m = re.fullmatch(val + sep + val, u)
            if not m:
                continue
            g = m.groups()
            if style == "none":
                vals = [(g[0], float(g[1].replace(",", ".")), None), (g[2], float(g[3].replace(",", ".")), None)]
            elif style == "suffix":
                vals = [(g[0], float(g[1].replace(",", ".")), g[2]), (g[3], float(g[4].replace(",", ".")), g[5])]
            else:
                vals = [(g[1], float(g[2].replace(",", ".")), g[0]), (g[4], float(g[5].replace(",", ".")), g[3])]
            return latlon_ok(*assign(vals))
    return None


def _dms_val(i, style):
    sym = (r"(?P<d{i}>[0-9]+) ?° ?(?P<m{i}>[0-9]+(?:\.[0-9]+)?) ?'"
           r"(?: ?(?P<s{i}>[0-9]+(?:\.[0-9]+)?) ?\")?").format(i=i)
    spc = r"(?P<sd{i}>[0-9]+) (?P<sm{i}>[0-9]+(?:\.[0-9]+)?)(?: (?P<ss{i}>[0-9]+(?:\.[0-9]+)?))?".format(i=i)
    if style == "none":
        return r"(?P<g{i}>[+-]?)".format(i=i) + sym
    if style == "suffix":
        return r"(?P<g{i}>[+-]?)(?:{sym}|{spc}) ?(?P<h{i}>[NSEWO])".format(i=i, sym=sym, spc=spc)
    return r"(?P<h{i}>[NSEWO]) ?(?P<g{i}>[+-]?)(?:{sym}|{spc})".format(i=i, sym=sym, spc=spc)


def parse_dms(u):
    for style in ("none", "suffix", "prefix"):
        m = re.fullmatch(_dms_val(1, style) + r"(?: ?[,;] ?| )" + _dms_val(2, style), u)
        if not m:
            continue
        vals = []
        for i in (1, 2):
            gd = m.groupdict()
            d = gd.get("d%d" % i) or gd.get("sd%d" % i)
            mi = gd.get("m%d" % i) or gd.get("sm%d" % i)
            se = gd.get("s%d" % i) or gd.get("ss%d" % i)
            if se is not None and "." in mi:
                raise ParseError("unrecognised")       # 52.5' 30" makes no sense
            d, mi, se = float(d), float(mi), float(se or 0.0)
            if mi >= 60.0 or se >= 60.0:
                raise ParseError("outOfRange")
            vals.append((gd.get("g%d" % i) or "", d + mi / 60.0 + se / 3600.0, gd.get("h%d" % i)))
        return latlon_ok(*assign(vals))
    return None


def parse(raw, ctx):
    """('ok', dict) or ('err', code, args)"""
    try:
        s = normalise(raw)
        if s == "":
            raise ParseError("empty")
        if utf16_len(s) > C["maxInputUtf16Units"]:
            raise ParseError("unrecognised")
        u = s.upper()
        al = G.DATUM_BY_ID[ctx["datumId"]]["ellipsoid"] in AL_ELLIPSOIDS
        m = FULL.fullmatch(u)
        if m:
            return ("ok", parse_full(m, ctx, al))
        if UPS.fullmatch(u):
            raise ParseError("polarUnsupported")
        m = SHORT_SQ.fullmatch(u)
        if m:
            return ("ok", parse_short(m.group(1), m.group(2), m.group(3), m.group(4), ctx, al))
        m = SHORT_DIG.fullmatch(u)
        if m:
            return ("ok", parse_short(None, None, m.group(1), m.group(2), ctx, al))
        for f in (parse_utm, parse_dd, parse_dms):
            r = f(u) if f is not parse_utm else f(u, ctx)
            if r is not None:
                return ("ok", r)
        raise ParseError("unrecognised")
    except ParseError as e:
        return ("err", e.code, e.args_)


def ok_json(r, datum):
    lat, lon = r["resolved"]
    wl, wo = GEO.to_wgs84(datum, lat, lon)
    out = {k: r[k] for k in ("source", "zone", "south", "band", "square")}
    out["easting"] = None if r["easting"] is None else float(r["easting"])
    out["northing"] = None if r["northing"] is None else float(r["northing"])
    for k in ("digits", "cellSizeM", "effectiveKind", "kindSegment"):
        out[k] = r[k]
    out["lat"] = None if r["lat"] is None else rdeg(r["lat"])
    out["lon"] = None if r["lon"] is None else rdeg(r["lon"])
    out["completedFrom"] = r["completedFrom"]
    out["completedFromPoint"] = r["completedFromPoint"]
    out["canonical"] = r["canonical"]
    out["resolved"] = {"lat": rdeg(lat), "lon": rdeg(lon)}
    out["resolvedWGS84"] = {"lat": rdeg(wl), "lon": rdeg(wo)}
    out["messages"] = r["messages"]
    return out


def parse_expect(raw, ctx):
    r = parse(raw, ctx)
    if r[0] == "ok":
        return {"ok": ok_json(r[1], ctx["datumId"])}, r
    code, args = r[1], r[2]
    k = PARSE_ERROR_KEYS[code]
    if k:
        key(k)
    return {"error": code, "args": args, "messageKey": k}, r


def ctx_of(datum="WGS84", first=True, kind="intersection", anchor=None, predicted=None):
    if predicted is not None:
        predicted = {"lat": rdeg(predicted[0]), "lon": rdeg(predicted[1])}
    return {"datumId": datum, "isFirstPoint": first, "kind": kind, "gridAnchor": anchor, "predicted": predicted}


# ----------------------------------------------------------------------------
# calibration_input.json
# ----------------------------------------------------------------------------

SYD = (-33.8568, 151.2153)          # same point as mgrs_samples.json: 56HLH 34900 52288


def input_cases():
    cases = []

    def add(cid, raw, ctx, want, note):
        exp, r = parse_expect(raw, ctx)
        got = "ok" if r[0] == "ok" else r[1]
        assert got == want, "%s: wanted %s got %s %r" % (cid, want, got, r[2] if r[0] == "err" else "")
        cases.append({"id": cid, "input": raw, "context": ctx, "expect": exp, "note": note})
        return r

    w = ctx_of()
    # ---- full MGRS
    r = add("mgrs_full_10_spaced", "56HLH 34900 52288", w, "ok", "the mgrs_samples.json Sydney point, 1 m cell")
    assert abs(r[1]["resolved"][0] - SYD[0]) < 1e-4 and abs(r[1]["resolved"][1] - SYD[1]) < 1e-4
    add("mgrs_full_compact_lowercase", "56hlh3490052288", w, "ok", "any case, no spaces")
    add("mgrs_full_6_contract_example", "56H LH 349 522", w, "ok",
        "contract s4.4 example: canonical pads to 56H LH 34900 52200, 100 m cell, Kind segment shown")
    add("mgrs_full_4_intersection", "56HLH 34 52", w, "ok", "4 digits at a grid intersection: 1 km cell, SW corner")
    add("mgrs_full_4_feature_too_coarse", "56HLH 34 52", ctx_of(kind="feature"), "tooCoarse",
        "a feature needs at least 6 figures (s4.5)")
    add("mgrs_full_6_feature", "56HLH 349 522", ctx_of(kind="feature"), "ok",
        "feature = cell centre: +50 m on E and N before the TM inverse")
    add("mgrs_full_8_feature", "56HLH 3490 5228", ctx_of(kind="feature"), "ok", "10 m cell, +5 m")
    add("mgrs_full_10_feature_ignored", "56HLH 34900 52288", ctx_of(kind="feature"), "ok",
        "no Kind segment at 10 figures, so the kind is intersection whatever the context says")
    add("mgrs_square_invalid_column", "56HAH3490052288", w, "invalidSquare",
        "contract case: A isn't a column letter in zone 56 (56 % 3 == 2: J-R). iOS isSafeUTMMGRS rule")
    add("mgrs_square_invalid_row", "56HLW 349 522", w, "invalidSquare", "rows stop at V")
    add("mgrs_too_coarse_square_only", "56HLH", w, "tooCoarse", "contract case: 100 km square only")
    add("mgrs_too_coarse_2_digits", "56HLH34", w, "tooCoarse", "contract case: 10 km square")
    add("mgrs_unequal_split", "56HLH 349 5228", w, "unequalDigits", "split groups of 3 and 4")
    add("mgrs_odd_total", "56HLH34952", w, "unequalDigits", "5 figures in one run")
    add("mgrs_12_digits", "56HLH 349000 522880", w, "unrecognised", "more than 10 figures isn't MGRS")
    add("mgrs_band_mismatch", "56J LH 349 522", w, "bandMismatch",
        "with band J the 2,000 km block lands the square at ~15.9 S (band L), 2 bands off J")
    # a point a little south of the H/J line typed with band J: within +-1 band, accepted
    E, N = GEO.fwd("WGS84", 56, True, -32.05, 151.2)
    e_in, n_in = int(E % 1e5 // 100), int(N % 1e5 // 100)
    r = add("mgrs_band_adjacent_accepted", "56J %s %03d %03d" % (square_of(56, E, N), e_in, n_in), w, "ok",
            "SW corner is at ~32.05 S (band H) but band J was typed: the +-1 band rule accepts it, same E/N as "
            "with H")
    assert band_of(r[1]["resolved"][0]) == "H"
    add("mgrs_ups_polar", "ZGC 12345 12345", w, "polarUnsupported", "UPS (polar) references aren't supported")
    add("mgrs_zone_61", "61HLH 349 522", w, "unrecognised", "zones are 1-60")
    add("mgrs_band_letter_i", "56ILH 349 522", w, "unrecognised", "I isn't a band letter")
    add("mgrs_gzd_32x_missing", "32XNG 00000 00000", w, "unrecognised",
        "32X, 34X and 36X don't exist (Svalbard). NGA's parser traps on them, so refuse before")
    t = mgrs_text("WGS84", 61.2181, -149.9003)          # Anchorage, zone 6
    add("mgrs_leading_zero_zone", "0" + t, w, "ok", "zone 06 (what our own UTM readout pads to) is zone 6")
    add("mgrs_unicode_whitespace", "56H LH 349　522", w, "ok",
        "NBSP, thin space, ideographic space read as spaces (fiduciaryFits.whiteSpaceCodePoints)")
    add("mgrs_tab_newline", "\t56HLH 349 522\n", w, "ok", "tab and newline are whitespace too, trimmed")
    add("mgrs_zero_width_space", "56HLH​349522", w, "unrecognised", "U+200B isn't whitespace")
    add("too_long", "56HLH 34900 52288" + " 0" * 30, w, "unrecognised", "over 64 UTF-16 units after trimming")
    add("empty", "", w, "empty", "Save disabled, no message")
    add("whitespace_only", "  \t", w, "empty", "only whitespace is empty")

    # ---- datums
    add("mgrs_nad27_old_lettering", "10SEG 47000 77000", ctx_of("NAD27"), "oldLettering",
        "NAD27 (Clarke 1866) sheets carry the AL lettering, MGRS refused, no decode attempt")
    add("mgrs_tokyo_old_lettering", "54SUE 12345 67890", ctx_of("TOKYO"), "oldLettering", "TOKYO is Bessel 1841")
    add("mgrs_ntf_old_lettering", "31UDQ 48251 11932", ctx_of("NTF"), "oldLettering", "NTF is Clarke 1880 IGN")
    add("utm_nad27_accepted", "10S 547000 4177000", ctx_of("NAD27"), "ok",
        "UTM is fine on NAD27, resolved on Clarke 1866 then shifted to WGS84")
    add("latlon_nad27_accepted", "37.7, -122.45", ctx_of("NAD27"), "ok", "lat/lon is taken as NAD27 on a NAD27 sheet")
    r = add("mgrs_ed50_d2_05", "32U NA 00000 40000", ctx_of("ED50"), "ok",
            "D2-05: ED50 sheet, E 500000 N 5540000 on International 1924, then (-87, -98, -121) to WGS84. "
            "truthWGS84 is PROJ's own EPSG:23032 -> 4326")
    truth = ed50_truth()
    w84 = GEO.to_wgs84("ED50", *r[1]["resolved"])
    d = GEO.dist(w84[0], w84[1], truth[0], truth[1])
    assert d < 5.0
    cases[-1]["truthWGS84"] = {"lat": rdeg(truth[0]), "lon": rdeg(truth[1])}
    cases[-1]["truthDistanceM"] = rm(d)
    cases[-1]["truthToleranceM"] = 5.0

    # ---- shorthand
    syd_anchor = {"zone": 56, "band": "H", "square": "LH", "fromPoint": 1}
    add("shorthand_first_point_no_context", "349 522", ctx_of(first=True), "needsFullReference",
        "first point, provisional map: no zone or square to complete from")
    add("shorthand_no_context_later_point", "349 522", ctx_of(first=False), "needsFullReference",
        "earlier points were all lat/lon and the map is provisional: still nothing to complete from")
    add("shorthand_anchor_digits", "349 522", ctx_of(first=False, anchor=syd_anchor), "ok",
        "provisional map, so zone/band/square come from the latest grid point (point 1)")
    add("shorthand_anchor_compact", "349522", ctx_of(first=False, anchor=dict(syd_anchor, fromPoint=3)), "ok",
        "one run of 6 figures, split in half")
    add("shorthand_anchor_square_digits", "LJ 349 522", ctx_of(first=False, anchor=syd_anchor), "ok",
        "typed square, zone and band from the anchor")
    add("shorthand_anchor_invalid_square", "AH 349 522", ctx_of(first=False, anchor=syd_anchor), "invalidSquare",
        "A isn't a zone 56 column")
    add("shorthand_square_only_too_coarse", "LH", ctx_of(first=False, anchor=syd_anchor), "tooCoarse",
        "square without figures")
    add("shorthand_2_digits_too_coarse", "3 5", ctx_of(first=False, anchor=syd_anchor), "tooCoarse", "10 km")
    add("shorthand_unequal", "3490 522", ctx_of(first=False, anchor=syd_anchor), "unequalDigits", "4 and 3")
    add("shorthand_feature_4_digits", "34 52", ctx_of(first=False, kind="feature", anchor=syd_anchor), "tooCoarse",
        "feature + 4 figures")
    add("shorthand_integers_are_shorthand", "33 151", ctx_of(first=False, anchor=syd_anchor), "unequalDigits",
        "all-digit input is always MGRS shorthand (tried before decimal degrees), 2 and 3 figures")
    add("shorthand_predicted_same_square", "349 522", ctx_of(first=False, predicted=(-33.857, 151.215)), "ok",
        "map prediction is in LH, nearest candidate is LH itself")
    # prediction just west of the LH/MH line, the typed figures are a few hundred metres east of it
    plat, plon = GEO.inv("WGS84", 56, True, 399800.0, 6252300.0)
    r = add("shorthand_predicted_straddles_east_edge", "002 523", ctx_of(first=False, predicted=(plat, plon)), "ok",
            "prediction at E 399800 (square LH); 002 523 is 400 m away in MH but 99.6 km away in LH: MH wins")
    assert r[1]["square"] == "MH"
    plat, plon = GEO.inv("WGS84", 56, True, 334900.0, 6299900.0)
    r = add("shorthand_predicted_straddles_north_edge", "349 001", ctx_of(first=False, predicted=(plat, plon)), "ok",
            "prediction just south of the LH/LJ line, 349 001 lands in LJ")
    assert r[1]["square"] == "LJ"
    add("shorthand_predicted_square_digits", "LH 349 522", ctx_of(first=False, predicted=(-33.857, 151.215)), "ok",
        "typed square, zone and band from the map prediction")
    add("shorthand_predicted_beats_anchor", "349 522",
        ctx_of(first=False, anchor={"zone": 55, "band": "H", "square": "CB", "fromPoint": 2},
               predicted=(-33.857, 151.215)), "ok",
        "displayedGeoref isn't provisional, so the map wins over the latest grid point")
    add("shorthand_predicted_first_point", "349 522", ctx_of(first=True, predicted=(-33.857, 151.215)), "ok",
        "refining a GeoPDF: the first point can already be shorthand")
    bergen = (60.3913, 5.3221)
    t = mgrs_text("WGS84", *bergen, digits=6)
    assert t.startswith("32V")
    add("shorthand_predicted_norway_32v", t.split(" ", 2)[2], ctx_of(first=False, predicted=bergen), "ok",
        "Bergen is 31V by longitude but the printed grid is 32V: completion uses the MGRS zone, Norway "
        "exception included (the fit plane zone doesn't, s5)")
    add("shorthand_old_lettering", "349 522",
        ctx_of("NAD27", first=False, anchor={"zone": 10, "band": "S", "square": "EG", "fromPoint": 1}),
        "oldLettering", "shorthand is MGRS too")
    ed_pred = GEO.to_wgs84("ED50", *GEO.inv("ED50", 32, False, 500150.0, 5540250.0))
    add("shorthand_predicted_ed50", "001 402", ctx_of("ED50", first=False, predicted=ed_pred), "ok",
        "prediction comes in as WGS84 (toWGS84), goes to ED50 lat/lon, then ED50 UTM for the square")

    # ---- UTM
    add("utm_band_h", "56H 334000 6250000", w, "ok", "band H: southern")
    add("utm_own_readout_hemisphere_s", "56S 334000mE 6250000mN", w, "ok",
        "our own readout: as band S (32-40 N) the northing lands at 56.4 N, outside S +-0.5, so S is the "
        "hemisphere")
    add("utm_own_readout_hemisphere_n", "33N 450000mE 6700000mN", w, "ok",
        "N as a band (0-8 N) doesn't fit 60.4 N, so N is the hemisphere")
    add("utm_band_s_north", "33S 500000 3600000", w, "ok", "S as a band fits (32.5 N): northern")
    add("utm_band_s_wins_over_hemisphere", "33S 500000mE 3700000mN", w, "ok",
        "known ambiguity: band S fits (33.4 N) so it's read as the band, even if it was a southern readout "
        "(56.6 S). The entry-time distance check is what catches that one")
    add("utm_band_n_equator", "33N 500000 500000", w, "ok", "band N fits (4.5 N)")
    add("utm_words_south", "56 south 334000 6250000", w, "ok", "zone + hemisphere word")
    add("utm_words_north", "56 NORTH 334000 6250000", w, "ok", "same figures, northern hemisphere")
    add("utm_band_mismatch", "56K 334000 6250000", w, "bandMismatch", "33.9 S isn't in band K")
    add("utm_easting_out_of_range", "10S 47000 4177000", w, "unrecognised", "UTM easting under 100000")
    add("utm_polar", "33N 500000 9400000", w, "polarUnsupported", "84.6 N is past the UTM limit")
    add("utm_zone_zero_padded", "06N 500000mE 4500000mN", w, "ok", "readout pads the zone to 2 digits")
    add("utm_lowercase_spaced_units", "56 h 334000 mE 6250000 mN", w, "ok", "any case, mE/mN spaced off")
    add("utm_nbsp", "55H 768000 6252000", w, "ok", "pasted with NBSPs")
    add("utm_leading_zero_figures", "56H 0334000 0006250000", w, "ok",
        "A3: leading zeros are fine, figures are read as numbers whatever their length (10 chars here)")

    # ---- decimal degrees
    add("dd_signed_comma", "-33.8568, 151.2153", w, "ok", "signed, comma separated")
    add("dd_signed_space", "-33.8568 151.2153", w, "ok", "signed, space separated")
    add("dd_suffix_letters", "33.8568 S, 151.2153 E", w, "ok", "hemisphere suffixes")
    add("dd_prefix_letters", "S33.8568 E151.2153", w, "ok", "hemisphere prefixes")
    add("dd_reversed_with_letters", "151.2153 E, 33.8568 S", w, "ok", "letters let the longitude come first")
    add("dd_degree_signs", "33.8568° S, 151.2153° E", w, "ok", "degree sign optional")
    add("dd_unicode_minus", "−33.8568, 151.2153", w, "ok", "U+2212 minus")
    add("dd_plus_sign", "+37.7, -122.45", w, "ok", "a leading + is a sign too")
    add("dd_german_semicolon", "-33,8568; 151,2153", w, "ok", "decimal comma, values split by ;")
    add("dd_german_whitespace", "-33,8568 151,2153", w, "ok", "both values with a decimal comma, split by a space")
    add("dd_german_o_is_east", "47,5 N 11,25 O", w, "ok", "O = Ost (east), decimal commas")
    add("dd_o_is_east_dot", "48.1374 N, 11.5755 O", w, "ok", "O = east with dot decimals")
    add("dd_comma_both_ways_refused", "-33,8568, 151,2153", w, "unrecognised",
        "decimal commas AND a comma separator: can't tell, refused")
    add("dd_integers_comma", "47,11", w, "ok", "no space: a comma separator, so 47 N 11 E (not 47.11)")
    add("dd_lat_out_of_range", "91, 10", w, "outOfRange", "lat over 90")
    add("dd_lon_out_of_range", "10, 181", w, "outOfRange", "lon over 180")
    add("dd_sign_and_letter_refused", "-33.8568 S, 151.2153 E", w, "unrecognised", "sign and letter on one value")
    add("dd_one_letter_refused", "-33.8568, 151.2153 E", w, "unrecognised", "letters on both values or neither")
    add("dd_two_latitudes_refused", "33.8 N, 151.2 S", w, "unrecognised", "needs one N/S and one E/W/O")
    add("dd_fullwidth_digits_refused", "３７.7, -122.45", w, "unrecognised", "ascii digits only")
    add("dd_nbsp_separator", "37.7 -122.45", w, "ok", "NBSP between the values")
    add("dd_lon_exactly_180", "-16.5, 180", w, "ok", "A2/B1: |lon| = 180 is in range (the fit clamps its zone to 60)")
    add("dd_lon_exactly_minus_180", "-16.5, -180", w, "ok", "A2/B1: lon -180, canonical 180.00000° W")

    # ---- degrees minutes (seconds)
    add("dm_symbols", "33°52.5'S 151°12.9'E", w, "ok", "degrees + decimal minutes")
    add("dms_symbols", "33°52'30\"S 151°12'55\"E", w, "ok", "degrees minutes seconds")
    add("dms_space_only", "33 52 30 S 151 12 55 E", w, "ok", "space-only needs the letters")
    add("dm_space_only", "33 52.5 S 151 12.9 E", w, "ok", "space-only degrees + decimal minutes")
    add("dms_unicode_primes", "33°52′30″S 151°12′55″E", w, "ok", "prime and double prime")
    add("dms_curly_quotes", "33°52’30”S 151°12’55”E", w, "ok",
        "right single and double quotes (iOS smart punctuation)")
    add("dms_two_apostrophes", "33°52'30''S 151°12'55''E", w, "ok", "'' is seconds")
    add("dms_prefix_letters", "S 33°52'30\" E 151°12'55\"", w, "ok", "hemisphere prefixes")
    add("dms_signed", "-33°52'30\", 151°12'55\"", w, "ok", "symbol forms may be signed instead")
    add("dms_seconds_60", "33°52'60\"S 151°12'55\"E", w, "outOfRange", "contract case: seconds must be < 60")
    add("dms_minutes_60", "33°60'00\"S 151°12'55\"E", w, "outOfRange", "minutes must be < 60")
    add("dms_lat_over_90", "90°30'00\"N 10°00'00\"E", w, "outOfRange", "90 30' N")
    add("dms_space_only_no_letters", "33 52 30 151 12 55", w, "unrecognised",
        "space-only without letters isn't anything (and too many groups for shorthand)")
    return cases


# ----------------------------------------------------------------------------
# display formats both apps share (A1, OD-F11, OD-F12, OD-F6). Format-only, en + de
# ----------------------------------------------------------------------------

# s10 datum sheet order + the one display-name table (A1). Technical names, not localised
DATUM_DISPLAY = [
    ("WGS84", "WGS84"), ("GDA2020", "GDA2020"), ("GDA94", "GDA94"), ("NAD83", "NAD83"), ("ETRS89", "ETRS89"),
    ("ED50", "ED50"), ("NAD27", "NAD27"), ("NAD27_CONUS_EAST", "NAD27 CONUS East"),
    ("NAD27_CONUS_WEST", "NAD27 CONUS West"), ("NAD27_ALASKA", "NAD27 Alaska"), ("NAD27_CANADA", "NAD27 Canada"),
    ("OSGB36", "OSGB36"), ("SK42", "SK-42"), ("TOKYO", "Tokyo"), ("CH1903", "CH1903"), ("NTF", "NTF"),
]
LOCALE_SEP = {"en": (",", "."), "de": (".", ",")}      # (grouping, decimal)


def fmt_fixed(v, decimals, loc):
    """DisplayFormat.number: no grouping, half-even. Callers keep v off ties"""
    s = "%.*f" % (decimals, v)
    return s.replace(".", LOCALE_SEP[loc][1])


def fmt_distance(m, loc):
    """DisplayFormat.distance, both apps"""
    if m < 1000:
        return fmt_fixed(m, 0, loc) + " m"
    return fmt_fixed(m / 1000.0, 2 if m < 100000 else 0, loc) + " km"


def cell_text(m):
    """grid cell sizes are powers of ten (and their halves): whole m under 1 km, whole km from there, no
    decimals in any locale. NOT DisplayFormat.distance, that gives '1.00 km' (OD-F11)"""
    assert m == int(m) and (m < 1000 or m % 1000 == 0), m
    return "%d m" % m if m < 1000 else "%d km" % (m // 1000)


def rms_text(m, loc):
    """OD-F12: one decimal below 10 m (decided on the rounded value, so 9.96 -> '10 m'), else DisplayFormat.distance"""
    if m < 9.95:
        return fmt_fixed(m, 1, loc) + " m"
    return fmt_distance(m, loc)


def grouped(n, loc):
    s = str(abs(n))
    out = ""
    for i, d in enumerate(s):
        if i and (len(s) - i) % 3 == 0:
            out += LOCALE_SEP[loc][0]
        out += d
    return ("-" if n < 0 else "") + out


def size_text(b, loc):
    """pdf_tile_render.json bakeFormat size rule (OD-F6 uses it for import limits + the Layers footer)"""
    b = max(0, b)
    dec = LOCALE_SEP[loc][1]
    tenths = (b + 50000) // 100000
    if b > 0:
        tenths = max(1, tenths)
    if tenths < 1000:
        return grouped(tenths // 10, loc) + dec + str(tenths % 10) + " MB"
    whole = (b + 500000) // 1000000
    if whole < 1000:
        return grouped(whole, loc) + " MB"
    tg = (b + 50000000) // 100000000
    return grouped(tg // 10, loc) + dec + str(tg % 10) + " GB"


def check_size_rule_matches_wp2():
    """same rule as WP2's fixture, or the two would drift"""
    import json
    p = os.path.join(REPO, "testdata", "pdf_tile_render.json")
    if not os.path.exists(p):
        print("warning: pdf_tile_render.json missing, size rule not cross-checked")
        return
    for c in json.load(open(p, encoding="utf-8"))["bakeFormat"]["cases"]:
        for loc in ("en", "de"):
            assert size_text(c["bytes"], loc) == c[loc]["size"], (c["bytes"], loc)


_CATALOGUE = None


def catalogue_text(k, loc, args):
    """the catalogue's template with {1}.. filled in, so the full phrase is pinned too"""
    global _CATALOGUE
    if _CATALOGUE is None:
        import json
        _CATALOGUE = json.load(open(os.path.join(REPO, "localization", "catalog.json"), encoding="utf-8"))
    s = _CATALOGUE[k][loc]
    for i, a in enumerate(args):
        s = s.replace("{%d}" % (i + 1), a)
    return s


def display_doc(cases):
    for i, _ in DATUM_DISPLAY:
        assert i in G.DATUM_BY_ID, i
    assert len(DATUM_DISPLAY) == len(G.DATUM_BY_ID)
    sizes = sorted({c["expect"]["ok"]["cellSizeM"] for c in cases if "ok" in c["expect"]
                    and c["expect"]["ok"]["cellSizeM"] is not None})
    halves = sorted({s / 2.0 for s in sizes if s > 1.0})
    rows = []
    for c in cases:
        ok = c["expect"].get("ok")
        if not ok or ok["cellSizeM"] is None or c["id"] not in ("mgrs_full_4_intersection", "mgrs_full_6_feature",
                                                                 "mgrs_full_8_feature", "mgrs_full_10_spaced"):
            continue
        cell = [m for m in ok["messages"] if m["key"].startswith("calibration_cell_")][0]
        texts = [cell_text(cell["args"][k]) for k in ("sizeM", "halfM") if k in cell["args"]]
        row = {"fromCase": c["id"], "key": cell["key"], "argText": {k: cell_text(v) for k, v in cell["args"].items()}}
        for loc in ("en", "de"):
            row[loc] = catalogue_text(cell["key"], loc, texts)
        rows.append(row)
    return {
        "rules": {
            "datumDisplayNames": "A1: the datum chip, datum sheet rows (in this order), the interpretation line's datum "
                                 "name, calibration_datum_changed {datum} and calibration_err_old_lettering {datum} all "
                                 "use this one table. Technical names, the same in every language",
            "cellSizeText": "OD-F11: the {size} / {half} args of calibration_cell_intersection / _feature. Whole "
                            "metres under 1000 ('100 m'), whole km from 1000 ('1 km'), no decimals, same in every "
                            "language. Not DisplayFormat.distance",
        },
        "datumDisplayNames": [{"id": i, "name": n} for i, n in DATUM_DISPLAY],
        "cellSizeText": [{"sizeM": s, "en": cell_text(s), "de": cell_text(s)} for s in sorted(set(sizes) | set(halves))],
        "interpretationCells": rows,
    }


def ed50_truth():
    t = G.Ref().pyproj.Transformer.from_crs("EPSG:23032", "EPSG:4326", always_xy=True)
    lon, lat = t.transform(500000.0, 5540000.0)
    return float(lat), float(lon)


def input_doc():
    cases = input_cases()
    nband = {b: band_bottom_northing(b) for b in BANDS}
    display = display_doc(cases)
    return {
        "description": "CoordinateInputParser contract (plans/WP4-calibration-lifecycle-shared_contract.md s4, "
                       "plus s5 reference resolution). Both apps run every case through the real parser. "
                       "Generated by scripts/gen_calibration_fixtures.py, never hand edited.",
        "schemaVersion": 1,
        "generator": "scripts/gen_calibration_fixtures.py",
        "tolerance": {"metres": 0.001, "degrees": 1e-9},
        "rules": {
            "normalise": "every fiduciaryFits.whiteSpaceCodePoints char -> space, U+2212 -> '-', U+2032 U+2019 -> "
                         "', U+2033 U+201D and '' -> \", collapse spaces, trim. Then uppercase for matching. Empty "
                         "-> empty. More than maxInputUtf16Units UTF-16 units -> unrecognised",
            "order": "full MGRS, UPS (-> polarUnsupported), MGRS shorthand (square + figures, or figures only), "
                     "UTM, decimal degrees, DM/DMS. The first form whose SHAPE matches owns the input: its errors "
                     "are reported, no falling through. So all-digit input is always shorthand",
            "fullMgrs": "zone 1-60 (leading 0 ok), band C-X without I/O, then optional single spaces between "
                        "zone, band and the two square letters, then one run of figures (split in half) or two "
                        "runs of equal length. Checks in order: zone/band/GZD (32X 34X 36X don't exist) -> "
                        "unrecognised; datum ellipsoid Clarke1866/Clarke1880IGN/Bessel1841 -> oldLettering; "
                        "column not in the zone's set (zone%3==1 A-H, 2 J-R, 0 S-Z) or row not A-V -> "
                        "invalidSquare; figures: unequal runs or odd total -> unequalDigits, 0 or 2 -> tooCoarse, "
                        "> 10 -> unrecognised, feature with 4 -> tooCoarse; E/N by the NGA rule (mgrsNorthing); "
                        "SW corner latitude (TM inverse on the sheet ellipsoid) outside -80..84 -> polarUnsupported, "
                        "more than 1 band from the typed band -> bandMismatch",
            "mgrsNorthing": "NGA MGRS.toUTM(): E = (column index + 1) * 100 km + figures. N = row index * 100 km "
                            "(odd zones ABCDEFGHJKLMNPQRSTUV, even zones FGHJKLMNPQRSTUVABCDE) + figures, plus "
                            "2,000 km until it's >= bandBottomNorthing[band] (floor to 100 km of the WGS84 UTM "
                            "northing of the band's south edge at lon 0, zone 31). Never through lat/lon",
            "bandBottomNorthing": nband,
            "shorthand": "figures, or square + figures. Checks: oldLettering, then context: with "
                         "context.predicted (displayedGeoref isn't provisional; WGS84 as toWGS84 gives it) the "
                         "prediction goes to the sheet datum, zone = its MGRS grid zone (Norway/Svalbard "
                         "exceptions included), band = its band; a typed square is used as is, figures only try "
                         "the predicted 100 km square and its 8 neighbours in that zone (skipping E outside "
                         "[100000, 900000) and N outside [0, 10000000]) and keep the candidate whose resolved "
                         "point (kind offset applied) is nearest the predicted E/N, band = band of its SW corner. "
                         "Else with context.gridAnchor: its zone, band and (unless typed) square. Else "
                         "needsFullReference. Then invalidSquare, figure rules and the band/polar checks as for "
                         "full MGRS",
            "utm": "'ZZL E N' (optional space before L, optional mE/mN) or 'ZZ NORTH|SOUTH E N'. E 100000-900000, "
                   "N 0-10000000, zone 1-60, whole metres; anything else -> unrecognised. L is tried as a band "
                   "first: inverse lat (sheet ellipsoid) within the band +-0.5 deg is accepted; else N or S is "
                   "read as the hemisphere; any other letter -> bandMismatch. Then lat outside -80..84 -> "
                   "polarUnsupported. band in the result = band of the resolved latitude",
            "decimal": "two values, each [+-]digits[.digits] with optional deg sign, hemisphere letters N S E W O "
                       "(O = east) as prefixes or suffixes on both values or on neither; letters may put the "
                       "longitude first; sign and letter on one value -> unrecognised. Separator ', ' ';' or a "
                       "space. Decimal comma only with ';' between the values, or when both values have one and "
                       "only a space separates them. |lat| > 90 or |lon| > 180 -> outOfRange",
            "dms": "deg°min'[sec\"] (signed or with letters) or space-only 'deg min [sec] L' (letters "
                   "required). Minutes with a fraction can't have seconds. min or sec >= 60, or the value past "
                   "90/180 -> outOfRange",
            "resolution": "s5: grid -> kind offset (feature: + cell/2 on E and N, cells under 10 figures only) "
                          "-> TM inverse on the sheet datum ellipsoid. Geographic is already sheet datum. "
                          "resolvedWGS84 = datum.toWGS84 (pdf_georef.json datums)",
            "canonical": "MGRS '%d%s %s %05d %05d' (zone unpadded, band, square, SW corner figures). UTM 'UTM "
                         "%d%s %d %d'. lat/lon '%.5f° N|S, %.5f° E|W'; fixture values never sit on a "
                         "rounding tie (Java and C round ties differently)",
            "kindSegment": "shown for MGRS with 4, 6 or 8 figures only; at 10 figures the kind is intersection",
            "messages": "the interpretation line, in order: calibration_reads_as {text}, then the cell phrase "
                        "(MGRS only; sizes in metres, format with DisplayFormat.distance), then the completion "
                        "phrase for shorthand. The datum display name is appended by the UI",
        },
        "errorKeys": PARSE_ERROR_KEYS,
        "display": display,
        "cases": cases,
    }


# ----------------------------------------------------------------------------
# fit report (contract s5, s6)
# ----------------------------------------------------------------------------

def ref_of(raw, datum, kind="intersection"):
    """structured reference straight off the parser, so the two fixtures can't disagree"""
    st = parse(raw, ctx_of(datum, first=True, kind=kind))
    assert st[0] == "ok", (raw, st)
    r = st[1]
    if r["source"] == "latLon":
        return {"geographic": {"lat": r["lat"], "lon": r["lon"]}}
    return {"grid": {"zone": r["zone"], "south": r["south"], "easting": float(r["easting"]),
                     "northing": float(r["northing"]), "cellSizeM": float(r["cellSizeM"] or 1.0),
                     "source": r["source"]}}


def point(number, page, raw, datum, kind="intersection", override=None):
    ref = ref_of(raw, datum, kind)
    if override:
        ref = {"geographic": ref["geographic"]}
    return {"number": number, "page": [rpt(page[0]), rpt(page[1])], "input": raw, "reference": ref,
            "kind": kind, "datumOverride": override}


def resolve(p, datum):
    """sheet-datum lat/lon of a point's reference (s5), plus the grid zone it was typed in"""
    r = p["reference"]
    if "grid" in r:
        g = r["grid"]
        off = g["cellSizeM"] / 2.0 if p["kind"] == "feature" and g["cellSizeM"] > 1.0 else 0.0
        lat, lon = GEO.inv(datum, g["zone"], g["south"], g["easting"] + off, g["northing"] + off)
        return lat, lon, (g["zone"], g["south"], g["easting"] + off, g["northing"] + off)
    lat, lon = r["geographic"]["lat"], r["geographic"]["lon"]
    if p.get("datumOverride") == "WGS84":
        lat, lon = GEO.from_wgs84(datum, lat, lon)
    return lat, lon, None


def standard_zone(lon):
    """6 degree zone, no Norway/Svalbard. lon 180 would be zone 61, clamp it (A2/B1): 180 -> 60, -180 -> 1"""
    return min(60, max(1, int(math.floor((lon + 180.0) / 6.0)) + 1))


def plane_zone(p, datum):
    lat, lon, grid = resolve(p, datum)
    if grid:
        return grid[0], grid[1]
    return standard_zone(lon), lat < 0


def to_plane(p, datum, zone, south):
    lat, lon, grid = resolve(p, datum)
    if grid and grid[0] == zone and grid[1] == south:
        return grid[2], grid[3]
    return GEO.fwd(datum, zone, south, lat, lon)


def box_corners(pb):
    (x0, y0), (x1, _), (_, y1) = pb[0], pb[1], pb[2]
    return x0, y0, x1, y1


def eigen_ratio(pages):
    return GEO.ref.eigen_ratio(pages)


def lsq(pairs):
    return GEO.ref.fit(pairs)


def apply(A, x, y):
    return G.Ref.apply(A, x, y)


def diag_m(A, pb):
    x0, y0, x1, y1 = box_corners(pb)
    a, b, c, d = apply(A, x0, y0), apply(A, x1, y0), apply(A, x1, y1), apply(A, x0, y1)
    return max(math.hypot(a[0] - c[0], a[1] - c[1]), math.hypot(b[0] - d[0], b[1] - d[1]))


def tau_of(D):
    return max(C["toleranceFloorM"], C["toleranceDiagonalFraction"] * D)


def rms_of(A, pairs):
    res = [math.hypot(*(u - v for u, v in zip(apply(A, x, y), (X, Y)))) for x, y, X, Y in pairs]
    return math.sqrt(sum(r * r for r in res) / len(res)), res


def fit_georef(A, datum, zone, south, pb):
    return {"crs": G.UTM(zone, south), "datum": datum, "affine": A, "crop": [tuple(p) for p in pb]}


def upright_corners(pb, rotate):
    """(name, raw corner, opposite raw corner) in TL TR BR BL order as the page is VIEWED"""
    x0, y0, x1, y1 = box_corners(pb)
    raw = {"bl": (x0, y0), "br": (x1, y0), "tr": (x1, y1), "tl": (x0, y1)}
    seq = {0: ("tl", "tr", "br", "bl"), 90: ("bl", "tl", "tr", "br"),
           180: ("br", "bl", "tl", "tr"), 270: ("tr", "br", "bl", "tl")}[rotate % 360]
    opp = {"tl": "br", "br": "tl", "tr": "bl", "bl": "tr"}
    return [(CORNERS[i], raw[k], raw[opp[k]]) for i, k in enumerate(seq)]


def next_corner(pb, rotate, pages):
    best = None
    f = C["nextCornerInsetFraction"]
    for name, (cx, cy), (ox, oy) in upright_corners(pb, rotate):
        ix, iy = cx + f * (ox - cx), cy + f * (oy - cy)
        dmin = min((math.hypot(ix - x, iy - y) for x, y in pages), default=math.inf)
        if best is None or dmin > best[0]:
            best = (dmin, name, (ix, iy))
    if pages:
        # a close race between corners would hinge on float noise, keep the cases clear of it
        ds = sorted((min(math.hypot(cx + f * (ox - cx) - x, cy + f * (oy - cy) - y) for x, y in pages))
                    for _, (cx, cy), (ox, oy) in upright_corners(pb, rotate))
        assert ds[-1] - ds[-2] > 1e-6, "next corner too close to call"
    return best[1], best[2]


def evaluate(case):
    datum, pb, rot = case["datumId"], case["pageBox"], case["rotate"]
    pts = sorted(case["points"], key=lambda p: p["number"])
    n = len(pts)
    e = {"n": n, "planeZone": None, "south": None, "eigenRatio": None, "affine": None, "diagonalM": None,
         "toleranceM": None, "rmsM": None, "maxM": None, "residualsM": None, "looRmsM": None, "looM": None,
         "anisotropy": None, "scaleDenominator": None, "grade": None, "exact": n == 3}
    pages = [tuple(p["page"]) for p in pts]
    x0, y0, x1, y1 = box_corners(pb)
    blocked = None
    if n:
        z, s = plane_zone(pts[0], datum)
        e["planeZone"], e["south"] = z, s
    fit = None
    if n >= 3:
        ratio = eigen_ratio(pages)
        e["eigenRatio"] = rsig(ratio)
        planes = [to_plane(p, datum, e["planeZone"], e["south"]) for p in pts]
        if ratio < C["degenerateEigenRatio"]:
            blocked = "degenerate"
        elif any(q is None for q in planes):
            blocked = "invalid"                 # a point > 90 deg from the plane CM can't be forwarded
        else:
            pairs = [(x, y, X, Y) for (x, y), (X, Y) in zip(pages, planes)]
            A = lsq(pairs)
            det = A[0] * A[4] - A[1] * A[3]
            if not all(math.isfinite(v) for v in A) or det == 0.0:
                blocked = "invalid"
            else:
                fit = (A, pairs)
    issue = None
    if fit:
        A, pairs = fit
        A = [rsig(v) for v in A]
        det = A[0] * A[4] - A[1] * A[3]
        D = diag_m(A, pb)
        tau = tau_of(D)
        rms, res = rms_of(A, pairs)
        e.update(affine=A, diagonalM=rm(D), toleranceM=rm(tau), rmsM=rm(rms), maxM=rm(max(res)),
                 residualsM={str(p["number"]): rm(r) for p, r in zip(pts, res)})
        loo, loo_rms = {}, {}
        for i, p in enumerate(pts):
            rest = pairs[:i] + pairs[i + 1:]
            if len(rest) < 3 or eigen_ratio([(q[0], q[1]) for q in rest]) < C["degenerateEigenRatio"]:
                loo[p["number"]], loo_rms[p["number"]] = None, None
                continue
            Ai = lsq(rest)
            loo_rms[p["number"]] = rms_of(Ai, rest)[0]
            px, py = apply(Ai, pairs[i][0], pairs[i][1])
            loo[p["number"]] = math.hypot(px - pairs[i][2], py - pairs[i][3])
        e["looRmsM"] = {str(k): (None if v is None else rm(v)) for k, v in loo_rms.items()}
        e["looM"] = {str(k): (None if v is None else rm(v)) for k, v in loo.items()}
        L = GEO.np.array([[A[0], A[1]], [A[3], A[4]]])
        sv = GEO.np.linalg.svd(L, compute_uv=False)
        aniso = float(sv[0] / sv[1])
        scale = math.sqrt(abs(det)) / PT_M
        e["anisotropy"], e["scaleDenominator"] = rsig(aniso), rsig(scale)
        g = fit_georef(A, datum, e["planeZone"], e["south"], pb)
        lats = []
        for cx, cy in ((x0, y0), (x1, y0), (x1, y1), (x0, y1)):
            la, lo = GEO.g_to_wgs84(g, cx, cy)
            lats.append(la)
            if not (math.isfinite(la) and math.isfinite(lo)):
                blocked = "invalid"
        if blocked is None and max(abs(v) for v in lats) > C["offEarthLatDeg"]:
            blocked = "invalid"
        if blocked is None and (aniso > C["implausibleAnisotropy"]
                                or not C["plausibleScaleMin"] <= scale <= C["plausibleScaleMax"]):
            blocked = "implausible"
        if blocked is None:
            if n >= 4:
                e["grade"] = "good" if rms <= tau else ("fair" if rms <= 3 * tau else "poor")
            if n >= C["outlierMinPoints"] and rms > tau:
                good = sorted((loo_rms[p["number"]], p["number"]) for p in pts
                              if loo_rms[p["number"]] is not None and loo_rms[p["number"]] <= tau)
                if len(good) == 1:
                    k = good[0][1]
                    issue = {"type": "outlier", "number": k, "distanceM": rm(loo[k])}
                elif len(good) >= 2:
                    issue = {"type": "ambiguous", "first": good[0][1], "second": good[1][1]}
                else:
                    issue = {"type": "disagree", "maxResidualM": rm(max(res))}
            elif n == 4 and rms > tau:
                issue = {"type": "disagreeAddFifth", "maxResidualM": rm(max(res))}
        case["_fit"] = (A, tau, e["planeZone"], e["south"]) if blocked is None else None
        case["_anyfit"] = (A, tau, e["planeZone"], e["south"])
    else:
        case["_fit"] = None
        case["_anyfit"] = None
    e["issue"] = issue
    flagged = [issue["number"]] if issue and issue["type"] == "outlier" else []
    e["flagged"] = flagged
    if e["grade"] is not None:
        tau = e["toleranceM"]
        e["rowStatus"] = {k: ("error" if v > 3 * tau or int(k) in flagged else ("warn" if v > tau else "ok"))
                          for k, v in e["residualsM"].items()}
    else:
        e["rowStatus"] = None
    spread = False
    if n >= 3:
        xs, ys = [p[0] for p in pages], [p[1] for p in pages]
        spread = (max(xs) - min(xs)) < C["spreadMinFraction"] * (x1 - x0) or \
                 (max(ys) - min(ys)) < C["spreadMinFraction"] * (y1 - y0)
    e["spreadLow"] = spread
    if n < 3 or spread:
        name, at = next_corner(pb, rot, pages)
        e["nextCorner"], e["nextCornerPage"] = name, [rpt(at[0]), rpt(at[1])]
    else:
        e["nextCorner"], e["nextCornerPage"] = None, None
    # finishability (s6.6)
    if n < 3:
        e["finish"] = {"state": "blocked", "reason": "needMore", "needMore": 3 - n}
    elif blocked:
        e["finish"] = {"state": "blocked", "reason": blocked}
    else:
        reasons = []
        if n == 3:
            reasons.append("exact")
        if e["grade"] == "poor":
            reasons.append("poor")
        if issue and issue["type"] in ("outlier", "ambiguous"):
            reasons.append(issue["type"])
        if spread:
            reasons.append("spreadLow")
        e["finish"] = {"state": "confirm", "reasons": reasons} if reasons else {"state": "ready"}
    e["blocked"] = blocked
    e["primaryStatus"] = primary_status(e)
    # B2/OD-F8: the next-corner hint is the secondary line whenever there is one, n = 0 included
    e["secondaryHint"] = msg(CORNER_KEYS[e["nextCorner"]]) if e["nextCorner"] else None
    e["confirmMessages"] = [confirm_message(r, e) for r in e["finish"].get("reasons", [])]
    return e


def primary_status(e):
    n = e["n"]
    if n == 0:
        return msg("calibration_intro")
    if e["blocked"]:
        return msg("calibration_" + e["blocked"])
    if n < 3:
        return msg("calibration_need_points", placed=n)
    i = e["issue"]
    if i:
        if i["type"] == "outlier":
            return msg("calibration_outlier", number=i["number"], distance=i["distanceM"])
        if i["type"] == "ambiguous":
            return msg("calibration_ambiguous", first=i["first"], second=i["second"])
        if i["type"] == "disagree":
            return msg("calibration_disagree", distance=i["maxResidualM"])
        return msg("calibration_disagree_add_fifth", distance=i["maxResidualM"])
    if n == 3:
        return msg("calibration_exact_fit")
    return msg("calibration_fit_summary", points=n, rms=e["rmsM"], grade=key("calibration_grade_" + e["grade"]))


def confirm_message(reason, e):
    i = e["issue"]
    if reason == "exact":
        return msg("calibration_finish_confirm_exact")
    if reason == "poor":
        return msg("calibration_finish_confirm_poor", rms=e["rmsM"])
    if reason == "outlier":
        return msg("calibration_outlier", number=i["number"], distance=i["distanceM"])
    if reason == "ambiguous":
        return msg("calibration_ambiguous", first=i["first"], second=i["second"])
    return msg("calibration_finish_confirm_spread")


# ---- the sheets the cases sit on

def d203_sheet():
    # D2-03: 1:50k UTM sheet at 45 N, 20 x 20 km of zone 32 around 9 E
    return G.Sheet("d203", G.Plane.utm(32, False), 490000, 4975000, 20000, 20000, G.S50K, scale_label="1:50000")


def page_box(sh):
    m = sh.crop or sh.media
    return [[rpt(m[0]), rpt(m[1])], [rpt(m[2]), rpt(m[1])], [rpt(m[2]), rpt(m[3])], [rpt(m[0]), rpt(m[3])]]


def grid_text(sh, X, Y, digits=10, zone=None):
    """MGRS of a plane point of a UTM sheet, as printed. zone= overrides the typed zone (typo cases)"""
    lat = sh.plane.inv(X, Y)[0]
    z = sh.plane.p["zone"]
    half = digits // 2
    unit = 10 ** (5 - half)
    sq = square_of(z, X, Y)
    return "%d%s %s %0*d %0*d" % (zone or z, band_of(lat), sq, half, int(X % 1e5 // unit), half, int(Y % 1e5 // unit))


def warp(sh, X, Y, amp, kind="bump"):
    """smooth non-affine scan warp in metres (what a scanned paper sheet does)"""
    u, v = (X - sh.X0) / sh.W, (Y - sh.Y0) / sh.H
    if kind == "bump":
        return amp * math.sin(math.pi * u) * math.sin(math.pi * v), 0.7 * amp * math.sin(math.pi * u) * math.cos(math.pi * v)
    return amp * (u * u - u), amp * (v * v - v) * 0.8


def noisy_page(sh, X, Y, rng, amp, place=0.5, kind="bump"):
    dE, dN = warp(sh, X, Y, amp, kind)
    x, y = sh.page(X + dE, Y + dN)
    return x + 2 * place * rng.u(), y + 2 * place * rng.u()


def make_case(cid, sh_or_box, datum, points, note, rotate=0, extra=None):
    pb = page_box(sh_or_box) if isinstance(sh_or_box, G.Sheet) else sh_or_box
    c = {"id": cid, "datumId": datum, "pageBox": pb, "rotate": rotate, "points": points, "note": note}
    if extra:
        c.update(extra)
    c["expect"] = evaluate(c)
    return c


def fit_cases():
    sheets = G.build_sheets()
    sh = d203_sheet()
    pb = page_box(sh)
    cases = []

    def pt(i, X, Y, page=None, raw=None, kind="intersection"):
        return point(i, page or sh.page(X, Y), raw or grid_text(sh, X, Y), "WGS84", kind)

    TL, TR, BR, BL = (491000, 4994000), (509000, 4994000), (509000, 4976000), (491000, 4976000)
    MID = (500000, 4985000)

    def check(c, **want):
        e = c["expect"]
        for k, v in want.items():
            got = e["finish"]["state"] if k == "finish" else (e["issue"]["type"] if k == "issue" and e["issue"]
                                                              else e.get(k))
            assert got == v, "%s: %s = %r, wanted %r" % (c["id"], k, got, v)
        return c

    # ---- need more points / next corner
    cases.append(check(make_case("need_more_0", sh, "WGS84", [], "no points: intro text, every corner ties at +inf, "
                                                               "so top-left"), nextCorner="topLeft"))
    cases.append(check(make_case("need_more_1", sh, "WGS84", [pt(1, *TL)], "one point near the top-left, the "
                                                                          "farthest inset corner is bottom-right"),
                       nextCorner="bottomRight"))
    cases.append(check(make_case("need_more_2", sh, "WGS84", [pt(1, *TL), pt(2, 503000, 4978000)],
                                 "two points, top-right is clearly the emptiest corner"), nextCorner="topRight"))

    # ---- D2-03: exact 3, then 4 graded, hold-outs under 1 m
    p3 = [pt(1, *TL), pt(2, *TR), pt(3, *BL)]
    c = check(make_case("d2_03_exact_3", sh, "WGS84", p3,
                        "D2-03: 1:50k UTM sheet at 45 N (zone 32), three corner intersections: exact fit, no grade, "
                        "Finish asks first. holdOut is the 4th corner, must be under 1 m"),
              finish="confirm", grade=None)
    hold_out(c, sh, [("bottom-right intersection", BR), ("centre", MID)])
    cases.append(c)
    p4 = p3 + [pt(4, *BR)]
    c = check(make_case("d2_03_good_4", sh, "WGS84", p4, "D2-03 with the 4th corner: Good, ready"),
              finish="ready", grade="good")
    hold_out(c, sh, [("centre", MID), ("off-grid", (497300, 4989100))])
    cases.append(c)

    # ---- realistic scans: 0.5 pt placement, smooth warp
    layout6 = [TL, TR, BR, BL, (496000, 4982000), (505000, 4990000)]
    rng = Lcg(6)
    p6 = [pt(i + 1, X, Y, page=noisy_page(sh, X, Y, rng, 10.0)) for i, (X, Y) in enumerate(layout6)]
    # point 6 typed as a 6-figure feature: the printed thing sits at the 100 m cell centre
    fX, fY = 505050, 4990050
    p6[5] = pt(6, fX, fY, page=noisy_page(sh, fX, fY, Lcg(66), 10.0), raw=grid_text(sh, 505000, 4990000, 6),
               kind="feature")
    cases.append(check(make_case("clean_6_warp_good", sh, "WGS84", p6,
                                 "6 points, 0.5 pt placement noise, 10 m scan warp, point 6 is a feature: Good, no "
                                 "outlier named (rule 6.4 only looks when RMS > tau)"),
                       finish="ready", grade="good", issue=None))

    def blunder(pts_, number, dE=1000.0):
        p = pts_[number - 1]
        g = p["reference"]["grid"]
        lat, lon = GEO.inv("WGS84", g["zone"], g["south"], g["easting"] + dE, g["northing"])
        raw = grid_text(sh, g["easting"] + dE, g["northing"])
        pts_[number - 1] = point(number, p["page"], raw, "WGS84")
        return pts_

    rng = Lcg(4)
    p4b = blunder([pt(i + 1, X, Y, page=noisy_page(sh, X, Y, rng, 10.0)) for i, (X, Y) in enumerate([TL, TR, BR, BL])], 3)
    cases.append(check(make_case("blunder_1km_n4_poor_add_fifth", sh, "WGS84", p4b,
                                 "point 3 typed 1 km east: n = 4 can't say which, Poor + 'add a 5th point'"),
                       grade="poor", issue="disagreeAddFifth", finish="confirm"))
    rng = Lcg(5)
    sym = [TL, TR, BR, BL, MID]
    p5 = blunder([pt(i + 1, X, Y, page=noisy_page(sh, X, Y, rng, 3.0, 0.3)) for i, (X, Y) in enumerate(sym)], 2)
    c = check(make_case("blunder_1km_n5_symmetric_ambiguous", sh, "WGS84", p5,
                        "4 corners + centre, point 2 (top-right) 1 km off. Dropping 2 OR its opposite corner 4 "
                        "leaves a set the affine fits, so it's 'point 2 or 4', smaller leave-one-out RMS first"),
              grade="poor", issue="ambiguous", finish="confirm")
    assert {c["expect"]["issue"]["first"], c["expect"]["issue"]["second"]} == {2, 4}
    loo = c["expect"]["looRmsM"]
    assert abs(loo["2"] - loo["4"]) > 0.1, "ambiguous pair order must not hinge on noise"
    cases.append(c)
    rng = Lcg(7)
    p6b = blunder([pt(i + 1, X, Y, page=noisy_page(sh, X, Y, rng, 10.0)) for i, (X, Y) in enumerate(layout6)], 4)
    c = check(make_case("blunder_1km_n6_outlier", sh, "WGS84", p6b, "6 points, point 4 typed 1 km east: named"),
              issue="outlier", finish="confirm")
    assert c["expect"]["issue"]["number"] == 4 and c["expect"]["flagged"] == [4]
    cases.append(c)
    rng = Lcg(8)
    p6c = blunder(blunder([pt(i + 1, X, Y, page=noisy_page(sh, X, Y, rng, 10.0)) for i, (X, Y) in
                           enumerate(layout6)], 2), 5, -1000.0)
    cases.append(check(make_case("two_blunders_n6_disagree", sh, "WGS84", p6c,
                                 "points 2 and 5 both 1 km off: no single point fixes it, 'disagree', Poor"),
                       grade="poor", issue="disagree", finish="confirm"))
    # Fair on its own (and disagree on its own) doesn't ask: a strong smooth warp spreads the error everywhere
    amp = None
    for a in range(40, 160, 2):
        rng = Lcg(9)
        pf = [pt(i + 1, X, Y, page=noisy_page(sh, X, Y, rng, float(a))) for i, (X, Y) in enumerate(layout6)]
        e = evaluate({"datumId": "WGS84", "pageBox": pb, "rotate": 0, "points": pf})
        if e["grade"] == "fair" and e["issue"] and e["issue"]["type"] == "disagree":
            r = e["rmsM"] / e["toleranceM"]
            if 1.3 < r < 2.5 and all(v > 1.15 * e["toleranceM"] for v in e["looRmsM"].values()):
                amp = a
                break
    assert amp, "couldn't build the fair/disagree case"
    cases.append(check(make_case("fair_disagree_ready", sh, "WGS84", pf,
                                 "a %d m smooth scan warp: Fair, every leave-one-out is still worse than tau so "
                                 "'disagree', and neither Fair nor disagree asks before Finish" % amp),
                       grade="fair", issue="disagree", finish="ready"))

    # ---- blocked
    zt = [pt(1, *TL), pt(2, *TR), pt(3, *BR), pt(4, *BL)]
    raw = grid_text(sh, BR[0], BR[1], zone=2)
    zt[2] = point(3, zt[2]["page"], raw, "WGS84")
    cases.append(check(make_case("zone_typo_invalid", sh, "WGS84", zt,
                                 "point 3 typed in zone 2 instead of 32 (a dropped digit): its lat/lon is 180 deg "
                                 "from the zone 32 CM, it can't be forwarded into the plane: invalid"),
                       blocked="invalid", finish="blocked"))
    sq = [pt(1, *TL), pt(2, *TR), pt(3, *BL)]
    g = sq[2]["reference"]["grid"]
    raw = grid_text(sh, g["easting"] + 100000, g["northing"])
    sq[2] = point(3, sq[2]["page"], raw, "WGS84")
    c = check(make_case("square_typo_implausible", sh, "WGS84", sq,
                        "point 3's column letter one off (100 km east): the exact 3-point fit is wildly stretched, "
                        "implausible (anisotropy)"), blocked="implausible", finish="blocked")
    assert c["expect"]["anisotropy"] > 3.0
    cases.append(c)
    same = [point(i + 1, sh.page(*xy), grid_text(sh, *TL), "WGS84") for i, xy in enumerate((TL, TR, BL))]
    cases.append(check(make_case("same_reference_three_times_invalid", sh, "WGS84", same,
                                 "the same reference typed for three spread points: the page -> plane map is "
                                 "singular, invalid"), blocked="invalid"))
    plan_box = [[0.0, 0.0], [1000.0, 0.0], [1000.0, 800.0], [0.0, 800.0]]
    s500 = 500 * PT_M
    plan = []
    for i, (x, y) in enumerate(((100.0, 100.0), (900.0, 100.0), (900.0, 700.0), (100.0, 700.0))):
        X, Y = 500000 + round(x * s500), 5000000 + round(y * s500)
        plan.append(point(i + 1, ((X - 500000) / s500, (Y - 5000000) / s500), "32T %d %d" % (X, Y), "WGS84"))
    c = check(make_case("scale_1_500_implausible", plan_box, "WGS84", plan,
                        "a 1:500 site plan: the fit is fine but the scale is outside 1:1,000-1:5,000,000"),
              blocked="implausible")
    assert c["expect"]["scaleDenominator"] < 1000
    cases.append(c)

    # D2-10, the exact fiduciaryFits near_collinear_d2_10 inputs
    tA = [8.8, 0.3, 546000.0, -0.25, 8.8, 4176000.0]
    col = []
    for i, (x, y) in enumerate(((100.0, 50.0), (550.0, 53.0), (1000.0, 49.0))):
        X, Y = G.Ref.apply(tA, x, y)
        la = GEO.inv("WGS84", 10, False, X, Y)[0]
        col.append(point(i + 1, (x, y), G.mgrs(10, False, round(X), round(Y), la), "WGS84"))
    box1100 = [[0.0, 0.0], [1100.0, 0.0], [1100.0, 1100.0], [0.0, 1100.0]]
    cases.append(check(make_case("d2_10_collar_degenerate", box1100, "WGS84", col,
                                 "D2-10, same inputs as pdf_georef.json fiduciaryFits near_collinear_d2_10: three "
                                 "taps along the bottom collar, eigen ratio ~1e-5", extra={
                                     "fromFiduciaryFits": "near_collinear_d2_10"}), blocked="degenerate"))
    colz = list(col)
    t = col[2]["input"]
    # 10 -> 40: same column set and row offset, so it still parses, but 180 deg from zone 10's CM
    colz[2] = point(3, col[2]["page"], "40" + t[2:], "WGS84")
    cases.append(check(make_case("d2_10_collar_zone_typo_still_degenerate", box1100, "WGS84", colz,
                                 "the same collar with point 3 also in the wrong zone: degenerate is checked first "
                                 "(it's a property of the page points, WP1's fitter refuses before fitting), so "
                                 "the user is told to spread the points, not to check zones"),
                       blocked="degenerate"))

    # ---- spread
    cl = [(493000, 4978000), (497000, 4978000), (497000, 4981000), (493000, 4981000)]
    cases.append(check(make_case("spread_low_4", sh, "WGS84", [pt(i + 1, *xy) for i, xy in enumerate(cl)],
                                 "4 good points bunched in the bottom-left fifth of the sheet: Good, but spread is "
                                 "low so Finish asks and the next-corner hint points top-right"),
                       spreadLow=True, finish="confirm", nextCorner="topRight"))
    cases.append(check(make_case("spread_low_exact_3", sh, "WGS84", [pt(i + 1, *xy) for i, xy in enumerate(cl[:3])],
                                 "3 clustered points: both exact and spreadLow, in that order"),
                       spreadLow=True, finish="confirm"))

    # ---- datums, zones
    ed = G.Sheet("ed50", G.Plane.utm(32, False, "ED50"), 495000, 5535000, 10000, 10000, G.S50K)
    eds = []
    for i, (X, Y) in enumerate(((496000, 5536000), (504000, 5536000), (504000, 5544000))):
        eds.append(point(i + 1, ed.page(X, Y), (grid_text(ed, X, Y) if i != 1 else "32U %d %d" % (X, Y)), "ED50"))
    gX, gY = 497000, 5543000
    la, lo = GEO.to_wgs84("ED50", *GEO.inv("ED50", 32, False, gX, gY))
    eds.append(point(4, ed.page(gX, gY), "%.7f, %.7f" % (la, lo), "ED50", override="WGS84"))
    c = check(make_case("ed50_gps_override_d2_05", ed, "ED50", eds,
                        "ED50 sheet (D2-05): MGRS/UTM typed in ED50, point 4 is a GPS fix (WGS84, datumOverride) "
                        "that goes through ED50 fromWGS84 first. checks[] has 32U NA 00000 40000 against PROJ's "
                        "EPSG:23032 truth (under 5 m)"), grade="good", finish="ready")
    A, tau, z, s = c["_fit"]
    g = fit_georef(A, "ED50", z, s, c["pageBox"])
    x, y = ed.page(500000, 5540000)
    x, y = rpt(x), rpt(y)
    w = GEO.g_to_wgs84(g, x, y)
    truth = ed50_truth()
    d = GEO.dist(w[0], w[1], truth[0], truth[1])
    assert d < 5.0
    c["checks"] = [{"label": "32U NA 00000 40000", "page": [x, y], "fitWGS84": {"lat": rdeg(w[0]), "lon": rdeg(w[1])},
                    "truthWGS84": {"lat": rdeg(truth[0]), "lon": rdeg(truth[1])}, "distanceM": rm(d),
                    "toleranceM": 5.0}]
    cases.append(c)

    zc = G.Sheet("zc", G.Plane.utm(55, True, "GDA94"), 766000, 6250000, 20000, 20000, G.S50K)
    z55 = [(768000, 6252000), (784000, 6252000), (784000, 6268000), (770000, 6268000), (776000, 6260000)]
    zp = []
    la, lo = zc.plane.inv(*z55[0])
    zp.append(point(1, zc.page(*z55[0]), grid_text(zc, *z55[0]), "GDA94"))
    la, lo = GEO.inv("GDA94", 55, True, *z55[1])
    zp.append(point(2, zc.page(*z55[1]), mgrs_text("GDA94", la, lo, zone=56), "GDA94"))
    la, lo = GEO.inv("GDA94", 55, True, *z55[2])
    zp.append(point(3, zc.page(*z55[2]), "%.7f, %.7f" % (la, lo), "GDA94"))
    zp.append(point(4, zc.page(*z55[3]), "55H %d %d" % z55[3], "GDA94"))
    zp.append(point(5, zc.page(*z55[4]), grid_text(zc, *z55[4]), "GDA94"))
    cases.append(check(make_case("zone_straddle_55_56", zc, "GDA94", zp,
                                 "GDA94 sheet drawn in zone 55 across 150 E; point 2 is typed in zone 56 and goes "
                                 "through lat/lon into the zone 55 plane (point 1's zone)"),
                       planeZone=55, finish="ready"))
    zd = [p for p in zp if p["number"] != 1]
    c = check(make_case("zone_straddle_point_1_deleted", zc, "GDA94", zd,
                        "same points with point 1 deleted: numbers keep their gap, and the plane zone is now point "
                        "2's typed zone 56 (lowest remaining number)"), planeZone=56)
    cases.append(c)
    cb = sheets["cbr50k"]
    cf = cb.fiducials()
    la, lo = cb.plane.inv(cf[0]["X"], cf[0]["Y"])
    cp = [point(1, cf[0]["page"], "%.7f, %.7f" % (la, lo), "WGS84")]
    cp += [point(i + 2, f["page"], f["label"], "WGS84") for i, f in enumerate(cf[1:])]
    cases.append(check(make_case("first_point_latlon_zone", cb, "WGS84", cp,
                                 "lowest point is lat/lon: zone floor((lon+180)/6)+1 = 55, south from the sign of "
                                 "lat (fiduciaryFits cbr50k_plain_latlon_first)",
                                 extra={"fromFiduciaryFits": "cbr50k_plain_latlon_first"}),
                       planeZone=55, south=True))

    # the WP1 typo set under rule 6.4, same answer as its flaggedOutliers
    sf = sheets["sf"]
    fs = sf.fiducials()
    tp = [(fi["page"], fi["label"]) for fi in fs]
    cxy = (548000, 4179000)
    tp.append((sf.page(*cxy), G.mgrs(10, False, cxy[0], cxy[1], sf.plane.inv(*cxy)[0])))
    tp[2] = (tp[2][0], tp[2][1].replace(" 51000 ", " 51300 "))
    c = check(make_case("fiduciary_typo_outlier", sf, "WGS84", [point(i + 1, pg, raw, "WGS84") for i, (pg, raw) in
                                                                enumerate(tp)],
                        "pdf_georef.json fiduciaryFits sf_plain_typo_outlier (index 2 = point 3, 300 m typo): "
                        "rule 6.4 names point 3, same as flaggedOutliers [2]",
                        extra={"fromFiduciaryFits": "sf_plain_typo_outlier"}), issue="outlier")
    assert c["expect"]["issue"]["number"] == 3
    cases.append(c)

    # /Rotate 90: corners are named as the page is viewed
    r90 = sheets["rot90"]
    cases.append(check(make_case("rotate_90_n0", r90, "WGS84", [], "/Rotate 90 page, no points: top-left AS VIEWED "
                                                                   "is the raw bottom-left corner", rotate=90),
                       nextCorner="topLeft"))
    m = r90.media
    f = min(r90.fiducials(), key=lambda q: math.hypot(q["page"][0] - m[0], q["page"][1] - m[1]))
    cases.append(check(make_case("rotate_90_n1", r90, "WGS84", [point(1, f["page"], f["label"], "WGS84")],
                                 "/Rotate 90, one point near the raw bottom-left (viewed top-left): next is the viewed "
                                 "bottom-right", rotate=90), nextCorner="bottomRight"))

    # A2/B1: first point typed exactly on the antimeridian. floor((lon+180)/6)+1 says 61 at lon 180, the zone
    # is clamped to 60 (and -180 is zone 1 anyway). Pages come straight off the clamped zone's plane
    def antimeridian(cid, lon0, west, zone, note):
        s = G.S50K
        lls = [(-16.5, lon0), (-16.42, lon0 + west * 0.10), (-16.58, lon0 + west * 0.10), (-16.42, lon0 + west * 0.02),
               (-16.58, lon0 + west * 0.03)]
        ens = [GEO.fwd("WGS84", zone, True, la, lo) for la, lo in lls]
        e0, n0 = min(e for e, _ in ens) - 50 * s, min(n for _, n in ens) - 50 * s
        w = (max(e for e, _ in ens) - e0) / s + 50
        h = (max(n for _, n in ens) - n0) / s + 50
        box = [[0.0, 0.0], [rpt(w), 0.0], [rpt(w), rpt(h)], [0.0, rpt(h)]]
        pts = [point(i + 1, ((e - e0) / s, (n - n0) / s), "%.7f, %.7f" % ll, "WGS84")
               for i, (ll, (e, n)) in enumerate(zip(lls, ens))]
        assert pts[0]["reference"]["geographic"]["lon"] == lon0
        return check(make_case(cid, box, "WGS84", pts, note), planeZone=zone, south=True, finish="ready")

    cases.append(antimeridian("first_point_lon_180_zone_60", 180.0, -1, 60,
                              "A2/B1: point 1 is lat/lon at exactly 180 E (Fiji). floor((180+180)/6)+1 = 61 isn't a "
                              "zone: clamped to 60, the fit works, nothing is blocked"))
    cases.append(antimeridian("first_point_lon_minus_180_zone_1", -180.0, 1, 1,
                              "A2/B1: the same at exactly 180 W: zone 1, points east of it"))

    # a GeoPDF being refined, no points yet (entry checks use it with its base)
    cases.append(make_case("geopdf_refine_empty", sf, "WGS84", [], "sf sheet as a GeoPDF, nothing placed yet"))
    return cases, sheets, sh


def hold_out(c, sh, pts):
    A, tau, z, s = c["_fit"]
    g = fit_georef(A, "WGS84", z, s, c["pageBox"])
    out = []
    for label, (X, Y) in pts:
        x, y = sh.page(X, Y)
        x, y = rpt(x), rpt(y)
        w = GEO.g_to_wgs84(g, x, y)
        t = GEO.to_wgs84("WGS84", *sh.plane.inv(X, Y))
        d = GEO.dist(w[0], w[1], t[0], t[1])
        assert d < 1.0, "%s hold-out %s is %.3f m off" % (c["id"], label, d)
        out.append({"label": label, "page": [x, y], "fitWGS84": {"lat": rdeg(w[0]), "lon": rdeg(w[1])},
                    "truthWGS84": {"lat": rdeg(t[0]), "lon": rdeg(t[1])}, "errorM": rm(d), "maxM": 1.0})
    c["holdOut"] = out


# ---- entry checks (s6.7)

def leverage(pages, x0):
    np = GEO.np
    A = np.array([[x, y, 1.0] for x, y in pages])
    v = np.array([x0[0], x0[1], 1.0])
    return float(v @ np.linalg.inv(A.T @ A) @ v)


def georef_json(g, origin):
    return {"origin": origin, "crs": G.crs_json(g["crs"]), "datum": G.datum_json(g["datum"]),
            "crop": [[rpt(x), rpt(y)] for x, y in g["crop"]], "affine": [rsig(v) for v in g["affine"]]}


def georef_back(j):
    """the georef exactly as the json has it"""
    return {"crs": j["crs"], "datum": j["datum"]["id"], "affine": j["affine"], "crop": [tuple(p) for p in j["crop"]]}


def sheet_georef(sh):
    A = [rsig(v) for v in sh.truth_affine()]
    m = sh.crop or sh.media
    crop = [(m[0], m[1]), (m[2], m[1]), (m[2], m[3]), (m[0], m[3])]
    return {"crs": sh.plane.crs(), "datum": sh.plane.datum, "affine": A, "crop": crop}


def entry_checks(cases, sheets, sh):
    by = {c["id"]: c for c in cases}
    out = []

    def run(eid, from_case, pending_page, raw, note, editing=None, base=None, base_provisional=True, want=None):
        c = by[from_case]
        datum = c["datumId"]
        pts = sorted(c["points"], key=lambda p: p["number"])
        pend = point(editing or (max([p["number"] for p in pts], default=0) + 1), pending_page, raw, datum)
        pages = [tuple(p["page"]) for p in pts]
        page = tuple(pend["page"])
        pred = None
        if editing is not None:
            others = [p for p in pts if p["number"] != editing]
            if len(others) >= 3 and eigen_ratio([tuple(p["page"]) for p in others]) >= C["degenerateEigenRatio"]:
                sub = {"datumId": datum, "pageBox": c["pageBox"], "rotate": c["rotate"], "points": others}
                evaluate(sub)
                if sub["_anyfit"]:
                    A, tau, z, s = sub["_anyfit"]
                    pred = ("fitWithoutEditing", fit_georef(A, datum, z, s, c["pageBox"]), tau,
                            leverage([tuple(p["page"]) for p in others], page))
        if pred is None and len(pts) >= 3 and c["_fit"]:
            A, tau, z, s = c["_fit"]
            pred = ("currentFit", fit_georef(A, datum, z, s, c["pageBox"]), tau, leverage(pages, page))
        bj = None
        if base is not None:
            bj = georef_json(base, "provisional" if base_provisional else "adobeVP")
            if pred is None and not base_provisional:
                bg = georef_back(bj)
                x0, y0, x1, y1 = box_corners(c["pageBox"])
                cs = [GEO.g_to_wgs84(bg, *p) for p in ((x0, y0), (x1, y0), (x1, y1), (x0, y1))]
                D = max(GEO.dist(*cs[0], *cs[2]), GEO.dist(*cs[1], *cs[3]))
                pred = ("base", bg, tau_of(D), 0.0)
        typed = resolve(pend, datum)
        tw = GEO.to_wgs84(datum, typed[0], typed[1])
        if pred is None:
            exp = {"predictor": "none", "leverage": None, "toleranceM": None, "predictedWGS84": None,
                   "typedWGS84": {"lat": rdeg(tw[0]), "lon": rdeg(tw[1])}, "distanceM": None, "thresholdM": None,
                   "warn": False, "message": None}
        else:
            kind, g, tau, h = pred
            pw = GEO.g_to_wgs84(g, *page)
            d = GEO.dist(pw[0], pw[1], tw[0], tw[1])
            thr = max(C["entryWarnSigma"] * tau * math.sqrt(1.0 + h), C["entryWarnFloorM"])
            assert abs(d - thr) > 0.05 * thr, "%s sits too close to the threshold" % eid
            exp = {"predictor": kind, "leverage": rsig(h), "toleranceM": rm(tau),
                   "predictedWGS84": {"lat": rdeg(pw[0]), "lon": rdeg(pw[1])},
                   "typedWGS84": {"lat": rdeg(tw[0]), "lon": rdeg(tw[1])}, "distanceM": rm(d),
                   "thresholdM": rm(thr), "warn": d > thr,
                   "message": msg("calibration_warn_far", distance=rm(d)) if d > thr else None}
            if exp["warn"]:
                key("calibration_save_anyway")
        if want is not None:
            assert (exp["predictor"], exp["warn"]) == want, "%s: %r" % (eid, (exp["predictor"], exp["warn"]))
        e = {"id": eid, "fromCase": from_case, "editing": editing,
             "pending": {"page": pend["page"], "input": raw, "reference": pend["reference"], "kind": pend["kind"]},
             "base": None if bj is None else {"provisional": base_provisional, "georef": bj},
             "expect": exp, "note": note}
        out.append(e)

    BR = (509000, 4976000)
    br = sh.page(*BR)
    run("exact3_next_point_ok", "d2_03_exact_3", br, grid_text(sh, *BR),
        "4th point typed right: predicted by the exact 3-point fit with its leverage, no warning",
        want=("currentFit", False))
    run("exact3_next_point_1km_slip", "d2_03_exact_3", br, grid_text(sh, BR[0], BR[1] + 1000),
        "4th point with a 1 km northing slip: warns, Save becomes Save anyway", want=("currentFit", True))
    p2 = by["d2_03_good_4"]["points"][1]
    TR = (509000, 4994000)
    run("edit_point_2_ok", "d2_03_good_4", p2["page"], grid_text(sh, *TR),
        "editing point 2 with the right figures: predicted by the other 3 points' fit (leverage of point 2 "
        "against them)", editing=2, want=("fitWithoutEditing", False))
    run("edit_point_2_300m_slip", "d2_03_good_4", p2["page"], grid_text(sh, TR[0] - 300, TR[1]),
        "editing point 2 with a 300 m slip: warns", editing=2, want=("fitWithoutEditing", True))
    p1 = by["d2_03_exact_3"]["points"][0]
    run("edit_with_only_2_others", "d2_03_exact_3", p1["page"], grid_text(sh, 491000, 4994000),
        "editing point 1 of 3: the other two can't make a fit, so the current fit predicts (h = 1 for an "
        "exact fit)", editing=1, want=("currentFit", False))
    sf = sheets["sf"]
    sfg = sheet_georef(sf)
    fx = sf.fiducials()
    run("geopdf_base_ok", "geopdf_refine_empty", fx[0]["page"], fx[0]["label"],
        "refining a GeoPDF with no points: the embedded georef predicts, h = 0, tau from the page box diagonals "
        "(WGS84 geodesic through the base)", base=sfg, base_provisional=False, want=("base", False))
    run("geopdf_base_1km_slip", "geopdf_refine_empty", fx[0]["page"],
        G.mgrs(10, False, fx[0]["X"] + 1000, fx[0]["Y"], sf.plane.inv(fx[0]["X"], fx[0]["Y"])[0]),
        "same with a 1 km easting slip: warns", base=sfg, base_provisional=False, want=("base", True))
    prov = provisional(page_box(sh), 0, (45.0, 9.0))
    run("provisional_no_check", "need_more_2", sh.page(*BR), grid_text(sh, *BR),
        "2 points on a provisional placement: nothing to predict with, no check", base=prov, base_provisional=True,
        want=("none", False))
    run("degenerate_fit_provisional_base", "d2_10_collar_degenerate", (600.0, 600.0), "10SEG 51000 80000",
        "3 collinear points (blocked) on a provisional placement: no check", base=provisional(
            by["d2_10_collar_degenerate"]["pageBox"], 0, (37.7, -122.4)), base_provisional=True,
        want=("none", False))
    return out


# ---- provisional placement + camera (s7)

def provisional(pb, rotate, centre):
    """PdfGeoreference.provisional(pageBox, rotate, centre): geographic WGS84, upright, nominal 1:50,000"""
    x0, y0, x1, y1 = box_corners(pb)
    lat = min(80.0, max(-80.0, centre[0]))
    lon = wrap180(centre[1])
    me, mn = GEO.ref.metres_per_unit({"kind": "geographic"}, "WGS84", lat)
    mpp = C["provisionalScaleDenominator"] * PT_M
    sx, sy = mpp / me, mpp / mn
    cosr, sinr = {0: (1.0, 0.0), 90: (0.0, -1.0), 180: (-1.0, 0.0), 270: (0.0, 1.0)}[rotate % 360]
    cx, cy = (x0 + x1) / 2.0, (y0 + y1) / 2.0
    a, b, d, e = sx * cosr, -sx * sinr, sy * sinr, sy * cosr
    A = [rsig(v) for v in (a, b, lon - a * cx - b * cy, d, e, lat - d * cx - e * cy)]
    return {"crs": {"kind": "geographic"}, "datum": "WGS84", "affine": A,
            "crop": [(x0, y0), (x1, y0), (x1, y1), (x0, y1)]}


def world(lat, lon):
    """web mercator world coords, zoom 0, 256 units across"""
    x = 256.0 * (lon + 180.0) / 360.0
    y = 256.0 * (1.0 - math.log(math.tan(math.pi / 4.0 + math.radians(lat) / 2.0)) / math.pi) / 2.0
    return x, y


def s_of(g, x, y):
    h = C["cameraJacobianStepPt"]
    W = lambda px, py: world(*GEO.g_to_wgs84(g, px, py))
    ax, ay = W(x + h, y)
    bx, by = W(x - h, y)
    cx, cy = W(x, y + h)
    dx, dy = W(x, y - h)
    j11, j21 = (ax - bx) / (2 * h), (ay - by) / (2 * h)
    j12, j22 = (cx - dx) / (2 * h), (cy - dy) / (2 * h)
    return math.sqrt(abs(j11 * j22 - j12 * j21))


def camera_cases(cases, sheets, sh):
    by = {c["id"]: c for c in cases}
    out = []

    def fitj(cid):
        c = by[cid]
        A, tau, z, s = c["_fit"]
        return georef_json(fit_georef(A, c["datumId"], z, s, c["pageBox"]), "fiduciaries")

    def run(cid, old_j, new_j, cam_page, zoom, heading, note, prov=None, want_clamp=False):
        old, new = georef_back(old_j), georef_back(new_j)
        lat, lon = GEO.g_to_wgs84(old, *cam_page)
        lat, lon = rdeg(lat), rdeg(lon)
        p = GEO.g_to_page(old, lat, lon)
        nl, no = GEO.g_to_wgs84(new, *p)
        raw = zoom + math.log2(s_of(old, *p) / s_of(new, *p))
        z = min(C["cameraZoomMax"], max(C["cameraZoomMin"], raw))
        assert (raw > C["cameraZoomMax"]) == want_clamp
        # the anchor's whole point: same page point under the crosshair afterwards
        back = GEO.g_to_page(new, nl, no)
        assert math.hypot(back[0] - p[0], back[1] - p[1]) < 1e-6
        c = {"id": cid, "old": old_j, "new": new_j,
             "camera": {"lat": lat, "lon": lon, "zoom": zoom, "heading": heading, "viewport": [390.0, 844.0]},
             "expect": {"pagePoint": [rpt(p[0]), rpt(p[1])], "lat": rdeg(nl), "lon": rdeg(no), "zoom": rzoom(z),
                        "unclampedZoom": rzoom(raw), "heading": heading}, "note": note}
        if prov:
            c["provisionalInputs"] = prov
        out.append(c)

    pb = page_box(sh)
    pv = provisional(pb, 0, (45.02, 8.97))
    run("provisional_to_fit", georef_json(pv, "provisional"), fitj("d2_03_exact_3"), (300.0, 900.0), 13.25, 30.0,
        "first refit: provisional 1:50k guess -> the 3-point fit. The page point under the crosshair stays, zoom "
        "moves by log2 of the scale ratio, heading untouched",
        prov={"pageBox": pb, "rotate": 0, "centre": {"lat": 45.02, "lon": 8.97}})
    run("fit_to_fit", fitj("d2_03_exact_3"), fitj("d2_03_good_4"), (640.0, 410.0), 16.5, 0.0,
        "4th point refines the fit: tiny move")
    r90 = sheets["rot90"]
    pb90 = page_box(r90)
    pv90 = provisional(pb90, 90, (37.75, -122.42))
    run("rotate_90_provisional_to_sheet", georef_json(pv90, "provisional"), georef_json(sheet_georef(r90), "fiduciaries"),
        (500.0, 300.0), 14.0, 350.0, "/Rotate 90 page: the provisional placement is upright as viewed, the real "
                                    "georef is the rot90 sheet's own",
        prov={"pageBox": pb90, "rotate": 90, "centre": {"lat": 37.75, "lon": -122.42}})
    sf = sheets["sf"]
    pbs = page_box(sf)
    pvs = provisional(pbs, 0, (37.79, -122.43))
    run("clamped_at_22", georef_json(pvs, "provisional"), georef_json(sheet_georef(sf), "fiduciaries"),
        (400.0, 500.0), 21.75, 12.0, "provisional 1:50k -> the real 1:25k sheet adds ~1 zoom level from 21.75: "
                                    "clamped to 22", want_clamp=True,
        prov={"pageBox": pbs, "rotate": 0, "centre": {"lat": 37.79, "lon": -122.43}})

    caps = []

    def cap(cid, gj, pbx, page, zoom, note, want):
        g = georef_back(gj)
        lat, lon = GEO.g_to_wgs84(g, *page)
        lat, lon = rdeg(lat), rdeg(lon)
        p = GEO.g_to_page(g, lat, lon)
        x0, y0, x1, y1 = box_corners(pbx)
        on = x0 <= p[0] <= x1 and y0 <= p[1] <= y1
        spp = 2.0 ** zoom * s_of(g, *p)
        hint = spp < C["zoomHintScreenPtPerPagePt"]
        assert (on, hint) == want, cid
        assert abs(spp - 1.0) > 0.05
        caps.append({"id": cid, "georef": gj, "pageBox": pbx,
                     "camera": {"lat": lat, "lon": lon, "zoom": zoom, "heading": 0.0, "viewport": [390.0, 844.0]},
                     "expect": {"page": [rpt(p[0]), rpt(p[1])], "onSheet": on, "screenPtPerPagePt": rsig(spp),
                                "zoomHint": hint,
                                "status": msg("calibration_off_sheet") if not on else (
                                    msg("calibration_zoom_hint") if hint else None)},
                     "note": note})

    fj = fitj("d2_03_good_4")
    cap("on_sheet_zoomed_in", fj, pb, (620.0, 700.0), 16.0, "crosshair on the sheet at z16: 1 page pt is several "
                                                             "screen pt, no hint", (True, False))
    cap("zoom_hint", fj, pb, (620.0, 700.0), 12.0, "same spot at z12: 1 page pt < 1 screen pt, the hint shows (it "
                                                  "doesn't block)", (True, True))
    cap("off_sheet", fj, pb, (-40.0, 700.0), 16.0, "crosshair left of the page box: Add point disabled", (False, False))
    cap("provisional_rotate_90", georef_json(pv90, "provisional"), pb90, (200.0, 600.0), 15.0,
        "captured through the provisional /Rotate 90 placement", (True, False))
    return out, caps


# ----------------------------------------------------------------------------
# import_limits.json: decisions, entry states, lifecycle tables
# ----------------------------------------------------------------------------

IMPORT_ERRORS = {
    "tooLarge": ("map_import_too_large", ["limit"]),
    "noSpace": ("map_import_no_space", ["size"]),
    "password": ("map_import_password", []),
    "tooManyPages": ("map_import_too_many_pages", ["limit"]),
    "pageSize": ("map_import_page_size", []),
    "tooComplex": ("map_import_too_complex", []),
    "invalidPdf": ("map_import_invalid_pdf", []),
    "invalidMbtiles": ("map_import_invalid_mbtiles", []),
    "libraryFull": ("map_import_library_full", ["limit"]),
    "locked": ("map_import_unlock_first", []),
    "interrupted": ("map_import_interrupted", []),
    "cancelled": ("map_import_cancelled", []),
    "failed": ("map_import_read_failed", ["detail"]),
}


def decide(page_count, pages, duplicate):
    """contract s9.4 + s9.6. pages = what the inspector found on the scanned pages (0..49)"""
    if duplicate is not None:
        # E9: an unavailable existing entry takes the new hash-verified copy first (relink), then the usual
        return {"action": "activateExisting" if duplicate["existingHasGeoref"] else "calibrateExisting",
                "relink": bool(duplicate.get("existingUnavailable")),
                "page": None, "toast": msg("map_import_duplicate", name=duplicate["name"]), "alert": None,
                "pickerBadges": None}
    valid = [i for i, p in enumerate(pages) if p["state"] == "valid"]
    if valid:
        i = valid[0]
        return {"action": "addAndActivate", "page": i, "alert": None, "pickerBadges": None,
                "toast": msg("map_import_page_used", page=i + 1, pages=page_count) if i != 0 else None}
    declared = [i for i, p in enumerate(pages) if p["state"] == "rejected"]
    if declared:
        if page_count == 1 or declared == [0]:
            return {"action": "addRejected", "page": 0, "toast": None, "pickerBadges": None,
                    "alert": {"message": msg("map_import_georef_rejected", reason=pages[0]["reason"]),
                              "buttons": [key("map_import_calibrate_now"), key("map_import_later")]}}
        return {"action": "pagePicker", "page": None, "toast": None, "alert": None, "pickerBadges": declared}
    if page_count == 1:
        return {"action": "addAndCalibrate", "page": 0, "toast": None, "alert": None, "pickerBadges": None}
    return {"action": "pagePicker", "page": None, "toast": None, "alert": None, "pickerBadges": []}


def decisions():
    V, R, N = (lambda: {"state": "valid"}), (lambda r: {"state": "rejected", "reason": r}), (lambda: {"state": "none"})
    rows = [
        ("single_page_valid", 1, [V()], None, "addAndActivate", "framed, no toast"),
        ("valid_on_page_2_of_3", 3, [N(), V(), N()], None, "addAndActivate", "toast: page 2 of 3"),
        ("first_valid_page_wins", 3, [V(), N(), V()], None, "addAndActivate", "page 1, no toast"),
        ("valid_beats_rejected", 3, [R("rmsGate"), N(), V()], None, "addAndActivate",
         "a usable georef anywhere wins over a broken one on page 1"),
        ("single_page_rejected", 1, [R("rmsGate")], None, "addRejected",
         "added as rejected, not active, alert with Calibrate now / Later"),
        ("only_first_page_declares", 3, [R("malformed"), N(), N()], None, "addRejected",
         "several pages but only page 1 declares one: same as single page"),
        ("rejected_on_page_2_picker", 3, [N(), R("unknownDatum"), N()], None, "pagePicker", "badge on page 2"),
        ("rejected_on_two_pages_picker", 3, [R("malformed"), N(), R("rmsGate")], None, "pagePicker",
         "badges on pages 1 and 3"),
        ("two_pages_both_rejected_picker", 2, [R("malformed"), R("malformed")], None, "pagePicker",
         "the first page isn't the ONLY declaring one"),
        ("single_page_plain", 1, [N()], None, "addAndCalibrate", "needsCalibration, calibration starts (E1)"),
        ("multi_page_plain_picker", 4, [N(), N(), N(), N()], None, "pagePicker", "no badges; cancel deletes the copy"),
        ("pages_past_the_scan_limit", 60, [N()] * 50, None, "pagePicker",
         "only pages 1-50 are scanned for a georef, the rest count as none"),
        ("duplicate_with_georef", 2, None, {"existingHasGeoref": True, "name": "Sydney 1:25k"}, "activateExisting",
         "contentKey already in the library: the copy is deleted, the existing entry activated"),
        ("duplicate_without_georef", 1, None, {"existingHasGeoref": False, "name": "Hut map"}, "calibrateExisting",
         "existing entry has no georef: calibration starts on it"),
        ("duplicate_of_unavailable_entry", 1, None,
         {"existingHasGeoref": True, "existingUnavailable": True, "name": "Sydney 1:25k"}, "activateExisting",
         "E9: the existing entry's file is missing or changed. The new copy has the entry's content key, so it is "
         "kept: one write re-links the entry to it (fileName, byteCount, fileModifiedAtMs), the old file goes, "
         "calibration and bake record stay, then it's activated"),
    ]
    out = []
    for cid, n, pages, dup, want, note in rows:
        o = decide(n, pages, dup)
        assert o["action"] == want, cid
        out.append({"id": cid, "pageCount": n, "pages": pages, "duplicate": dup, "outcome": o, "note": note})
    return out


def prechecks():
    lim = IMPORT
    rows = []

    def run(cid, library, entries, kind, size, free, note):
        if library != "loaded":
            res = ("locked", {})
        elif entries >= lim["maxLibraryEntries"]:
            res = ("libraryFull", {"limit": lim["maxLibraryEntries"]})
        elif size > (lim["pdfMaxBytes"] if kind == "pdf" else lim["mbtilesMaxBytes"]):
            res = ("tooLarge", {"limit": lim["pdfMaxBytes"] if kind == "pdf" else lim["mbtilesMaxBytes"]})
        elif free < size + lim["freeSpaceMarginBytes"]:
            res = ("noSpace", {"size": size + lim["freeSpaceMarginBytes"]})
        else:
            res = None
        exp = None if res is None else {"error": res[0], "args": res[1], "messageKey": key(IMPORT_ERRORS[res[0]][0])}
        if exp and res[0] in ("tooLarge", "noSpace"):
            # OD-F6: byte args go through the shared size rule
            exp["argText"] = {loc: {k: size_text(v, loc) for k, v in res[1].items()} for loc in ("en", "de")}
        rows.append({"id": cid, "library": library, "entryCount": entries, "kind": kind, "sizeBytes": size,
                     "freeBytes": free, "expect": exp, "note": note})

    big = 100 * GiB
    run("ok", "loaded", 3, "pdf", 40 * MiB, big, "nothing in the way")
    run("locked_first", "locked", 100, "pdf", 600 * MiB, 0, "locked wins over every other failure")
    run("corrupt_counts_as_locked", "corrupt", 3, "pdf", 1 * MiB, big, "library not Loaded")
    run("library_full", "loaded", 100, "pdf", 1 * MiB, big, "100 entries already")
    run("library_one_slot_left", "loaded", 99, "pdf", 1 * MiB, big, "99 is fine")
    run("pdf_at_limit", "loaded", 3, "pdf", 512 * MiB, big, "exactly 512 MiB is allowed")
    run("pdf_over_limit", "loaded", 3, "pdf", 512 * MiB + 1, big, "one byte over")
    run("mbtiles_at_limit", "loaded", 3, "mbtiles", 4 * GiB, big, "exactly 4 GiB is allowed")
    run("mbtiles_over_limit", "loaded", 3, "mbtiles", 4 * GiB + 1, big, "one byte over")
    run("space_exactly_enough", "loaded", 3, "pdf", 40 * MiB, 104 * MiB, "free = size + 64 MiB is enough")
    run("space_one_byte_short", "loaded", 3, "pdf", 40 * MiB, 104 * MiB - 1, "{size} is what's needed: size + 64 MiB")
    run("full_before_too_large", "loaded", 100, "pdf", 600 * MiB, 0, "check order: locked, full, size, space")
    return rows


def inspections():
    lim = IMPORT
    rows = []

    def run(cid, openable, encrypted, empty_pw_opens, boxes, note, outcome=None):
        res = outcome
        if res is None:
            if not openable:
                res = "invalidPdf"
            elif encrypted and not empty_pw_opens:
                res = "password"
            elif len(boxes) > lim["maxPages"]:
                res = "tooManyPages"
            else:
                bad = False
                for b in boxes:
                    for box in (b["mediaBox"], b.get("cropBox") or b["mediaBox"]):
                        w, h = box[2] - box[0], box[3] - box[1]
                        if not (lim["pageSideMinPt"] <= w <= lim["pageSideMaxPt"]
                                and lim["pageSideMinPt"] <= h <= lim["pageSideMaxPt"]):
                            bad = True
                res = "pageSize" if bad else None
        exp = None
        if res:
            args = {"limit": lim["maxPages"]} if res == "tooManyPages" else {}
            exp = {"error": res, "args": args, "messageKey": key(IMPORT_ERRORS[res][0])}
        rows.append({"id": cid, "openable": openable, "encrypted": encrypted, "emptyPasswordOpens": empty_pw_opens,
                     "pageCount": len(boxes), "pages": boxes if len(boxes) <= 3 else
                     {"allPages": boxes[0], "count": len(boxes)}, "expect": exp, "note": note})

    a4 = {"mediaBox": [0.0, 0.0, 595.0, 842.0], "cropBox": None}
    run("ok", True, False, False, [a4], "a plain A4 page")
    run("not_a_pdf", False, False, False, [], "can't be opened at all")
    run("password", True, True, False, [a4], "needs a user password")
    run("encrypted_empty_password_ok", True, True, True, [a4], "owner-password only: opens with the empty password")
    run("500_pages_ok", True, False, False, [a4] * 500, "the limit itself is fine")
    run("501_pages", True, False, False, [a4] * 501, "one page too many")
    run("side_36_ok", True, False, False, [{"mediaBox": [0.0, 0.0, 36.0, 14400.0], "cropBox": None}],
        "both limits are inclusive")
    run("side_under_36", True, False, False, [{"mediaBox": [0.0, 0.0, 35.99, 500.0], "cropBox": None}],
        "MediaBox side under 36 pt")
    run("crop_side_over_14400", True, False, False,
        [a4, {"mediaBox": [0.0, 0.0, 20000.0, 20000.0], "cropBox": [0.0, 0.0, 14400.01, 800.0]}],
        "any page, either box: page 2's boxes are both outside")
    run("crop_inside_media_too_big", True, False, False,
        [{"mediaBox": [0.0, 0.0, 20000.0, 800.0], "cropBox": [0.0, 0.0, 1000.0, 800.0]}],
        "the MediaBox counts even when the CropBox is fine")
    return rows


def entry_states():
    rows = []

    def run(cid, entry, file_status, draft_points, note):
        pdf = entry.get("pdf")
        # an entry re-adopted by a corrupt-library rebuild whose PDF didn't inspect has pageCount 0: it stays
        # unavailable whatever its file stamp says, only Delete (S2)
        if file_status != "ok" or (pdf is not None and pdf["pageCount"] == 0):
            state = "unavailable"
        elif entry["kind"] == "mbtiles":
            state = "derived" if entry.get("derivedFromId") else "offlineTiles"
        elif pdf.get("manual"):
            state = "calibrated"
        elif pdf.get("embedded"):
            state = "geoPDF"
        elif pdf.get("embeddedIssue"):
            state = "rejected"
        else:
            state = "needsCalibration"
        effective = None
        if pdf:
            effective = "manual" if pdf.get("manual") else ("embedded" if pdf.get("embedded") else None)
        can_active = state in ("geoPDF", "calibrated", "offlineTiles", "derived")
        if state == "unavailable":
            sub = msg("map_state_unavailable")
        elif draft_points is not None and entry["kind"] == "pdf":
            sub = msg("map_state_draft", points=draft_points)
        else:
            sub = {"geoPDF": lambda: msg("map_state_geopdf"),
                   "calibrated": lambda: (msg("map_state_calibrated", points=pdf["manual"]["n"],
                                              rms=pdf["manual"]["rmsM"]) if pdf["manual"].get("rmsM") is not None
                                          else msg("map_state_calibrated_exact", points=pdf["manual"]["n"])),
                   "rejected": lambda: msg("map_state_rejected"),
                   "needsCalibration": lambda: msg("map_state_needs_calibration"),
                   "offlineTiles": lambda: msg("map_state_offline_tiles"),
                   "derived": lambda: msg("map_state_derived", name=entry["parentName"])}[state]()
        tap = "none" if state == "unavailable" else ("activate" if can_active else "calibrate")
        menu = []
        if entry["kind"] == "pdf" and state != "unavailable":
            menu.append(key("map_action_calibrate"))
            if pdf["pageCount"] > 1:
                menu.append(key("map_action_choose_page"))
            if effective:
                menu.append("generateOfflineTiles")
            if pdf.get("manual") and pdf.get("embedded"):
                menu.append(key("map_action_use_embedded"))
        menu.append(key("map_action_delete"))
        rows.append({"id": cid, "entry": entry, "fileStatus": file_status, "draftPoints": draft_points,
                     "expect": {"state": state, "effectiveGeoref": effective, "canBeDurableActive": can_active,
                                "subtitle": sub, "rowTap": tap, "menu": menu}, "note": note})

    pdf = lambda **k: {"kind": "pdf", "pdf": dict({"pageCount": 1, "embedded": False, "embeddedIssue": None,
                                                     "manual": None}, **k)}
    run("geopdf", pdf(embedded=True), "ok", None, "embedded georef only")
    run("calibrated_graded", pdf(manual={"n": 5, "rmsM": 4.2}), "ok", None, "manual calibration with an RMS")
    run("calibrated_exact", pdf(manual={"n": 3, "rmsM": None}), "ok", None, "3 points: no RMS, 'unchecked'")
    run("calibrated_over_embedded", pdf(embedded=True, manual={"n": 4, "rmsM": 2.0}, pageCount=3), "ok", None,
        "manual wins, 'use the PDF's own georeferencing' is offered, multi-page so 'choose page' too")
    run("rejected", pdf(embeddedIssue="rmsGate"), "ok", None, "declared but unusable georef")
    run("needs_calibration", pdf(), "ok", None, "plain PDF")
    run("needs_calibration_with_draft", pdf(), "ok", 2, "a draft's subtitle replaces the state one")
    run("calibrated_with_draft", pdf(manual={"n": 4, "rmsM": 3.0}), "ok", 6, "refining: the draft subtitle shows")
    run("missing_file", pdf(embedded=True), "missing", None, "unavailable overrides everything")
    run("size_mismatch", pdf(manual={"n": 4, "rmsM": 3.0}), "sizeOrMtimeMismatch", 3,
        "unavailable even with a draft; only Delete is offered")
    run("recovered_uninspectable_pdf", pdf(pageCount=0), "ok", None,
        "S2 rebuild adopted a PDF that failed inspection: pageCount 0, unavailable even though the file is fine, "
        "Delete only")
    run("mbtiles", {"kind": "mbtiles", "derivedFromId": None}, "ok", None, "imported MBTiles")
    run("baked_tiles", {"kind": "mbtiles", "derivedFromId": "6f1c2d3e-0000-4000-8000-000000000001",
                        "parentName": "Sydney 1:25k"}, "ok", None, "tiles baked from a PDF entry")
    return rows


def draft_active_rows():
    """T3/C1/C5/C6: what the draft's active flag is after each step. draft: none | active | inactive | deleted"""
    rows = [
        ("begin_add_writes_active", "none", ["start", "beginAdd"], "active", "running", None,
         "beginAdd writes the draft with pending, active = true"),
        ("cancel_entry_stays_active", "none", ["start", "beginAdd", "cancelEntry"], "active", "running", None,
         "pending cleared, still in the session"),
        ("add_cancel_then_clean_leave", "none", ["start", "beginAdd", "cancelEntry", "leave"], "inactive", "ended",
         None, "C1: state equals the start seed so leave ends at once, but a draft exists: rewrite it active = false "
               "or E3 resumes it every launch"),
        ("add_undo_then_clean_leave", "none", ["start", "beginAdd", "savePoint", "undo", "leave"], "inactive",
         "ended", None, "C1: same after add + undo"),
        ("clean_leave_no_draft", "none", ["start", "leave"], "none", "ended", None, "nothing to write"),
        ("dirty_leave_keep", "none", ["start", "beginAdd", "savePoint", "leave", "leaveKeep"], "inactive", "ended",
         None, "calibration_leave_keep"),
        ("dirty_leave_discard", "none", ["start", "beginAdd", "savePoint", "leave", "leaveDiscard"], "deleted",
         "ended", None, "calibration_leave_discard"),
        ("dirty_leave_continue", "none", ["start", "beginAdd", "savePoint", "leave", "leaveContinue"], "active",
         "running", None, "calibration_leave_continue is cancel"),
        ("suspend_dirty", "none", ["start", "beginAdd", "savePoint", "suspend"], "inactive", "ended",
         msg("calibration_paused"), "s2.8"),
        ("suspend_clean_with_draft", "none", ["start", "beginAdd", "cancelEntry", "suspend"], "inactive", "ended",
         msg("calibration_paused"), "C1: suspend rewrites an existing draft active = false even when not dirty"),
        ("suspend_clean_no_draft", "none", ["start", "suspend"], "none", "ended", msg("calibration_paused"),
         "nothing to write"),
        ("prompt_resume_writes_active", "inactive", ["start", "resumePromptResume"], "active", "running", None,
         "C6 (iOS): Resume writes active = true straight away, before any mutation"),
        ("prompt_start_over", "inactive", ["start", "resumePromptStartOver"], "deleted", "running", None,
         "draft deleted, the seed is used; the next mutation writes a new active draft"),
        ("auto_resume_writes_active", "active", ["launchAutoResume"], "active", "running", None,
         "C6: E3 rewrites active = true (and reopens draft.pending)"),
        ("process_death_keeps_active", "none", ["start", "beginAdd", "savePoint", "processDeath"], "active",
         "ended", None, "what E3 is for"),
        ("finish_success_deletes", "none", ["start", "savePoint3", "finish"], "deleted", "ended",
         msg("calibration_done_exact"), "s2.6"),
        ("first_datum_pick_is_seed", "none", ["startPlainPdf", "chooseDatumFromInitialSheet", "leave"], "none",
         "ended", None, "C5: the datum picked on the s2.3 step 12 sheet at 0 points is part of the start seed: not "
                         "dirty, no draft, no leave dialog"),
        ("datum_change_later_is_mutation", "none", ["start", "savePoint", "setDatum"], "active", "running",
         msg("calibration_datum_changed", datum="ED50"), "any later datum change is an undoable mutation"),
    ]
    return [{"id": i, "draftBefore": b, "steps": s, "expect": {"draft": d, "session": sess, "toast": t}, "note": n}
            for i, b, s, d, sess, t, n in rows]


def auto_resume_rows():
    """E3 at launch / first Loaded restore (M10, C2, C6, C8, F3, OD-F1)"""
    rows = []

    def run(cid, draft, entry="ok", library="loaded", suspect="none", note=""):
        resume = (draft is not None and draft["active"] and entry == "ok" and library == "loaded"
                  and suspect == "none")
        if library == "locked":
            action = "deferUntilLoaded"
        elif resume:
            action = "resume"
        else:
            action = "skip"
        exp = {"action": action,
               "draftAfter": None if draft is None else ("active" if draft["active"] else "inactive"),
               "frameSheet": resume, "reopenPending": bool(resume and draft.get("pending")),
               "toast": msg("calibration_resumed", points=draft["points"]) if resume and draft["points"] > 0 else None}
        rows.append({"id": cid, "draft": draft, "entry": entry, "library": library, "crashSuspect": suspect,
                     "expect": exp, "note": note})

    run("resume", {"active": True, "points": 4, "pending": False},
        note="frames the sheet like an E1/E2 start (OD-F1), then calibration_off_sheet still wins the status line")
    run("resume_reopens_pending", {"active": True, "points": 3, "pending": True},
        note="C6 (Android): the entry card reopens on draft.pending")
    run("resume_zero_points_no_toast", {"active": True, "points": 0, "pending": True},
        note="C6 (iOS): no calibration_resumed toast at 0 points")
    run("inactive_draft", {"active": False, "points": 4, "pending": False}, note="kept for the resume prompt only")
    run("no_draft", None)
    run("suspect_other_entry", {"active": True, "points": 4, "pending": False}, suspect="otherEntry",
        note="M10/C2: any pending crash suspect skips it; the draft stays active for the next launch")
    run("suspect_this_entry", {"active": True, "points": 4, "pending": False}, suspect="thisEntry",
        note="M10")
    run("suspect_this_preview", {"active": True, "points": 4, "pending": False}, suspect="thisEntryPreview",
        note="C8: the guard was armed with the previewed (non-durable) entry's token, so that entry is the suspect")
    run("library_locked", {"active": True, "points": 4, "pending": False}, library="locked",
        note="F3: runs after the first Loaded restore (unlock or Retry), same rules then")
    run("library_corrupt", {"active": True, "points": 4, "pending": False}, library="corrupt",
        note="start precondition: Loaded")
    run("entry_unavailable", {"active": True, "points": 4, "pending": False}, entry="unavailable",
        note="start precondition: file present; the draft stays")
    run("entry_missing", {"active": True, "points": 4, "pending": False}, entry="missing",
        note="reconcile prunes the draft once its contentKey isn't in the library")
    return rows


def choose_page_rows():
    """Layers Choose page (E1, E4, E14)"""
    rows = [
        ("same_page_noop", 0, 0, True, "valid", True,
         {"confirm": None, "write": False, "then": "none"}, "E1: picking the current page does nothing"),
        ("manual_asks_first", 0, 2, True, "none", False,
         {"confirm": key("map_change_page_confirm"), "write": True, "then": "calibrate"},
         "E1: a hand calibration is only wiped after the confirm"),
        ("no_manual_no_confirm", 0, 2, False, "none", False,
         {"confirm": None, "write": True, "then": "calibrate"}, "no calibration to lose"),
        ("to_geopdf_page_activates", 0, 1, True, "valid", False,
         {"confirm": key("map_change_page_confirm"), "write": True, "then": "activate"},
         "E4 (Android): a page with a valid georef is activated, framed"),
        ("active_entry_to_plain_page", 1, 0, False, "none", True,
         {"confirm": None, "write": True, "then": "calibrate", "activeOnline": True},
         "the active entry loses its georef: the same write sets active = online(preferredOnlineStyle)"),
    ]
    return {"pickerMessage": msg("map_choose_page_message", pages=3),
            "note": "E14: the picker opened from Layers uses map_choose_page_message; map_import_choose_page_message "
                    "('none has usable georeferencing') is for the import picker only. The write is the s8.2 change "
                    "page transition (manual, bake and draft go)",
            "rows": [{"id": i, "currentPage": c, "pickedPage": p, "hasManual": m, "pickedPageGeoref": g,
                      "entryIsActive": a, "expect": e, "note": n} for i, c, p, m, g, a, e, n in rows]}


# 3.0.1: legacy causes where the read was authenticated (the key was there) but something in the old
# stores can't be read or converted, now or on any later try. These used to block forever (S3), now
# they salvage: write what converts, adopt the rest, freeze the old stores, never clean up
LEGACY_UNCERTAIN = ("corrupt", "quarantinedOnly", "retainedCorrupt", "retainedQuarantinedOnly", "sessionInvalid",
                    "pdfHashMismatch", "pdfUnconvertible", "v1PointsUnrebuildable")
# SafeStore moves an undecodable selector aside on the read, so the next pass only sees the .corrupt copy
LEGACY_AFTER_READ = {"corrupt": "quarantinedOnly", "retainedCorrupt": "retainedQuarantinedOnly"}
# what D8 can clear: a live legacy store is there. a quarantine copy is never cleared
LEGACY_CLEARABLE = ("readable", "namesNothing", "locked", "corrupt", "retainedCorrupt", "sessionInvalid",
                    "pdfHashMismatch", "pdfUnconvertible", "v1PointsUnrebuildable")
LEGACY_PLATFORMS = {"retainedCorrupt": ["android"], "retainedQuarantinedOnly": ["android"],
                    "v1PointsUnrebuildable": ["ios"]}
LEGACY_CODES = {
    "none": "no legacy store and no quarantine copy of one",
    "readable": "every legacy store authenticates, decodes and converts",
    "locked": "the mission-data key (or the keychain/keystore behind it) fails during the legacy read",
    "namesNothing": "an authenticated legacy read that names no file that still exists (S4)",
    "corrupt": "the active-map selector file is there but won't authenticate or decode (or is plaintext after "
               "sealed-only): the read quarantines it to <name>.corrupt-<epoch>",
    "quarantinedOnly": "only <selector>.corrupt-<epoch> is left (2.x or an earlier 3.x pass quarantined it), "
                       "iOS also: the selector was sealed here before and no library was ever written",
    "retainedCorrupt": "Android: retained_imported_map_source.json is there but won't authenticate or decode",
    "retainedQuarantinedOnly": "Android: only retained_imported_map_source.json.corrupt-<epoch> is left",
    "sessionInvalid": "a legacy PDF session record is stored but won't unseal with the key available, won't "
                      "decode or fails validation (iOS active_pdf_v1, Android prefs active_pdf / pdf_calibrations)",
    "pdfHashMismatch": "the legacy session's PDF is on disk but no longer hashes to the stored content key",
    "pdfUnconvertible": "the legacy PDF is on disk but won't hash (IO), won't open, lacks the session's page, "
                        "can't be linked or copied, or lies outside the managed directories",
    "v1PointsUnrebuildable": "iOS: a v1 calibration whose PDFKit display space can't be rebuilt, so its points "
                             "can't be put in raw page space (they used to be dropped silently, wp4-ios-8)",
}


def restore_plan(g):
    """one launch / unlock / Retry restore, 3.0.1 rules (s8.2 r1 + the 3.0.1 amendment)"""
    lf, key_, legacy, flag = g["libraryFile"], g["missionKey"], g["legacy"], g["recoveryPreservesOrphans"]

    def out(status, migration, cleanup, clear, issue, notice=None, flag_after=None):
        usable = status in ("loaded", "empty")
        return {"status": status, "migration": migration, "reconcile": cleanup, "bakeSweep": cleanup,
                "draftPrune": cleanup, "clearLegacy": clear, "issue": issue, "notice": notice,
                "recoveryPreservesOrphans": flag_after if usable else None,
                "importAllowed": usable, "basemapChangeAllowed": usable}

    def blocked():
        return out("migrationPending", "blocked", False, False, "lockedRetry")

    def write(migration, clear, notice, flag_after, has_drafts):
        # drafts first, then the one library write. either failing writes nothing that counts and clears nothing
        if g["writes"] == "libraryFails" or (g["writes"] == "draftFails" and has_drafts):
            return blocked()
        return out("loaded", migration, not flag_after, clear, None, notice, flag_after)

    if key_ == "locked":
        return out("locked", "none", False, False, "lockedRetry")
    if lf == "ok":
        # D8 only on a library that may clean up; a recovery-flagged one leaves the old stores frozen
        return out("loaded", "none", not flag, (not flag) and legacy in LEGACY_CLEARABLE, None, flag_after=flag)
    if lf in ("unreadable", "newerSchema") or g["corruptSibling"] or g["writtenBefore"]:
        return out("corrupt", "none", False, False, "corruptRetry")
    if g["ledgerOnly"]:
        # 3.0.2 (F2, Android): the authenticated ledger names the library but there's no file and no marker, so a
        # first write died between its ledger mark and the rename. Old stores still waiting means it was the
        # migration's write: salvage (keeps names and calibrations that convert, deletes nothing, cleans nothing
        # up), never an authoritative run or a Corrupt that strands them behind a rebuild. With no old store
        # nothing says what it was, so Corrupt as before (its Retry rebuild deletes nothing either)
        if legacy == "none":
            return out("corrupt", "none", False, False, "corruptRetry")
        if legacy == "locked":
            return blocked()
        return write("salvage", False, "recovered", True, legacy != "namesNothing")
    # a genuine Empty from here on
    if legacy == "none":
        if g["managedFiles"]:
            # never an authoritative cleanup while files are there: adopt them instead (S2 rules)
            return write("adoptOrphans", False, "recovered", True, False)
        return out("empty", "none", True, False, None, flag_after=False)
    if legacy == "locked":
        return blocked()
    if legacy == "readable":
        return write("run", True, None, False, True)
    if legacy == "namesNothing":
        return write("writeEmptyAndClear", True, None, False, False)
    assert legacy in LEGACY_UNCERTAIN, legacy
    return write("salvage", False, "recovered", True, True)


def state_after(g, e):
    """what's on disk for the next restore (Retry, unlock or the next launch)"""
    n = dict(g)
    if e["migration"] in ("run", "writeEmptyAndClear", "salvage", "adoptOrphans"):
        n["libraryFile"] = "ok"
        n["recoveryPreservesOrphans"] = e["recoveryPreservesOrphans"]
    if e["clearLegacy"]:
        n["legacy"] = "none"
    elif e["migration"] in ("blocked", "salvage"):
        n["legacy"] = LEGACY_AFTER_READ.get(g["legacy"], g["legacy"])
    return n


def library_load_rows():
    """s8.2 r1 + 3.0.1: what a launch / unlock / Retry restore does (S1-S4, D8, F3, salvage, orphan adoption)"""
    rows = []

    def run(cid, given, note, platforms=None):
        e = restore_plan(given)
        nxt = restore_plan(state_after(given, e))
        e["nextRestore"] = {k: nxt[k] for k in ("status", "migration", "reconcile", "clearLegacy", "issue",
                                                "notice", "recoveryPreservesOrphans", "importAllowed")}
        # the loop the 3.0.0 bugs lived in: a second pass must never turn a protected state into a cleanup
        if e["migration"] in ("salvage", "adoptOrphans", "blocked") or e["recoveryPreservesOrphans"]:
            assert not e["reconcile"] and not nxt["reconcile"] and not nxt["clearLegacy"], cid
        assert e["status"] != "empty" or not given["managedFiles"], cid
        # a ledger-only library (Android) never authorises cleanup, whatever is or isn't around it
        assert not given["ledgerOnly"] or (not e["reconcile"] and not e["clearLegacy"]), cid
        platforms = platforms or LEGACY_PLATFORMS.get(given["legacy"], ["ios", "android"])
        rows.append({"id": cid, "platforms": platforms, "given": given, "expect": e, "note": note})

    def g(file="absent", key_="unlocked", sibling=False, written=False, legacy="none", recovery=False,
          files=False, writes="ok", ledger=False):
        return {"libraryFile": file, "missionKey": key_, "corruptSibling": sibling, "writtenBefore": written,
                "ledgerOnly": ledger, "legacy": legacy, "recoveryPreservesOrphans": recovery, "managedFiles": files,
                "writes": writes}

    run("loaded", g("ok"), "the normal launch")
    run("loaded_rebuilt", g("ok", recovery=True),
        "S1/S2: sealed recovery provenance survives cold launch; unknown map files, bakes and drafts are preserved")
    run("loaded_legacy_left", g("ok", legacy="readable"),
        "D8: legacy stores still there after a Loaded restore are cleared (the drafts were saved before the write)")
    run("locked", g("ok", key_="locked"), "F3: issue with Retry; Retry and mission-data unlock re-run migration + restore")
    run("undecryptable", g("unreadable"), "quarantined to .corrupt-<epoch>; Retry rebuilds (libraryRetry)")
    run("newer_schema", g("newerSchema"), "same as unreadable")
    run("vanished_with_quarantine", g("absent", sibling=True), "S1: the second load after a quarantine is NOT empty")
    run("vanished_after_written", g("absent", written=True),
        "S1: a library this device wrote before and is gone now is corrupt, never empty")
    run("first_launch", g(), "genuinely empty, nothing to migrate and no file in the managed directories: "
                             "authoritative, and the cleanup it authorises has nothing to delete")
    run("migrate", g(legacy="readable"), "drafts, then the library write, then clear legacy, then restore as Loaded")
    run("legacy_locked", g(legacy="locked"), "no migration, no deletion, Retry or unlock tries again")
    run("legacy_quarantined", g(legacy="corrupt", files=True),
        "3.0.1 (wp4-ios-1, wp4-android-1/7): the read quarantines the selector, it's never 'no legacy left' and "
        "no longer blocks forever: salvage writes what converts, adopts every other map file, freezes the old "
        "stores, recoveryPreservesOrphans=true")
    run("legacy_pdf_unconvertible", g(legacy="pdfUnconvertible", files=True),
        "3.0.1: was S3 blocked forever. Salvage: the PDF is adopted where it is (re-inspected, pageCount 0 if it "
        "won't inspect), its session stays frozen in the legacy store")
    run("legacy_names_nothing", g(legacy="namesNothing"),
        "S4: authenticated legacy read that names no existing file: write an empty library, clear legacy")
    run("first_launch_with_orphan_files", g(files=True),
        "3.0.1 (gap-android-blockers-empirical-2): Empty with no legacy but files in the managed directories is "
        "never authoritative. They're adopted by the S2 rules in one write with recoveryPreservesOrphans=true")
    run("first_launch_orphans_write_fails", g(files=True, writes="libraryFails"),
        "the adoption write failed: nothing changes, Retry; never an empty authoritative restore")
    run("legacy_quarantined_only", g(legacy="quarantinedOnly", files=True),
        "3.0.1: the pass after a quarantine (Retry, relaunch, or a selector 2.x quarantined): only the .corrupt "
        "copy is left and it still counts as legacy, so salvage, never an empty restore that wipes the files")
    run("legacy_retained_corrupt", g(legacy="retainedCorrupt", files=True),
        "Android: the retained selector won't read; salvage keeps (adopts) the retained pack")
    run("legacy_retained_quarantined_only", g(legacy="retainedQuarantinedOnly", files=True),
        "Android: only the retained selector's .corrupt copy is left; the next read must not drop the retained "
        "pack from the inputs and reconcile it away")
    run("legacy_session_invalid", g(legacy="sessionInvalid", files=True),
        "was S3 blocked forever: the record stays frozen, the PDF file is adopted")
    run("legacy_pdf_hash_mismatch", g(legacy="pdfHashMismatch", files=True),
        "was S3 blocked forever: the changed file is adopted and re-inspected; the old calibration was for other "
        "bytes so it isn't applied, it stays frozen in the legacy store")
    run("legacy_v1_points_unrebuildable", g(legacy="v1PointsUnrebuildable", files=True),
        "iOS (wp4-ios-8): the PDF converts without its v1 calibration; the parked points stay in the frozen "
        "legacy calibration store instead of being cleared")
    run("legacy_salvage_write_fails", g(legacy="corrupt", files=True, writes="libraryFails"),
        "salvage write failed: nothing changes, Retry. The read already quarantined the selector, the next pass "
        "sees quarantinedOnly and still salvages")
    run("migrate_draft_save_fails", g(legacy="readable", writes="draftFails"),
        "wp4-android-8: drafts are saved BEFORE the library write; a failed save writes no library and clears "
        "nothing, Retry")
    run("migrate_library_write_fails", g(legacy="readable", writes="libraryFails"),
        "drafts already saved, library write failed: nothing cleared, Retry redoes it (same draft keys overwritten)")
    run("loaded_rebuilt_legacy_left", g("ok", recovery=True, legacy="readable"),
        "3.0.1: a recovery-flagged library never runs D8, the old stores stay frozen")
    run("loaded_after_salvage", g("ok", recovery=True, legacy="quarantinedOnly", files=True),
        "the launch after a salvage: Loaded, imports and basemap changes work, nothing cleaned up or cleared")
    run("loaded_quarantine_copy_only", g("ok", legacy="quarantinedOnly"),
        "an ordinary library next to an old .corrupt copy: normal cleanup, the copy is never cleared")
    # 3.0.2 (F2): a library write that fails now leaves no record (Android marks its ledger after the temp file is
    # flushed, iOS after the rename), so migrate_library_write_fails and friends hold through the real store. What's
    # left is the instant between Android's ledger mark and the rename
    run("vanished_after_written_legacy_left", g("absent", written=True, legacy="readable"),
        "3.0.2: a completed write on record (iOS keychain record, Android the sealed-only marker file) and the "
        "library gone is Corrupt even with old stores still there. Only the Android ledger-only state below salvages")
    run("ledger_only_legacy_left", g(ledger=True, legacy="readable"),
        "3.0.2 (F2): Android's first library write died between the ledger mark and the rename while the old "
        "stores wait: a pending migration that salvages (2.x names and calibrations that convert are kept), never "
        "Corrupt and never an authoritative run", platforms=["android"])
    run("ledger_only_legacy_locked", g(ledger=True, legacy="locked"),
        "3.0.2: same, key locked during the legacy read: pending, Retry or unlock", platforms=["android"])
    run("ledger_only_no_legacy", g(ledger=True, files=True),
        "3.0.2: a ledger-only library with no old store left proves nothing either way: Corrupt as before, Retry "
        "rebuilds and deletes nothing", platforms=["android"])
    return {"rows": rows,
            "legacyCodes": LEGACY_CODES,
            "givenFields": {
                "managedFiles": "after s9.8 interrupted-import recovery, the managed directories (iOS ImportedMaps + "
                                "offline_tiles, Android pdf_maps + mbtiles + offline_tiles) hold a regular file the "
                                "reconcile or the bake sweep would delete (map file, sidecar, .partial, bake) that no "
                                "in-flight import owns",
                "writes": "ok | draftFails (a migration draft save returns false or throws) | libraryFails "
                          "(the sealed library write fails). 3.0.2: on both platforms a failing write leaves no "
                          "written-before record, so tests make the real store's write fail (a full disk, an "
                          "unwritable directory or a seam below SafeStore), not a stub above it",
                "writtenBefore": "a completed library write is on record while the file is absent: iOS the keychain "
                                 "sealed-only record (SealedMigrationPolicy.requiresSealed), Android the "
                                 ".imported_map_library.json.sealed-only-v1 marker file (plus the ledger)",
                "ledgerOnly": "Android only: the authenticated sealed-only ledger (DataKey sentinel) names the "
                              "library label but neither the library file nor its .sealed-only-v1 marker exists. "
                              "SafeStore.writeAtomically marks the ledger after the temp file is flushed and before "
                              "the rename, and the marker after the rename, so only a kill or a failed rename "
                              "between the two leaves this. iOS has no such state"},
            "retry": {
                "corrupt": {"action": "rebuild", "message": key("map_library_corrupt_message"),
                            "adoptedName": msg("map_recovered_name", number=1),
                            "steps": ["re-adopt every opaque map file no in-flight import owns (not tacmap-bake-*)",
                                      "contentKey re-hashed, displayName map_recovered_name {n} in mtime order",
                                      "PDF: s9.5 inspection (marker + watchdog) + s9.6 first valid page, no picker; "
                                      "failed inspection -> pageCount 0 (unavailable, Delete only)",
                                      "MBTiles: validated, failed -> still adopted, unavailable",
                                      "active = online(default style); one library write",
                                      "write fails -> nothing changes, still corrupt",
                                      "after the write the library is Loaded with recoveryPreservesOrphans=true: no reconcile/sweep/prune"],
                            "deletes": "no imported map file, bake or draft; the sealed flag disables cleanup on later restores and transitions too"},
                "locked": {"action": "reload", "note": "re-run migration + restore, same as mission-data unlock"},
                "blocked": {"action": "reload", "note": "a failed draft or library write: Retry re-runs the same "
                                                       "migration from the start, nothing was cleared"}},
            "salvage": {
                "when": "Empty library, legacy present, read authenticated, at least one cause in legacyCodes is "
                        "uncertain (corrupt, quarantinedOnly, retained*, sessionInvalid, pdfHashMismatch, "
                        "pdfUnconvertible, v1PointsUnrebuildable)",
                "steps": ["convert every legacy input that converts, with the s8.2 migration rules (link to the opaque "
                          "name on iOS, manual calibration, drafts for leftover points)",
                          "a legacy input that doesn't convert is not converted and its file is not linked",
                          "adopt every other map file in the managed directories by the S2 rebuild rules (re-hash, "
                          "s9.5 inspection with marker + watchdog, s9.6 first valid page, pageCount 0 / unavailable "
                          "on failure, MBTiles validated); files referenced by a converted entry, and the old names "
                          "of converted links, aren't adopted twice",
                          "adopted names: map_recovered_name {n} in mtime order, except an iOS file with a "
                          "non-opaque legacy name, which keeps its file stem as the name and is linked to "
                          "ImportedMaps/map-<uuid>.<ext> like the migration (adopted in place if link and copy fail)",
                          "active = the converted active entry if it can be the durable active selection, else "
                          "online(preferred style if one was readable, else the default style)",
                          "recoveryPreservesOrphans = true",
                          "save the drafts, then one library write; either failing -> nothing changes, migrationPending, Retry",
                          "after the write: unlink only the old names of converted links; clear NO legacy store; "
                          "show map_library_recovered_notice once (it replaces map_migration_uncalibrated)"],
                "notice": msg("map_library_recovered_notice"),
                "deletes": "nothing: no map file, bake, draft, legacy store or quarantine copy"},
            "adoptOrphans": {
                "when": "Empty library, no legacy state at all, managedFiles",
                "steps": ["S2 rebuild over the managed directories (same candidates, names, inspection and "
                          "MBTiles validation)", "active = online(default style)", "recoveryPreservesOrphans = true",
                          "one library write; failing -> nothing changes, migrationPending, Retry",
                          "show map_library_recovered_notice once"],
                "notice": msg("map_library_recovered_notice"),
                "deletes": "nothing"},
            "rule": "reconcile, the bake sweep and the draft prune only ever run on a state read back as loaded "
                    "(or a genuine first-launch empty whose managed directories are empty), or the result of a "
                    "library write made from one. Never from a state built in memory to stand in for a locked, "
                    "corrupt or blocked one. Rebuilt, salvaged and adopted states persist "
                    "recoveryPreservesOrphans=true and never authorize cleanup or D8; explicit deletion still "
                    "removes known owned files. Locked forever is not an outcome: only a locked key or a failed "
                    "write keeps a restore pending, and Retry or unlock tries again"}


def import_pipeline():
    """E2, E5, M7, OD-F5"""
    return {"order": ["precheck", "copyAndHash", "dedupe", "inspect", "decision", "probe", "write"],
            "duplicateSkips": ["inspect", "decision", "probe"],
            "cancelVisible": ["copying", "reading"],
            "cancelRechecked": ["after copyAndHash", "after inspect", "after probe"],
            "onCancel": {"discardCopy": True, "write": False, "toast": msg("map_import_cancelled")},
            "errors": "every s9.2/s9.5/M7 failure is an alert with OK (OD-F5); cancelled and duplicate stay toasts",
            "pageUsedToast": "map_import_page_used after the write, never before the probe (E7)"}


def lifecycle():
    return {
        "draftActive": draft_active_rows(),
        "autoResume": auto_resume_rows(),
        "suspectStart": {"event": "startCalibration (E1/E2) on the pending crash suspect",
                         "expect": "guard resolved as Open Anyway first, then calibration starts and publishes",
                         "note": "C2/M10: picking the suspect in Layers counts as Open Anyway too"},
        "choosePage": choose_page_rows(),
        "importPipeline": import_pipeline(),
        "entryPoints": [
            {"id": "E1", "triggers": ["import: single-page PDF with no usable georef",
                                      "import: page picked in the page picker",
                                      "rejected-georef alert: map_import_calibrate_now"]},
            {"id": "E2", "triggers": ["Layers row tap on needsCalibration or rejected",
                                      "row menu map_action_calibrate on any PDF entry, GeoPDFs included"]},
            {"id": "E3", "triggers": ["launch: a draft with active == true whose entry exists, after the library "
                                      "restore"], "toast": msg("calibration_resumed", points=4)},
        ],
        "startPreconditions": ["library Loaded (not locked or corrupt)", "the entry's file is present",
                               "not already calibrating"],
        "seed": [
            {"draft": True, "manual": True, "use": "draft"},
            {"draft": False, "manual": True, "use": "manual"},
            {"draft": False, "manual": False, "use": "empty",
             "datumSheet": "shown first unless the PDF has an embedded datum (preselected)"},
        ],
        "resume": [
            {"draftExists": False, "draftActive": False, "draftDiffersFromSeed": False, "action": "none"},
            {"draftExists": True, "draftActive": False, "draftDiffersFromSeed": False, "action": "none"},
            {"draftExists": True, "draftActive": False, "draftDiffersFromSeed": True, "action": "prompt",
             "prompt": {"title": key("calibration_resume_title"),
                        "message": msg("calibration_resume_message", points=4, age="3 days ago"),
                        "buttons": {key("calibration_resume"): "use the draft",
                                    key("calibration_start_over"): "delete the draft, use the seed"}}},
            {"draftExists": True, "draftActive": True, "draftDiffersFromSeed": True, "action": "silentResume"},
        ],
        "leave": [
            {"dirty": False, "action": "endSession", "draft": "if one exists, rewrite it active = false (C1)"},
            {"dirty": True, "action": "dialog", "title": key("calibration_leave_title"),
             "message": key("calibration_leave_message"),
             "buttons": [{"key": key("calibration_leave_keep"), "effect": "draft.active = false, end"},
                         {"key": key("calibration_leave_discard"), "effect": "delete the draft, end",
                          "destructive": True},
                         {"key": key("calibration_leave_continue"), "effect": "cancel", "cancel": True}]},
        ],
        "afterLeave": {"preview": "restore the previous durable source, published without reframing",
                       "georeferencedEntry": "render its effective georef again"},
        "suspend": {"when": "the active map source changes to a different entry mid-session",
                    "effect": "end the session, keep the draft with active = false (rewritten even when not dirty, C1)",
                    "notASuspend": "an unlock / Retry restore that republishes the same durable selection while a "
                                   "calibration display is up (C3): no republish, the session goes on",
                    "toast": msg("calibration_paused")},
        "commit": {"check": "entryId, contentKey and pageIndex all match the target, else nothing is written",
                   "write": "one library write: entry.pdf.manual = {datumId, points, georef, n, rmsM, grade, "
                            "savedAtMs} and active = entry",
                   "success": ["delete the draft (best effort)", "end the session, restore heading-up and chrome",
                               {"n3": msg("calibration_done_exact"),
                                "n4plus": msg("calibration_done", points=5, rms=3.4)}],
                   "failure": {"stay": True, "draftKept": True, "alert": msg("calibration_save_failed"),
                               "buttons": ["Retry", "Not Now"]}},
        "finishConfirm": {"title": key("calibration_finish_confirm_title"),
                          "buttons": [{"key": key("calibration_add_more"), "role": "cancel", "default": True},
                                      {"key": key("calibration_finish_anyway")}]},
        "draft": {"maxDrafts": CALIBRATION["maxDrafts"], "eviction": "LRU by updatedAtMs",
                  "keyFormat": "<contentKey>#<pageIndex>", "writeFailure": msg("calibration_draft_unsaved"),
                  "deletedOn": ["finish success", "discard", "entry delete", "reconcile without its contentKey",
                                "change page"]},
        "mutations": {"undoDepth": CALIBRATION["undoDepth"], "numbering": "number = nextNumber++, never "
                                                                          "renumbered, delete leaves a gap",
                      "deleteToast": msg("calibration_point_deleted", number=3),
                      "datumToast": msg("calibration_datum_changed", datum="ED50"),
                      "maxPointsStatus": msg("calibration_max_points", max=CALIBRATION["maxCalibrationPoints"])},
        "libraryWrites": [
            {"transition": "select online", "write": "active = online(style)"},
            {"transition": "activate entry", "write": "active = entry(id)"},
            {"transition": "import commit", "write": "add entry, plus active if it has a georef or is MBTiles"},
            {"transition": "commit calibration", "write": "set manual; active = entry"},
            {"transition": "revert to embedded", "write": "manual = nil"},
            {"transition": "change page", "write": "pageIndex, embedded, embeddedIssue; manual = nil; draft deleted"},
            {"transition": "delete", "write": "remove the entry and its derived entries; active = "
                                              "online(preferredOnlineStyle) if any of them was active",
             "confirm": {"title": msg("map_delete_title", name="Sydney 1:25k"), "message": key("map_delete_message")}},
        ],
    }


# ----------------------------------------------------------------------------
# MBTiles admission (s3.1 + the 3.0.1 amendment): tiles and metadata may be a table or a view
# ----------------------------------------------------------------------------

MBT_KNOWN = {"name": (128, True), "format": (32, True), "minzoom": (16, False), "maxzoom": (16, False),
             "bounds": (256, False)}
MBT_TILE_AGGREGATE = (
    "SELECT MIN(CASE WHEN typeof(zoom_level)='integer' THEN zoom_level END), "
    "MAX(CASE WHEN typeof(zoom_level)='integer' THEN zoom_level END), COUNT(*), "
    "SUM(CASE WHEN typeof(zoom_level)='integer' AND typeof(tile_column)='integer' AND typeof(tile_row)='integer' "
    "THEN CASE WHEN zoom_level BETWEEN 0 AND 30 AND tile_column BETWEEN 0 AND ((1 << zoom_level) - 1) "
    "AND tile_row BETWEEN 0 AND ((1 << zoom_level) - 1) THEN 1 ELSE 0 END ELSE 0 END) FROM tiles")
# the reference stands in for the apps' 30 s admission budget with a VM step budget, same verdicts
MBT_REFERENCE_PROGRESS_CALLS = 20000

# 3.0.2 (SEC-1): file SQL never runs on a read. A view is only admitted as a plain projection of one ordinary
# table or an equi-join of two, and every table a read touches is ordinary (not virtual) with no generated column.
# Every connection is hardened before its first statement. The caps are per value and per schema statement
MBT_MAX_TILE_BYTES = 4 * 1024 * 1024
# iOS SQLITE_LIMIT_LENGTH; Android has no sqlite3_limit, so its view path checks the same byte length itself
MBT_MAX_VALUE_BYTES = MBT_MAX_TILE_BYTES + 65536
# iOS SQLITE_LIMIT_SQL_LENGTH (a longer schema statement fails the schema load); Android scans sqlite_master
MBT_MAX_SCHEMA_SQL_BYTES = 100000
# Android PRAGMA hard_heap_limit, process wide, only where SQLite >= 3.31 (API 31+)
MBT_ANDROID_HEAP_LIMIT = 128 * 1024 * 1024
MBT_ANDROID_HEAP_LIMIT_MIN_SQLITE = "3.31.0"
MBT_GENERATED_COLUMNS_MIN_SQLITE = "3.31.0"
# sqlite.org/lang_keywords.html (147) plus the two literals a bare word can turn into. A bare identifier in a view
# may not be one of these, so nothing the shape check reads as a column can be a keyword that makes a value
SQLITE_KEYWORDS = (
    "ABORT ACTION ADD AFTER ALL ALTER ALWAYS ANALYZE AND AS ASC ATTACH AUTOINCREMENT BEFORE BEGIN BETWEEN BY "
    "CASCADE CASE CAST CHECK COLLATE COLUMN COMMIT CONFLICT CONSTRAINT CREATE CROSS CURRENT CURRENT_DATE "
    "CURRENT_TIME CURRENT_TIMESTAMP DATABASE DEFAULT DEFERRABLE DEFERRED DELETE DESC DETACH DISTINCT DO DROP EACH "
    "ELSE END ESCAPE EXCEPT EXCLUDE EXCLUSIVE EXISTS EXPLAIN FAIL FILTER FIRST FOLLOWING FOR FOREIGN FROM FULL "
    "GENERATED GLOB GROUP GROUPS HAVING IF IGNORE IMMEDIATE IN INDEX INDEXED INITIALLY INNER INSERT INSTEAD "
    "INTERSECT INTO IS ISNULL JOIN KEY LAST LEFT LIKE LIMIT MATCH MATERIALIZED NATURAL NO NOT NOTHING NOTNULL NULL "
    "NULLS OF OFFSET ON OR ORDER OTHERS OUTER OVER PARTITION PLAN PRAGMA PRECEDING PRIMARY QUERY RAISE RANGE "
    "RECURSIVE REFERENCES REGEXP REINDEX RELEASE RENAME REPLACE RESTRICT RETURNING RIGHT ROLLBACK ROW ROWS "
    "SAVEPOINT SELECT SET TABLE TEMP TEMPORARY THEN TIES TO TRANSACTION TRIGGER UNBOUNDED UNION UNIQUE UPDATE "
    "USING VACUUM VALUES VIEW VIRTUAL WHEN WHERE WINDOW WITH WITHOUT").split()
assert len(SQLITE_KEYWORDS) == 147 and len(set(SQLITE_KEYWORDS)) == 147
MBT_RESERVED = sorted(set(SQLITE_KEYWORDS) | {"TRUE", "FALSE"})
MBT_VIEW_WHITESPACE = " \t\r\n"
MBT_VIEW_PUNCT = "(),.*="
MBT_VIEW_QUOTES = {'"': '"', "[": "]", "`": "`"}


class _MbtReject(Exception):
    pass


class _ShapeReject(Exception):
    pass


def _vs_tokens(sql):
    """the view tokenizer: ASCII words, three quoted identifier forms, six punctuation marks, ASCII whitespace.
    Anything else (digits starting a token, string literals, comments, operators, parameters, ';', non-ASCII)
    is a token reject. It never has to understand SQL it doesn't admit"""
    out, i, n = [], 0, len(sql)
    while i < n:
        ch = sql[i]
        if ch in MBT_VIEW_WHITESPACE:
            i += 1
        elif ch in MBT_VIEW_PUNCT:
            out.append(("p", ch))
            i += 1
        elif ch.isascii() and (ch.isalpha() or ch == "_"):
            j = i + 1
            while j < n and sql[j].isascii() and (sql[j].isalnum() or sql[j] == "_"):
                j += 1
            out.append(("w", sql[i:j]))
            i = j
        elif ch in MBT_VIEW_QUOTES:
            j = sql.find(MBT_VIEW_QUOTES[ch], i + 1)
            body = sql[i + 1:j] if j > i else ""
            # non-empty printable ASCII, no quote or bracket inside, and no quote or bracket right after the
            # closing mark: SQLite reads "a""b" as one name a"b, a reader that saw a and b could check the wrong table
            if j < 0 or not body or any(not (0x20 <= ord(c) <= 0x7E) or c in '"[]`' for c in body) \
                    or (j + 1 < n and sql[j + 1] in '"[]`'):
                raise _ShapeReject("token")
            out.append(("q", body))
            i = j + 1
        else:
            raise _ShapeReject("token")
    return out


def view_shape(sql, relation):
    """the base table names a plain view reads, or _ShapeReject(tooLong | token | shape). The grammar:
    CREATE VIEW <relation> [(ident, ...)] AS SELECT col [, col ...] FROM table
    [[INNER | LEFT [OUTER] | CROSS] JOIN table (ON equalities | USING (ident, ...)) | , table WHERE equalities]
    where col = * | ident.* | colref [[AS] ident], colref = ident | ident.ident, table = ident [[AS] ident],
    equalities = colref = colref [AND colref = colref ...], bare or in one pair of parens,
    ident = a bare word that isn't reserved, or a quoted identifier. Nothing may follow.
    3.0.2 SEC-M1-SHADOW: no IF NOT EXISTS. SQLite drops it when it stores a view, so only a hand edited schema has
    it, and the duplicate it lets SQLite skip is how a decoy row sat behind the live view admission never read.
    3.0.2 SHADOW-PARITY-2: the comma join and the AND are still an equi-join of two tables on column refs, and
    they're how gdal2mbtiles (FROM map, images WHERE ...) and tippecanoe/tile-join (ON ... and ...) store tiles"""
    if sql is None or len(sql.encode("utf-8")) > MBT_MAX_SCHEMA_SQL_BYTES:
        raise _ShapeReject("tooLong" if sql is not None else "shape")
    toks = _vs_tokens(sql)
    pos = [0]

    def peek():
        return toks[pos[0]] if pos[0] < len(toks) else None

    def kw(word):
        t = peek()
        if t and t[0] == "w" and t[1].upper() == word:
            pos[0] += 1
            return True
        return False

    def punct(ch):
        t = peek()
        if t == ("p", ch):
            pos[0] += 1
            return True
        return False

    def need(ok):
        if not ok:
            raise _ShapeReject("shape")

    def maybe_ident():
        t = peek()
        if t and (t[0] == "q" or (t[0] == "w" and t[1].upper() not in MBT_RESERVED)):
            pos[0] += 1
            return t[1]
        return None

    def ident():
        name = maybe_ident()
        need(name is not None)
        return name

    def colref():
        ident()
        if punct("."):
            ident()

    def result_column():
        if punct("*"):
            return
        ident()
        if punct("."):
            if punct("*"):
                return
            ident()
        if kw("AS"):
            ident()
        else:
            maybe_ident()

    tables = []

    def table_ref():
        tables.append(ident())
        if kw("AS"):
            ident()
        else:
            maybe_ident()

    def equalities():
        paren = punct("(")
        while True:
            colref()
            need(punct("="))
            colref()
            if not kw("AND"):
                break
        if paren:
            need(punct(")"))

    need(kw("CREATE"))
    need(kw("VIEW"))
    need(ident().lower() == relation)
    if punct("("):
        ident()
        while punct(","):
            ident()
        need(punct(")"))
    need(kw("AS"))
    need(kw("SELECT"))
    result_column()
    while punct(","):
        result_column()
    need(kw("FROM"))
    table_ref()
    joined = False
    if punct(","):
        # the comma join gdal2mbtiles writes, its WHERE is only the join's equalities
        table_ref()
        need(kw("WHERE"))
        equalities()
    elif kw("INNER") or kw("CROSS"):
        need(kw("JOIN"))
        joined = True
    elif kw("LEFT"):
        kw("OUTER")
        need(kw("JOIN"))
        joined = True
    elif kw("JOIN"):
        joined = True
    if joined:
        table_ref()
        if kw("ON"):
            equalities()
        elif kw("USING"):
            need(punct("("))
            ident()
            while punct(","):
                ident()
            need(punct(")"))
        else:
            raise _ShapeReject("shape")
    need(peek() is None)
    return tables


def table_declares(sql, name):
    """True when a table's sqlite_master.sql makes exactly the ordinary table `name`: the words CREATE TABLE (first two
    ASCII words, so not CREATE VIRTUAL TABLE), then one identifier, a viewShape quoted identifier or a word that isn't
    reserved (a bare word needs whitespace before it), equal to name ASCII case-insensitively and not followed by a
    quote mark or a dot. 3.0.2 SEC-M1-SHADOW: the identifier closes two ways a row admission reads could differ from
    the object SQLite runs. IF NOT EXISTS (SQLite never stores it, only a hand edit does, and the duplicate it skips
    sits behind a live object of the same name), and a row whose name column says one table while its sql makes
    another, which SQLite without the schema name cross-check (older builds, older Android) loads without complaint"""
    m = re.match(r"[ \t\r\n]*([A-Za-z]+)[ \t\r\n]+([A-Za-z]+)", sql or "")
    if not m or m.group(1).upper() != "CREATE" or m.group(2).upper() != "TABLE":
        return False
    i, n = m.end(), len(sql)
    spaced = i < n and sql[i] in MBT_VIEW_WHITESPACE
    while i < n and sql[i] in MBT_VIEW_WHITESPACE:
        i += 1
    if i < n and sql[i] in MBT_VIEW_QUOTES:
        j = sql.find(MBT_VIEW_QUOTES[sql[i]], i + 1)
        ident = sql[i + 1:j] if j > i else ""
        if j < 0 or not ident or any(not (0x20 <= ord(c) <= 0x7E) or c in '"[]`' for c in ident):
            return False
        end = j + 1
    elif spaced and i < n and sql[i].isascii() and (sql[i].isalpha() or sql[i] == "_"):
        j = i + 1
        while j < n and sql[j].isascii() and (sql[j].isalnum() or sql[j] == "_"):
            j += 1
        ident, end = sql[i:j], j
        if ident.upper() in MBT_RESERVED:
            return False
    else:
        return False
    if end < n and sql[end] in '"[]`.':
        return False
    return name.isascii() and ident.lower() == name.lower()


def _mbt_base_table(conn, name):
    """what every relation a read touches has to be: one ordinary table, no generated column"""
    rows = conn.execute("SELECT type, sql FROM sqlite_master WHERE name = ? COLLATE NOCASE LIMIT 2", (name,)).fetchall()
    if len(rows) != 1 or rows[0][0] != "table" or not table_declares(rows[0][1], name):
        raise _MbtReject("base")
    # needs SQLite >= 3.26 for table_xinfo; generated columns need 3.31, older readers can't even parse them
    if any(h in (2, 3) for (h,) in conn.execute("SELECT hidden FROM pragma_table_xinfo(?)", (name,))):
        raise _MbtReject("generated")


def _mbt_prefix(blob, length, characters, truncates):
    """the bounded UTF-8 prefix rule both readers use (s3.1)"""
    blob = bytes(blob or b"")
    if length is None or (not truncates and length > characters * 4) or b"\x00" in blob:
        raise _MbtReject("metadata")
    truncated = length > len(blob)
    for dropped in range(0, (min(3, len(blob)) if truncated else 0) + 1):
        try:
            text = blob[:len(blob) - dropped].decode("utf-8")
        except UnicodeDecodeError:
            continue
        if not truncates and len(text) > characters:
            raise _MbtReject("metadata")
        return text[:characters]
    raise _MbtReject("metadata")


def _mbt_zoom(text):
    if len(text) > 16 or not re.fullmatch(r"(?:0|[1-9][0-9]?)", text.strip()) or int(text.strip()) > 30:
        raise _MbtReject("metadata")
    return int(text.strip())


def _mbt_blob_prefix(conn, column, rowid, characters, truncates):
    """the table path: incremental blob by rowid, like iOS (Android's substr(CAST) by rowid gives the same bytes)"""
    with conn.blobopen("metadata", column, rowid, readonly=True) as b:
        length = len(b)
        prefix = b.read(min(length, characters * 4))
    return _mbt_prefix(prefix, length, characters, truncates)


def _mbt_admit(conn, probes, start_budget):
    types = {}
    for rel in ("metadata", "tiles"):
        # 3.0.2 SEC-M1-SHADOW: NOCASE, the way SQLite resolves FROM tiles. A case variant row (TILES) is a second
        # object by that name and fails closed, the same as base tables
        found = conn.execute("SELECT type, sql FROM sqlite_master WHERE name = ? COLLATE NOCASE LIMIT 2",
                             (rel,)).fetchall()
        if len(found) != 1 or found[0][0] not in ("table", "view"):
            raise _MbtReject("relation")
        types[rel] = found[0][0]
        # 3.0.2: a view is admitted only as a plain projection or equi-join, checked on its text before anything
        # reads it, so no expression from the file ever runs
        if found[0][0] == "view":
            try:
                bases = view_shape(found[0][1], rel)
            except _ShapeReject:
                raise _MbtReject("shape")
        else:
            bases = [rel]
        for base in bases:
            _mbt_base_table(conn, base)
    # 3.0.2 (F3): the admission budget is for views only. A table's aggregate is one scan the file size bounds
    if "view" in types.values():
        start_budget()
    if conn.execute("PRAGMA encoding").fetchone()[0].upper() != "UTF-8":
        raise _MbtReject("encoding")
    known, values = [], {}
    if types["metadata"] == "table":
        rows = conn.execute("SELECT rowid, typeof(name), typeof(value) FROM metadata LIMIT 65").fetchall()
        if len(rows) > 64:
            raise _MbtReject("rows")
        for rowid, name_type, value_type in rows:
            if name_type != "text":
                raise _MbtReject("metadata")
            k = _mbt_blob_prefix(conn, "name", rowid, 32, True).lower()
            if k not in MBT_KNOWN:
                continue
            if value_type != "text" or k in known:
                raise _MbtReject("metadata")
            known.append(k)
            values[k] = _mbt_blob_prefix(conn, "value", rowid, *MBT_KNOWN[k])
    else:
        # key-addressed path: a view has no rowid. A name or known value over MBT_MAX_VALUE_BYTES fails here
        # (SQLITE_LIMIT_LENGTH on iOS and in this reference, an explicit length check on Android)
        rows = conn.execute("SELECT typeof(name), typeof(value), length(CAST(name AS BLOB)), "
                            "substr(CAST(name AS BLOB), 1, 128) FROM metadata LIMIT 65").fetchall()
        if len(rows) > 64:
            raise _MbtReject("rows")
        for name_type, value_type, name_len, name_prefix in rows:
            if name_type != "text":
                raise _MbtReject("metadata")
            k = _mbt_prefix(name_prefix, name_len, 32, True).lower()
            if k not in MBT_KNOWN:
                continue
            if value_type != "text" or k in known:
                raise _MbtReject("metadata")
            known.append(k)
        for k in known:
            chars, truncates = MBT_KNOWN[k]
            hit = conn.execute("SELECT typeof(value), length(CAST(value AS BLOB)), substr(CAST(value AS BLOB), 1, ?) "
                               "FROM metadata WHERE lower(name) = ? LIMIT 2", (chars * 4, k)).fetchall()
            if len(hit) != 1 or hit[0][0] != "text":
                raise _MbtReject("metadata")
            values[k] = _mbt_prefix(hit[0][2], hit[0][1], chars, truncates)
    lo, hi, count, valid = conn.execute(MBT_TILE_AGGREGATE).fetchone()
    if not all(isinstance(v, int) for v in (lo, hi, count, valid)) or count <= 0 or count != valid:
        raise _MbtReject("tiles")
    minimum = _mbt_zoom(values["minzoom"]) if "minzoom" in values else lo
    maximum = _mbt_zoom(values["maxzoom"]) if "maxzoom" in values else hi
    if minimum > maximum:
        raise _MbtReject("metadata")
    if "bounds" in values:
        parts = values["bounds"].split(",")
        try:
            b = [float(p.strip()) for p in parts]
        except ValueError:
            raise _MbtReject("metadata")
        if len(b) != 4 or not all(math.isfinite(v) for v in b) or not (-180 <= b[0] < b[2] <= 180 and -90 <= b[1] < b[3] <= 90):
            raise _MbtReject("metadata")
    out = {"accepted": True, "minZoom": minimum, "maxZoom": maximum,
           "name": (values.get("name") or "").strip()[:128] or None,
           "format": (values.get("format") or "").strip().lower()[:32] or None}
    tiles = []
    for z, x, y in probes.get("tiles", []):
        hit = None
        if minimum <= z <= maximum:
            where = (z, x, (1 << z) - 1 - y)
            # length first, the payload only when it's a blob of 1 byte to 4 MiB (both readers)
            n = conn.execute("SELECT CASE WHEN typeof(tile_data)='blob' THEN length(tile_data) END FROM tiles "
                             "WHERE zoom_level=? AND tile_column=? AND tile_row=? LIMIT 1", where).fetchone()
            if n and n[0] is not None and 0 < n[0] <= MBT_MAX_TILE_BYTES:
                row = conn.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=? "
                                   "LIMIT 1", where).fetchone()
                hit = bytes(row[0]).hex() if row and row[0] is not None else None
        tiles.append({"z": z, "x": x, "y": y, "hex": hit})
    if tiles:
        out["tiles"] = tiles
    ext = {}
    for k in probes.get("extensions", []):
        try:
            if types["metadata"] == "table":
                hit = conn.execute("SELECT rowid, typeof(value) FROM metadata WHERE lower(name) = ? LIMIT 2",
                                   (k,)).fetchall()
                ext[k] = (_mbt_blob_prefix(conn, "value", hit[0][0], 128, False)
                          if len(hit) == 1 and hit[0][1] == "text" else None)
            else:
                hit = conn.execute("SELECT typeof(value), length(CAST(value AS BLOB)), substr(CAST(value AS BLOB), "
                                   "1, 512) FROM metadata WHERE lower(name) = ? LIMIT 2", (k,)).fetchall()
                ext[k] = _mbt_prefix(hit[0][2], hit[0][1], 128, False) if len(hit) == 1 and hit[0][0] == "text" else None
        except (_MbtReject, sqlite3.DataError):
            ext[k] = None
    if ext:
        out["extensions"] = ext
    out["relations"] = types
    return out


def mbtiles_build(statements):
    """the pack's bytes: statements in order on one fresh connection with Python's sqlite3, committed and closed"""
    with tempfile.TemporaryDirectory() as scratch:
        path = os.path.join(scratch, "pack.mbtiles")
        build = sqlite3.connect(path)
        try:
            for s in statements:
                build.execute(s)
            build.commit()
        finally:
            build.close()
        with open(path, "rb") as fh:
            return fh.read()


def mbtiles_reference(statements, probes=None, pack=None):
    """build the pack in a scratch file with Python's sqlite3 (or take its bytes as pack), then open it again read
    only and hardened the way both readers are (3.0.2): value and schema-statement caps, trusted_schema and
    automatic indexes off. A fresh connection, so the schema is parsed under the cap like a real open"""
    with tempfile.TemporaryDirectory() as scratch:
        path = os.path.join(scratch, "pack.mbtiles")
        with open(path, "wb") as fh:
            fh.write(mbtiles_build(statements) if pack is None else pack)
        conn = sqlite3.connect("file:%s?mode=ro" % path, uri=True)
        try:
            conn.setlimit(sqlite3.SQLITE_LIMIT_LENGTH, MBT_MAX_VALUE_BYTES)
            conn.setlimit(sqlite3.SQLITE_LIMIT_SQL_LENGTH, MBT_MAX_SCHEMA_SQL_BYTES)
            conn.execute("PRAGMA trusted_schema=OFF")
            conn.execute("PRAGMA automatic_index=OFF")
            calls = [0]

            def tick():
                calls[0] += 1
                return 1 if calls[0] > MBT_REFERENCE_PROGRESS_CALLS else 0

            def start_budget():
                conn.set_progress_handler(tick, 1000)
            try:
                return _mbt_admit(conn, probes or {}, start_budget)
            except _MbtReject as r:
                return {"accepted": False, "rejectedAt": str(r)}
            except sqlite3.OperationalError as e:
                return {"accepted": False, "rejectedAt": "budget" if "interrupt" in str(e) else "sql"}
            except sqlite3.DataError:
                # string or blob too big: a value over the cap on the view path
                return {"accepted": False, "rejectedAt": "length"}
            except sqlite3.DatabaseError as e:
                return {"accepted": False, "rejectedAt": "schema" if "malformed database schema" in str(e) else "sql"}
        finally:
            conn.close()


def _sql_text(v):
    return "'" + v.replace("'", "''") + "'"


def _mbt_row_sql(row):
    """one fixture metadata row as SQL values (the same encodings the platform harnesses use)"""
    k = _sql_text(row["key"])
    if "integerValue" in row:
        v = str(row["integerValue"])
    elif "textBytesHex" in row:
        v = "CAST(X'%s' AS TEXT)" % row["textBytesHex"]
    elif "valueRepeat" in row:
        v = _sql_text(row["valueRepeat"][0] * row["valueRepeat"][1])
    else:
        v = "CAST(X'%s' AS TEXT)" % row["value"].encode("utf-8").hex()
    return k, v


# no declared column types, like both platform harnesses: affinity would turn 0 into '0' and hide the case
MBT_TABLES = ["CREATE TABLE metadata (name, value)",
              "CREATE TABLE tiles (zoom_level, tile_column, tile_row, tile_data)"]
MBT_VARIANTS = [
    {"id": "metadataView", "sql": ["ALTER TABLE metadata RENAME TO metadata_base",
                                   "CREATE VIEW metadata AS SELECT name, value FROM metadata_base"]},
    {"id": "tilesView", "sql": ["ALTER TABLE tiles RENAME TO tiles_base",
                                "CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles_base"]},
]
MBT_VARIANTS.append({"id": "bothViews", "sql": MBT_VARIANTS[0]["sql"] + MBT_VARIANTS[1]["sql"]})


def _mbt_check_vectors(cases, tile_cases, ext_cases):
    """the reference must agree with every hand-listed vector, and every variant must agree with the table"""
    def meta_pack(rows, unknown=0):
        s = list(MBT_TABLES) + ["INSERT INTO tiles VALUES (0, 0, 0, X'010203')"]
        s += ["INSERT INTO metadata VALUES ('extension_%d', 'value')" % i for i in range(unknown)]
        s += ["INSERT INTO metadata VALUES (%s, %s)" % _mbt_row_sql(r) for r in rows]
        return s

    def zoom_pack(c):
        if c["storageType"] == "integer":
            v = str(c["value"])
        elif c["storageType"] == "real":
            v = "CAST(%r AS REAL)" % c["value"]
        else:
            v = _sql_text(c["valueRepeat"][0] * c["valueRepeat"][1] if "valueRepeat" in c else c["value"])
        return list(MBT_TABLES) + ["INSERT INTO tiles VALUES (%s, 0, 0, X'010203')" % v]

    def same(builder, want, name, probes=None):
        base = mbtiles_reference(builder, probes)
        want(base)
        for var in MBT_VARIANTS:
            got = mbtiles_reference(builder + var["sql"], probes)
            for k in ("accepted", "minZoom", "maxZoom", "name", "format", "extensions"):
                assert got.get(k) == base.get(k), (name, var["id"], k, got.get(k), base.get(k))

    for c in cases:
        def want(r, c=c):
            assert r["accepted"] == c["accepted"], (c["id"], r)
            for k in ("name", "format"):
                if k in c:
                    assert r[k] == c[k], (c["id"], k)
        same(meta_pack(c["rows"], c.get("unknownRows", 0)), want, c["id"])
    for c in tile_cases:
        def want(r, c=c):
            assert r["accepted"] == c["accepted"], (c["id"], r)
        same(zoom_pack(c), want, c["id"])
    for c in ext_cases:
        def want(r, c=c):
            assert r["accepted"] == c["mapAccepted"], (c["id"], r)
            assert r["extensions"][c["requestedKey"]] == c["expected"], (c["id"], r)
        same(meta_pack(c["rows"]), want, c["id"], {"extensions": [c["requestedKey"]]})


def mbtiles_relation_cases():
    """3.0.1 (integration-commits-3, wp4-android-6): whole packs as SQL, tables and views. Expected values come
    from the reference admission above, never typed in"""
    meta = ["INSERT INTO meta_base VALUES ('name', 'Sample'), ('format', 'png')"]
    tiles_rows = "(0, 0, 0, X'01'), (1, 0, 1, X'0203')"
    base_tiles = ["CREATE TABLE tiles_base (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)",
                  "CREATE UNIQUE INDEX tiles_base_index ON tiles_base (zoom_level, tile_column, tile_row)",
                  "INSERT INTO tiles_base VALUES " + tiles_rows]
    meta_table = ["CREATE TABLE metadata (name text, value text)",
                  "INSERT INTO metadata VALUES ('name', 'Sample'), ('format', 'png')"]
    meta_view = ["CREATE TABLE meta_base (name text, value text)"] + meta + [
        "CREATE VIEW metadata AS SELECT name, value FROM meta_base"]
    tiles_view = base_tiles + ["CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles_base"]
    probes = {"tiles": [(0, 0, 0), (1, 0, 0), (1, 1, 1)]}
    small = ["PRAGMA page_size=512"]
    rows = [
        ("tablesBaseline", meta_table + [
            "CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)",
            "INSERT INTO tiles VALUES " + tiles_rows], probes, True,
         "plain tables, the shape every admission rule was written for"),
        ("nodeMbtilesDedup", meta_table + [
            "CREATE TABLE map (zoom_level integer, tile_column integer, tile_row integer, tile_id text, grid_id text)",
            "CREATE UNIQUE INDEX map_index ON map (zoom_level, tile_column, tile_row)",
            "CREATE TABLE images (tile_data blob, tile_id text)",
            "CREATE UNIQUE INDEX images_id ON images (tile_id)",
            "INSERT INTO map VALUES (0, 0, 0, 'a', NULL), (1, 0, 1, 'b', NULL)",
            "INSERT INTO images VALUES (X'01', 'a'), (X'0203', 'b')",
            "CREATE VIEW tiles AS SELECT map.zoom_level AS zoom_level, map.tile_column AS tile_column, "
            "map.tile_row AS tile_row, images.tile_data AS tile_data FROM map JOIN images ON images.tile_id = map.tile_id"],
         probes, True, "node-mbtiles / TileMill / mbutil / MapTiler deduplicated schema: tiles is a VIEW over map + "
                       "images. 2.x Android opened it, 3.0.0 Android refused it (the regression)"),
        ("metadataView", meta_view + [
            "CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)",
            "INSERT INTO tiles VALUES " + tiles_rows], probes, True,
         "metadata as a view: no rowid, no incremental blob, the key-addressed reads"),
        ("bothViews", meta_view + tiles_view, probes, True, "both relations views"),
        ("metadataViewBakeExtension", meta_view + [
            "INSERT INTO meta_base VALUES ('tacmap_bake_key', '%s'), ('tacmap_tile_px', '768')" % ("c" * 64)] + tiles_view,
         {"tiles": [(0, 0, 0)], "extensions": ["tacmap_bake_key", "tacmap_tile_px"]}, True,
         "the lazy bake extension reader works on a metadata view too"),
        ("tilesIsAnIndex", meta_table + base_tiles[:1] + ["CREATE INDEX tiles ON tiles_base (zoom_level)"],
         None, False, "a relation named tiles that is an index: neither a table nor a view"),
        ("tilesMissing", meta_table, None, False, "no tiles relation at all"),
        ("tilesViewTextZoom", meta_table + base_tiles + [
            "CREATE VIEW tiles AS SELECT CAST(zoom_level AS TEXT) AS zoom_level, tile_column, tile_row, tile_data "
            "FROM tiles_base"], None, False,
         "3.0.2: a CAST in a view is an expression of the file's, refused by the shape check before any read "
         "(3.0.1 refused it later, at the TEXT zoom)"),
        ("tilesViewOutOfRange", meta_table + base_tiles + [
            "CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row + 5 AS tile_row, tile_data FROM tiles_base"],
         None, False, "3.0.2: arithmetic in a view, refused by the shape check (3.0.1: by the row range)"),
        ("metadataViewDuplicateKnown", ["CREATE TABLE meta_base (name text, value text)"] + meta + [
            "CREATE VIEW metadata AS SELECT name, value FROM meta_base UNION ALL SELECT 'NAME', 'second'"] + tiles_view,
         None, False, "3.0.2: UNION and literals are refused by the shape check (the duplicate itself is still "
                      "refused through a plain view, see variantRule)"),
        ("metadataViewNonTextKnown", ["CREATE TABLE meta_base (name text, value text)"] + meta + [
            "CREATE VIEW metadata AS SELECT name, value FROM meta_base UNION ALL SELECT 'minzoom', 0"] + tiles_view,
         None, False, "3.0.2: refused by the shape check"),
        ("metadataViewUnbounded", [
            "CREATE VIEW metadata AS WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i + 1 FROM n) "
            "SELECT 'extension_' || i AS name, 'v' AS value FROM n"] + tiles_view,
         None, False, "3.0.2: a recursive CTE is refused by the shape check"),
        ("metadataViewLargeUnknownValue", ["CREATE TABLE meta_base (name text, value text)"] + meta + [
            "CREATE VIEW metadata AS SELECT name, value FROM meta_base UNION ALL "
            "SELECT 'vendor', CAST(zeroblob(1048576) AS TEXT)"] + tiles_view,
         None, False, "3.0.2 (was accepted in 3.0.1): zeroblob() in a view is file SQL that allocates, the class "
                      "SEC-1 is about. Refused by the shape check"),
        ("tilesViewEndless", meta_table + [
            "CREATE VIEW tiles AS WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n) "
            "SELECT 0 AS zoom_level, 0 AS tile_column, 0 AS tile_row, X'01' AS tile_data FROM n"],
         None, False, "3.0.2: refused by the shape check at once (3.0.1: by the admission budget). "
                      "budgetUnindexedJoin is the budget's case now"),
        # 3.0.2: the plain shapes real producers write are admitted
        ("mbutilLowercaseView", meta_table + [
            "create table map (zoom_level integer, tile_column integer, tile_row integer, tile_id text)",
            "create table images (tile_data blob, tile_id text)",
            "create unique index map_index on map (zoom_level, tile_column, tile_row)",
            "create unique index images_id on images (tile_id)",
            "insert into map values (0, 0, 0, 'a'), (1, 0, 1, 'b')",
            "insert into images values (X'01', 'a'), (X'0203', 'b')",
            "create view tiles as select map.zoom_level as zoom_level, map.tile_column as tile_column, "
            "map.tile_row as tile_row, images.tile_data as tile_data from map join images on images.tile_id = "
            "map.tile_id"], probes, True, "mbutil writes the dedup schema in lowercase"),
        ("martinNormalizedLeftJoin", meta_table + [
            "CREATE TABLE map (zoom_level INTEGER NOT NULL, tile_column INTEGER NOT NULL, tile_row INTEGER NOT NULL, "
            "tile_id TEXT, PRIMARY KEY(zoom_level, tile_column, tile_row))",
            "CREATE TABLE images (tile_data BLOB, tile_id TEXT NOT NULL PRIMARY KEY)",
            "INSERT INTO map VALUES (0, 0, 0, 'a'), (1, 0, 1, 'b'), (1, 1, 0, 'gone')",
            "INSERT INTO images VALUES (X'01', 'a'), (X'0203', 'b')",
            "CREATE VIEW tiles AS SELECT map.zoom_level AS zoom_level, map.tile_column AS tile_column, "
            "map.tile_row AS tile_row, images.tile_data AS tile_data FROM map LEFT JOIN images "
            "ON images.tile_id = map.tile_id"],
         probes, True, "martin's normalized schema is a LEFT JOIN; a map row whose image is gone is no tile"),
        ("planetilerShallowJoin", meta_table + [
            "CREATE TABLE tiles_shallow (zoom_level integer, tile_column integer, tile_row integer, "
            "tile_data_id integer, primary key(zoom_level, tile_column, tile_row)) without rowid",
            "CREATE TABLE tiles_data (tile_data_id integer primary key, tile_data blob)",
            "INSERT INTO tiles_shallow VALUES (0, 0, 0, 1), (1, 0, 1, 2)",
            "INSERT INTO tiles_data VALUES (1, X'01'), (2, X'0203')",
            "CREATE VIEW tiles AS SELECT tiles_shallow.zoom_level AS zoom_level, tiles_shallow.tile_column AS "
            "tile_column, tiles_shallow.tile_row AS tile_row, tiles_data.tile_data AS tile_data FROM tiles_shallow "
            "JOIN tiles_data ON tiles_shallow.tile_data_id = tiles_data.tile_data_id"],
         probes, True, "planetiler's deduplicated layout, other table names, a WITHOUT ROWID base"),
        ("viewColumnListQuotedAliases", meta_table + base_tiles + [
            "CREATE VIEW \"tiles\"(zoom_level, tile_column, tile_row, tile_data) AS SELECT \"t\".\"zoom_level\", "
            "[t].[tile_column], `t`.`tile_row`, t.tile_data FROM \"tiles_base\" AS t"],
         probes, True, "a column list, the three quoted identifier forms and a table alias"),
        ("starProjection", meta_table + base_tiles + ["CREATE VIEW tiles AS SELECT * FROM tiles_base"],
         probes, True, "SELECT * over one ordinary table"),
        ("usingJoin", meta_table + [
            "CREATE TABLE map (zoom_level integer, tile_column integer, tile_row integer, tile_id text)",
            "CREATE TABLE images (tile_data blob, tile_id text)",
            "CREATE UNIQUE INDEX map_index ON map (zoom_level, tile_column, tile_row)",
            "CREATE UNIQUE INDEX images_id ON images (tile_id)",
            "INSERT INTO map VALUES (0, 0, 0, 'a'), (1, 0, 1, 'b')",
            "INSERT INTO images VALUES (X'01', 'a'), (X'0203', 'b')",
            "CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, tile_data FROM map JOIN images "
            "USING (tile_id)"], probes, True, "JOIN ... USING"),
        # 3.0.2 SHADOW-PARITY-2: producers 3.0.1 opened whose tiles view the first 3.0.2 grammar refused. Their own
        # DDL, tile ids and dedup the way they write them
        ("gdal2mbtilesCommaJoin", [
            "CREATE TABLE images (\n    tile_id INTEGER PRIMARY KEY,\n    tile_data BLOB NOT NULL\n)",
            "CREATE TABLE map (\n    zoom_level INTEGER NOT NULL,\n    tile_column INTEGER NOT NULL,\n    tile_row INTEGER "
            "NOT NULL,\n    tile_id INTEGER NOT NULL\n        REFERENCES images (tile_id)\n        ON DELETE CASCADE ON "
            "UPDATE CASCADE,\n    PRIMARY KEY (zoom_level, tile_column, tile_row)\n)",
            GDAL2MBTILES_VIEW,
            "CREATE TABLE metadata (\n    name TEXT PRIMARY KEY,\n    value TEXT NOT NULL\n)",
            "INSERT OR REPLACE INTO images (tile_id, tile_data) VALUES (1, X'89504E470D0A1A0A01'), "
            "(2, X'89504E470D0A1A0A02')",
            "INSERT OR REPLACE INTO map (zoom_level, tile_column, tile_row, tile_id) VALUES (0, 0, 0, 1), (1, 0, 1, 2), "
            "(1, 1, 0, 1)",
            "INSERT OR REPLACE INTO metadata (name, value) VALUES ('name', 'Sample'), ('type', 'baselayer'), "
            "('version', '1.0.0'), ('description', 'gdal2mbtiles'), ('format', 'png')"],
         {"tiles": [(0, 0, 0), (1, 0, 0), (1, 1, 1), (1, 0, 1)]}, True,
         "gdal2mbtiles (ecometrica, PyPI 2.1.5), a raster PNG producer: tiles is FROM map, images WHERE map.tile_id = "
         "images.tile_id. 3.0.1 opened it, 3.0.2 before SHADOW-PARITY-2 refused it at the shape check. 1/1/1 is a "
         "deduplicated tile, 1/0/1 has none"),
        ("tippecanoeAndJoin", [
            "CREATE TABLE metadata (name text, value text);",
            "create unique index name on metadata (name);",
            "CREATE TABLE map (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_id TEXT);",
            "CREATE UNIQUE INDEX map_index ON map (zoom_level, tile_column, tile_row);",
            "CREATE TABLE images (zoom_level integer, tile_data blob, tile_id text);",
            "CREATE UNIQUE INDEX images_id ON images (zoom_level, tile_id);",
            TIPPECANOE_VIEW + ";",
            "INSERT INTO metadata VALUES ('name', 'Sample'), ('format', 'pbf')",
            "INSERT INTO images VALUES (0, X'1F8B0801', 'a'), (1, X'1F8B0802', 'a')",
            "INSERT INTO map VALUES (0, 0, 0, 'a'), (1, 0, 1, 'a')"],
         probes, True, "felt/tippecanoe and tile-join: ON images.tile_id = map.tile_id and images.zoom_level = "
                       "map.zoom_level, so the same tile id at two zooms is two images. Vector tiles TacMap doesn't "
                       "draw, but 3.0.1 opened the pack"),
        ("commaJoinWhereCallsFunction", [
            "CREATE TABLE images (tile_id INTEGER PRIMARY KEY, tile_data BLOB NOT NULL)",
            "CREATE TABLE map (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_id INTEGER)",
            "INSERT INTO images VALUES (1, X'01'), (2, X'0203')",
            "INSERT INTO map VALUES (0, 0, 0, 1), (1, 0, 1, 2)",
            "CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, tile_data FROM map, images "
            "WHERE map.tile_id = images.tile_id AND length(zeroblob(map.tile_id)) = map.tile_id"] + meta_table,
         None, False, "SEC-1 through the comma join: its WHERE is only the join's equalities. A zeroblob() sized by a "
                      "value from the file there is refused by the shape check before any read (true on every row, "
                      "so a looser WHERE would admit the pack and run it on every tile)"),
        ("dedupNoIndexOversizedImage", meta_table + [
            "CREATE TABLE map (zoom_level integer, tile_column integer, tile_row integer, tile_id text)",
            "CREATE TABLE images (tile_data blob, tile_id text)",
            "INSERT INTO map VALUES (0, 0, 0, 'a'), (1, 0, 1, 'big'), (1, 1, 0, 'c')",
            "INSERT INTO images VALUES (X'01', 'a'), (zeroblob(5242880), 'big'), (X'0203', 'c')",
            "CREATE VIEW tiles AS SELECT map.zoom_level AS zoom_level, map.tile_column AS tile_column, "
            "map.tile_row AS tile_row, images.tile_data AS tile_data FROM map JOIN images ON images.tile_id = "
            "map.tile_id"],
         probes, True, "the 3.0.1 A4 worry: with no index SQLite built an automatic index holding every image, "
                       "so one image over the value cap broke every read. Automatic indexes are off: the small "
                       "tiles read, the 5 MiB one is no tile (over maxTileBytes)"),
        ("tableOversizedValues", [
            "CREATE TABLE metadata (name text, value text)",
            "INSERT INTO metadata VALUES ('name', replace(hex(zeroblob(2621440)), '0', 'n')), ('format', 'png')",
            "CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)",
            "INSERT INTO tiles VALUES (0, 0, 0, X'01'), (1, 0, 1, zeroblob(5242880)), (1, 1, 0, X'0203')"],
         probes, True, "tables never hit the value cap: iOS reads metadata by incremental blob, tiles are "
                       "length-checked before the payload. The 5 MiB name truncates, the 5 MiB tile is no tile"),
        ("metadataViewOversizedName", [
            "CREATE TABLE meta_base (name text, value text)",
            "INSERT INTO meta_base VALUES ('name', replace(hex(zeroblob(2621440)), '0', 'n')), ('format', 'png')",
            "CREATE VIEW metadata AS SELECT name, value FROM meta_base",
            "CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)",
            "INSERT INTO tiles VALUES " + tiles_rows],
         None, False, "the view path has to materialise a value: over maxValueBytes fails closed (iOS "
                      "SQLITE_LIMIT_LENGTH, Android its own length check)"),
        # 3.0.2 (SEC-1): file SQL in a view never runs
        ("viewCallsFunction", meta_table + base_tiles + [
            "CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, randomblob(16) AS tile_data FROM tiles_base"],
         None, False, "SEC-1: a built-in that allocates, the bomb's shape with a harmless size"),
        ("viewDateTrigger", meta_table + base_tiles + [
            "CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, CASE WHEN date('now') > '2000-01-01' "
            "THEN tile_data END AS tile_data FROM tiles_base"],
         None, False, "SEC-1: a view whose value depends on the date passes 3.0.1 admission and turns later"),
        ("viewKeywordValue", meta_table + base_tiles + [
            "CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, CURRENT_TIMESTAMP AS tile_data "
            "FROM tiles_base"],
         None, False, "a reserved word as a bare column could make a value, so it isn't an identifier here"),
        ("viewWhereClause", meta_table + base_tiles + [
            "CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles_base "
            "WHERE zoom_level = tile_row"], None, False, "no WHERE"),
        ("viewOverView", meta_table + base_tiles + [
            "CREATE VIEW inner_tiles AS SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles_base",
            "CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, tile_data FROM inner_tiles"],
         None, False, "a view's base has to be an ordinary table, never another view"),
        ("viewOverVirtualTable", meta_table + [
            "CREATE TABLE map (zoom_level integer, tile_column integer, tile_row integer, tile_id text)",
            "CREATE VIRTUAL TABLE images USING fts4(tile_data, tile_id)",
            "INSERT INTO map VALUES (0, 0, 0, 'a')",
            "INSERT INTO images (tile_data, tile_id) VALUES (X'01', 'a')",
            "CREATE VIEW tiles AS SELECT map.zoom_level AS zoom_level, map.tile_column AS tile_column, "
            "map.tile_row AS tile_row, images.tile_data AS tile_data FROM map JOIN images ON images.tile_id = "
            "map.tile_id"], None, False, "a virtual table runs module code on read, never a base"),
        ("tilesVirtualTable", meta_table + [
            "CREATE VIRTUAL TABLE tiles USING fts4(zoom_level, tile_column, tile_row, tile_data)",
            "INSERT INTO tiles VALUES (0, 0, 0, X'01')"],
         None, False, "tiles itself a virtual table"),
        ("tilesTableGeneratedColumn", meta_table + [
            "CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, raw blob, "
            "tile_data blob GENERATED ALWAYS AS (raw) VIRTUAL)",
            "INSERT INTO tiles (zoom_level, tile_column, tile_row, raw) VALUES (0, 0, 0, X'01')"],
         None, False, "a generated column is file SQL on every read (the 3.0.0 bomb: GENERATED ALWAYS AS "
                      "(hex(zeroblob(N)))). Needs SQLite 3.31+, older readers can't parse the schema at all"),
        ("viewOverGeneratedColumn", meta_table + [
            "CREATE TABLE tiles_base (zoom_level integer, tile_column integer, tile_row integer, raw blob, "
            "tile_data blob GENERATED ALWAYS AS (raw) VIRTUAL)",
            "INSERT INTO tiles_base (zoom_level, tile_column, tile_row, raw) VALUES (0, 0, 0, X'01')",
            "CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles_base"],
         None, False, "same through a plain view"),
        ("schemaStatementTooLong", meta_table + [
            "CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)",
            "INSERT INTO tiles VALUES " + tiles_rows,
            "CREATE TABLE junk (\"%s\")" % ("x" * MBT_MAX_SCHEMA_SQL_BYTES)],
         None, False, "any schema statement over maxSchemaSqlBytes fails the open (iOS SQLITE_LIMIT_SQL_LENGTH "
                      "fails the schema load; Android scans sqlite_master): the parser's memory follows the "
                      "schema text"),
        ("budgetUnindexedJoin", [
            "CREATE TABLE metadata (name text, value text)",
            "INSERT INTO metadata VALUES ('name', 'Sample'), ('format', 'png')",
            "CREATE TABLE map (zoom_level integer, tile_column integer, tile_row integer, tile_id text)",
            "CREATE TABLE images (tile_data blob, tile_id text)",
            "WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < 19999) "
            "INSERT INTO map SELECT 0, 0, 0, 't' || i FROM n",
            "WITH RECURSIVE n(i) AS (SELECT 0 UNION ALL SELECT i + 1 FROM n WHERE i < 19999) "
            "INSERT INTO images SELECT X'01', 't' || i FROM n",
            "CREATE VIEW tiles AS SELECT map.zoom_level AS zoom_level, map.tile_column AS tile_column, "
            "map.tile_row AS tile_row, images.tile_data AS tile_data FROM map JOIN images ON images.tile_id = "
            "map.tile_id"],
         None, False, "a plain join with no index and automatic indexes off is a 20,000 x 20,000 nested loop: "
                      "the admission budget (views only) stops it. Tests shorten the budget with their seam"),
        # 3.0.2 SEC-M1-SHADOW: the sqlite_master row admission checks has to be the object SQLite runs. These write
        # sqlite_master with writable_schema so they ship packBase64 too, 512 byte pages keep that small
        ("tilesShadowedByCaseVariant", small + meta_table + base_tiles + [
            "CREATE VIEW TILES AS SELECT zoom_level, tile_column, tile_row, randomblob(16) AS tile_data FROM tiles_base",
            "PRAGMA writable_schema=ON",
            "INSERT INTO sqlite_master (type, name, tbl_name, rootpage, sql) VALUES ('view', 'tiles', 'tiles', 0, "
            "'CREATE VIEW IF NOT EXISTS tiles AS SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles_base')",
            "PRAGMA writable_schema=OFF"],
         None, False, "the live tiles is the TILES view (loaded first, FROM tiles resolves any case); the plain row "
                      "named exactly tiles is a dormant duplicate SQLite skips because of IF NOT EXISTS. 3.0.2 "
                      "before this looked the relation up case-exact, checked the decoy and ran randomblob() on "
                      "every read. Refused: two rows NOCASE"),
        ("metadataShadowedByCaseVariant", small + ["CREATE TABLE meta_base (name text, value text)"] + meta + tiles_view + [
            "CREATE VIEW METADATA AS SELECT name, value || hex(randomblob(4)) AS value FROM meta_base",
            "PRAGMA writable_schema=ON",
            "INSERT INTO sqlite_master (type, name, tbl_name, rootpage, sql) VALUES ('view', 'metadata', 'metadata', "
            "0, 'CREATE VIEW IF NOT EXISTS metadata AS SELECT name, value FROM meta_base')",
            "PRAGMA writable_schema=OFF"],
         None, False, "the same through metadata"),
        ("tableRowKeepsIfNotExists", small + meta_table + [
            "CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)",
            "INSERT INTO tiles VALUES " + tiles_rows,
            "PRAGMA writable_schema=ON",
            "UPDATE sqlite_master SET sql = 'CREATE TABLE IF NOT EXISTS tiles (zoom_level integer, tile_column "
            "integer, tile_row integer, tile_data blob)' WHERE name = 'tiles'",
            "PRAGMA writable_schema=OFF"],
         None, False, "harmless on its own, but SQLite never stores IF NOT EXISTS, so only a hand edit does, and on "
                      "SQLite without the schema name cross-check the same edit hides a decoy table row behind a "
                      "live view of that name. Refused at the base table check (baseTableShape)"),
        # rows whose name column lies about the object their sql makes. SQLite with the init-time name cross-check
        # (this reference, iOS, recent Android) refuses these as a malformed schema. Older builds without it (older
        # Android) load them, and 3.0.2 before SEC-M1-SHADOW admitted them and ran the expression; the
        # fixed readers refuse them there by viewShape or baseTableShape. Same verdict either way
        ("viewRowNameLieBehindIfNotExists", small + meta_table + base_tiles + [
            "CREATE VIEW zzz AS SELECT zoom_level FROM tiles_base",
            "PRAGMA writable_schema=ON",
            "UPDATE sqlite_master SET sql = 'CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, "
            "randomblob(16) AS tile_data FROM tiles_base' WHERE name = 'zzz'",
            "INSERT INTO sqlite_master (type, name, tbl_name, rootpage, sql) VALUES ('view', 'tiles', 'tiles', 0, "
            "'CREATE VIEW IF NOT EXISTS tiles AS SELECT zoom_level, tile_column, tile_row, tile_data FROM tiles_base')",
            "PRAGMA writable_schema=OFF"],
         None, False, "the live tiles comes from a row named zzz, the row named tiles is a skipped decoy"),
        ("baseRowNameLie", small + meta_table + [
            "CREATE TABLE decoy (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)",
            "INSERT INTO decoy VALUES " + tiles_rows,
            "CREATE VIEW t AS SELECT zoom_level, tile_column, tile_row, randomblob(16) AS tile_data FROM decoy",
            "CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, tile_data FROM t",
            "PRAGMA writable_schema=ON",
            "UPDATE sqlite_master SET name = 't', tbl_name = 't' WHERE name = 'decoy'",
            "UPDATE sqlite_master SET name = 'zzz', tbl_name = 'zzz' WHERE type = 'view' AND name = 't'",
            "PRAGMA writable_schema=OFF"],
         None, False, "tiles is a plain view over t; the row named t is a table whose sql makes decoy, the live t "
                      "is an expression view in a row named zzz. No IF NOT EXISTS anywhere"),
        ("baseRowNameLieBehindIfNotExists", small + meta_table + base_tiles + [
            "CREATE VIEW zzz AS SELECT zoom_level FROM tiles_base",
            "CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, tile_data FROM b",
            "PRAGMA writable_schema=ON",
            "UPDATE sqlite_master SET sql = 'CREATE VIEW b AS SELECT zoom_level, tile_column, tile_row, "
            "randomblob(16) AS tile_data FROM tiles_base' WHERE name = 'zzz'",
            "INSERT INTO sqlite_master (type, name, tbl_name, rootpage, sql) VALUES ('table', 'b', 'b', 2, "
            "'CREATE TABLE IF NOT EXISTS b (zoom_level integer, tile_column integer, tile_row integer, tile_data blob)')",
            "PRAGMA writable_schema=OFF"],
         None, False, "the base table row b is a skipped IF NOT EXISTS decoy, the live b is an expression view in a "
                      "row named zzz"),
    ]
    min_sqlite = {"tilesTableGeneratedColumn": MBT_GENERATED_COLUMNS_MIN_SQLITE,
                  "viewOverGeneratedColumn": MBT_GENERATED_COLUMNS_MIN_SQLITE}
    want_reason = {"tilesIsAnIndex": "relation", "tilesMissing": "relation", "viewOverView": "base",
                   "viewOverVirtualTable": "base", "tilesVirtualTable": "base",
                   "tilesTableGeneratedColumn": "generated", "viewOverGeneratedColumn": "generated",
                   "metadataViewOversizedName": "length", "schemaStatementTooLong": "schema",
                   "budgetUnindexedJoin": "budget", "tilesShadowedByCaseVariant": "relation",
                   "metadataShadowedByCaseVariant": "relation", "tableRowKeepsIfNotExists": "base",
                   "viewRowNameLieBehindIfNotExists": "schema", "baseRowNameLie": "schema",
                   "baseRowNameLieBehindIfNotExists": "schema"}
    out = []
    for cid, sql, pr, accepted, note in rows:
        # a row that writes sqlite_master itself ships its file too: Apple's sqlite has SQLITE_DBCONFIG_DEFENSIVE on,
        # which refuses writable_schema, so that harness can't run its sql[]. The reference judges those same bytes
        pack = mbtiles_build(sql) if any(s.startswith("PRAGMA writable_schema") for s in sql) else None
        r = mbtiles_reference(sql, pr, pack)
        assert r["accepted"] == accepted, (cid, r)
        if not accepted:
            assert r["rejectedAt"] == want_reason.get(cid, "shape"), (cid, r)
        row = {"id": cid, "sql": sql, "expect": r, "note": note}
        if pack is not None:
            row["packBase64"] = base64.b64encode(pack).decode("ascii")
        if cid == "budgetUnindexedJoin":
            row["testBudgetMs"] = 250
        if cid in min_sqlite:
            row["minSqliteVersion"] = min_sqlite[cid]
        out.append(row)
    assert len({r["id"] for r in out}) == len(out)
    return out


# 3.0.2: the view grammar on its own, no SQLite involved. Both readers port view_shape() and must give the same
# verdict, reason and base tables. Reasons: tooLong (over maxSchemaSqlBytes), token (the tokenizer met something
# it doesn't admit), shape (the grammar, reserved words and the view's own name)
NODE_MBTILES_VIEW = ("CREATE VIEW tiles AS\n    SELECT\n        map.zoom_level AS zoom_level,\n"
                     "        map.tile_column AS tile_column,\n        map.tile_row AS tile_row,\n"
                     "        images.tile_data AS tile_data\n    FROM map\n    JOIN images ON images.tile_id = map.tile_id")
DEDUP_SELECT = ("SELECT map.zoom_level AS zoom_level, map.tile_column AS tile_column, map.tile_row AS tile_row, "
                "images.tile_data AS tile_data FROM map")
# 3.0.2 SHADOW-PARITY-2: two producers 3.0.1 opened and the first 3.0.2 grammar refused, as SQLite stores them
# (CREATE VIEW then the text from the name on, so the indentation stays and the semicolon goes).
# gdal2mbtiles (ecometrica, PyPI 2.1.5) MBTiles._create, a raster PNG producer: a comma join
GDAL2MBTILES_VIEW = ("CREATE VIEW tiles AS\n                    SELECT zoom_level, tile_column, tile_row, tile_data\n"
                     "                    FROM map, images\n                    WHERE map.tile_id = images.tile_id")
# felt/tippecanoe and tile-join mbtiles.cpp (vector tiles): ON with two equalities
TIPPECANOE_VIEW = ("CREATE VIEW tiles AS " + DEDUP_SELECT + " JOIN images ON images.tile_id = map.tile_id and "
                   "images.zoom_level = map.zoom_level")


def view_shape_cases():
    ok = [
        ("nodeMbtiles", "tiles", NODE_MBTILES_VIEW, ["map", "images"],
         "node-mbtiles/TileMill, as SQLite stores it (IF NOT EXISTS and the semicolon dropped)"),
        ("mbutilLowercase", "tiles", DEDUP_SELECT.lower().replace("select", "create view tiles as select", 1)
         + " join images on images.tile_id = map.tile_id", ["map", "images"], "keywords are case-insensitive"),
        ("leftJoin", "tiles", "CREATE VIEW tiles AS " + DEDUP_SELECT + " LEFT JOIN images ON images.tile_id = map.tile_id",
         ["map", "images"], "martin normalized"),
        ("leftOuterJoin", "tiles", "CREATE VIEW tiles AS " + DEDUP_SELECT
         + " LEFT OUTER JOIN images ON images.tile_id = map.tile_id", ["map", "images"], ""),
        ("innerJoin", "tiles", "CREATE VIEW tiles AS " + DEDUP_SELECT + " INNER JOIN images ON images.tile_id = map.tile_id",
         ["map", "images"], ""),
        ("crossJoinOn", "tiles", "CREATE VIEW tiles AS " + DEDUP_SELECT + " CROSS JOIN images ON images.tile_id = map.tile_id",
         ["map", "images"], ""),
        ("onInParens", "tiles", "CREATE VIEW tiles AS " + DEDUP_SELECT + " JOIN images ON (images.tile_id = map.tile_id)",
         ["map", "images"], ""),
        ("usingOne", "tiles", "CREATE VIEW tiles AS SELECT zoom_level, tile_column, tile_row, tile_data FROM map "
         "JOIN images USING (tile_id)", ["map", "images"], ""),
        ("usingTwo", "tiles", "CREATE VIEW tiles AS SELECT * FROM a JOIN b USING (k, j)", ["a", "b"], ""),
        ("columnList", "tiles", "CREATE VIEW tiles(zoom_level, tile_column, tile_row, tile_data) AS SELECT a, b, c, d "
         "FROM t", ["t"], ""),
        ("quotedForms", "tiles", "CREATE VIEW \"tiles\" AS SELECT \"t\".\"zoom_level\", [t].[tile_column], "
         "`t`.`tile_row`, t.tile_data FROM \"tiles_base\" AS t", ["tiles_base"],
         "the three quoted forms; base names come back without quotes"),
        ("aliasesWithoutAs", "tiles", "CREATE VIEW tiles AS SELECT m.zoom_level zoom_level, m.tile_column tile_column, "
         "m.tile_row tile_row, i.tile_data tile_data FROM map m JOIN images i ON i.tile_id = m.tile_id",
         ["map", "images"], ""),
        ("tableStar", "tiles", "CREATE VIEW tiles AS SELECT t.* FROM tiles_base t", ["tiles_base"], ""),
        ("mixedCase", "tiles", "Create View Tiles As Select * From T", ["T"],
         "the view name compares ASCII case-insensitively"),
        ("metadataPlain", "metadata", "CREATE VIEW metadata AS SELECT name, value FROM meta_base", ["meta_base"], ""),
        ("metadataRenamed", "metadata", "CREATE VIEW metadata AS SELECT k AS name, v AS value FROM kv", ["kv"], ""),
        ("newlinesTabs", "tiles", "CREATE\tVIEW\r\ntiles\nAS\n\tSELECT\n\t*\nFROM\n\tt", ["t"], "ASCII whitespace"),
        # 3.0.2 SHADOW-PARITY-2: the same equi-join written as a comma join, and ON with an AND of equalities
        ("gdal2mbtiles", "tiles", GDAL2MBTILES_VIEW, ["map", "images"],
         "gdal2mbtiles (raster PNG): a comma join, the join's equality in WHERE. 3.0.1 opened it, 3.0.2 before "
         "SHADOW-PARITY-2 refused it"),
        ("tippecanoe", "tiles", TIPPECANOE_VIEW, ["map", "images"],
         "felt/tippecanoe and tile-join: two equalities joined by a lowercase and"),
        ("onWithAnd", "tiles", "CREATE VIEW tiles AS SELECT * FROM a JOIN b ON b.k = a.k AND b.j = a.j", ["a", "b"],
         "refused before SHADOW-PARITY-2: an AND of column equalities is still an equi-join"),
        ("onAndInParens", "tiles", "CREATE VIEW tiles AS " + DEDUP_SELECT + " LEFT JOIN images ON (images.tile_id = "
         "map.tile_id AND images.zoom_level = map.zoom_level AND images.tile_id = map.tile_id)", ["map", "images"],
         "one pair of parens round the whole condition, any number of equalities"),
        ("commaJoinWhereAnd", "tiles", "CREATE VIEW tiles AS SELECT * FROM a, b WHERE a.k = b.k AND a.j = b.j",
         ["a", "b"], "a comma join takes the same equalities ON does"),
        ("commaJoinAliasesParens", "tiles", "CREATE VIEW tiles AS SELECT m.zoom_level, m.tile_column, m.tile_row, "
         "i.tile_data FROM map AS m, \"images\" i WHERE (m.tile_id = i.tile_id)", ["map", "images"],
         "table aliases, a quoted table and the condition in parens"),
    ]
    bad = [
        ("tooLong", "tiles", "CREATE VIEW tiles AS SELECT * FROM t" + " " * MBT_MAX_SCHEMA_SQL_BYTES, "tooLong",
         "over maxSchemaSqlBytes before anything is tokenized"),
        ("digit", "tiles", "CREATE VIEW tiles AS SELECT zoom_level + 1 AS zoom_level FROM t", "token", "no numbers"),
        ("stringLiteral", "tiles", "CREATE VIEW tiles AS SELECT 'x' AS tile_data FROM t", "token", "no string literals"),
        ("blobLiteral", "tiles", "CREATE VIEW tiles AS SELECT X'01' AS tile_data FROM t", "token", ""),
        ("lineComment", "tiles", "CREATE VIEW tiles AS SELECT * FROM t -- note", "token", "no comments"),
        ("blockComment", "tiles", "CREATE VIEW tiles AS SELECT /* x */ * FROM t", "token", ""),
        ("semicolon", "tiles", "CREATE VIEW tiles AS SELECT * FROM t;", "token", ""),
        ("parameter", "tiles", "CREATE VIEW tiles AS SELECT ? AS tile_data FROM t", "token", ""),
        ("concatOperator", "tiles", "CREATE VIEW tiles AS SELECT a || b AS tile_data FROM t", "token", ""),
        ("lessThan", "tiles", "CREATE VIEW tiles AS " + DEDUP_SELECT + " JOIN images ON images.tile_id < map.tile_id",
         "token", ""),
        ("nonAscii", "tiles", "CREATE VIEW tiles AS SELECT zöom FROM t", "token", "ASCII only"),
        ("doubledQuote", "tiles", "CREATE VIEW tiles AS SELECT \"a\"\"b\" FROM t", "token", "no quote escapes"),
        ("doubledQuoteTable", "tiles", "CREATE VIEW tiles AS SELECT * FROM \"x\"\"y\"", "token",
         "SQLite's table is x\"y; splitting it into table x with alias y would check the wrong base"),
        ("doubledBacktick", "tiles", "CREATE VIEW tiles AS SELECT * FROM `x``y`", "token", ""),
        ("adjacentQuoted", "tiles", "CREATE VIEW tiles AS SELECT \"a\"[b] FROM t", "token", ""),
        ("emptyQuoted", "tiles", "CREATE VIEW tiles AS SELECT \"\" FROM t", "token", ""),
        ("unterminatedQuote", "tiles", "CREATE VIEW tiles AS SELECT \"abc FROM t", "token", ""),
        ("formFeed", "tiles", "CREATE VIEW tiles AS SELECT *\fFROM t", "token", "only space, tab, CR, LF"),
        ("function", "tiles", "CREATE VIEW tiles AS SELECT zoom_level, hex(tile_data) AS tile_data FROM t", "shape",
         "no function calls"),
        ("cast", "tiles", "CREATE VIEW tiles AS SELECT CAST(zoom_level AS TEXT) AS zoom_level FROM t", "shape", ""),
        ("caseExpression", "tiles", "CREATE VIEW tiles AS SELECT CASE WHEN tile_data IS NULL THEN tile_data END "
         "AS tile_data FROM t", "shape", ""),
        ("keywordValue", "tiles", "CREATE VIEW tiles AS SELECT CURRENT_TIMESTAMP AS tile_data FROM t", "shape",
         "a reserved word can make a value"),
        ("nullValue", "tiles", "CREATE VIEW tiles AS SELECT NULL AS tile_data FROM t", "shape", ""),
        ("trueValue", "tiles", "CREATE VIEW tiles AS SELECT TRUE AS zoom_level FROM t", "shape",
         "TRUE/FALSE are literals when no such column exists"),
        ("reservedColumn", "tiles", "CREATE VIEW tiles AS SELECT key FROM t", "shape",
         "every reserved word, even ones SQLite would take as a name"),
        ("where", "tiles", "CREATE VIEW tiles AS SELECT * FROM t WHERE zoom_level = tile_row", "shape",
         "WHERE only as a comma join's condition, on one table it's a filter"),
        ("unionAll", "tiles", "CREATE VIEW tiles AS SELECT * FROM a UNION ALL SELECT * FROM b", "shape", ""),
        ("withCte", "tiles", "CREATE VIEW tiles AS WITH x AS (SELECT * FROM t) SELECT * FROM x", "shape", ""),
        ("distinct", "tiles", "CREATE VIEW tiles AS SELECT DISTINCT * FROM t", "shape", ""),
        ("orderBy", "tiles", "CREATE VIEW tiles AS SELECT * FROM t ORDER BY zoom_level", "shape", ""),
        ("threeTables", "tiles", "CREATE VIEW tiles AS SELECT * FROM a JOIN b ON b.k = a.k JOIN c ON c.k = a.k",
         "shape", "at most two tables"),
        ("naturalJoin", "tiles", "CREATE VIEW tiles AS SELECT * FROM a NATURAL JOIN b", "shape", ""),
        ("commaJoin", "tiles", "CREATE VIEW tiles AS SELECT * FROM a, b", "shape",
         "a comma join needs its WHERE equalities, without them it's a cross join"),
        ("joinWithoutConstraint", "tiles", "CREATE VIEW tiles AS SELECT * FROM a JOIN b", "shape", ""),
        # 3.0.2 SHADOW-PARITY-2: the comma join's WHERE and the AND chain are column equalities and nothing else
        ("commaJoinWhereOr", "tiles", "CREATE VIEW tiles AS SELECT * FROM a, b WHERE a.k = b.k OR a.j = b.j", "shape",
         "only AND joins equalities"),
        ("commaJoinWhereFunction", "tiles", "CREATE VIEW tiles AS SELECT * FROM map, images WHERE map.tile_id = "
         "lower(images.tile_id)", "shape", "the comma join's WHERE is column refs too"),
        ("commaJoinWhereLiteral", "tiles", "CREATE VIEW tiles AS SELECT * FROM a, b WHERE a.k = b.k AND a.z = 0",
         "token", "no filter on a value, only the join"),
        ("commaJoinOn", "tiles", "CREATE VIEW tiles AS SELECT * FROM a, b ON a.k = b.k", "shape",
         "SQLite takes ON after a comma, no producer writes it"),
        ("commaJoinThreeTables", "tiles", "CREATE VIEW tiles AS SELECT * FROM a, b, c WHERE a.k = b.k AND b.k = c.k",
         "shape", "at most two tables either way"),
        ("commaJoinThenJoin", "tiles", "CREATE VIEW tiles AS SELECT * FROM a, b JOIN c ON c.k = a.k", "shape", ""),
        ("joinOnThenWhere", "tiles", "CREATE VIEW tiles AS SELECT * FROM a JOIN b ON b.k = a.k WHERE a.j = b.j",
         "shape", "no WHERE after a JOIN"),
        ("onAndDangling", "tiles", "CREATE VIEW tiles AS SELECT * FROM a JOIN b ON b.k = a.k AND", "shape", ""),
        ("onAndNot", "tiles", "CREATE VIEW tiles AS SELECT * FROM a JOIN b ON b.k = a.k AND NOT b.j = a.j", "shape",
         ""),
        ("onEachEqualityInParens", "tiles", "CREATE VIEW tiles AS SELECT * FROM a JOIN b ON (b.k = a.k) AND (b.j = a.j)",
         "shape", "parens go round the whole condition or nowhere"),
        ("onAndUnclosedParen", "tiles", "CREATE VIEW tiles AS SELECT * FROM a JOIN b ON (b.k = a.k AND b.j = a.j",
         "shape", ""),
        ("commaJoinWhereNestedParens", "tiles", "CREATE VIEW tiles AS SELECT * FROM a, b WHERE ((a.k = b.k))", "shape",
         ""),
        ("commaJoinWhereChained", "tiles", "CREATE VIEW tiles AS SELECT * FROM a, b WHERE a.k = b.k = a.j", "shape",
         "(a.k = b.k) = a.j compares a truth value"),
        ("commaJoinWhereCollate", "tiles", "CREATE VIEW tiles AS SELECT * FROM a, b WHERE a.k = b.k COLLATE NOCASE",
         "shape", ""),
        ("subquery", "tiles", "CREATE VIEW tiles AS SELECT * FROM (SELECT * FROM t)", "shape", ""),
        ("schemaQualifiedTable", "tiles", "CREATE VIEW tiles AS SELECT * FROM main.map", "shape", ""),
        ("threePartColumn", "tiles", "CREATE VIEW tiles AS SELECT main.t.zoom_level FROM t", "shape", ""),
        ("wrongName", "tiles", "CREATE VIEW tiles_old AS SELECT * FROM t", "shape", "the view has to be the relation"),
        ("metadataNameForTiles", "tiles", "CREATE VIEW metadata AS SELECT * FROM t", "shape", ""),
        ("notAView", "tiles", "CREATE TABLE tiles AS SELECT * FROM t", "shape", ""),
        ("trailingWords", "tiles", "CREATE VIEW tiles AS SELECT * FROM t a b", "shape", ""),
        ("starAlias", "tiles", "CREATE VIEW tiles AS SELECT * AS x FROM t", "shape", ""),
        ("emptyColumnList", "tiles", "CREATE VIEW tiles() AS SELECT * FROM t", "shape", ""),
        ("temporaryView", "tiles", "CREATE TEMP VIEW tiles AS SELECT * FROM t", "shape", ""),
        ("ifNotExists", "tiles", "CREATE VIEW IF NOT EXISTS tiles AS SELECT * FROM t", "shape",
         "3.0.2 SEC-M1-SHADOW (3.0.2 builds before it accepted this): SQLite drops IF NOT EXISTS when it stores a "
         "view, so only a hand edited schema has it, and the duplicate it skips is a decoy behind the live view"),
    ]
    out = []
    for cid, rel, sql, tables, note in ok:
        got = view_shape(sql, rel)
        assert got == tables, (cid, got)
        out.append({"id": cid, "relation": rel, "sql": sql, "expect": {"accepted": True, "tables": tables},
                    "note": note})
    for cid, rel, sql, reason, note in bad:
        try:
            view_shape(sql, rel)
            raise AssertionError(cid)
        except _ShapeReject as r:
            assert str(r) == reason, (cid, str(r), reason)
        out.append({"id": cid, "relation": rel, "sql": sql, "expect": {"accepted": False, "reason": reason},
                    "note": note})
    assert len({r["id"] for r in out}) == len(out)
    return out


def table_shape_cases():
    """3.0.2 SEC-M1-SHADOW: table_declares() on its own, no SQLite. Both readers port it and must agree"""
    rows = [
        ("plain", "tiles", "CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, "
         "tile_data blob)", True, "what GDAL, tippecanoe and rio-mbtiles write"),
        ("noSpaceBeforeParen", "tiles", "CREATE TABLE tiles(zoom_level, tile_column, tile_row, tile_data)", True, ""),
        ("lowercase", "map", "create table map (zoom_level integer, tile_column integer, tile_row integer, "
         "tile_id text)", True, "mbutil"),
        ("withoutRowid", "tiles_shallow", "CREATE TABLE tiles_shallow (zoom_level integer, tile_column integer, "
         "tile_row integer, tile_data_id integer, primary key(zoom_level, tile_column, tile_row)) without rowid", True,
         "planetiler; only the name is read, the rest of the text can be anything"),
        ("renamedDoubleQuoted", "metadata_base", "CREATE TABLE \"metadata_base\" (name, value)", True,
         "ALTER TABLE ... RENAME stores the new name double quoted"),
        ("bracketQuoted", "map", "CREATE TABLE [map] (a)", True, ""),
        ("backtickQuoted", "images", "CREATE TABLE `images` (a)", True, ""),
        ("quotedNoSpace", "t", " \n create\ttable\"t\"(a)", True, "a quoted name needs no space before it"),
        ("quotedWithSpace", "tiles base", "CREATE TABLE \"tiles base\" (a)", True, ""),
        ("nameCaseDiffers", "tiles", "CREATE TABLE Tiles (a)", True,
         "ASCII case-insensitive, the way SQLite resolves names"),
        ("quotedReservedWord", "if", "CREATE TABLE \"if\" (a)", True, "a quoted reserved word is just a name"),
        ("ifNotExists", "tiles", "CREATE TABLE IF NOT EXISTS tiles (a)", False,
         "SQLite drops IF NOT EXISTS when it stores a table, so only a hand edit has it"),
        ("declaresAnotherTable", "t", "CREATE TABLE decoy (a)", False,
         "the row's name column says t while its sql makes decoy (SQLite without the name cross-check loads it)"),
        ("virtualTable", "tiles", "CREATE VIRTUAL TABLE tiles USING fts4(a)", False, "module code on every read"),
        ("commentedVirtual", "t", "CREATE/**/VIRTUAL TABLE t USING fts4(a)", False,
         "a comment isn't whitespace, VIRTUAL can't pass as part of the first word"),
        ("view", "tiles", "CREATE VIEW tiles AS SELECT * FROM t", False, ""),
        ("temporaryTable", "tiles", "CREATE TEMP TABLE tiles (a)", False, ""),
        ("schemaQualified", "tiles", "CREATE TABLE main.tiles (a)", False, ""),
        ("schemaPrefixMatchesName", "main", "CREATE TABLE main.tiles (a)", False,
         "no dot after the name, so the schema part never counts as the table"),
        ("commentBeforeName", "t", "CREATE TABLE /**/t (a)", False, ""),
        ("commentBetweenWords", "t", "CREATE/**/TABLE t (a)", False, ""),
        ("glued", "t", "CREATETABLE t (a)", False, ""),
        ("tableWordRunsOn", "_x", "CREATE TABLE_x (a)", False, "TABLE_x is one word to SQLite"),
        ("tabBeforeName", "t", "CREATE TABLE\tt", True, ""),
        ("bareReservedWord", "key", "CREATE TABLE key (a)", False,
         "a reserved word is never a bare name here, which is what keeps IF out"),
        ("doubledQuote", "x", "CREATE TABLE \"x\"\"y\" (a)", False, "SQLite's table is x\"y"),
        ("emptyQuoted", "t", "CREATE TABLE \"\" (a)", False, ""),
        ("unterminatedQuote", "t", "CREATE TABLE \"t (a)", False, ""),
        ("singleQuoted", "t", "CREATE TABLE 't' (a)", False, "not one of the three identifier forms"),
        ("nonAsciiBare", "zöom", "CREATE TABLE zöom (a)", False, "ASCII only"),
        ("nonAsciiQuoted", "zöom", "CREATE TABLE \"zöom\" (a)", False, ""),
        ("formFeed", "t", "CREATE\fTABLE t (a)", False, "only space, tab, CR, LF"),
        ("wordsOnly", "t", "CREATE TABLE", False, ""),
        ("empty", "t", "", False, ""),
        ("noSql", "t", None, False, "a table row with NULL sql"),
    ]
    out = []
    for cid, name, sql, accepted, note in rows:
        assert table_declares(sql, name) is accepted, cid
        out.append({"id": cid, "name": name, "sql": sql, "expect": {"accepted": accepted}, "note": note})
    assert len({r["id"] for r in out}) == len(out)
    return out


def mbtiles_connection():
    """3.0.2 (SEC-1): what every MBTiles connection gets before its first statement, on both readers"""
    return {
        "maxTileBytes": MBT_MAX_TILE_BYTES,
        "maxValueBytes": MBT_MAX_VALUE_BYTES,
        "maxSchemaSqlBytes": MBT_MAX_SCHEMA_SQL_BYTES,
        "pragmas": ["trusted_schema=OFF (SQLite >= 3.31; iOS always, Android API 31+)", "automatic_index=OFF"],
        "ios": {"sqlite3_limit": {"SQLITE_LIMIT_LENGTH": MBT_MAX_VALUE_BYTES,
                                  "SQLITE_LIMIT_SQL_LENGTH": MBT_MAX_SCHEMA_SQL_BYTES},
                "hardHeapLimit": None},
        "android": {"hardHeapLimitBytes": MBT_ANDROID_HEAP_LIMIT,
                    "hardHeapLimitMinSqlite": MBT_ANDROID_HEAP_LIMIT_MIN_SQLITE,
                    "explicitChecks": [
                        "before anything else: no sqlite_master row has length(CAST(sql AS BLOB)) > maxSchemaSqlBytes",
                        "view path: a descriptor name, a known value or a bake extension whose "
                        "length(CAST(... AS BLOB)) > maxValueBytes fails closed (extension: reads as missing)"]},
        "generatedColumnsMinSqlite": MBT_GENERATED_COLUMNS_MIN_SQLITE,
        "rules": (
            "Every connection, the admission open and the lazy prevalidated open alike, before its first statement: "
            "PRAGMA trusted_schema=OFF where SQLite >= 3.31, PRAGMA automatic_index=OFF, and the value and schema "
            "caps (iOS sqlite3_limit; Android PRAGMA hard_heap_limit where SQLite >= 3.31 plus its explicit "
            "checks). Then, before any statement names tiles or metadata: each must be exactly one sqlite_master "
            "row (name compared NOCASE, the way SQLite resolves it) of type table or view. A view's "
            "sqlite_master.sql must pass viewShape (reserved words, grammar, maxSchemaSqlBytes) and gives the base "
            "tables. Every table a read touches (tiles or metadata when a table, each view's base tables) must be "
            "exactly one sqlite_master row (name compared NOCASE) of type table whose sql passes baseTableShape "
            "(the words CREATE TABLE, so not CREATE VIRTUAL TABLE, then exactly that table's name) and, where "
            "SQLite >= 3.31, has no column with pragma_table_xinfo hidden 2 or 3 (generated). Any failure: not "
            "admitted, and a lazy open serves no tile. Because every row checked must declare its own name with no "
            "IF NOT EXISTS, it is the object SQLite loaded for that name (a second one would be a schema error), so "
            "no expression stored in the file is ever evaluated on a read"),
    }


# 3.0.2 (SEC-1): the MBTiles open guard, modelled on pdf_tile_render.json crashGuard but with its own file and
# state. One active basemap at a time, armed on every foreground restore and activation (not verified once)
MBT_GUARD_MAX_IN_PROGRESS = 4


class MbtOpenGuard:
    def __init__(self):
        self.in_progress = []   # oldest first
        self.suspect = None

    def state(self):
        return {"v": 1, "inProgress": list(self.in_progress), "suspect": self.suspect}

    def step(self, ev):
        op = ev["op"]
        if op == "arm":
            if not ev.get("foreground", True):
                return {"armed": False}
            tok = ev["token"]
            self.in_progress = [t for t in self.in_progress if t != tok] + [tok]
            while len(self.in_progress) > MBT_GUARD_MAX_IN_PROGRESS:
                self.in_progress.pop(0)
            return {"armed": True}
        if op == "complete":
            self.in_progress = [t for t in self.in_progress if t != ev["token"]]
            return {}
        if op == "disarmBackground":
            self.in_progress = []
            return {}
        if op == "launch":
            rt = ev.get("restoredToken")
            if rt is not None and rt in self.in_progress:
                self.suspect = rt
                res = {"decision": "suppress"}
            elif rt is not None and self.suspect == rt:
                res = {"decision": "suppress"}
            else:
                res = {"decision": "none"}
            self.in_progress = []
            return res
        if op == "resolve":
            if ev["choice"] in ("openAnyway", "deleted"):
                self.suspect = None
            return {}
        raise ValueError(op)


def mbtiles_open_guard():
    def t(i):
        return "00000000-0000-4000-8000-%012d" % i
    A, B, C, D, E = t(1), t(2), t(3), t(4), t(5)
    cases = [
        ("a clean restore: armed before the open, completed after the first draw, next launch restores it", [
            {"op": "arm", "token": A, "foreground": True},
            {"op": "complete", "token": A},
            {"op": "launch", "restoredToken": A}]),
        ("crash while the restored pack is admitted: held back next launch; Not Now asks again; Open Anyway clears", [
            {"op": "arm", "token": A, "foreground": True},
            {"op": "launch", "restoredToken": A},
            {"op": "resolve", "choice": "notNow"},
            {"op": "launch", "restoredToken": A},
            {"op": "resolve", "choice": "openAnyway"},
            {"op": "arm", "token": A, "foreground": True},
            {"op": "complete", "token": A},
            {"op": "launch", "restoredToken": A}]),
        ("crash in the first draw after a tap whose library write landed: held back next launch", [
            {"op": "arm", "token": A, "foreground": True},
            {"op": "complete", "token": A},
            {"op": "arm", "token": B, "foreground": True},
            {"op": "launch", "restoredToken": B}]),
        ("crash while admitting a tapped pack before its write: the map that is restored is untouched", [
            {"op": "arm", "token": A, "foreground": True},
            {"op": "complete", "token": A},
            {"op": "arm", "token": B, "foreground": True},
            {"op": "launch", "restoredToken": A},
            {"op": "launch", "restoredToken": B}]),
        ("never armed in the background; going to the background disarms without blaming the pack", [
            {"op": "arm", "token": A, "foreground": False},
            {"op": "arm", "token": A, "foreground": True},
            {"op": "disarmBackground"},
            {"op": "launch", "restoredToken": A}]),
        ("Delete Map clears the suspect", [
            {"op": "arm", "token": A, "foreground": True},
            {"op": "launch", "restoredToken": A},
            {"op": "resolve", "choice": "deleted"},
            {"op": "launch", "restoredToken": None}]),
        ("a standing suspect stays held back until resolved; another map restores normally meanwhile", [
            {"op": "arm", "token": A, "foreground": True},
            {"op": "launch", "restoredToken": A},
            {"op": "resolve", "choice": "notNow"},
            {"op": "arm", "token": B, "foreground": True},
            {"op": "complete", "token": B},
            {"op": "launch", "restoredToken": B},
            {"op": "launch", "restoredToken": A}]),
        ("re-arming keeps one marker per pack (moved to newest); at most 4 markers, the oldest goes", [
            {"op": "arm", "token": A, "foreground": True},
            {"op": "arm", "token": B, "foreground": True},
            {"op": "arm", "token": A, "foreground": True},
            {"op": "arm", "token": C, "foreground": True},
            {"op": "arm", "token": D, "foreground": True},
            {"op": "arm", "token": E, "foreground": True},
            {"op": "launch", "restoredToken": B}]),
        ("complete for a pack that isn't armed changes nothing", [
            {"op": "arm", "token": A, "foreground": True},
            {"op": "complete", "token": B},
            {"op": "launch", "restoredToken": A}]),
    ]
    out = []
    for label, events in cases:
        g = MbtOpenGuard()
        steps = []
        for ev in events:
            res = g.step(ev)
            steps.append({"event": ev, "result": res, "state": g.state()})
        out.append({"label": label, "steps": steps})
    # the two crash rows really hold the pack back, the clean ones really don't
    assert out[1]["steps"][1]["result"] == {"decision": "suppress"} and out[0]["steps"][2]["result"] == {"decision": "none"}
    assert out[3]["steps"][3]["result"] == {"decision": "none"} and out[7]["steps"][6]["result"] == {"decision": "none"}
    return {
        "fileName": "mbtiles_open_guard.json",
        "fileVersion": 1,
        "maxInProgress": MBT_GUARD_MAX_IN_PROGRESS,
        "token": "the library entry id (a random UUID; iOS entry.id.uuidString, Android entry.id). Nothing else goes "
                 "in the file: no name, path or hash",
        "location": {"ios": "Application Support, excluded from backup, completeUntilFirstUserAuthentication, "
                            "written atomically then fsynced", "android": "noBackupFilesDir, written atomically then "
                                                                         "fsynced"},
        "decode": "v != 1 or unreadable: an empty state. Tokens that aren't UUIDs are dropped, inProgress keeps "
                  "its newest maxInProgress",
        "firstDrawQuietMs": 500,
        "noReadCompleteMs": 2000,
        "window": (
            "arm(foreground only) before the restore or activation opens the pack, durably on disk before the open "
            "starts. complete when: the open refused the pack (nothing will draw); or after the source is "
            "published, at least one tile read has been delivered and no read for it has been pending for "
            "firstDrawQuietMs; or no read was requested within noReadCompleteMs of publication (camera outside "
            "the pack); or the source was replaced or closed before that. disarmBackground when the scene / "
            "Activity goes to the background, next to the PDF guard's. launch: once per process, at the first "
            "restore that knows its restored entry (the PDF guard's moment), restoredToken = the durable active "
            "entry when it is an MBTiles pack, else null; a later restore in the same process still holds back a "
            "standing suspect"),
        "suppress": (
            "don't open the pack: publish online(preferred style) in memory only (the durable selection and the "
            "entry stay as they are, like the PDF guard) and show the PDF guard's alert reused as is: "
            "pdf_guard_crash_title {name} / pdf_guard_crash_message with pdf_guard_open_anyway, "
            "pdf_guard_delete_map (the usual delete confirm) and Not Now. Open Anyway = resolve(openAnyway), then "
            "the normal guarded open. Picking the entry in Layers counts as Open Anyway. Not Now = "
            "resolve(notNow): next launch asks again. Delete = the library delete, then resolve(deleted)"),
        "cases": out,
    }


def mbtiles_admission():
    adm = {
        "maxRows": 64,
        "maxKeyCharacters": 32,
        "utf8PrefixBytesPerCharacter": 4,
        "knownValueMaxCharacters": {"name": 128, "format": 32, "minzoom": 16, "maxzoom": 16, "bounds": 256},
        "truncateFields": ["key", "name", "format"],
        "consumedBakeExtensionMaxCharacters": 128,
        "extensionCases": [
            {"id": "validBakeKey", "requestedKey": "tacmap_bake_key", "rows": [{"key": "tacmap_bake_key", "value": "a" * 64}], "mapAccepted": True, "expected": "a" * 64},
            {"id": "mixedCaseBakeKey", "requestedKey": "tacmap_bake_key", "rows": [{"key": "TACMAP_BAKE_KEY", "value": "b" * 64}], "mapAccepted": True, "expected": "b" * 64},
            {"id": "caseVariantDuplicate", "requestedKey": "tacmap_bake_key", "rows": [{"key": "tacmap_bake_key", "value": "first"}, {"key": "TACMAP_BAKE_KEY", "value": "second"}], "mapAccepted": True, "expected": None},
            {"id": "validTilePixels", "requestedKey": "tacmap_tile_px", "rows": [{"key": "tacmap_tile_px", "value": "768"}], "mapAccepted": True, "expected": "768"},
            {"id": "bakeKeyNul", "requestedKey": "tacmap_bake_key", "rows": [{"key": "tacmap_bake_key", "value": "a\u0000hidden"}], "mapAccepted": True, "expected": None},
            {"id": "duplicateBakeKey", "requestedKey": "tacmap_bake_key", "rows": [{"key": "tacmap_bake_key", "value": "first"}, {"key": "tacmap_bake_key", "value": "second"}], "mapAccepted": True, "expected": None},
            {"id": "oversizedBakeKey", "requestedKey": "tacmap_bake_key", "rows": [{"key": "tacmap_bake_key", "valueRepeat": ["x", 1048576]}], "mapAccepted": True, "expected": None},
            {"id": "nonTextBakeKey", "requestedKey": "tacmap_bake_key", "rows": [{"key": "tacmap_bake_key", "integerValue": 7}], "mapAccepted": True, "expected": None},
            {"id": "invalidUtf8BakeKey", "requestedKey": "tacmap_bake_key", "rows": [{"key": "tacmap_bake_key", "textBytesHex": "ff"}], "mapAccepted": True, "expected": None},
        ],
        "tileZoomMin": 0,
        "tileZoomMax": 30,
        "tileZoomCases": [
            {"id": "integerZero", "storageType": "integer", "value": 0, "accepted": True},
            {"id": "integerThirty", "storageType": "integer", "value": 30, "accepted": True},
            {"id": "negativeInteger", "storageType": "integer", "value": -1, "accepted": False},
            {"id": "integerThirtyOne", "storageType": "integer", "value": 31, "accepted": False},
            {"id": "numericText", "storageType": "text", "value": "0", "accepted": False},
            {"id": "hugeText", "storageType": "text", "valueRepeat": ["9", 1048576], "accepted": False},
            {"id": "realZero", "storageType": "real", "value": 0.0, "accepted": False},
        ],
        "rules": "Read LIMIT 65 scalar descriptors before text. Copy only bounded UTF-8 BLOB prefixes; "
                 "skip unknown values. Known duplicate keys/non-text values, non-text keys, invalid UTF-8 "
                 "or NUL in copied prefixes fail closed. Numeric/bounds overflow fails closed. "
                 "ASCII vectors below are portable; platform Unicode character segmentation may differ. "
                 "Only tacmap_bake_key/tacmap_tile_px are consumed lazily, with strict UTF-8/NUL/type/128-character/duplicate checks; invalid extension reads are missing and ordinary maps remain admissible.",
        "cases": [
            {"id": "descriptors64", "unknownRows": 64, "rows": [], "accepted": True},
            {"id": "descriptors65", "unknownRows": 65, "rows": [], "accepted": False},
            {"id": "unknownLargeValue", "rows": [{"key": "vendor", "valueRepeat": ["x", 1048576]}], "accepted": True},
            {"id": "nameTruncated", "rows": [{"key": "name", "value": "n" * 129}], "accepted": True, "name": "n" * 128},
            {"id": "formatTruncated", "rows": [{"key": "format", "value": "f" * 33}], "accepted": True, "format": "f" * 32},
            {"id": "duplicateKnown", "rows": [{"key": "name", "value": "first"}, {"key": "NAME", "value": "second"}], "accepted": False},
            {"id": "knownNonText", "rows": [{"key": "minzoom", "integerValue": 0}], "accepted": False},
            {"id": "zoomOverflow", "rows": [{"key": "minzoom", "value": "0" * 17}], "accepted": False},
            {"id": "boundsOverflow", "rows": [{"key": "bounds", "value": " " * 257}], "accepted": False},
            {"id": "nameNul", "rows": [{"key": "name", "value": "a\u0000b"}], "accepted": False},
            {"id": "invalidUtf8", "rows": [{"key": "name", "textBytesHex": "ff"}], "accepted": False},
            {"id": "unknownLargeKey", "rows": [{"key": "v" * 1000, "value": "ignored"}], "accepted": True},
        ],
    }
    _mbt_check_vectors(adm["cases"], adm["tileZoomCases"], adm["extensionCases"])
    # 3.0.1: tiles and metadata may each be a table or a view (MBTiles 1.3), the 2.x Android behaviour
    adm["relationTypes"] = ["table", "view"]
    adm["admissionBudgetMs"] = 30000
    adm["admissionBudgetAppliesTo"] = "views"
    adm["viewQueryBudgetMs"] = 2000
    adm["relationRules"] = (
        "Exactly one sqlite_master row named 'tiles' and one named 'metadata' (3.0.2 SEC-M1-SHADOW: name compared "
        "NOCASE, the way SQLite resolves FROM tiles), each type 'table' or 'view'; anything else (missing, a case "
        "variant second row, index, trigger) fails closed. 3.0.2: a VIEW is admitted only if its "
        "sqlite_master.sql passes viewShape, and every table a read touches passes connection.rules (ordinary, "
        "not virtual, no generated column), all before any statement names tiles or metadata. A TABLE keeps the "
        "existing reads (rowid descriptors; iOS incremental blob, Android substr(CAST) by rowid). A VIEW has no "
        "rowid and no incremental blob, so it uses key-addressed bounded reads: descriptors = SELECT typeof(name), "
        "typeof(value), length(CAST(name AS BLOB)), substr(CAST(name AS BLOB), 1, 128) FROM metadata LIMIT 65; "
        "each known key present = SELECT typeof(value), length(CAST(value AS BLOB)), substr(CAST(value AS BLOB), "
        "1, <chars*4>) FROM metadata WHERE lower(name) = ? LIMIT 2, exactly one TEXT row or fail closed; the "
        "bake extension reader the same with 512 bytes and missing on anything but exactly one TEXT row. On the "
        "view path a name or value over connection.maxValueBytes fails closed. Every other admission check is "
        "unchanged on both. 3.0.2 (F3): when tiles or metadata is a view, every admission statement after the "
        "relation checks shares one deadline of admissionBudgetMs; on expiry the statement is interrupted (iOS "
        "sqlite3_progress_handler, Android CancellationSignal) and the pack is not admitted. Two tables get no "
        "admission budget (3.0.1 gave them one): their aggregate is a single scan the file size bounds, and it "
        "now runs off the main thread. After admission, statements against a relation that is a VIEW (tile "
        "reads, lazy extension reads) each get viewQueryBudgetMs; an interrupted read is a missing tile / missing "
        "extension. Tables get no per-read budget")
    adm["connection"] = mbtiles_connection()
    adm["viewShape"] = {
        "maxSqlBytes": MBT_MAX_SCHEMA_SQL_BYTES,
        "whitespace": [" ", "\t", "\r", "\n"],
        "punctuation": list(MBT_VIEW_PUNCT),
        "quotedIdentifiers": [["\"", "\""], ["[", "]"], ["`", "`"]],
        "reservedWords": MBT_RESERVED,
        "tokens": (
            "ASCII whitespace separates tokens. A word is [A-Za-z_][A-Za-z0-9_]* (ASCII). A quoted identifier is "
            "one of the three pairs with a non-empty body of printable ASCII (0x20-0x7E) holding none of "
            "\" [ ] `, and the character right after its closing mark may not be one of \" [ ] ` either (SQLite "
            "reads \"a\"\"b\" as the one name a\"b; splitting it would check the wrong base table). Punctuation "
            "is ( ) , . * =. Anything else, anywhere in the text, is reason token. Text longer than maxSqlBytes "
            "(UTF-8) is reason tooLong before tokenizing"),
        "grammar": (
            "CREATE VIEW name [( ident {, ident} )] AS SELECT column {, column} FROM table "
            "[join] <end>. name = ident equal to the relation (ASCII case-insensitive). column = * | ident . * | "
            "colref [[AS] ident]. colref = ident [. ident]. table = ident [[AS] ident]. join = [INNER | LEFT "
            "[OUTER] | CROSS] JOIN table (ON equalities | USING ( ident {, ident} )) | , table WHERE equalities. "
            "equalities = eq {AND eq} | ( eq {AND eq} ), eq = colref = colref (3.0.2 SHADOW-PARITY-2: the comma "
            "join and the AND, how gdal2mbtiles and tippecanoe/tile-join store tiles; still an equi-join of two "
            "tables on column refs). ident = a quoted identifier, or a word not in reservedWords (ASCII "
            "case-insensitive). Keywords match words only, ASCII case-insensitively. Any other token sequence is "
            "reason shape, IF NOT EXISTS included (3.0.2 SEC-M1-SHADOW: SQLite never stores it, so it only turns up "
            "in a hand edited schema). Accepted: the base tables, each table's ident in order (quoted ones without "
            "their marks)"),
        "cases": view_shape_cases(),
    }
    adm["baseTableShape"] = {
        "rule": (
            "3.0.2 SEC-M1-SHADOW, table_declares(sql, name) in the generator: a table row's sqlite_master.sql "
            "passes when it matches [ \\t\\r\\n]*([A-Za-z]+)[ \\t\\r\\n]+([A-Za-z]+) with the two words CREATE and "
            "TABLE (ASCII case-insensitive), then optional viewShape whitespace and one identifier: a viewShape "
            "quoted identifier, or a word ([A-Za-z_][A-Za-z0-9_]*, ASCII, not in viewShape.reservedWords) that "
            "has at least one whitespace character before it. The character right after the identifier may not be "
            "\" [ ] ` or a dot, and the identifier (quoted ones without their marks) has to equal name ASCII "
            "case-insensitively. Nothing after that is read. Any other text, and NULL, fails"),
        "cases": table_shape_cases(),
    }
    adm["relationVariants"] = MBT_VARIANTS
    adm["variantRule"] = ("every cases[] and extensionCases[] row gives the same verdict and values after its own "
                          "setup plus metadataView or bothViews; every tileZoomCases[] row after tilesView or "
                          "bothViews. The generator proves it against its reference admission")
    adm["relationCases"] = mbtiles_relation_cases()
    adm["relationCasesRule"] = ("run sql[] in order on an empty database file with an ordinary connection (a row "
                                "with packBase64 is that exact file instead: write the decoded bytes and skip sql[], "
                                "which needs writable_schema and Apple's SQLite refuses that by default), open it "
                                "with the real reader (testBudgetMs, when given, replaces admissionBudgetMs through "
                                "the test seam) and compare expect: accepted, and when accepted minZoom, maxZoom, "
                                "name, format, each tiles[] probe (XYZ, hex null = no tile) and extensions{}. Skip a "
                                "row whose minSqliteVersion is above the runtime sqlite_version() (it can't be "
                                "built there). rejectedAt and relations are the reference's notes, not asserted")
    return adm


def import_doc():
    return {
        "description": "Import limits, import errors -> message keys, the pure ImportDecision table, library entry "
                       "states and the calibration lifecycle tables (plans/WP4-calibration-lifecycle-shared_contract"
                       ".md s2, s3, s8, s9). Both apps load the constants from here in tests. Generated by "
                       "scripts/gen_calibration_fixtures.py, never hand edited.",
        "schemaVersion": 1,
        "generator": "scripts/gen_calibration_fixtures.py",
        "calibration": CALIBRATION,
        "import": IMPORT,
        "mbtilesMetadataAdmission": mbtiles_admission(),
        "mbtilesOpenGuard": mbtiles_open_guard(),
        "errors": {k: {"key": key(v[0]), "args": v[1]} for k, v in IMPORT_ERRORS.items()},
        "georefRejectReasons": ["lptsOutOfRange", "nonFinite", "gptsOffEarth", "rmsGate", "degenerateViewport",
                                "malformed", "unknownDatum", "unsupportedProjection"],
        "rules": {
            "prechecks": "s9.2 in order: library not Loaded -> locked; entries >= maxLibraryEntries -> libraryFull "
                         "{limit}; size > pdfMaxBytes / mbtilesMaxBytes -> tooLarge {limit in bytes}; free < size + "
                         "freeSpaceMarginBytes -> noSpace {size = size + margin, in bytes}. Byte args are formatted "
                         "by the platform (ByteCountFormatter / Formatter.formatShortFileSize)",
            "inspection": "s9.5 in order: can't open -> invalidPdf; encrypted and the empty password doesn't open "
                          "it -> password; pages > maxPages -> tooManyPages {limit}; any page's MediaBox or CropBox "
                          "(CropBox defaults to MediaBox) with a side outside [pageSideMinPt, pageSideMaxPt] -> "
                          "pageSize. Watchdog past parseTimeoutMs, and on Android OutOfMemoryError / "
                          "StackOverflowError at the inspector boundary, -> tooComplex",
            "decision": "s9.4 + s9.6, see decisions[]. pages[] lists the scanned pages only (georefScanPages); "
                        "page in an outcome is 0-based, the toast args are 1-based",
            "reasonText": "map_import_georef_rejected {reason} gets a georefRejectReasons code; the contract has "
                          "no per-reason copy yet",
            "entryStates": "s8.2 derived state: unavailable (file missing or size/mtime mismatch) overrides; "
                           "mbtiles -> offlineTiles or derived; manual -> calibrated; embedded -> geoPDF; "
                           "embeddedIssue -> rejected; else needsCalibration. effective georef = manual ?? embedded. "
                           "Subtitle: unavailable, else a draft's map_state_draft, else the state's. rowTap: "
                           "activate when it can be the durable active selection, calibrate for the other PDFs, "
                           "nothing when unavailable (the start precondition needs the file). Menu ids are the "
                           "map_action_* keys plus generateOfflineTiles (WP2) for entries with an effective "
                           "georef; unavailable rows only offer delete",
        },
        "prechecks": prechecks(),
        "inspections": inspections(),
        "decisions": decisions(),
        "entryStates": entry_states(),
        "lifecycle": lifecycle(),
        "libraryLoad": library_load_rows(),
        "sizeDisplay": {"rule": "OD-F6: import byte args (too_large {limit}, no_space {size}) and the Layers footer "
                                "{size} use the pdf_tile_render.json bakeFormat size rule. The footer counts every "
                                "entry's file plus its bake file (OD-F14)",
                        "cases": [{"bytes": b, "en": size_text(b, "en"), "de": size_text(b, "de")}
                                  for b in (IMPORT["pdfMaxBytes"], IMPORT["mbtilesMaxBytes"], 40 * MiB + 64 * MiB,
                                            7 * 1024, 0)]},
    }


# ----------------------------------------------------------------------------
# calibration_fit_report.json
# ----------------------------------------------------------------------------

def strip_private(c):
    return {k: v for k, v in c.items() if not k.startswith("_")}


def rms_display(cases):
    """OD-F12 the {rms} arg text, en + de. Values sit well clear of rounding ties"""
    vals = [0.0, 0.04, 0.37, 0.96, 4.2, 9.94, 9.96, 10.0, 25.7, 999.4, 1234.0]
    for v in [c["expect"]["rmsM"] for c in cases if c["expect"]["rmsM"] is not None and c["expect"]["grade"]]:
        if v not in vals and len(vals) < 15:
            vals.append(v)
    out = []
    for v in vals:
        for d in (1, 0):
            f = abs(v) * 10 ** d % 1.0
            assert abs(f - 0.5) > 1e-4, "%r sits near a rounding tie" % v
        out.append({"rmsM": v, "en": rms_text(v, "en"), "de": rms_text(v, "de")})
    return {"rule": "OD-F12: the {rms} arg of calibration_fit_summary, calibration_done, calibration_finish_confirm_poor "
                    "and map_state_calibrated. rmsM < 9.95: one decimal + ' m' (DisplayFormat.number, 1 decimal, "
                    "locale decimal separator), else DisplayFormat.distance. Other distances (outlier, disagree, "
                    "warn_far, residual rows) stay DisplayFormat.distance",
            "cases": out}


def fit_doc():
    cases, sheets, sh = fit_cases()
    checks = entry_checks(cases, sheets, sh)
    anchors, caps = camera_cases(cases, sheets, sh)
    for c in cases:
        c["expect"].pop("blocked", None)
    return {
        "description": "CalibrationFitReport (plans/WP4-calibration-lifecycle-shared_contract.md s5-s7): the fit "
                       "panel classification, entry-time checks, the camera anchor and capture. Expected numbers "
                       "from PROJ (pyproj) + numpy. Generated by scripts/gen_calibration_fixtures.py, never hand "
                       "edited.",
        "schemaVersion": 1,
        "generator": "scripts/gen_calibration_fixtures.py",
        "tolerance": {"metres": 0.01, "ratio": 1e-4, "affineRelative": 1e-7, "degrees": 1e-9, "zoom": 1e-6,
                      "pagePoints": 1e-6},
        "rules": {
            "points": "page = raw pdf user space (y up, /Rotate ignored). reference is what the parser gives "
                      "(calibration_input.json): grid {zone, south, easting, northing (SW corner), cellSizeM, "
                      "source} or geographic {lat, lon}; kind feature adds cellSizeM/2 to E and N for cells over "
                      "1 m. geographic is sheet datum unless datumOverride WGS84 (GPS): then datum.fromWGS84",
            "planeZone": "zone + hemisphere of the LOWEST-numbered point: its grid zone if typed as grid, else "
                         "floor((lon+180)/6)+1 clamped to 1..60 (lon 180 -> 60) and lat < 0 (no Norway/Svalbard "
                         "exceptions). A grid point in that "
                         "zone is used as is, everything else goes lat/lon -> TM forward on the sheet ellipsoid. A "
                         "point more than 90 deg of longitude from the plane CM can't be forwarded",
            "fit": "least squares page -> plane affine [a,b,c,d,e,f] (X = a x + b y + c), the WP1 fitter. "
                   "residual = plane distance, RMS = sqrt(sum r^2 / n). D = longer pageBox diagonal mapped through "
                   "the fit (plane metres), tau = max(10, 0.0005 D)",
            "blocked": "n >= 3, in this order: degenerate (page eigen ratio < 0.02, the fitter refuses, no fit); "
                       "invalid (a point can't be forwarded, the fit isn't finite or |det L| == 0, or a pageBox "
                       "corner's toWGS84 is nil / non-finite / |lat| > 85); implausible (anisotropy = sigma_max / "
                       "sigma_min of L > 1.5, or scaleDenominator = sqrt|det L| / (0.0254/72) outside 1,000 - "
                       "5,000,000). Contract s6.3 (amended r1) has the same order: invalid can't be evaluated "
                       "without the fit WP1 refuses for a degenerate set, so degenerate goes first "
                       "(d2_10_collar_zone_typo_still_degenerate pins it). Fit numbers are reported whenever a "
                       "fit exists, grade/issue/rowStatus only when not blocked",
            "grade": "n >= 4: good (RMS <= tau), fair (<= 3 tau), poor. n = 3: exact (grade null)",
            "leaveOneOut": "looRmsM[k] = RMS of the fit without k on its own points, looM[k] = k's distance to "
                           "that fit; null when the others are < 3 or degenerate",
            "issue": "n >= 5 and RMS > tau: S = {k : looRms_k <= tau}; |S| = 1 outlier {number, distanceM = "
                     "looM}; |S| >= 2 ambiguous {first, second} = the two smallest looRms, smaller first, ties to "
                     "the lower number; |S| = 0 disagree {maxResidualM}. n = 4 and RMS > tau: disagreeAddFifth "
                     "{maxResidualM}. flagged = [outlier number]",
            "rowStatus": "points sheet glyph per point when graded: error (> 3 tau or flagged), warn (> tau), ok",
            "spread": "n >= 3: points' page bbox narrower than 0.25 of the pageBox width or shorter than 0.25 of "
                      "its height",
            "nextCorner": "while n < 3 or spreadLow: pageBox corners as VIEWED after /Rotate (rotate 90: viewed "
                          "top-left is raw bottom-left), each moved 10% of the way to the opposite corner; pick the "
                          "one with the largest minimum page distance to the points; ties go top-left, top-right, "
                          "bottom-right, bottom-left. nextCornerPage is that inset point in raw page space",
            "finish": "blocked {reason: needMore (needMore = 3 - n) | degenerate | invalid | implausible}, else "
                      "confirm {reasons in order exact, poor, outlier|ambiguous, spreadLow}, else ready",
            "primaryStatus": "panel line 1 without the camera-dependent calibration_off_sheet: intro (n = 0), "
                             "blocked reason, issue, exact fit text (n = 3) or fit summary, need points (n < 3). "
                             "Distances/RMS args are metres (DisplayFormat.distance), points args are counts for "
                             "the calibration_point plural, grade is the calibration_grade_* key",
            "entryChecks": "predictor: (1) editing k with >= 3 other points that aren't degenerate: their fit, h = "
                           "x0'(A'A)^-1 x0 over the other points' [x y 1] rows; (2) n >= 3 and not blocked: the "
                           "current fit, h over all current points; (3) base georef that isn't provisional: the "
                           "base, h = 0, tau from the WGS84 geodesic length of the pageBox diagonals through it; "
                           "(4) none. d = WGS84 geodesic between predictor.toWGS84(page) and the typed reference "
                           "(resolved, toWGS84). warn when d > max(3 tau sqrt(1+h), 50)",
            "cameraAnchor": "p = old.toPage(camera); centre = new.toWGS84(p); s(g) = sqrt|det J|, J = d(web "
                            "mercator zoom-0 256-unit world of g.toWGS84)/d(page) by central differences, h = 1 pt; "
                            "zoom = clamp(zoom + log2(s_old(p)/s_new(p)), 2, 22); heading unchanged",
            "capture": "page = georef.toPage(camera centre); onSheet = inside the pageBox (edges count); "
                       "screenPtPerPagePt = 2^zoom * s(georef) at that page point; zoomHint when < 1",
            "provisional": "provisionalInputs: PdfGeoreference.provisional(pageBox, rotate, centre) = geographic "
                           "WGS84, 1 pt = 50000 * 0.0254/72 m east and north at the centre latitude (WGS84 radii), "
                           "rotated by -rotate about the box centre, centre lat clamped to +-80",
        },
        "messageKeys": {
            "blocked": {k: key("calibration_" + k) for k in ("degenerate", "invalid", "implausible")},
            "issue": {"outlier": key("calibration_outlier"), "ambiguous": key("calibration_ambiguous"),
                      "disagree": key("calibration_disagree"),
                      "disagreeAddFifth": key("calibration_disagree_add_fifth")},
            "confirm": {"exact": key("calibration_finish_confirm_exact"),
                        "poor": key("calibration_finish_confirm_poor"), "outlier": key("calibration_outlier"),
                        "ambiguous": key("calibration_ambiguous"),
                        "spreadLow": key("calibration_finish_confirm_spread")},
            "grade": {g: key("calibration_grade_" + g) for g in ("good", "fair", "poor")},
            "nextCorner": {k: key(v) for k, v in CORNER_KEYS.items()},
            "spreadLow": key("calibration_spread_low"),
        },
        "rmsDisplay": rms_display(cases),
        "cases": [strip_private(c) for c in cases],
        "entryChecks": checks,
        "cameraAnchor": anchors,
        "capture": caps,
    }


# ----------------------------------------------------------------------------

def contract_keys():
    if not os.path.exists(CONTRACT):
        print("warning: %s missing, message keys not cross-checked" % CONTRACT)
        return None
    text = open(CONTRACT, encoding="utf-8").read()
    return set(re.findall(r"`((?:calibration|map)_[a-z0-9_]+)", text))


def main():
    global GEO
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.join(REPO, "testdata"), help="testdata dir (default: repo testdata/)")
    args = ap.parse_args()
    GEO = Geo()
    check_size_rule_matches_wp2()
    docs = [("calibration_input.json", input_doc()), ("calibration_fit_report.json", fit_doc()),
            ("import_limits.json", import_doc())]
    known = contract_keys()
    if known is not None:
        # calibration_next_corner_{top_left|...} and calibration_grade_good / _fair / _poor are written as
        # one pattern each in the contract
        known |= set(CORNER_KEYS.values()) | {"calibration_grade_fair", "calibration_grade_poor"}
        missing = sorted(k for k in USED_KEYS if k not in known)
        assert not missing, "keys not in the contract copy table: %s" % missing
    os.makedirs(args.out, exist_ok=True)
    for name, doc in docs:
        doc["reference"] = {"pyproj": GEO.ref.pyproj.__version__, "proj": GEO.ref.pyproj.proj_version_str,
                            "numpy": GEO.np.__version__}
        with open(os.path.join(args.out, name), "w") as fh:
            fh.write(G.dumps(doc) + "\n")
        print("wrote %s" % name)


if __name__ == "__main__":
    main()
