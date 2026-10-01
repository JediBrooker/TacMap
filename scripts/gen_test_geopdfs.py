#!/usr/bin/env python3
"""Shared GeoPDF georeference fixtures for the iOS + Android PDF import.

Writes (default: the repo's testdata/):
  geopdf/*.pdf       tiny vector-only synthetic map sheets, plus the tacmap_budget_*.pdf
                     page-budget cases (shared indirect arrays, no ink)
  pdf_georef.json    expected georeference numbers both unit-test suites load

This is the "independent third implementation" from plans/02-pdf-import-revision.md.
The PDFs are built with stdlib only (plain python Kruger TM + Snyder LCC for
placing ink and GPTS). Every expected number in the json comes from PROJ via
pyproj plus a small numpy datum shift, never from app code. So you need:

  pip install pyproj numpy
  python3 scripts/gen_test_geopdfs.py              # regenerate testdata/
  python3 scripts/gen_test_geopdfs.py --out /tmp/x # somewhere else
  python3 scripts/gen_test_geopdfs.py --measure-usgs samples/USGS_SF_North.pdf
      (re-measures the printed UTM grid of the real USGS sheet, needs pymupdf,
       prints the table that USGS_PRINTED_GRID below was pasted from)

Deterministic on purpose: no dates, content streams stored uncompressed, and
every number goes into the PDF as a fixed decimal string (degrees 12dp,
points/metres 6dp). The json is computed from those exact strings, so a parser
that keeps doubles reproduces it. Heads up: MuPDF (and PDFBox 3.x) hold reals
as float32, which moves GPTS by up to ~1 m at lon 150, so never round-trip GPTS
through them when checking this stuff.

The real USGS US Topo sample (samples/USGS_SF_North.pdf, 38 MB, not in git) is
pinned by its /VP dictionaries copied verbatim below, and also rebuilt as a
small stand-in PDF carrying those exact dictionaries.
"""

import argparse
import hashlib
import json
import math
import os
import re
import sys
from decimal import Decimal

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

PT_M = 0.0254 / 72.0          # metres of paper per PDF point
S25K = 25000 * PT_M           # ground metres per point at 1:25k
S50K = 50000 * PT_M

DEG_DP = 12                   # decimals for degrees written into PDFs
PT_DP = 6                     # decimals for page points written into PDFs
M_DP = 6                      # decimals for plane metres written into PDFs


# ----------------------------------------------------------------------------
# numbers. PDF has no exponent syntax, so never let repr() leak a 1e-05 in
# ----------------------------------------------------------------------------

def dec(v, nd):
    v = round(float(v), nd)
    if v == 0:
        return "0"
    s = format(Decimal(repr(v)), "f")
    if "." in s:
        s = s.rstrip("0").rstrip(".")
    return s


def num(v, nd):
    """the double a pdf reader gets back for dec(v, nd)"""
    return float(dec(v, nd))


def nums(vals, nd):
    return " ".join(dec(v, nd) for v in vals)


# ----------------------------------------------------------------------------
# the shared datum table. This is the contract both platforms load. Values are
# EPSG published parameters (checked against proj.db), see DATUM_NOTES for
# where the apps' old constants were off.
# ----------------------------------------------------------------------------

ELLIPSOIDS = {
    "WGS84": (6378137.0, 298.257223563),
    "GRS80": (6378137.0, 298.257222101),
    "Clarke1866": (6378206.4, 294.978698213898),
    "International1924": (6378388.0, 297.0),
    "Airy1830": (6377563.396, 299.3249646),
    "Bessel1841": (6377397.155, 299.1528128),
    "Clarke1880IGN": (6378249.2, 293.466021293627),
    "Krassovsky1940": (6378245.0, 298.3),
}


def _d(id_, ell, transform, dx=0.0, dy=0.0, dz=0.0, source="", wkt=(), lgi=(), prefixes=(),
       epsg_datum=None, epsg_geog=(), extra=None, note=None):
    d = {"id": id_, "ellipsoid": ell, "a": ELLIPSOIDS[ell][0], "invF": ELLIPSOIDS[ell][1],
         "transform": transform, "dx": dx, "dy": dy, "dz": dz}
    if extra:
        d.update(extra)
    d["source"] = source
    d["aliases"] = {"wktDatumNames": list(wkt), "lgiCodes": list(lgi), "lgiCodePrefixes": list(prefixes),
                    "epsgDatum": epsg_datum, "epsgGeographic": list(epsg_geog)}
    if note:
        d["note"] = note
    return d


GDA94_HELMERT = {"rxArcsec": -0.0394924, "ryArcsec": -0.0327221, "rzArcsec": -0.0328979,
                 "scalePpm": -0.009994, "convention": "coordinateFrame"}

DATUMS = [
    _d("WGS84", "WGS84", "identity", source="identity",
       wkt=["WGS_1984", "WGS 84", "WGS84", "World Geodetic System 1984", "D_WGS_1984"],
       lgi=["WE", "WGE", "WD"], epsg_datum=6326, epsg_geog=[4326],
       note="WD kept for backward compat with the old tables, see discrepancies"),
    _d("NAD83", "GRS80", "identity", source="identity (sub-metre, plan s1)",
       wkt=["North_American_Datum_1983", "North American Datum 1983", "NAD83", "D_North_American_1983"],
       lgi=["NA", "NAR"], prefixes=["NAR-"], epsg_datum=6269, epsg_geog=[4269]),
    _d("GDA94", "GRS80", "helmert7", 0.06155, -0.01087, -0.04019,
       source="EPSG:8048 GDA94 to GDA2020 (1), coordinate frame; GDA2020 then taken as WGS84",
       wkt=["Geocentric_Datum_of_Australia_1994", "GDA94", "D_GDA_1994"],
       lgi=["GD"], epsg_datum=6283, epsg_geog=[4283], extra=GDA94_HELMERT),
    _d("GDA2020", "GRS80", "identity", source="identity (GDA2020 ~ WGS84 G2139 at the cm level)",
       wkt=["Geocentric_Datum_of_Australia_2020", "GDA2020", "D_GDA2020"],
       epsg_datum=1168, epsg_geog=[7844]),
    _d("ETRS89", "GRS80", "identity", source="identity (plan s1)",
       wkt=["European_Terrestrial_Reference_System_1989", "ETRS89", "D_ETRS_1989"],
       epsg_datum=6258, epsg_geog=[4258]),
    _d("NAD27", "Clarke1866", "translation3", -8.0, 160.0, 176.0, source="EPSG:1173 NAD27 to WGS 84 (4), CONUS mean (NIMA NAS-C)",
       wkt=["North_American_Datum_1927", "North American Datum 1927", "NAD27", "D_North_American_1927"],
       lgi=["NS", "NAS", "NAS-C"], prefixes=["NAS-"], epsg_datum=6267, epsg_geog=[4267],
       note="any NAS-x code without its own row falls back to this CONUS mean"),
    _d("NAD27_CONUS_EAST", "Clarke1866", "translation3", -9.0, 161.0, 179.0,
       source="EPSG:1174 NAD27 to WGS 84 (5), CONUS east of Mississippi (NAS-A)", lgi=["NAS-A"]),
    _d("NAD27_CONUS_WEST", "Clarke1866", "translation3", -8.0, 159.0, 175.0,
       source="EPSG:1175 NAD27 to WGS 84 (6), CONUS west of Mississippi (NAS-B)", lgi=["NAS-B"]),
    _d("NAD27_ALASKA", "Clarke1866", "translation3", -5.0, 135.0, 172.0,
       source="EPSG:1176 NAD27 to WGS 84 (7), Alaska mainland (NAS-D)", lgi=["NAS-D"]),
    _d("NAD27_CANADA", "Clarke1866", "translation3", -10.0, 158.0, 187.0,
       source="EPSG:1172 NAD27 to WGS 84 (3), Canada mean (NAS-E)", lgi=["NAS-E"]),
    _d("ED50", "International1924", "translation3", -87.0, -98.0, -121.0,
       source="EPSG:1133 ED50 to WGS 84 (1), DMA western Europe mean (EUR-M)",
       wkt=["European_Datum_1950", "European Datum 1950", "ED50", "D_European_1950"],
       lgi=["EU", "EUR", "EUR-M"], prefixes=["EUR-"], epsg_datum=6230, epsg_geog=[4230]),
    _d("OSGB36", "Airy1830", "helmert7", 446.448, -125.157, 542.06,
       source="EPSG:1314 OSGB36 to WGS 84 (6), the OS 'petroleum' 7-param, rotations flipped from position vector "
              "to coordinate frame",
       extra={"rxArcsec": -0.15, "ryArcsec": -0.247, "rzArcsec": -0.842, "scalePpm": -20.489,
              "convention": "coordinateFrame"},
       wkt=["OSGB_1936", "OSGB 1936", "OSGB36", "D_OSGB_1936", "Ordnance Survey of Great Britain 1936"],
       lgi=["OB", "OG", "OS", "OGB", "OGB-M"], prefixes=["OGB-"], epsg_datum=6277, epsg_geog=[4277]),
    _d("TOKYO", "Bessel1841", "translation3", -146.414, 507.337, 680.507,
       source="EPSG:15484 Tokyo to WGS 84 (108), Japan onshore",
       wkt=["Tokyo", "D_Tokyo"], lgi=["TC", "TOY"], prefixes=["TOY-"], epsg_datum=6301, epsg_geog=[4301]),
    _d("CH1903", "Bessel1841", "translation3", 674.374, 15.056, 405.346,
       source="EPSG:1766 CH1903 to WGS 84 (2)",
       wkt=["CH1903", "D_CH1903"], lgi=["CH"], epsg_datum=6149, epsg_geog=[4149]),
    _d("NTF", "Clarke1880IGN", "translation3", -168.0, -60.0, 320.0,
       source="EPSG:1193 NTF to WGS 84 (1) (Greenwich based NTF only, Paris meridian CRSs not covered)",
       wkt=["Nouvelle_Triangulation_Francaise", "NTF", "D_NTF"], lgi=["NT", "NF"], epsg_datum=6275, epsg_geog=[4275]),
    _d("SK42", "Krassovsky1940", "helmert7", 23.92, -141.27, -80.9,
       source="EPSG:1267 Pulkovo 1942 to WGS 84 (17), Russia (GOST R 51794-2001), coordinate frame",
       extra={"rxArcsec": 0.0, "ryArcsec": -0.35, "rzArcsec": -0.82, "scalePpm": -0.12,
              "convention": "coordinateFrame"},
       wkt=["Pulkovo_1942", "Pulkovo 1942", "SK-42", "SK42", "D_Pulkovo_1942"],
       lgi=["KK", "SPK"], epsg_datum=6284, epsg_geog=[4284]),
]
DATUM_BY_ID = {d["id"]: d for d in DATUMS}

# old app constants that differ from the table above (both platforms had the
# same numbers, iOS DatumShift + Android LgiDatum.fromCode). measured errors are
# filled in by the generator against the full published 7-param transforms.
DATUM_NOTES = [
    {"datum": "OSGB36", "was": "translation3 [446.448, -125.157, 542.06]", "now": "helmert7 EPSG:1314 (full)",
     "why": "old values are only the translation half of the 7-param EPSG:1314 (rotations 0.15/0.247/0.842 arcsec, "
            "s -20.489 ppm). Dropping those leaves 13-15 m. The published 3-param set EPSG:1195 (375,-111,431) is "
            "better than the truncation but still 5-9 m, so the table uses the full 7-param through the same "
            "helmert7 path GDA94 needs anyway.",
     "check": {"points": [[51.5, -0.12], [55.95, -3.19], [50.37, -4.14]],
               "candidates": {"oldTranslationOnly": [446.448, -125.157, 542.06], "epsg1195ThreeParam": [375.0, -111.0, 431.0]}}},
    {"datum": "SK42", "was": "translation3 [23.92, -141.27, -80.9]", "now": "helmert7 EPSG:1267 (full)",
     "why": "old values are the translation half of EPSG:1267 (ry -0.35 rz -0.82 arcsec, s -0.12 ppm). The only "
            "Russia-wide 3-param set, EPSG:1254 (28,-130,-95), is worse still in the east (18-32 m), so the table "
            "uses the full 7-param.",
     "check": {"points": [[55.75, 37.62], [55.03, 82.92], [43.12, 131.9]],
               "candidates": {"oldTranslationOnly": [23.92, -141.27, -80.9], "epsg1254ThreeParam": [28.0, -130.0, -95.0]}}},
    {"datum": "GDA94", "was": "GeoPDF path (iOS DatumShift 'GD', Android LgiDatum 'GD') used a zero shift, "
                              "while the fiducial path (Datum.gda94) used the ICSM 7-param",
     "now": "one rule: ICSM 7-param GDA94 -> GDA2020 (EPSG:8048), GDA2020 == WGS84",
     "why": "same sheet landed ~1.5 m apart depending on how it was georeferenced"},
    {"datum": "Clarke1866/Clarke1880IGN", "was": [294.9786982, 293.4660213], "now": [294.978698213898, 293.466021293627],
     "why": "EPSG derives invF from a,b; old truncated values move points < 1 micron, harmless, listed for completeness"},
    {"datum": "WD", "was": "WGS84 identity", "now": "unchanged (WGS84 identity)",
     "why": "in the NIMA TR8350.2 2-letter ellipsoid table WD is WGS 72, and WGS72 -> WGS84 is not identity "
            "(dz 4.5 m, rz 0.554 arcsec, ~17 m at the equator). Couldn't confirm which producers emit WD, so kept "
            "the old mapping, flagged here. Unverified."},
    {"datum": "LGIDict codes", "was": "only WE WD GD NA OB OG OS EU NS TC CH NT NF KK",
     "now": "added DIGEST style WGE, NAR/NAR-*, NAS/NAS-A..E/NAS-*, EUR/EUR-*, OGB/OGB-*, TOY/TOY-*, SPK (D1-06)",
     "why": "real TerraGo/USGS LGIDicts use these (e.g. /Datum (NAS-C), (NAS-E), (WGE))"},
]


# ----------------------------------------------------------------------------
# plain python projection math, only used to place ink + write GPTS. The json
# never trusts this, pyproj recomputes everything (and we check they agree).
# ----------------------------------------------------------------------------

def _kruger(a, invf):
    f = 1.0 / invf
    n = f / (2 - f)
    A = a / (1 + n) * (1 + n ** 2 / 4 + n ** 4 / 64 + n ** 6 / 256)
    al = [None,
          n / 2 - 2 * n ** 2 / 3 + 5 * n ** 3 / 16 + 41 * n ** 4 / 180 - 127 * n ** 5 / 288 + 7891 * n ** 6 / 37800,
          13 * n ** 2 / 48 - 3 * n ** 3 / 5 + 557 * n ** 4 / 1440 + 281 * n ** 5 / 630 - 1983433 * n ** 6 / 1935360,
          61 * n ** 3 / 240 - 103 * n ** 4 / 140 + 15061 * n ** 5 / 26880 + 167603 * n ** 6 / 181440,
          49561 * n ** 4 / 161280 - 179 * n ** 5 / 168 + 6601661 * n ** 6 / 7257600,
          34729 * n ** 5 / 80640 - 3418889 * n ** 6 / 1995840,
          212378941 * n ** 6 / 319334400]
    be = [None,
          n / 2 - 2 * n ** 2 / 3 + 37 * n ** 3 / 96 - n ** 4 / 360 - 81 * n ** 5 / 512 + 96199 * n ** 6 / 604800,
          n ** 2 / 48 + n ** 3 / 15 - 437 * n ** 4 / 1440 + 46 * n ** 5 / 105 - 1118711 * n ** 6 / 3870720,
          17 * n ** 3 / 480 - 37 * n ** 4 / 840 - 209 * n ** 5 / 4480 + 5569 * n ** 6 / 90720,
          4397 * n ** 4 / 161280 - 11 * n ** 5 / 504 - 830251 * n ** 6 / 7257600,
          4583 * n ** 5 / 161280 - 108847 * n ** 6 / 3991680,
          20648693 * n ** 6 / 638668800]
    return A, al, be, math.sqrt(f * (2 - f))


def tm_fwd(lat, lon, lon0, k0, fe, fn, ell):
    A, al, _, e = _kruger(*ell)
    phi = math.radians(lat)
    lam = math.radians(lon - lon0)
    t = math.sinh(math.atanh(math.sin(phi)) - e * math.atanh(e * math.sin(phi)))
    xi = math.atan2(t, math.cos(lam))
    eta = math.atanh(math.sin(lam) / math.sqrt(1 + t * t))
    x = eta + sum(al[j] * math.cos(2 * j * xi) * math.sinh(2 * j * eta) for j in range(1, 7))
    y = xi + sum(al[j] * math.sin(2 * j * xi) * math.cosh(2 * j * eta) for j in range(1, 7))
    return fe + k0 * A * x, fn + k0 * A * y


def tm_inv(E, N, lon0, k0, fe, fn, ell):
    A, _, be, e = _kruger(*ell)
    xi = (N - fn) / (k0 * A)
    eta = (E - fe) / (k0 * A)
    xp = xi - sum(be[j] * math.sin(2 * j * xi) * math.cosh(2 * j * eta) for j in range(1, 7))
    ep = eta - sum(be[j] * math.cos(2 * j * xi) * math.sinh(2 * j * eta) for j in range(1, 7))
    tp = math.sin(xp) / math.sqrt(math.sinh(ep) ** 2 + math.cos(xp) ** 2)
    # newton on tau (Karney 2011 eq 19-21). the audit's tm.py had this derivative
    # upside down, which only crawls linearly and leaves mm errors 3 deg off CM
    t = tp
    for _ in range(15):
        s = math.sinh(e * math.atanh(e * t / math.sqrt(1 + t * t)))
        tt = t * math.sqrt(1 + s * s) - s * math.sqrt(1 + t * t)
        dt = (tp - tt) * (1 + (1 - e * e) * t * t) / ((1 - e * e) * math.sqrt(1 + tt * tt) * math.sqrt(1 + t * t))
        t += dt
        if abs(dt) < 1e-15:
            break
    return math.degrees(math.atan(t)), lon0 + math.degrees(math.atan2(math.sinh(ep), math.cos(xp)))


def _lcc_consts(lat1, lat2, lat0, ell):
    a, invf = ell
    f = 1 / invf
    e = math.sqrt(f * (2 - f))

    def m(p):
        return math.cos(p) / math.sqrt(1 - e * e * math.sin(p) ** 2)

    def t(p):
        return math.tan(math.pi / 4 - p / 2) / ((1 - e * math.sin(p)) / (1 + e * math.sin(p))) ** (e / 2)

    p1, p2, p0 = map(math.radians, (lat1, lat2, lat0))
    n = (math.log(m(p1)) - math.log(m(p2))) / (math.log(t(p1)) - math.log(t(p2)))
    F = m(p1) / (n * t(p1) ** n)
    return a, e, n, F, a * F * t(p0) ** n, t


def lcc_fwd(lat, lon, lat1, lat2, lat0, lon0, fe, fn, ell):
    a, e, n, F, rho0, t = _lcc_consts(lat1, lat2, lat0, ell)
    rho = a * F * t(math.radians(lat)) ** n
    th = n * math.radians(lon - lon0)
    return fe + rho * math.sin(th), fn + rho0 - rho * math.cos(th)


def lcc_inv(X, Y, lat1, lat2, lat0, lon0, fe, fn, ell):
    a, e, n, F, rho0, _ = _lcc_consts(lat1, lat2, lat0, ell)
    x, y = X - fe, rho0 - (Y - fn)
    rho = math.copysign(math.hypot(x, y), n)
    tt = (rho / (a * F)) ** (1 / n)
    th = math.atan2(math.copysign(1, n) * x, math.copysign(1, n) * y)
    phi = math.pi / 2 - 2 * math.atan(tt)
    for _ in range(30):
        nxt = math.pi / 2 - 2 * math.atan(tt * ((1 - e * math.sin(phi)) / (1 + e * math.sin(phi))) ** (e / 2))
        if abs(nxt - phi) < 1e-15:
            phi = nxt
            break
        phi = nxt
    return math.degrees(phi), lon0 + math.degrees(th / n)


class Plane:
    """the projected (or lat/lon) plane a sheet is a linear image of"""

    def __init__(self, kind, datum, **p):
        self.kind, self.datum, self.p = kind, datum, p
        self.ell = ELLIPSOIDS[DATUM_BY_ID[datum]["ellipsoid"]]

    @staticmethod
    def utm(zone, south, datum="WGS84"):
        return Plane("utm", datum, zone=zone, south=south)

    def crs(self):
        """fixture crs block (plan s1 GeoCrs)"""
        if self.kind == "utm":
            z, s = self.p["zone"], self.p["south"]
            return {"kind": "transverseMercator", "lat0": 0.0, "lon0": float(6 * z - 183), "k0": 0.9996,
                    "fe": 500000.0, "fn": 10000000.0 if s else 0.0, "utmZone": z, "hemisphere": "S" if s else "N"}
        if self.kind == "lcc":
            q = self.p
            return {"kind": "lambertConformalConic2SP", "lat1": q["lat1"], "lat2": q["lat2"], "lat0": q["lat0"],
                    "lon0": q["lon0"], "fe": q["fe"], "fn": q["fn"]}
        return {"kind": "geographic"}

    def fwd(self, lat, lon):
        if self.kind == "utm":
            z = self.p["zone"]
            return tm_fwd(lat, lon, 6 * z - 183.0, 0.9996, 500000.0, 1e7 if self.p["south"] else 0.0, self.ell)
        if self.kind == "lcc":
            q = self.p
            return lcc_fwd(lat, lon, q["lat1"], q["lat2"], q["lat0"], q["lon0"], q["fe"], q["fn"], self.ell)
        return lon, lat

    def inv(self, X, Y):
        if self.kind == "utm":
            z = self.p["zone"]
            return tm_inv(X, Y, 6 * z - 183.0, 0.9996, 500000.0, 1e7 if self.p["south"] else 0.0, self.ell)
        if self.kind == "lcc":
            q = self.p
            return lcc_inv(X, Y, q["lat1"], q["lat2"], q["lat0"], q["lon0"], q["fe"], q["fn"], self.ell)
        return Y, X


# ----------------------------------------------------------------------------
# MGRS (AA lettering, fine for WGS84/GRS80/Intl 1924; NAD27 sheets never use it)
# ----------------------------------------------------------------------------

LETTERS_COL = ["ABCDEFGH", "JKLMNPQR", "STUVWXYZ"]
LETTERS_ROW = "ABCDEFGHJKLMNPQRSTUV"
BANDS = "CDEFGHJKLMNPQRSTUVWX"


def band_of(lat):
    return BANDS[max(0, min(19, int((lat + 80) // 8)))]


def mgrs(zone, south, E, N, lat, spaced=True):
    col = LETTERS_COL[(zone - 1) % 3][int(E // 100000) - 1]
    row = LETTERS_ROW[(int(N // 100000) % 20 + (5 if zone % 2 == 0 else 0)) % 20]
    e = int(round(E)) % 100000
    n = int(round(N)) % 100000
    if spaced:
        return "%d%s%s%s %05d %05d" % (zone, band_of(lat), col, row, e, n)
    return "%d%s%s%s%05d%05d" % (zone, band_of(lat), col, row, e, n)


# ----------------------------------------------------------------------------
# sheets: a sheet is a (possibly rotated) linear image of its plane on the page
# ----------------------------------------------------------------------------

class Sheet:
    def __init__(self, key, plane, X0, Y0, W, H, sx, sy=None, grid=1000.0, rotate_deg=0.0,
                 margin=72.0, media_origin=(0.0, 0.0), page_rotate=0, crop_inset=0.0, scale_label=""):
        self.key, self.plane = key, plane
        self.X0, self.Y0, self.W, self.H = X0, Y0, W, H
        self.sx, self.sy = sx, (sy if sy is not None else sx)
        self.grid, self.rotate_deg, self.page_rotate = grid, rotate_deg, page_rotate
        self.scale_label = scale_label
        r = math.radians(rotate_deg)
        if rotate_deg % 90 == 0:
            self.c, self.s = [(1, 0), (0, 1), (-1, 0), (0, -1)][int(rotate_deg // 90) % 4]
        else:
            self.c, self.s = math.cos(r), math.sin(r)
        self.fw, self.fh = W / self.sx, H / self.sy
        rw = abs(self.fw * self.c) + abs(self.fh * self.s)
        rh = abs(self.fw * self.s) + abs(self.fh * self.c)
        ox, oy = media_origin
        self.media = [ox, oy, ox + rw + 2 * margin, oy + rh + 2 * margin]
        self.cx, self.cy = ox + margin + rw / 2, oy + margin + rh / 2
        self.crop = None
        if crop_inset:
            m = self.media
            self.crop = [m[0] + crop_inset, m[1] + crop_inset, m[2] - crop_inset, m[3] - crop_inset]

    # exact page <-> plane, the construction truth
    def page(self, X, Y, du=0.0, dv=0.0):
        u = (X - self.X0) / self.sx - self.fw / 2 + du
        v = (Y - self.Y0) / self.sy - self.fh / 2 + dv
        return self.cx + self.c * u - self.s * v, self.cy + self.s * u + self.c * v

    def plane_of(self, x, y):
        dx, dy = x - self.cx, y - self.cy
        u, v = self.c * dx + self.s * dy, -self.s * dx + self.c * dy
        return self.X0 + (u + self.fw / 2) * self.sx, self.Y0 + (v + self.fh / 2) * self.sy

    def latlon(self, x, y):
        return self.plane.inv(*self.plane_of(x, y))

    def truth_affine(self):
        """[a,b,c,d,e,f]: X = a x + b y + c ; Y = d x + e y + f"""
        X0, Y0 = self.plane_of(0, 0)
        X1, Y1 = self.plane_of(1, 0)
        X2, Y2 = self.plane_of(0, 1)
        return [X1 - X0, X2 - X0, X0, Y1 - Y0, Y2 - Y0, Y0]

    def frame_pts(self):
        X0, Y0, W, H = self.X0, self.Y0, self.W, self.H
        return [(X0, Y0), (X0 + W, Y0), (X0 + W, Y0 + H), (X0, Y0 + H)]

    def corners(self):
        return [self.page(X, Y) for X, Y in self.frame_pts()]

    def gridvals(self, axis, step=None):
        step = step or self.grid
        lo, span = (self.X0, self.W) if axis == "X" else (self.Y0, self.H)
        # floor, not round: a 7.5' frame on a 1' graticule must stop at 7', not draw an 8th line outside
        n = int(math.floor(span / step + 1e-9))
        return [lo + i * step for i in range(n + 1)]

    def fiducials(self):
        g = self.grid
        X0, Y0, W, H = self.X0, self.Y0, self.W, self.H
        # four ring targets on the inner corners. the audit layout had F4 on the F1-F3
        # diagonal, which makes 3 of the 4 nearly collinear for leave-one-out
        pts = [(X0 + g, Y0 + g), (X0 + W - g, Y0 + g), (X0 + W - g, Y0 + H - g), (X0 + g, Y0 + H - g)]
        out = []
        for i, (X, Y) in enumerate(pts):
            lat, lon = self.plane.inv(X, Y)
            label = "%.0fmE %.0fmN" % (X, Y)
            if self.plane.kind == "utm" and self.plane.datum != "NAD27":
                label = mgrs(self.plane.p["zone"], self.plane.p["south"], X, Y, lat)
            elif self.plane.kind == "geog":
                label = "%s %s" % (dms(Y, "NS"), dms(X, "EW"))
            out.append({"id": "F%d" % (i + 1), "X": X, "Y": Y, "page": self.page(X, Y), "label": label})
        return out

    def label(self, axis, v):
        if self.plane.kind == "geog":
            return dms(v, "EW" if axis == "X" else "NS", short=True)
        return "%02d" % ((int(round(v)) // 1000) % 100)

    # -------------------------------------------------------------- drawing
    def content(self, extra_ops=()):
        m = self.media
        out = ["q 1 1 1 rg %s %s %s %s re f Q" % (dec(m[0], 3), dec(m[1], 3), dec(m[2] - m[0], 3), dec(m[3] - m[1], 3))]
        ang = math.atan2(self.s, self.c)

        def line(p, q, w, rgb):
            out.append("q %g %g %g RG %g w 0 J %.4f %.4f m %.4f %.4f l S Q" % (rgb + (w,) + tuple(p) + tuple(q)))

        def text(p, s, size, rgb=(1, 0, 0), angle=0.0):
            c, sn = math.cos(angle), math.sin(angle)
            s = s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")
            out.append("BT /F1 %g Tf %g %g %g rg %.5f %.5f %.5f %.5f %.3f %.3f Tm (%s) Tj ET"
                       % (size, rgb[0], rgb[1], rgb[2], c, sn, -sn, c, p[0], p[1], s))

        cs = self.corners()
        for i in range(4):
            line(cs[i], cs[(i + 1) % 4], 1.2, (0, 0, 0))
        red = (1, 0, 0)
        big = self.grid * 10
        for X in self.gridvals("X"):
            w = 1.4 if self.plane.kind != "geog" and int(round(X)) % int(big) == 0 else 0.6
            line(self.page(X, self.Y0), self.page(X, self.Y0 + self.H), w, red)
            lab = self.label("X", X)
            text(self.page(X, self.Y0, -5, -14), lab, 8, angle=ang)
            text(self.page(X, self.Y0 + self.H, -5, 5), lab, 8, angle=ang)
        for Y in self.gridvals("Y"):
            w = 1.4 if self.plane.kind != "geog" and int(round(Y)) % int(big) == 0 else 0.6
            line(self.page(self.X0, Y), self.page(self.X0 + self.W, Y), w, red)
            lab = self.label("Y", Y)
            text(self.page(self.X0, Y, -18 if self.plane.kind != "geog" else -34, -3), lab, 8, angle=ang)
            text(self.page(self.X0 + self.W, Y, 5, -3), lab, 8, angle=ang)
        for X, Y in self.frame_pts():
            p = self.page(X, Y)
            line((p[0] - 10, p[1]), (p[0] + 10, p[1]), 0.8, (0, 0, 0))
            line((p[0], p[1] - 10), (p[0], p[1] + 10), 0.8, (0, 0, 0))
            if self.plane.kind == "geog":
                lab = "%s %s" % (dms(Y, "NS"), dms(X, "EW"))
            else:
                lab = "%dmE %dmN" % (round(X), round(Y))
            text(self.page(X, Y, 20, 4), lab, 5, (0, 0, 0), ang)
        for f in self.fiducials():
            x, y = f["page"]
            r = 4.0
            k = 0.5523 * r
            out.append("q 0 0 0 RG 0.8 w %.3f %.3f m %.3f %.3f %.3f %.3f %.3f %.3f c %.3f %.3f %.3f %.3f %.3f %.3f c "
                       "%.3f %.3f %.3f %.3f %.3f %.3f c %.3f %.3f %.3f %.3f %.3f %.3f c S Q" % (
                           x + r, y, x + r, y + k, x + k, y + r, x, y + r, x - k, y + r, x - r, y + k, x - r, y,
                           x - r, y - k, x - k, y - r, x, y - r, x + k, y - r, x + r, y - k, x + r, y))
            text((x + 6, y + 6), "%s %s" % (f["id"], f["label"]), 5, (0, 0, 0))
        out.extend(extra_ops)
        text((m[0] + 10, m[1] + 10), "TacMap synthetic %s  %s  %s  grid %s (red)" % (
            self.key, self.plane_title(), self.scale_label, self.grid_title()), 7, (0, 0, 0))
        return "\n".join(out)

    def plane_title(self):
        if self.plane.kind == "utm":
            return "UTM %d%s %s" % (self.plane.p["zone"], "S" if self.plane.p["south"] else "N", self.plane.datum)
        if self.plane.kind == "lcc":
            return "LCC %s" % self.plane.datum
        return "geographic %s" % self.plane.datum

    def grid_title(self):
        if self.plane.kind == "geog":
            return "%g minute" % (self.grid * 60)
        return "%d m" % self.grid


def dms(v, hemis, short=False):
    h = hemis[0] if v >= 0 else hemis[1]
    t = abs(v) * 60
    d, mi = int(t // 60), t - 60 * int(t // 60)
    if abs(mi - round(mi)) < 1e-6:
        mi = round(mi)
        if mi == 60:
            d, mi = d + 1, 0
        return ("%d %02d'%s" if short else "%d deg %02d' %s") % (d, mi, h)
    return "%d %06.3f'%s" % (d, mi, h)


# ----------------------------------------------------------------------------
# georeference writers. each returns (page dict extras, what was written)
# ----------------------------------------------------------------------------

def wkt_utm_simple(zone, south):
    return ('PROJCS["WGS 84 / UTM zone %d%s",GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],'
            'PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]],PROJECTION["Transverse_Mercator"],'
            'PARAMETER["latitude_of_origin",0],PARAMETER["central_meridian",%d],PARAMETER["scale_factor",0.9996],'
            'PARAMETER["false_easting",500000],PARAMETER["false_northing",%d],UNIT["metre",1]]'
            % (zone, "S" if south else "N", 6 * zone - 183, 10000000 if south else 0))


WKT_32755 = ('PROJCS["WGS 84 / UTM zone 55S",GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563,'
             'AUTHORITY["EPSG","7030"]],AUTHORITY["EPSG","6326"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],'
             'UNIT["degree",0.0174532925199433,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4326"]],'
             'PROJECTION["Transverse_Mercator"],PARAMETER["latitude_of_origin",0],PARAMETER["central_meridian",147],'
             'PARAMETER["scale_factor",0.9996],PARAMETER["false_easting",500000],PARAMETER["false_northing",10000000],'
             'UNIT["metre",1,AUTHORITY["EPSG","9001"]],AXIS["Easting",EAST],AXIS["Northing",NORTH],AUTHORITY["EPSG","32755"]]')
WKT_ESRI_NAD27_UTM10 = ('PROJCS["NAD_1927_UTM_Zone_10N",GEOGCS["GCS_North_American_1927",DATUM["D_North_American_1927",'
                        'SPHEROID["Clarke_1866",6378206.4,294.978698213898]],PRIMEM["Greenwich",0.0],'
                        'UNIT["Degree",0.0174532925199433]],PROJECTION["Transverse_Mercator"],PARAMETER["False_Easting",500000.0],'
                        'PARAMETER["False_Northing",0.0],PARAMETER["Central_Meridian",-123.0],PARAMETER["Scale_Factor",0.9996],'
                        'PARAMETER["Latitude_Of_Origin",0.0],UNIT["Meter",1.0]]')
WKT_ESRI_GCS_NAD83 = ('GEOGCS["GCS_North_American_1983",DATUM["D_North_American_1983",SPHEROID["GRS_1980",6378137.0,'
                      '298.257222101]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]]')


def pdf_str(s):
    return "(" + s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"


def iso_vp(sheet, gcs, bbox_mode="neatline", bounds="unit", name="Map Layers"):
    """ISO 32000-2 /VP /Measure /GEO. gcs = ("PROJCS"|"GEOGCS", "wkt", text) or ("PROJCS", "epsg", code)"""
    cs = sheet.corners()
    if bbox_mode == "neatline":
        xs, ys = [p[0] for p in cs], [p[1] for p in cs]
        bbox = [min(xs), min(ys), max(xs), max(ys)]
    elif bbox_mode == "esri":
        # USGS/Esri ArcSOC shape: whole page, y reversed (top first), control quad a hair outside
        m = sheet.media
        bbox = [m[0] + 6.0, m[3], m[2] - 6.0, m[1] + 40.0]
    else:
        raise ValueError(bbox_mode)
    bbox = [num(v, PT_DP) for v in bbox]
    ox, oy, dx, dy = bbox[0], bbox[1], bbox[2] - bbox[0], bbox[3] - bbox[1]
    if bbox_mode == "esri":
        # SW, SE, NE, NW in page terms. y is reversed in this bbox so south is ly ~ 1
        lpts = [-0.00708, 1.00514, 1.00708, 1.00514, 1.00708, -0.00514, -0.00708, -0.00514]
    else:
        lpts = []
        for x, y in cs:
            lpts += [(x - ox) / dx, (y - oy) / dy]
    lpts = [num(v, DEG_DP) for v in lpts]
    page_pts = [(ox + lpts[2 * i] * dx, oy + lpts[2 * i + 1] * dy) for i in range(len(lpts) // 2)]
    gpts = []
    for x, y in page_pts:
        la, lo = sheet.latlon(x, y)
        gpts += [num(la, DEG_DP), num(lo, DEG_DP)]
    bnd = [0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0] if bounds == "unit" else list(lpts)
    kind, how, val = gcs
    if how == "wkt":
        gcs_pdf = "<< /Type /%s /WKT %s >>" % (kind, pdf_str(val))
    else:
        gcs_pdf = "<< /Type /%s /EPSG %d >>" % (kind, val)
    extras = ("/VP [<< /Type /Viewport /Name %s /BBox [%s] /Measure << /Type /Measure /Subtype /GEO "
              "/Bounds [%s] /GPTS [%s] /LPTS [%s] /GCS %s >> >>]" % (
                  pdf_str(name), nums(bbox, PT_DP), nums(bnd, DEG_DP), nums(gpts, DEG_DP), nums(lpts, DEG_DP), gcs_pdf))
    written = {"kind": "adobeVP", "viewports": [{"name": name, "bbox": bbox, "lpts": lpts, "gpts": gpts, "bounds": bnd,
                                                 "gcs": {"type": kind, how: val}}]}
    return extras, written


def lgi_projection_ut(plane, datum_code):
    return ("<< /Type /Projection /ProjectionType (UT) /Zone %d /Hemisphere (%s) /Datum (%s) >>"
            % (plane.p["zone"], "S" if plane.p["south"] else "N", datum_code))


def lgi_ctm(sheet, datum_code="WE", description="Layers"):
    a, b, c, d, e, f = sheet.truth_affine()
    ctm = [num(v, DEG_DP) for v in (a, d, b, e, c, f)]      # pdf matrix order: X = A x + C y + E ; Y = B x + D y + F
    nl = []
    for p in sheet.corners():
        nl += [num(p[0], PT_DP), num(p[1], PT_DP)]
    extras = ("/LGIDict << /Type /LGIDict /Version (2.1) /Description (%s) /CTM [%s] /Neatline [%s] /Projection %s >>"
              % (description, nums(ctm, DEG_DP), nums(nl, PT_DP), lgi_projection_ut(sheet.plane, datum_code)))
    written = {"kind": "lgiDict", "entries": [{"description": description, "ctm": ctm, "neatline": nl,
                                               "projection": {"ProjectionType": "UT", "Zone": sheet.plane.p["zone"],
                                                              "Hemisphere": "S" if sheet.plane.p["south"] else "N",
                                                              "Datum": datum_code}}]}
    return extras, written


def lgi_reg(sheet, datum_code="WE"):
    regs = []
    for p in sheet.corners():
        X, Y = sheet.plane_of(*p)
        regs.append([num(p[0], PT_DP), num(p[1], PT_DP), num(X, M_DP), num(Y, M_DP)])
    extras = ("/LGIDict << /Type /LGIDict /Version (2.1) /Description (Layers) /Registration [%s] /Projection %s >>"
              % (" ".join("[%s]" % nums(r, PT_DP) for r in regs), lgi_projection_ut(sheet.plane, datum_code)))
    written = {"kind": "lgiDict", "entries": [{"description": "Layers", "registration": regs,
                                               "projection": {"ProjectionType": "UT", "Zone": sheet.plane.p["zone"],
                                                              "Hemisphere": "S" if sheet.plane.p["south"] else "N",
                                                              "Datum": datum_code}}]}
    return extras, written


LCC_INSET = {"lon": (-123.5, -121.5), "lat": (37.0, 39.0)}     # the locator inset maps to this 2x2 deg box


def lgi_lcc_multi(sheet):
    """TerraGo/USGS style: two entries, a small LL inset first and the LE main map, neither called Layers"""
    m = sheet.media
    ix0, ix1, iy0, iy1 = m[0] + 8.0, m[0] + 64.0, m[3] - 64.0, m[3] - 8.0
    inl = [num(v, PT_DP) for v in (ix0, iy0, ix1, iy0, ix1, iy1, ix0, iy1)]
    ix0, iy0, ix1, iy1 = inl[0], inl[1], inl[2], inl[5]
    (lo0, lo1), (la0, la1) = LCC_INSET["lon"], LCC_INSET["lat"]
    ia = (lo1 - lo0) / (ix1 - ix0)
    idd = (la1 - la0) / (iy1 - iy0)
    ictm = [num(v, DEG_DP) for v in (ia, 0.0, 0.0, idd, lo0 - ia * ix0, la0 - idd * iy0)]
    a, b, c, d, e, f = sheet.truth_affine()
    ctm = [num(v, DEG_DP) for v in (a, d, b, e, c, f)]
    nl = []
    for p in sheet.corners():
        nl += [num(p[0], PT_DP), num(p[1], PT_DP)]
    q = sheet.plane.p
    # numbers as pdf strings, exactly like the USGS LGIDict posted on the Global Mapper forum
    proj = ("<< /Type /Projection /ProjectionType (LE) /Datum (NAS-C) /StandardParallelOne (%s) /StandardParallelTwo (%s) "
            "/OriginLatitude (%s) /CentralMeridian (%s) /FalseEasting (%s) /FalseNorthing (%s) /Units (M) >>"
            % tuple(dec(q[k], DEG_DP) for k in ("lat1", "lat2", "lat0", "lon0", "fe", "fn")))
    extras = ("/LGIDict [<< /Type /LGIDict /Version (2.1) /Description (Location Map) /CTM [%s] /Neatline [%s] "
              "/Projection << /Type /Projection /ProjectionType (LL) /Datum (WGE) >> >> "
              "<< /Type /LGIDict /Version (2.1) /Description (Main Map) /CTM [%s] /Neatline [%s] /Projection %s >>]"
              % (nums(ictm, DEG_DP), nums(inl, PT_DP), nums(ctm, DEG_DP), nums(nl, PT_DP), proj))
    written = {"kind": "lgiDict", "entries": [
        {"description": "Location Map", "ctm": ictm, "neatline": inl,
         "projection": {"ProjectionType": "LL", "Datum": "WGE"}},
        {"description": "Main Map", "ctm": ctm, "neatline": nl,
         "projection": {"ProjectionType": "LE", "Datum": "NAS-C", "StandardParallelOne": q["lat1"],
                        "StandardParallelTwo": q["lat2"], "OriginLatitude": q["lat0"], "CentralMeridian": q["lon0"],
                        "FalseEasting": q["fe"], "FalseNorthing": q["fn"], "Units": "M",
                        "note": "numbers are pdf strings in the file, e.g. /CentralMeridian (-122.6)"}}]}
    inset_ops = ["q 0 0 1 RG 0.6 w %s re S Q" % " ".join("%.3f" % v for v in (ix0, iy0, ix1 - ix0, iy1 - iy0)),
                 "BT /F1 4 Tf 0 0 1 rg 1 0 0 1 %.3f %.3f Tm (Location Map) Tj ET" % (ix0 + 2, iy0 + 2)]
    return extras, written, inset_ops


# ----------------------------------------------------------------------------
# pdf assembly (stdlib, uncompressed so the bytes are the same everywhere)
# ----------------------------------------------------------------------------

def write_pdf(path, media, content, extras="", crop=None, rotate=0):
    data = content.encode("latin-1")
    crop_s = (" /CropBox [%s]" % nums(crop, 3)) if crop else ""
    rot_s = (" /Rotate %d" % rotate) if rotate else ""
    page = ("<< /Type /Page /Parent 2 0 R /MediaBox [%s]%s%s /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R %s >>"
            % (nums(media, 3), crop_s, rot_s, extras))
    objs = [b"<< /Type /Catalog /Pages 2 0 R >>",
            b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            page.encode("latin-1"),
            b"<< /Length %d >>\nstream\n" % len(data) + data + b"\nendstream",
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"]
    buf = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offs = []
    for i, o in enumerate(objs):
        offs.append(len(buf))
        buf += b"%d 0 obj\n" % (i + 1) + o + b"\nendobj\n"
    x = len(buf)
    buf += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
    for o in offs:
        buf += b"%010d 00000 n \n" % o
    buf += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, x)
    with open(path, "wb") as fh:
        fh.write(bytes(buf))
    return hashlib.sha256(bytes(buf)).hexdigest(), len(buf)


# ----------------------------------------------------------------------------
# USGS US Topo sample, pinned verbatim (CA_San_Francisco_North_20211230_TM_geo.pdf)
# ----------------------------------------------------------------------------

USGS_SHA256 = "46d26fdd22bdafd9c47b312a94b7bff0c8b288048bacc88c01fea1806ab2a61f"
USGS_MEDIA = [0.0, 0.0, 1728.0, 2088.0]
# the /WKT literals exactly as stored (obj 425 and 421/423): note the escaped
# parens and the backslash+CR line continuations mid-word
USGS_WKT_UTM_LITERAL = (
    b'PROJCS["GCS North American 1983 UTM Zone 10N \\(Calculated\\)",GEOGCS["GCS_North_American_1983",'
    b'DATUM["D_North_American_1983",SPHEROID["GRS_1980",6378137.0,298.257222101]],PRIMEM["Greenwich",0.0],'
    b'UNIT["Degree",0.0174532925199433]],PROJECTION["Transverse_Mer\\\rcator"],PARAMETER["False_Easting",500000.0],'
    b'PARAMETER["False_Northing",0.0],PARAMETER["Central_Meridian",-123.0],PARAMETER["Scale_Factor",0.9996],'
    b'PARAMETER["Latitude_Of_Origin",0.0],UNIT["Meter",1.0]]')
USGS_WKT_MERC_LITERAL = (
    b'PROJCS["World_Mercator",GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]],'
    b'PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]],PROJECTION["Mercator"],PARAMETER["False_Easting",0.0],'
    b'PARAMETER["False_Northing",0.0]\\\r,PARAMETER["Central_Meridian",0.0],PARAMETER["Standard_Parallel_1",0.0],'
    b'UNIT["Meter",1.0]]')
USGS_VPS = [
    {"name": "Map Layers", "measureObj": 424, "gcsObj": 425,
     "bbox": "0 2088 1727.95998 56.69373",
     "bounds": "-0.00708 1 0 -0.00514 1.00708 0 1 1.00514",
     "gpts": "37.73318 -122.52121 37.88898 -122.52021 37.88818 -122.35264 37.73238 -122.354",
     "lpts": "-0.00708 1 0 -0.00514 1.00708 0 1 1.00514", "wkt": USGS_WKT_UTM_LITERAL},
    {"name": "Quadrangle Location", "measureObj": 422, "gcsObj": 423,
     "bbox": "1114.1742 191.51415 1197.69226 131.57396",
     "bounds": "0 1 0 0 1 0 1 1",
     "gpts": "32.26355 -128.02763 42.24228 -128.02763 42.24228 -110.58561 32.26355 -110.58561",
     "lpts": "0 1 0 0 1 0 1 1", "wkt": USGS_WKT_MERC_LITERAL},
    {"name": "Adjoining Sheet Diagram", "measureObj": 420, "gcsObj": 421,
     "bbox": "1105.5344 100.79387 1157.0132 35.99367",
     "bounds": "0 1 0 0 1 0 1 1",
     "gpts": "37.62479 -122.62527 37.99979 -122.62527 37.99979 -122.24975 37.62479 -122.24975",
     "lpts": "0 1 0 0 1 0 1 1", "wkt": USGS_WKT_MERC_LITERAL},
]
# printed 1000 m UTM grid (orange, 0.18 pt, layer "Projection and Grids") measured
# from the real file's content stream with --measure-usgs. Vertices sit on a
# 0.18 pt lattice there (~1.5 m), so each line is a least-squares fit through
# ~110 vertices. [E, N, x, y]
USGS_PRINTED_GRID = [
    (546000, 4180000, 440.534, 488.793),
    (546000, 4182000, 441.961, 724.995),
    (546000, 4184000, 443.389, 961.187),
    (546000, 4186000, 444.816, 1197.401),
    (546000, 4188000, 446.243, 1433.608),
    (546000, 4190000, 447.670, 1669.806),
    (546000, 4192000, 449.097, 1905.998),
    (548000, 4180000, 676.742, 487.370),
    (548000, 4182000, 678.163, 723.572),
    (548000, 4184000, 679.583, 959.768),
    (548000, 4186000, 681.003, 1195.977),
    (548000, 4188000, 682.424, 1432.183),
    (548000, 4190000, 683.844, 1668.385),
    (548000, 4192000, 685.264, 1904.583),
    (550000, 4180000, 912.944, 485.948),
    (550000, 4182000, 914.365, 722.150),
    (550000, 4184000, 915.785, 958.348),
    (550000, 4186000, 917.205, 1194.553),
    (550000, 4188000, 918.626, 1430.758),
    (550000, 4190000, 920.046, 1666.964),
    (550000, 4192000, 921.467, 1903.167),
    (552000, 4180000, 1149.142, 484.526),
    (552000, 4182000, 1150.561, 720.727),
    (552000, 4184000, 1151.979, 956.929),
    (552000, 4186000, 1153.398, 1193.129),
    (552000, 4188000, 1154.817, 1429.332),
    (552000, 4190000, 1156.235, 1665.543),
    (552000, 4192000, 1157.654, 1901.752),
    (554000, 4180000, 1385.334, 483.104),
    (554000, 4182000, 1386.757, 719.305),
    (554000, 4184000, 1388.179, 955.509),
    (554000, 4186000, 1389.602, 1191.704),
    (554000, 4188000, 1391.025, 1427.907),
    (554000, 4190000, 1392.447, 1664.122),
    (554000, 4192000, 1393.870, 1900.336),
]


def pdf_literal_decode(raw):
    """decode the body of a pdf literal string (no outer parens)"""
    out = bytearray()
    i = 0
    esc = {ord("n"): b"\n", ord("r"): b"\r", ord("t"): b"\t", ord("b"): b"\b", ord("f"): b"\f",
           ord("("): b"(", ord(")"): b")", ord("\\"): b"\\"}
    while i < len(raw):
        ch = raw[i]
        if ch == 0x5C:
            nxt = raw[i + 1]
            if nxt in esc:
                out += esc[nxt]
                i += 2
            elif nxt == 0x0D:                      # backslash + EOL is a continuation, both vanish
                i += 3 if i + 2 < len(raw) and raw[i + 2] == 0x0A else 2
            elif nxt == 0x0A:
                i += 2
            elif 0x30 <= nxt <= 0x37:
                j = i + 1
                while j < len(raw) and j < i + 4 and 0x30 <= raw[j] <= 0x37:
                    j += 1
                out.append(int(raw[i + 1:j], 8) & 0xFF)
                i = j
            else:
                out.append(nxt)
                i += 2
        else:
            out.append(ch)
            i += 1
    return bytes(out)


def utf16_pdf_name(s):
    return b"(\xfe\xff" + s.encode("utf-16-be") + b")"


def usgs_vp_extras():
    parts = []
    for vp in USGS_VPS:
        parts.append(b"<< /Type /Viewport /Name " + utf16_pdf_name(vp["name"]) +
                     b" /BBox [" + vp["bbox"].encode() + b"] /Measure << /Type /Measure /Subtype /GEO /Bounds [" +
                     vp["bounds"].encode() + b"] /GPTS [" + vp["gpts"].encode() + b"] /LPTS [" + vp["lpts"].encode() +
                     b"] /GCS << /Type /PROJCS /WKT (" + vp["wkt"] + b") >> >> >>")
    return b"/VP [" + b" ".join(parts) + b"]"


# ----------------------------------------------------------------------------
# reference math: pyproj + numpy. everything in the json comes through here
# ----------------------------------------------------------------------------

class Ref:
    def __init__(self):
        try:
            import numpy as np
            import pyproj
        except ImportError:
            sys.exit("need pyproj + numpy for the expectations: pip install pyproj numpy")
        self.np, self.pyproj = np, pyproj
        self._proj = {}

    # ---- datums
    def datum(self, d):
        if isinstance(d, str):
            return DATUM_BY_ID[d]
        return d

    def ell(self, d):
        d = self.datum(d)
        return d["a"], d["invF"]

    def ecef(self, lat, lon, a, invf, h=0.0):
        np = self.np
        f = 1 / invf
        e2 = f * (2 - f)
        p, l = np.radians(lat), np.radians(lon)
        N = a / np.sqrt(1 - e2 * np.sin(p) ** 2)
        return np.array([(N + h) * np.cos(p) * np.cos(l), (N + h) * np.cos(p) * np.sin(l), (N * (1 - e2) + h) * np.sin(p)])

    def geodetic(self, v, a, invf):
        np = self.np
        f = 1 / invf
        e2 = f * (2 - f)
        x, y, z = v
        lon = np.arctan2(y, x)
        p = np.hypot(x, y)
        lat = np.arctan2(z, p * (1 - e2))
        for _ in range(40):
            N = a / np.sqrt(1 - e2 * np.sin(lat) ** 2)
            nxt = np.arctan2(z + e2 * N * np.sin(lat), p)
            if abs(nxt - lat) < 1e-16:
                lat = nxt
                break
            lat = nxt
        return float(np.degrees(lat)), float(np.degrees(lon))

    def helmert_cf(self, v, d, sign=1.0):
        np = self.np
        arc = np.pi / 180 / 3600
        rx, ry, rz = (sign * d[k] * arc for k in ("rxArcsec", "ryArcsec", "rzArcsec"))
        s = 1 + sign * d["scalePpm"] * 1e-6
        t = sign * np.array([d["dx"], d["dy"], d["dz"]])
        R = np.array([[1, rz, -ry], [-rz, 1, rx], [ry, -rx, 1]])
        return t + s * (R @ v)

    def to_wgs84(self, d, lat, lon):
        d = self.datum(d)
        if d["transform"] == "identity":
            return lat, lon
        v = self.ecef(lat, lon, d["a"], d["invF"])
        if d["transform"] == "helmert7":
            v = self.helmert_cf(v, d, 1.0)
        else:
            v = v + self.np.array([d["dx"], d["dy"], d["dz"]])
        return self.geodetic(v, *ELLIPSOIDS["WGS84"])

    def from_wgs84(self, d, lat, lon):
        d = self.datum(d)
        if d["transform"] == "identity":
            return lat, lon
        v = self.ecef(lat, lon, *ELLIPSOIDS["WGS84"])
        if d["transform"] == "helmert7":
            # exact inverse of the forward similarity, not the sign flip. the flip is
            # ~3 mm off for OSGB36 (s = -20 ppm) which blows the 1e-9 deg tolerance
            np = self.np
            arc = np.pi / 180 / 3600
            rx, ry, rz = (d[k] * arc for k in ("rxArcsec", "ryArcsec", "rzArcsec"))
            M = (1 + d["scalePpm"] * 1e-6) * np.array([[1, rz, -ry], [-rz, 1, rx], [ry, -rx, 1]])
            v = np.linalg.solve(M, v - np.array([d["dx"], d["dy"], d["dz"]]))
        else:
            v = v - self.np.array([d["dx"], d["dy"], d["dz"]])
        return self.geodetic(v, d["a"], d["invF"])

    def proj_pipeline_check(self, d, lat, lon):
        """same shift through PROJ's own cart+helmert, to catch sign slips in the numpy version"""
        d = self.datum(d)
        a, invf = d["a"], d["invF"]
        if d["transform"] == "identity":
            return lat, lon
        h = "+proj=helmert +x=%r +y=%r +z=%r" % (d["dx"], d["dy"], d["dz"])
        if d["transform"] == "helmert7":
            h += " +rx=%r +ry=%r +rz=%r +s=%r +convention=coordinate_frame" % (
                d["rxArcsec"], d["ryArcsec"], d["rzArcsec"], d["scalePpm"])
        pipe = ("+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad +step +proj=cart +a=%r +rf=%r "
                "+step %s +step +inv +proj=cart +ellps=WGS84 +step +proj=unitconvert +xy_in=rad +xy_out=deg" % (a, invf, h))
        t = self.pyproj.Transformer.from_pipeline(pipe)
        lo, la, _ = t.transform(lon, lat, 0.0)
        return la, lo

    # ---- projections
    def proj(self, crs, d):
        a, invf = self.ell(d)
        k = crs["kind"]
        if k == "transverseMercator":
            s = "+proj=tmerc +algo=poder_engsager +lat_0=%r +lon_0=%r +k_0=%r +x_0=%r +y_0=%r" % (
                crs["lat0"], crs["lon0"], crs["k0"], crs["fe"], crs["fn"])
        elif k == "lambertConformalConic2SP":
            s = "+proj=lcc +lat_1=%r +lat_2=%r +lat_0=%r +lon_0=%r +x_0=%r +y_0=%r" % (
                crs["lat1"], crs["lat2"], crs["lat0"], crs["lon0"], crs["fe"], crs["fn"])
        elif k == "lambertConformalConic1SP":
            s = "+proj=lcc +lat_1=%r +lat_0=%r +lon_0=%r +k_0=%r +x_0=%r +y_0=%r" % (
                crs["lat0"], crs["lat0"], crs["lon0"], crs["k0"], crs["fe"], crs["fn"])
        elif k == "mercator1SP":
            s = "+proj=merc +lon_0=%r +k_0=%r +x_0=%r +y_0=%r" % (crs["lon0"], crs["k0"], crs["fe"], crs["fn"])
        else:
            raise ValueError(k)
        s += " +a=%r +rf=%r +units=m +no_defs" % (a, invf)
        if s not in self._proj:
            self._proj[s] = self.pyproj.Proj(s)
        return self._proj[s]

    def fwd(self, crs, d, lat, lon):
        if crs["kind"] == "geographic":
            return float(lon), float(lat)
        x, y = self.proj(crs, d)(lon, lat)
        return float(x), float(y)

    def inv(self, crs, d, X, Y):
        if crs["kind"] == "geographic":
            return float(Y), float(X)
        lon, lat = self.proj(crs, d)(X, Y, inverse=True)
        return float(lat), float(lon)

    def metres_per_unit(self, crs, d, lat):
        """(east, north) metres per plane unit; 1 for projected, local radii for lat/lon"""
        if crs["kind"] != "geographic":
            return 1.0, 1.0
        a, invf = self.ell(d)
        f = 1 / invf
        e2 = f * (2 - f)
        p = math.radians(lat)
        w = 1 - e2 * math.sin(p) ** 2
        N = a / math.sqrt(w)
        M = a * (1 - e2) / w ** 1.5
        return math.radians(1) * N * math.cos(p), math.radians(1) * M

    # ---- affine
    def fit(self, pairs):
        """least squares page->plane. pairs [(x, y, X, Y)] -> [a,b,c,d,e,f]"""
        np = self.np
        P = np.array([[p[0], p[1]] for p in pairs], dtype=float)
        Q = np.array([[p[2], p[3]] for p in pairs], dtype=float)
        pm, qm = P.mean(0), Q.mean(0)
        A = np.column_stack([P - pm, np.ones(len(P))])
        sol = np.linalg.lstsq(A, Q - qm, rcond=None)[0]
        a, d = sol[0]
        b, e = sol[1]
        c = qm[0] + sol[2][0] - a * pm[0] - b * pm[1]
        f = qm[1] + sol[2][1] - d * pm[0] - e * pm[1]
        return [float(v) for v in (a, b, c, d, e, f)]

    @staticmethod
    def apply(A, x, y):
        a, b, c, d, e, f = A
        return a * x + b * y + c, d * x + e * y + f

    @staticmethod
    def unapply(A, X, Y):
        a, b, c, d, e, f = A
        det = a * e - b * d
        dx, dy = X - c, Y - f
        return (e * dx - b * dy) / det, (-d * dx + a * dy) / det

    def eigen_ratio(self, pts):
        np = self.np
        P = np.array(pts, dtype=float)
        C = np.cov(P.T, bias=True)
        w = np.linalg.eigvalsh(C)
        return float(w[0] / w[1]) if w[1] > 0 else 0.0

    # ---- the georef model from plan s1
    def to_wgs84_page(self, g, x, y):
        X, Y = self.apply(g["affine"], x, y)
        la, lo = self.inv(g["crs"], g["datum"], X, Y)
        return self.to_wgs84(g["datum"], la, lo), (X, Y)

    def to_page(self, g, lat, lon):
        la, lo = self.from_wgs84(g["datum"], lat, lon)
        X, Y = self.fwd(g["crs"], g["datum"], la, lo)
        return self.unapply(g["affine"], X, Y)

    def geod_m(self, lat1, lon1, lat2, lon2):
        g = self.pyproj.Geod(ellps="WGS84")
        return float(g.inv(lon1, lat1, lon2, lat2)[2])


# rounding for the json so it stays stable across machines
def rdeg(v):
    return round(float(v), 11)


def rm(v):
    return round(float(v), 6)


def rpt(v):
    return round(float(v), 7)


def rsig(v):
    return float("%.15g" % v)


def datum_json(d):
    if isinstance(d, str):
        return {"id": d}
    return {"id": "custom", "a": d["a"], "invF": d["invF"], "dx": d["dx"], "dy": d["dy"], "dz": d["dz"]}


def crs_json(crs):
    return {k: v for k, v in crs.items()}


# ----------------------------------------------------------------------------
# sheet definitions
# ----------------------------------------------------------------------------

LCC_PARAMS = dict(lat1=33.0, lat2=45.0, lat0=37.5, lon0=-122.6, fe=0.0, fn=0.0)
GEOG_SY = 1.0 / 6000.0                                 # 1 minute of latitude = 100 pt
GEOG_SX = GEOG_SY / math.cos(math.radians(37.8125))    # keeps the sheet roughly conformal at mid latitude


def build_sheets():
    s = {}
    s["sf"] = Sheet("sf", Plane.utm(10, False), 546000, 4176000, 6000, 8000, S25K, scale_label="1:25000")
    s["edge"] = Sheet("edge", Plane.utm(33, False), 690000, 5500000, 8000, 8000, S25K, scale_label="1:25000")
    s["syd"] = Sheet("syd", Plane.utm(56, True), 326000, 6244000, 6000, 8000, S25K, scale_label="1:25000")
    s["rot5"] = Sheet("rot5", Plane.utm(10, False), 546000, 4176000, 6000, 8000, S25K, rotate_deg=5.0, scale_label="1:25000")
    s["offset"] = Sheet("offset", Plane.utm(10, False), 546000, 4176000, 6000, 8000, S25K,
                        media_origin=(100.0, 150.0), crop_inset=20.0, scale_label="1:25000")
    s["rot90"] = Sheet("rot90", Plane.utm(10, False), 546000, 4176000, 6000, 8000, S25K, rotate_deg=90.0,
                       page_rotate=90, scale_label="1:25000")
    s["cbr50k"] = Sheet("cbr50k", Plane.utm(55, True), 680000, 6080000, 20000, 20000, S50K, scale_label="1:50000")
    s["lcc"] = Sheet("lcc", Plane("lcc", "NAD27", **LCC_PARAMS), 8000, 24000, 6000, 8000, S25K, scale_label="1:25000")
    s["geog"] = Sheet("geog", Plane("geog", "NAD83"), -122.5, 37.75, 0.125, 0.125, GEOG_SX, GEOG_SY,
                      grid=1.0 / 60.0, scale_label="~1:52500 plate carree")
    s["hols"] = Sheet("hols", Plane.utm(56, True, "GDA94"), 306000, 6232000, 6000, 8000, S25K, scale_label="1:25000")
    s["sf27"] = Sheet("sf27", Plane.utm(10, False, "NAD27"), 546000, 4176000, 6000, 8000, S25K, scale_label="1:25000")
    return s


# (file stem, sheet key, georef writer kind, expected crs source)
PLAN = [
    ("sf_iso", "sf", "iso"), ("sf_esri", "sf", "esri"), ("sf_lgictm", "sf", "lgictm"), ("sf_lgireg", "sf", "lgireg"),
    ("sf_plain", "sf", "plain"), ("edge_iso", "edge", "iso"), ("edge_lgictm", "edge", "lgictm"),
    ("syd_iso", "syd", "iso"), ("syd_lgictm", "syd", "lgictm"), ("rot5_plain", "rot5", "plain"),
    ("rot5_iso", "rot5", "iso_lptsbounds"), ("offset_iso", "offset", "iso"),
    ("rot90_iso", "rot90", "iso"), ("cbr50k_iso", "cbr50k", "iso_wkt32755"), ("cbr50k_plain", "cbr50k", "plain"),
    ("lcc_lgile", "lcc", "lgile"), ("geog_iso", "geog", "iso_geogcs"), ("hols_epsg", "hols", "epsg28356"),
    ("sf27_iso", "sf27", "iso_nad27"),
]


def write_sheet(outdir, stem, sh, kind):
    extra_ops = ()
    if kind == "plain":
        extras, written = "", {"kind": "none"}
    elif kind in ("iso", "iso_lptsbounds"):
        z, south = sh.plane.p["zone"], sh.plane.p["south"]
        extras, written = iso_vp(sh, ("PROJCS", "wkt", wkt_utm_simple(z, south)),
                                 bounds="lpts" if kind == "iso_lptsbounds" else "unit")
    elif kind == "esri":
        z, south = sh.plane.p["zone"], sh.plane.p["south"]
        extras, written = iso_vp(sh, ("PROJCS", "wkt", wkt_utm_simple(z, south)), bbox_mode="esri", bounds="lpts")
    elif kind == "iso_wkt32755":
        extras, written = iso_vp(sh, ("PROJCS", "wkt", WKT_32755))
    elif kind == "iso_geogcs":
        extras, written = iso_vp(sh, ("GEOGCS", "wkt", WKT_ESRI_GCS_NAD83))
    elif kind == "epsg28356":
        extras, written = iso_vp(sh, ("PROJCS", "epsg", 28356))
    elif kind == "iso_nad27":
        extras, written = iso_vp(sh, ("PROJCS", "wkt", WKT_ESRI_NAD27_UTM10))
    elif kind == "lgictm":
        extras, written = lgi_ctm(sh)
    elif kind == "lgireg":
        extras, written = lgi_reg(sh)
    elif kind == "lgile":
        extras, written, extra_ops = lgi_lcc_multi(sh)
    else:
        raise ValueError(kind)
    fname = "tacmap_grid_%s.pdf" % stem
    sha, size = write_pdf(os.path.join(outdir, fname), sh.media, sh.content(extra_ops), extras, sh.crop, sh.page_rotate)
    return fname, sha, size, written


def write_usgs_standin(outdir, ref):
    """1728x2088 page with the real /VP dictionaries plus the model's UTM grid in red"""
    g = usgs_georef(ref)
    ops = ["q 1 1 1 rg 0 0 1728 2088 re f Q"]
    # 7.5 minute quad neatline (NAD83 == WGS84 here) and the 1000 m grid inside it
    quad = [(37.75, -122.5), (37.75, -122.375), (37.875, -122.375), (37.875, -122.5)]
    qp = [ref.to_page(g, la, lo) for la, lo in quad]
    ops.append("q 0 0 0 RG 1.2 w %.4f %.4f m %s h S Q" % (qp[0][0], qp[0][1], " ".join("%.4f %.4f l" % p for p in qp[1:])))
    for E in range(544000, 557001, 1000):
        a = ref.to_page(g, *ref.to_wgs84("NAD83", *ref.inv(g["crs"], "NAD83", E, 4177000)))
        b = ref.to_page(g, *ref.to_wgs84("NAD83", *ref.inv(g["crs"], "NAD83", E, 4192000)))
        ops.append("q 1 0 0 RG 0.6 w %.4f %.4f m %.4f %.4f l S Q" % (a[0], a[1], b[0], b[1]))
    for N in range(4177000, 4192001, 1000):
        a = ref.to_page(g, *ref.to_wgs84("NAD83", *ref.inv(g["crs"], "NAD83", 543000, N)))
        b = ref.to_page(g, *ref.to_wgs84("NAD83", *ref.inv(g["crs"], "NAD83", 557000, N)))
        ops.append("q 1 0 0 RG 0.6 w %.4f %.4f m %.4f %.4f l S Q" % (a[0], a[1], b[0], b[1]))
    ops.append("BT /F1 9 Tf 0 0 0 rg 1 0 0 1 20 20 Tm (TacMap stand-in: real USGS SF North /VP dictionaries, "
               "grid drawn from the fixture model, not the USGS artwork) Tj ET")
    content = "\n".join(ops)
    data = content.encode("latin-1")
    extras = usgs_vp_extras()
    page = (b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 1728 2088] /CropBox [0 0 1728 2088] /Rotate 0 "
            b"/Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R " + extras + b" >>")
    objs = [b"<< /Type /Catalog /Pages 2 0 R >>", b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>", page,
            b"<< /Length %d >>\nstream\n" % len(data) + data + b"\nendstream",
            b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"]
    buf = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offs = []
    for i, o in enumerate(objs):
        offs.append(len(buf))
        buf += b"%d 0 obj\n" % (i + 1) + o + b"\nendobj\n"
    x = len(buf)
    buf += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
    for o in offs:
        buf += b"%010d 00000 n \n" % o
    buf += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, x)
    fname = "tacmap_usgs_sf_north_vp.pdf"
    with open(os.path.join(outdir, fname), "wb") as fh:
        fh.write(bytes(buf))
    return fname, hashlib.sha256(bytes(buf)).hexdigest(), len(buf)


# ----------------------------------------------------------------------------
# expected georefs
# ----------------------------------------------------------------------------

def vp_page_pts(vp):
    bbox, lpts = vp["bbox"], vp["lpts"]
    ox, oy, dx, dy = bbox[0], bbox[1], bbox[2] - bbox[0], bbox[3] - bbox[1]
    return [(ox + lpts[2 * i] * dx, oy + lpts[2 * i + 1] * dy) for i in range(len(lpts) // 2)]


def vp_crop(vp):
    bbox = vp["bbox"]
    ox, oy, dx, dy = bbox[0], bbox[1], bbox[2] - bbox[0], bbox[3] - bbox[1]
    b = vp.get("bounds")
    if b:
        return [(ox + b[2 * i] * dx, oy + b[2 * i + 1] * dy) for i in range(len(b) // 2)]
    return [(bbox[0], bbox[1]), (bbox[2], bbox[1]), (bbox[2], bbox[3]), (bbox[0], bbox[3])]


def fit_vp(ref, vp, crs, datum):
    """plan s1 Adobe construction: GPTS forward in the source datum, LS affine, rms gate"""
    pp = vp_page_pts(vp)
    g = vp["gpts"]
    pairs = []
    for i, (x, y) in enumerate(pp):
        X, Y = ref.fwd(crs, datum, g[2 * i], g[2 * i + 1])
        pairs.append((x, y, X, Y))
    A = ref.fit(pairs)
    return A, pairs


def fit_stats(ref, A, pairs, crs, datum):
    res = []
    for x, y, X, Y in pairs:
        pX, pY = ref.apply(A, x, y)
        la = ref.inv(crs, datum, X, Y)[0]
        me, mn = ref.metres_per_unit(crs, datum, la)
        res.append(math.hypot((pX - X) * me, (pY - Y) * mn))
    Xs = [p[2] for p in pairs]
    Ys = [p[3] for p in pairs]
    la = ref.inv(crs, datum, sum(Xs) / len(Xs), sum(Ys) / len(Ys))[0]
    me, mn = ref.metres_per_unit(crs, datum, la)
    diag = math.hypot((max(Xs) - min(Xs)) * me, (max(Ys) - min(Ys)) * mn)
    rms = math.sqrt(sum(r * r for r in res) / len(res))
    return {"rmsMetres": rm(rms), "maxResidualMetres": rm(max(res)), "residualsMetres": [rm(r) for r in res],
            "sheetDiagonalMetres": rm(diag), "gateLimitMetres": rm(max(2.0, 0.002 * diag)),
            "passesGate": rms <= max(2.0, 0.002 * diag)}


def affine_tol(ref, g, crop):
    xs = [p[0] for p in crop]
    ys = [p[1] for p in crop]
    L = max(max(xs) - min(xs), max(ys) - min(ys))
    cx, cy = sum(xs) / len(xs), sum(ys) / len(ys)
    X, Y = ref.apply(g["affine"], cx, cy)
    la = ref.inv(g["crs"], g["datum"], X, Y)[0]
    me, mn = ref.metres_per_unit(g["crs"], g["datum"], la)
    unit = 1.0 / min(me, mn)            # plane units per metre
    lin = 1e-3 * unit / L
    tr = 1e-3 * unit + lin * (abs(cx) + abs(cy))
    return [rsig(lin), rsig(lin), rsig(tr), rsig(lin), rsig(lin), rsig(tr)]


def check_point(ref, g, label, x, y, kind):
    x, y = rpt(x), rpt(y)       # compute from exactly what the json says, not the unrounded value
    (lat, lon), (X, Y) = ref.to_wgs84_page(g, x, y)
    lat, lon = rdeg(lat), rdeg(lon)
    px, py = ref.to_page(g, lat, lon)
    return {"label": label, "kind": kind, "page": [rpt(x), rpt(y)], "plane": [rsig(X), rsig(Y)],
            "wgs84": [lat, lon], "toPage": [rpt(px), rpt(py)]}


def georef_json(ref, g, origin, selected, crop, extra=None):
    out = {"origin": origin, "selected": selected, "crs": crs_json(g["crs"]), "datum": datum_json(g["datum"]),
           "crop": [[rpt(x), rpt(y)] for x, y in crop],
           "affine": [rsig(v) for v in g["affine"]], "affineTol": affine_tol(ref, g, crop)}
    if extra:
        out.update(extra)
    return out


def usgs_georef(ref):
    vp = usgs_vp_parsed()[0]
    crs = {"kind": "transverseMercator", "lat0": 0.0, "lon0": -123.0, "k0": 0.9996, "fe": 500000.0, "fn": 0.0,
           "utmZone": 10, "hemisphere": "N"}
    A, pairs = fit_vp(ref, vp, crs, "NAD83")
    return {"crs": crs, "datum": "NAD83", "affine": A, "pairs": pairs, "vp": vp}


def usgs_vp_parsed():
    out = []
    for v in USGS_VPS:
        out.append({"name": v["name"], "bbox": [float(t) for t in v["bbox"].split()],
                    "lpts": [float(t) for t in v["lpts"].split()], "gpts": [float(t) for t in v["gpts"].split()],
                    "bounds": [float(t) for t in v["bounds"].split()],
                    "wkt": pdf_literal_decode(v["wkt"]).decode("latin-1"),
                    "wktPdfLiteral": v["wkt"].decode("latin-1")})
    return out


def sheet_expectation(ref, stem, sh, kind, fname, sha, size, written):
    plane = sh.plane
    datum = plane.datum
    crs = plane.crs()
    entry = {"id": stem, "file": "geopdf/" + fname, "sha256": sha, "bytes": size, "page": 0,
             "mediaBox": [rpt(v) for v in sh.media], "cropBox": [rpt(v) for v in sh.crop] if sh.crop else None,
             "rotate": sh.page_rotate, "writer": kind}
    entry["written"] = written
    truth = {"plane": crs_json(crs), "datum": datum, "pageToPlane": [rsig(v) for v in sh.truth_affine()],
             "frame": {"X0": sh.X0, "Y0": sh.Y0, "W": sh.W, "H": sh.H}, "gridStep": sh.grid,
             "fiducialTargets": [{"id": f["id"], "plane": [rsig(f["X"]), rsig(f["Y"])],
                                  "page": [rpt(f["page"][0]), rpt(f["page"][1])], "label": f["label"]}
                                 for f in sh.fiducials()]}
    entry["truth"] = truth
    if kind == "plain":
        entry["expected"] = {"origin": "none", "georef": None,
                             "note": "no geo metadata; import goes to fiduciary calibration (see fiduciaryFits)"}
        entry["gridChecks"] = truth_grid(ref, sh)
        return entry, None

    # the parser-side derivation, from the numbers as written
    if written["kind"] == "adobeVP":
        vp = written["viewports"][0]
        A, pairs = fit_vp(ref, vp, crs, datum)
        g = {"crs": crs, "datum": datum, "affine": A}
        stats = fit_stats(ref, A, pairs, crs, datum)
        crop = vp_crop(vp)
        controls = [{"page": [rpt(x), rpt(y)], "gpts": [vp["gpts"][2 * i], vp["gpts"][2 * i + 1]],
                     "plane": [rsig(X), rsig(Y)]} for i, (x, y, X, Y) in enumerate(pairs)]
        exp = georef_json(ref, g, "adobeVP", {"kind": "viewport", "index": 0, "name": vp["name"]}, crop,
                          {"controls": controls, "fit": stats})
    else:
        entries = written["entries"]
        idx = select_lgi_entry(entries)
        e = entries[idx]
        if "ctm" in e:
            A_, B_, C_, D_, E_, F_ = e["ctm"]
            A = [A_, C_, E_, B_, D_, F_]
            nl = e["neatline"]
            crop = [(nl[2 * i], nl[2 * i + 1]) for i in range(len(nl) // 2)]
            source = "ctm"
            stats = None
        else:
            pairs = [tuple(r) for r in e["registration"]]
            A = ref.fit(pairs)
            crop = [(r[0], r[1]) for r in e["registration"]]
            source = "registration"
            stats = fit_stats(ref, A, pairs, crs, datum)
        g = {"crs": crs, "datum": datum, "affine": A}
        sel = {"kind": "lgiEntry", "index": idx, "description": e["description"], "source": source}
        if len(entries) > 1:
            sel["rule"] = "no entry named Layers, so the largest neatline (shoelace area in page space) wins"
        exp = georef_json(ref, g, "lgiDict", sel, crop, {"fit": stats} if stats else None)
        if source == "registration":
            exp["crop"] = [[rpt(x), rpt(y)] for x, y in crop]
            exp["cropNote"] = "no /Neatline, crop = registration points polygon"
    entry["expected"] = exp
    checks = []
    for i, c in enumerate(exp.get("controls", [])):
        checks.append(check_point(ref, g, "control %d" % (i + 1), c["page"][0], c["page"][1], "control"))
    for X, Y, lab in sheet_check_grid(sh):
        x, y = sh.page(X, Y)
        checks.append(check_point(ref, g, lab, x, y, "grid"))
    for f in sh.fiducials():
        checks.append(check_point(ref, g, "fiducial %s" % f["id"], f["page"][0], f["page"][1], "fiducial"))
    exp["checks"] = checks
    exp["tolerances"] = {"wgs84Metres": 0.01, "pagePoints": 0.001, "planeMetres": 0.001, "rmsMetres": 0.001}
    # how far the parsed model sits from the construction truth (should be ~0)
    worst = 0.0
    for c in checks:
        tX, tY = sh.plane_of(*c["page"])
        tla, tlo = plane.inv(tX, tY)
        wla, wlo = ref.to_wgs84(datum, tla, tlo)
        worst = max(worst, ref.geod_m(wla, wlo, c["wgs84"][0], c["wgs84"][1]))
    entry["modelVsConstructionMaxMetres"] = rm(worst)
    return entry, g


def shoelace(poly):
    return abs(sum(poly[i][0] * poly[(i + 1) % len(poly)][1] - poly[(i + 1) % len(poly)][0] * poly[i][1]
                   for i in range(len(poly)))) / 2


def select_lgi_entry(entries):
    """plan s1: the entry described Layers, else the largest neatline by page-space area"""
    for i, e in enumerate(entries):
        if e["description"] == "Layers":
            return i

    def area(e):
        nl = e.get("neatline") or [v for r in e.get("registration", []) for v in r[:2]]
        return shoelace([(nl[2 * k], nl[2 * k + 1]) for k in range(len(nl) // 2)])
    return max(range(len(entries)), key=lambda i: (area(entries[i]), -i))


def sheet_check_grid(sh):
    """grid intersections for checks: every 2nd line on 1:25k sheets, 5 km on the 20 km sheet"""
    if sh.plane.kind == "geog":
        Xs = sh.gridvals("X", sh.grid * 3)       # printed graticule intersections, every 3rd minute
        Ys = sh.gridvals("Y", sh.grid * 3)
        out = []
        for X in Xs:
            for Y in Ys:
                out.append((X, Y, "%s %s" % (dms(Y, "NS"), dms(X, "EW"))))
        return out
    step = 5000.0 if sh.W > 10000 else 2000.0
    out = []
    for X in sh.gridvals("X", step):
        for Y in sh.gridvals("Y", step):
            out.append((X, Y, "%dE %dN" % (round(X), round(Y))))
    return out


def truth_grid(ref, sh):
    out = []
    for X, Y, lab in sheet_check_grid(sh):
        x, y = sh.page(X, Y)
        la, lo = ref.inv(sh.plane.crs(), sh.plane.datum, X, Y)
        wla, wlo = ref.to_wgs84(sh.plane.datum, la, lo)
        out.append({"label": lab, "plane": [rsig(X), rsig(Y)], "page": [rpt(x), rpt(y)], "wgs84": [rdeg(wla), rdeg(wlo)]})
    return out


def usgs_sheet(ref, fname, sha, size):
    g = usgs_georef(ref)
    vps = usgs_vp_parsed()
    crop = vp_crop(g["vp"])
    stats = fit_stats(ref, g["affine"], g["pairs"], g["crs"], "NAD83")
    controls = [{"page": [rpt(x), rpt(y)], "gpts": [g["vp"]["gpts"][2 * i], g["vp"]["gpts"][2 * i + 1]],
                 "plane": [rsig(X), rsig(Y)]} for i, (x, y, X, Y) in enumerate(g["pairs"])]
    areas = [abs((v["bbox"][2] - v["bbox"][0]) * (v["bbox"][3] - v["bbox"][1])) for v in vps]
    exp = georef_json(ref, g, "adobeVP", {"kind": "viewport", "index": 0, "name": "Map Layers",
                                          "rule": "largest BBox area among viewports that give a valid georef",
                                          "bboxAreas": [rm(a) for a in areas]}, crop,
                      {"controls": controls, "fit": stats})
    checks = []
    for i, c in enumerate(controls):
        checks.append(check_point(ref, g, "control %d" % (i + 1), c["page"][0], c["page"][1], "control"))
    grid = []
    for E in range(544000, 556001, 2000):
        for N in range(4178000, 4192001, 2000):
            grid.append((E, N))
    grid.append((550000, 4185000))
    for E, N in grid:
        la, lo = ref.inv(g["crs"], "NAD83", E, N)
        wla, wlo = ref.to_wgs84("NAD83", la, lo)
        x, y = ref.to_page(g, wla, wlo)
        c = check_point(ref, g, "%dE %dN" % (E, N), x, y, "printedGrid")
        c["truthWgs84"] = [rdeg(wla), rdeg(wlo)]
        c["gridNAD83"] = [E, N]
        checks.append(c)
    for la, lo in [(37.75, -122.5), (37.75, -122.375), (37.875, -122.375), (37.875, -122.5)]:
        x, y = ref.to_page(g, la, lo)
        checks.append(check_point(ref, g, "quad corner %g %g" % (la, lo), x, y, "quadNeatline"))
    exp["checks"] = checks
    exp["tolerances"] = {"wgs84Metres": 0.01, "pagePoints": 0.001, "planeMetres": 0.001, "rmsMetres": 0.001,
                         "printedGridMetres": 0.05}
    measured = []
    for E, N, mx, my in USGS_PRINTED_GRID:
        la, lo = ref.inv(g["crs"], "NAD83", E, N)
        (wla, wlo), _ = ref.to_wgs84_page(g, mx, my)
        err = ref.geod_m(la, lo, wla, wlo)
        px, py = ref.to_page(g, la, lo)
        measured.append({"gridNAD83": [E, N], "pageMeasured": [mx, my], "pageModel": [rpt(px), rpt(py)],
                         "modelErrorMetres": round(err, 3)})
    entry = {"id": "usgs_sf_north", "file": "geopdf/" + fname, "sha256": sha, "bytes": size, "page": 0,
             "mediaBox": USGS_MEDIA, "cropBox": USGS_MEDIA, "rotate": 0, "writer": "usgsStandIn",
             "source": {"file": "samples/USGS_SF_North.pdf (CA_San_Francisco_North_20211230_TM_geo.pdf, not in git; "
                                "tests that open it must skip when absent)", "sha256": USGS_SHA256,
                        "pageObject": 20, "note": "the stand-in pdf carries these /VP dicts byte for byte "
                                                  "(inline instead of indirect Measure/GCS objects)"},
             "viewports": [{"name": v["name"], "bbox": v["bbox"], "lpts": v["lpts"], "gpts": v["gpts"], "bounds": v["bounds"],
                            "gcs": {"type": "PROJCS", "wkt": v["wkt"], "wktPdfLiteral": v["wktPdfLiteral"]},
                            "measureObj": u["measureObj"], "gcsObj": u["gcsObj"]} for v, u in zip(vps, USGS_VPS)],
             "pdfPageExtras": usgs_vp_extras().decode("latin-1"),
             "expected": exp,
             "printedGridMeasured": {
                 "note": "the real sheet's printed 1000 m grid (orange, layer Projection and Grids) measured with "
                         "--measure-usgs; its vertices sit on a 0.18 pt (~1.5 m) lattice and GPTS are rounded to "
                         "1e-5 deg (control residual ~0.22 m), so ~0.7 m is the floor here, plan target is 1 m",
                 "toleranceMetres": 1.0,
                 "points": measured,
                 "maxModelErrorMetres": round(max((m["modelErrorMetres"] for m in measured), default=0.0), 3)}}
    return entry, g


# ----------------------------------------------------------------------------
# datums section
# ----------------------------------------------------------------------------

DATUM_CASE_POINTS = {
    "WGS84": [(37.8, -122.4)],
    "NAD83": [(37.8, -122.4)],
    "GDA94": [(-35.2809, 149.13), (-33.99, 150.95), (-12.46, 130.84)],
    "GDA2020": [(-35.2809, 149.13)],
    "ETRS89": [(52.52, 13.405)],
    "NAD27": [(37.8, -122.44), (39.74, -104.99), (38.9, -77.04)],
    "NAD27_CONUS_EAST": [(38.9, -77.04)],
    "NAD27_CONUS_WEST": [(39.74, -104.99)],
    "NAD27_ALASKA": [(61.22, -149.9)],
    "NAD27_CANADA": [(45.42, -75.7)],
    "ED50": [(52.52, 13.405), (40.42, -3.70), (50.0, 9.0)],
    "OSGB36": [(51.5, -0.12), (55.95, -3.19)],
    "TOKYO": [(35.68, 139.77), (43.06, 141.35)],
    "CH1903": [(46.95, 7.44)],
    "NTF": [(48.8566, 2.3522), (43.6, 1.44)],
    "SK42": [(55.75, 37.62), (55.03, 82.92)],
}


def datums_section(ref):
    to_cases, from_cases = [], []
    worst_pipe = 0.0
    worst_rt = 0.0
    for d in DATUMS:
        for lat, lon in DATUM_CASE_POINTS.get(d["id"], []):
            w = ref.to_wgs84(d["id"], lat, lon)
            p = ref.proj_pipeline_check(d["id"], lat, lon)
            worst_pipe = max(worst_pipe, abs(w[0] - p[0]), abs(w[1] - p[1]))
            shift = ref.geod_m(lat, lon, w[0], w[1])
            to_cases.append({"datum": d["id"], "lat": lat, "lon": lon, "wgs84": [rdeg(w[0]), rdeg(w[1])],
                             "shiftMetres": round(shift, 3)})
            b = ref.from_wgs84(d["id"], lat, lon)
            from_cases.append({"datum": d["id"], "wgs84": [lat, lon], "source": [rdeg(b[0]), rdeg(b[1])]})
            rt = ref.from_wgs84(d["id"], *w)
            worst_rt = max(worst_rt, ref.geod_m(lat, lon, rt[0], rt[1]))
    notes = []
    for n in DATUM_NOTES:
        n = dict(n)
        chk = n.pop("check", None)
        if chk:
            # truth = PROJ's own helmert in the EPSG record's native convention, so this
            # also proves the PV -> CF rotation flip in the table is right (table error ~0)
            d = DATUM_BY_ID[n["datum"]]
            meas = {"reference": "PROJ helmert with the full EPSG 7-param, native convention", "points": chk["points"],
                    "errorMetres": {}}
            cands = dict(chk["candidates"])
            cands["table"] = None
            for cname, t in cands.items():
                errs = []
                for lat, lon in chk["points"]:
                    truth = full_7param(ref, n["datum"], lat, lon)
                    dd = d if t is None else dict(d, transform="translation3", dx=t[0], dy=t[1], dz=t[2])
                    w = ref.to_wgs84(dd, lat, lon)
                    errs.append(round(ref.geod_m(truth[0], truth[1], w[0], w[1]), 3))
                meas["errorMetres"][cname] = errs
            n["measured"] = meas
        notes.append(n)
    return {
        "rules": {
            "toWGS84": "source geodetic (h=0) -> ECEF on the datum ellipsoid -> shift -> geodetic on the WGS84 ellipsoid "
                       "(height dropped). Always finish on WGS84, even for GDA94 whose EPSG:8048 target is GDA2020 on "
                       "GRS80; finishing on GRS80 instead moves lat by ~1e-4 m, right at the 1e-9 deg tolerance",
            "fromWGS84": "WGS84 geodetic (h=0) -> ECEF -> inverse shift -> geodetic on the datum ellipsoid; "
                         "translation3 subtracts dx,dy,dz; helmert7 is the exact inverse X = M^-1 (X' - T) with "
                         "M = (1+s*1e-6) R (solve the 3x3). Flipping the signs of all seven is NOT close enough for "
                         "OSGB36 (~3 mm)",
            "helmert7": "coordinate frame convention: X' = T + (1+s*1e-6) * [[1, rz, -ry], [-rz, 1, rx], [ry, -rx, 1]] X, "
                        "rotations in arcsec",
            "wktNameMatch": "strip a leading 'D_', uppercase, drop everything not A-Z/0-9, then compare",
            "lgiCodeMatch": "trim + uppercase; exact lgiCodes first, then lgiCodePrefixes",
            "roundTripNote": "fromWGS84(toWGS84(p)) is not exactly p because the height is dropped; "
                             "worst here %.2e m" % worst_rt},
        "table": DATUMS,
        "discrepancies": notes,
        "tolerance": {"degrees": 1e-9},
        "toWGS84": to_cases,
        "fromWGS84": from_cases,
        "selfCheck": {"numpyVsProjPipelineMaxDeg": float("%.3g" % worst_pipe)},
    }


def full_7param(ref, datum_id, lat, lon):
    d = DATUM_BY_ID[datum_id]
    if datum_id == "OSGB36":
        h = ("+proj=helmert +x=446.448 +y=-125.157 +z=542.06 +rx=0.15 +ry=0.247 +rz=0.842 +s=-20.489 "
             "+convention=position_vector")
    else:
        h = ("+proj=helmert +x=23.92 +y=-141.27 +z=-80.9 +rx=0 +ry=-0.35 +rz=-0.82 +s=-0.12 "
             "+convention=coordinate_frame")
    pipe = ("+proj=pipeline +step +proj=unitconvert +xy_in=deg +xy_out=rad +step +proj=cart +a=%r +rf=%r "
            "+step %s +step +inv +proj=cart +ellps=WGS84 +step +proj=unitconvert +xy_in=rad +xy_out=deg"
            % (d["a"], d["invF"], h))
    lo, la, _ = ref.pyproj.Transformer.from_pipeline(pipe).transform(lon, lat, 0.0)
    return la, lo


# ----------------------------------------------------------------------------
# projections section
# ----------------------------------------------------------------------------

def TM(lat0, lon0, k0, fe, fn):
    return {"kind": "transverseMercator", "lat0": lat0, "lon0": lon0, "k0": k0, "fe": fe, "fn": fn}


def UTM(zone, south):
    c = TM(0.0, float(6 * zone - 183), 0.9996, 500000.0, 10000000.0 if south else 0.0)
    c.update(utmZone=zone, hemisphere="S" if south else "N")
    return c


def LCC2(lat1, lat2, lat0, lon0, fe, fn):
    return {"kind": "lambertConformalConic2SP", "lat1": lat1, "lat2": lat2, "lat0": lat0, "lon0": lon0, "fe": fe, "fn": fn}


def LCC1(lat0, lon0, k0, fe, fn):
    return {"kind": "lambertConformalConic1SP", "lat0": lat0, "lon0": lon0, "k0": k0, "fe": fe, "fn": fn}


def MERC(lon0, k0, fe, fn):
    return {"kind": "mercator1SP", "lon0": lon0, "k0": k0, "fe": fe, "fn": fn}


def merc_k0(lat_ts, ell_name):
    a, invf = ELLIPSOIDS[ell_name]
    f = 1 / invf
    e2 = f * (2 - f)
    p = math.radians(lat_ts)
    return math.cos(p) / math.sqrt(1 - e2 * math.sin(p) ** 2)


def ell_datum(name):
    """a datum-shaped dict just to carry an ellipsoid into Ref"""
    a, invf = ELLIPSOIDS[name]
    return {"a": a, "invF": invf, "transform": "identity", "dx": 0, "dy": 0, "dz": 0}


PROJ_CASES = [
    ("utm10n_wgs84_near_cm", UTM(10, False), "WGS84", 37.8, -123.001),
    ("utm10n_wgs84_sf", UTM(10, False), "WGS84", 37.7749, -122.4194),
    ("utm10n_wgs84_cm_plus_3p9_equator", UTM(10, False), "WGS84", 0.0, -119.1),
    ("utm10n_wgs84_cm_plus_3p9_lat1", UTM(10, False), "WGS84", 1.0, -119.1),
    ("utm10n_wgs84_cm_minus_3p9_lat45", UTM(10, False), "WGS84", 45.0, -126.9),
    ("utm55s_wgs84_canberra", UTM(55, True), "WGS84", -35.2809, 149.13),
    ("utm55s_wgs84_cm_plus_3p9_lat_m30", UTM(55, True), "WGS84", -30.0, 150.9),
    ("utm55s_wgs84_cm_minus_3p9_lat_m42", UTM(55, True), "WGS84", -42.0, 143.1),
    ("utm56s_grs80_holsworthy", UTM(56, True), "GRS80", -33.99, 150.95),
    ("utm33n_wgs84_lat70_cm", UTM(33, False), "WGS84", 70.0, 15.0),
    ("utm33n_wgs84_lat70_plus_3p9", UTM(33, False), "WGS84", 70.0, 18.9),
    ("utm10n_clarke1866_nad27_sf", UTM(10, False), "Clarke1866", 37.8, -122.43),
    ("utm32n_intl1924_ed50", UTM(32, False), "International1924", 50.0, 9.0),
    ("tm_bng_airy_lat0_49", TM(49.0, -2.0, 0.9996012717, 400000.0, -100000.0), "Airy1830", 51.5, -0.12),
    ("tm_nztm_grs80", TM(0.0, 173.0, 0.9996, 1600000.0, 10000000.0), "GRS80", -41.29, 174.78),
    ("lcc2sp_ca_zone3_grs80", LCC2(38.4333333333333, 37.0666666666667, 36.5, -120.5, 2000000.0, 500000.0), "GRS80", 37.8, -122.4),
    ("lcc2sp_lambert93_grs80", LCC2(49.0, 44.0, 46.5, 3.0, 700000.0, 6600000.0), "GRS80", 48.8566, 2.3522),
    ("lcc2sp_sheet_nad27_clarke1866", LCC2(33.0, 45.0, 37.5, -122.6, 0.0, 0.0), "Clarke1866", 37.76, -122.45),
    ("lcc2sp_southern_ga_lambert", LCC2(-18.0, -36.0, 0.0, 134.0, 0.0, 0.0), "GRS80", -35.2809, 149.13),
    ("lcc1sp_jamaica_wgs84", LCC1(18.0, -77.0, 1.0, 750000.0, 650000.0), "WGS84", 18.0, -76.8),
    ("lcc1sp_k_not_1_grs80", LCC1(-35.0, 149.0, 0.9999, 1000000.0, 2000000.0), "GRS80", -35.2809, 149.13),
    ("merc1sp_world_mercator_sf", MERC(0.0, 1.0, 0.0, 0.0), "WGS84", 37.8, -122.4),
    ("merc1sp_makassar_bessel_k0997", MERC(110.0, 0.997, 3900000.0, 900000.0), "Bessel1841", -5.1, 119.4),
    ("merc1sp_k0_from_lat_ts42_krassovsky", MERC(51.0, merc_k0(42.0, "Krassovsky1940"), 0.0, 0.0), "Krassovsky1940", 42.5, 50.0),
    ("geographic_wgs84", {"kind": "geographic"}, "WGS84", 37.8, -122.4),
]


def projections_section(ref):
    out = []
    worst_py = 0.0
    for name, crs, ell, lat, lon in PROJ_CASES:
        d = ell_datum(ell)
        X, Y = ref.fwd(crs, d, lat, lon)
        X, Y = rm(X), rm(Y)
        la, lo = ref.inv(crs, d, X, Y)
        case = {"name": name, "crs": crs, "ellipsoid": ell, "a": d["a"], "invF": d["invF"], "lat": lat, "lon": lon,
                "x": X, "y": Y, "inverseLat": rdeg(la), "inverseLon": rdeg(lo)}
        if name.startswith("merc1sp_k0_from_lat_ts"):
            p = ref.pyproj.Proj("+proj=merc +lat_ts=42 +lon_0=51 +x_0=0 +y_0=0 +ellps=krass +units=m +no_defs")
            x2, y2 = p(lon, lat)
            case["note"] = "Mercator 2SP folded into 1SP via k0 = cos(lat_ts)/sqrt(1-e2 sin2(lat_ts)); matches +lat_ts=42 to %.1e m" % max(abs(x2 - X), abs(y2 - Y))
        if crs["kind"] == "transverseMercator":
            z = crs["lon0"]
            kx, ky = tm_fwd(lat, lon, z, crs["k0"], crs["fe"], crs["fn"], ELLIPSOIDS[ell]) if crs["lat0"] == 0 else (X, Y)
            worst_py = max(worst_py, abs(kx - X), abs(ky - Y))
        out.append(case)
    return {"tolerance": {"planeMetres": 1e-3, "degrees": 1e-9},
            "note": "x = easting (or lon for geographic), y = northing (or lat). forward: (lat,lon)->(x,y); "
                    "inverse: (x,y)->(inverseLat,inverseLon). all on the named ellipsoid, no datum shift. "
                    "truth = PROJ %s tmerc poder_engsager / lcc / merc" % ref.pyproj.proj_version_str,
            "selfCheck": {"krugerVsProjMaxMetres": float("%.3g" % worst_py)},
            "cases": out}


# ----------------------------------------------------------------------------
# gcs parse section
# ----------------------------------------------------------------------------

WKT = {
    "gda94_mga55_ogc": ('PROJCS["GDA94 / MGA zone 55",GEOGCS["GDA94",DATUM["Geocentric_Datum_of_Australia_1994",'
                        'SPHEROID["GRS 1980",6378137,298.257222101,AUTHORITY["EPSG","7019"]],AUTHORITY["EPSG","6283"]],'
                        'PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],UNIT["degree",0.0174532925199433,AUTHORITY["EPSG","9122"]],'
                        'AUTHORITY["EPSG","4283"]],PROJECTION["Transverse_Mercator"],PARAMETER["latitude_of_origin",0],'
                        'PARAMETER["central_meridian",147],PARAMETER["scale_factor",0.9996],PARAMETER["false_easting",500000],'
                        'PARAMETER["false_northing",10000000],UNIT["metre",1,AUTHORITY["EPSG","9001"]],AXIS["Easting",EAST],'
                        'AXIS["Northing",NORTH],AUTHORITY["EPSG","28355"]]'),
    "wgs84_utm33n_ogc": ('PROJCS["WGS 84 / UTM zone 33N",GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563,'
                         'AUTHORITY["EPSG","7030"]],AUTHORITY["EPSG","6326"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],'
                         'UNIT["degree",0.0174532925199433,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4326"]],'
                         'PROJECTION["Transverse_Mercator"],PARAMETER["latitude_of_origin",0],PARAMETER["central_meridian",15],'
                         'PARAMETER["scale_factor",0.9996],PARAMETER["false_easting",500000],PARAMETER["false_northing",0],'
                         'UNIT["metre",1,AUTHORITY["EPSG","9001"]],AXIS["Easting",EAST],AXIS["Northing",NORTH],AUTHORITY["EPSG","32633"]]'),
    "geogcs_wgs84_ogc": ('GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563,AUTHORITY["EPSG","7030"]],'
                         'AUTHORITY["EPSG","6326"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],UNIT["degree",'
                         '0.0174532925199433,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4326"]]'),
    "geogcs_nad83_esri": WKT_ESRI_GCS_NAD83,
    "lcc2sp_ca3_ogc": ('PROJCS["NAD83 / California zone 3",GEOGCS["NAD83",DATUM["North_American_Datum_1983",SPHEROID["GRS 1980",'
                       '6378137,298.257222101,AUTHORITY["EPSG","7019"]],AUTHORITY["EPSG","6269"]],PRIMEM["Greenwich",0,'
                       'AUTHORITY["EPSG","8901"]],UNIT["degree",0.0174532925199433,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4269"]],'
                       'PROJECTION["Lambert_Conformal_Conic_2SP"],PARAMETER["latitude_of_origin",36.5],PARAMETER["central_meridian",-120.5],'
                       'PARAMETER["standard_parallel_1",38.4333333333333],PARAMETER["standard_parallel_2",37.0666666666667],'
                       'PARAMETER["false_easting",2000000],PARAMETER["false_northing",500000],UNIT["metre",1,AUTHORITY["EPSG","9001"]],'
                       'AXIS["Easting",EAST],AXIS["Northing",NORTH],AUTHORITY["EPSG","26943"]]'),
    "lcc2sp_ca3_esri_ftus": ('PROJCS["NAD_1983_StatePlane_California_III_FIPS_0403_Feet",GEOGCS["GCS_North_American_1983",'
                             'DATUM["D_North_American_1983",SPHEROID["GRS_1980",6378137.0,298.257222101]],PRIMEM["Greenwich",0.0],'
                             'UNIT["Degree",0.0174532925199433]],PROJECTION["Lambert_Conformal_Conic"],PARAMETER["False_Easting",6561666.667],'
                             'PARAMETER["False_Northing",1640416.667],PARAMETER["Central_Meridian",-120.5],'
                             'PARAMETER["Standard_Parallel_1",38.4333333333333],PARAMETER["Standard_Parallel_2",37.0666666666667],'
                             'PARAMETER["Latitude_Of_Origin",36.5],UNIT["US survey foot",0.304800609601219]]'),
    "lcc1sp_jamaica_ogc": ('PROJCS["JAD2001 / Jamaica Metric Grid",GEOGCS["JAD2001",DATUM["Jamaica_2001",SPHEROID["WGS 84",6378137,'
                           '298.257223563,AUTHORITY["EPSG","7030"]],AUTHORITY["EPSG","6758"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],'
                           'UNIT["degree",0.0174532925199433,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4758"]],'
                           'PROJECTION["Lambert_Conformal_Conic_1SP"],PARAMETER["latitude_of_origin",18],PARAMETER["central_meridian",-77],'
                           'PARAMETER["scale_factor",1],PARAMETER["false_easting",750000],PARAMETER["false_northing",650000],'
                           'UNIT["metre",1,AUTHORITY["EPSG","9001"]],AXIS["Easting",EAST],AXIS["Northing",NORTH],AUTHORITY["EPSG","3448"]]'),
    "merc2sp_caspian_ogc": ('PROJCS["Pulkovo 1942 / Caspian Sea Mercator",GEOGCS["Pulkovo 1942",DATUM["Pulkovo_1942",'
                            'SPHEROID["Krassowsky 1940",6378245,298.3,AUTHORITY["EPSG","7024"]],AUTHORITY["EPSG","6284"]],'
                            'PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],UNIT["degree",0.0174532925199433,AUTHORITY["EPSG","9122"]],'
                            'AUTHORITY["EPSG","4284"]],PROJECTION["Mercator_2SP"],PARAMETER["standard_parallel_1",42],'
                            'PARAMETER["central_meridian",51],PARAMETER["false_easting",0],PARAMETER["false_northing",0],'
                            'UNIT["metre",1,AUTHORITY["EPSG","9001"]],AUTHORITY["EPSG","3388"]]'),
    "merc1sp_makassar_ogc": ('PROJCS["Makassar / NEIEZ",GEOGCS["Makassar",DATUM["Makassar",SPHEROID["Bessel 1841",6377397.155,'
                             '299.1528128,AUTHORITY["EPSG","7004"]],AUTHORITY["EPSG","6257"]],PRIMEM["Greenwich",0,AUTHORITY["EPSG","8901"]],'
                             'UNIT["degree",0.0174532925199433,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4257"]],PROJECTION["Mercator_1SP"],'
                             'PARAMETER["central_meridian",110],PARAMETER["scale_factor",0.997],PARAMETER["false_easting",3900000],'
                             'PARAMETER["false_northing",900000],UNIT["metre",1,AUTHORITY["EPSG","9001"]],AXIS["Easting",EAST],'
                             'AXIS["Northing",NORTH],AUTHORITY["EPSG","3002"]]'),
    "nztm_ogc": ('PROJCS["NZGD2000 / New Zealand Transverse Mercator 2000",GEOGCS["NZGD2000",DATUM["New_Zealand_Geodetic_Datum_2000",'
                 'SPHEROID["GRS 1980",6378137,298.257222101,AUTHORITY["EPSG","7019"]],AUTHORITY["EPSG","6167"]],PRIMEM["Greenwich",0,'
                 'AUTHORITY["EPSG","8901"]],UNIT["degree",0.0174532925199433,AUTHORITY["EPSG","9122"]],AUTHORITY["EPSG","4167"]],'
                 'PROJECTION["Transverse_Mercator"],PARAMETER["latitude_of_origin",0],PARAMETER["central_meridian",173],'
                 'PARAMETER["scale_factor",0.9996],PARAMETER["false_easting",1600000],PARAMETER["false_northing",10000000],'
                 'UNIT["metre",1,AUTHORITY["EPSG","9001"]],AUTHORITY["EPSG","2193"]]'),
    "albers_usgs_esri": ('PROJCS["USA_Contiguous_Albers_Equal_Area_Conic_USGS_version",GEOGCS["GCS_North_American_1983",'
                         'DATUM["D_North_American_1983",SPHEROID["GRS_1980",6378137.0,298.257222101]],PRIMEM["Greenwich",0.0],'
                         'UNIT["Degree",0.0174532925199433]],PROJECTION["Albers"],PARAMETER["False_Easting",0.0],'
                         'PARAMETER["False_Northing",0.0],PARAMETER["Central_Meridian",-96.0],PARAMETER["Standard_Parallel_1",29.5],'
                         'PARAMETER["Standard_Parallel_2",45.5],PARAMETER["Latitude_Of_Origin",23.0],UNIT["Meter",1.0]]'),
    "webmerc_aux_esri": ('PROJCS["WGS_1984_Web_Mercator_Auxiliary_Sphere",GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",'
                         'SPHEROID["WGS_1984",6378137.0,298.257223563]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]],'
                         'PROJECTION["Mercator_Auxiliary_Sphere"],PARAMETER["False_Easting",0.0],PARAMETER["False_Northing",0.0],'
                         'PARAMETER["Central_Meridian",0.0],PARAMETER["Standard_Parallel_1",0.0],PARAMETER["Auxiliary_Sphere_Type",0.0],'
                         'UNIT["Meter",1.0]]'),
    "unknown_datum_towgs84": ('GEOGCS["Some local datum",DATUM["Local_survey_1927",SPHEROID["Clarke 1866",6378206.4,294.978698213898],'
                              'TOWGS84[-8,160,176,0,0,0,0]],PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]]'),
    "unknown_datum_grs80_no_towgs84": ('GEOGCS["SIRGAS 2000",DATUM["Sistema_de_Referencia_Geocentrico_para_las_AmericaS_2000",'
                                       'SPHEROID["GRS 1980",6378137,298.257222101]],PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]]'),
    "unknown_datum_intl_no_towgs84": ('GEOGCS["Hu Tzu Shan 1950",DATUM["Hu_Tzu_Shan_1950",SPHEROID["International 1924",6378388,297]],'
                                      'PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]]'),
    "known_name_wins_over_towgs84": ('GEOGCS["ED50",DATUM["European_Datum_1950",SPHEROID["International 1924",6378388,297],'
                                     'TOWGS84[-84,-107,-120,0,0,0,0]],PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]]'),
    "garbage": 'PROJCS["truncated",GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.2572',
}


def gcs_section(ref):
    usgs = usgs_vp_parsed()
    k0_caspian = merc_k0(42.0, "Krassovsky1940")
    ftus = 0.304800609601219
    cases = [
        ("usgs_sf_north_utm10n_esri_calculated", {"wkt": usgs[0]["wkt"], "wktPdfLiteral": usgs[0]["wktPdfLiteral"]},
         {"status": "ok", "crs": UTM(10, False), "datum": {"id": "NAD83"}}),
        ("usgs_inset_world_mercator_esri", {"wkt": usgs[1]["wkt"], "wktPdfLiteral": usgs[1]["wktPdfLiteral"]},
         {"status": "ok", "crs": MERC(0.0, 1.0, 0.0, 0.0), "datum": {"id": "WGS84"},
          "note": "ESRI 'Mercator' with Standard_Parallel_1 = 0 -> k0 = 1"}),
        ("gda94_mga55_ogc", {"wkt": WKT["gda94_mga55_ogc"]}, {"status": "ok", "crs": UTM(55, True), "datum": {"id": "GDA94"}}),
        ("wgs84_utm33n_ogc", {"wkt": WKT["wgs84_utm33n_ogc"]}, {"status": "ok", "crs": UTM(33, False), "datum": {"id": "WGS84"}}),
        ("wgs84_utm55s_ogc_full", {"wkt": WKT_32755}, {"status": "ok", "crs": UTM(55, True), "datum": {"id": "WGS84"}}),
        ("nad27_utm10n_esri", {"wkt": WKT_ESRI_NAD27_UTM10}, {"status": "ok", "crs": UTM(10, False), "datum": {"id": "NAD27"}}),
        ("geogcs_wgs84_ogc", {"wkt": WKT["geogcs_wgs84_ogc"]}, {"status": "ok", "crs": {"kind": "geographic"}, "datum": {"id": "WGS84"}}),
        ("geogcs_nad83_esri", {"wkt": WKT["geogcs_nad83_esri"]}, {"status": "ok", "crs": {"kind": "geographic"}, "datum": {"id": "NAD83"}}),
        ("lcc2sp_ca3_ogc", {"wkt": WKT["lcc2sp_ca3_ogc"]},
         {"status": "ok", "crs": LCC2(38.4333333333333, 37.0666666666667, 36.5, -120.5, 2000000.0, 500000.0), "datum": {"id": "NAD83"},
          "note": "parallels exactly as written (13 dp), don't 'fix' them to 38 26'"}),
        ("lcc2sp_ca3_esri_us_feet", {"wkt": WKT["lcc2sp_ca3_esri_ftus"]},
         {"status": "ok", "crs": LCC2(38.4333333333333, 37.0666666666667, 36.5, -120.5, 6561666.667 * ftus, 1640416.667 * ftus),
          "datum": {"id": "NAD83"}, "linearUnitMetres": ftus,
          "note": "ESRI 'Lambert_Conformal_Conic' with two parallels is 2SP; plane is always metres, fe/fn converted"}),
        ("lcc1sp_jamaica_ogc", {"wkt": WKT["lcc1sp_jamaica_ogc"]},
         {"status": "ok", "crs": LCC1(18.0, -77.0, 1.0, 750000.0, 650000.0),
          "datum": ZERO_SHIFT("WGS84"), "datumAssumed": True,
          "note": "Jamaica_2001 isn't in the table, no TOWGS84, WGS84-sized ellipsoid -> zero shift on its own "
                  "spheroid, flagged as assumed"}),
        ("merc2sp_caspian_ogc", {"wkt": WKT["merc2sp_caspian_ogc"]},
         {"status": "ok", "crs": MERC(51.0, k0_caspian, 0.0, 0.0), "datum": {"id": "SK42"},
          "note": "Mercator_2SP folded into 1SP: k0 = cos(42)/sqrt(1-e2 sin2(42)) on Krassovsky"}),
        ("merc1sp_makassar_ogc", {"wkt": WKT["merc1sp_makassar_ogc"]},
         {"status": "unknownDatum", "crs": MERC(110.0, 0.997, 3900000.0, 900000.0),
          "note": "projection fine, but Makassar (Bessel) has no table row and no TOWGS84 -> loud failure, offer manual calibration"}),
        ("nztm_tm_wkt", {"wkt": WKT["nztm_ogc"]},
         {"status": "ok", "crs": TM(0.0, 173.0, 0.9996, 1600000.0, 10000000.0), "datum": ZERO_SHIFT("GRS80"),
          "datumAssumed": True,
          "note": "any TM given by WKT is supported; NZGD2000 not in table, GRS80 spheroid -> zero shift on GRS80"}),
        ("albers_usgs_esri", {"wkt": WKT["albers_usgs_esri"]},
         {"status": "fallbackLocalTM", "datum": {"id": "NAD83"}, "reason": "unsupportedProjection"}),
        ("webmerc_aux_sphere_esri", {"wkt": WKT["webmerc_aux_esri"]},
         {"status": "fallbackLocalTM", "datum": {"id": "WGS84"}, "reason": "unsupportedProjection",
          "note": "see fallbackExample below for the full numbers"}),
        ("unreadable_wkt", {"wkt": WKT["garbage"]},
         {"status": "fallbackLocalTM", "datum": {"id": "WGS84"}, "datumAssumed": True, "reason": "unreadableGcs"}),
        ("unknown_datum_with_towgs84", {"wkt": WKT["unknown_datum_towgs84"]},
         {"status": "ok", "crs": {"kind": "geographic"},
          "datum": {"id": "custom", "a": 6378206.4, "invF": 294.978698213898, "dx": -8.0, "dy": 160.0, "dz": 176.0}}),
        ("unknown_datum_grs80_no_towgs84", {"wkt": WKT["unknown_datum_grs80_no_towgs84"]},
         {"status": "ok", "crs": {"kind": "geographic"}, "datum": ZERO_SHIFT("GRS80"), "datumAssumed": True}),
        ("unknown_datum_non_wgs_ellipsoid", {"wkt": WKT["unknown_datum_intl_no_towgs84"]},
         {"status": "unknownDatum", "crs": {"kind": "geographic"}}),
        ("known_name_wins_over_towgs84", {"wkt": WKT["known_name_wins_over_towgs84"]},
         {"status": "ok", "crs": {"kind": "geographic"}, "datum": {"id": "ED50"},
          "note": "a recognised DATUM name always uses the table row, TOWGS84 is only read for unrecognised names"}),
    ]
    epsg = [
        (32610, {"status": "ok", "crs": UTM(10, False), "datum": {"id": "WGS84"}}),
        (32755, {"status": "ok", "crs": UTM(55, True), "datum": {"id": "WGS84"}}),
        (26910, {"status": "ok", "crs": UTM(10, False), "datum": {"id": "NAD83"}}),
        (26710, {"status": "ok", "crs": UTM(10, False), "datum": {"id": "NAD27"}}),
        (28355, {"status": "ok", "crs": UTM(55, True), "datum": {"id": "GDA94"}}),
        (28356, {"status": "ok", "crs": UTM(56, True), "datum": {"id": "GDA94"}}),
        (7855, {"status": "ok", "crs": UTM(55, True), "datum": {"id": "GDA2020"}}),
        (4326, {"status": "ok", "crs": {"kind": "geographic"}, "datum": {"id": "WGS84"}}),
        (4269, {"status": "ok", "crs": {"kind": "geographic"}, "datum": {"id": "NAD83"}}),
        (4283, {"status": "ok", "crs": {"kind": "geographic"}, "datum": {"id": "GDA94"}}),
        (7844, {"status": "ok", "crs": {"kind": "geographic"}, "datum": {"id": "GDA2020"}}),
        (2193, {"status": "fallbackLocalTM", "datum": {"id": "WGS84"}, "datumAssumed": True, "reason": "unsupportedEpsg"}),
        (3857, {"status": "fallbackLocalTM", "datum": {"id": "WGS84"}, "datumAssumed": True, "reason": "unsupportedEpsg"}),
    ]
    out = [{"id": cid, "input": inp, "expected": exp} for cid, inp, exp in cases]
    out += [{"id": "epsg_%d" % code, "input": {"epsg": code}, "expected": exp} for code, exp in epsg]
    out += [{"id": cid, "input": inp, "expected": exp} for cid, inp, exp in gcs_parity_cases()]
    # sanity: the expected crs params match what PROJ itself parses out of the same WKT/EPSG
    for c in out:
        exp = c["expected"]
        if exp["status"] != "ok" or "crs" not in exp:
            continue
        ours = exp["crs"]
        if ours["kind"] == "geographic":
            continue
        src = c["input"].get("wkt") or ("EPSG:%d" % c["input"]["epsg"])
        pc = ref.pyproj.CRS.from_user_input(src)
        d = datum_for_check(exp["datum"])
        pt = (37.8, -122.4) if ours["kind"] != "transverseMercator" else ref.inv(ours, d, ours["fe"] + 1234.5, ours["fn"] + (4.1e6 if ours["fn"] == 0 else -3.9e6))
        if ours["kind"] == "geographic":
            continue
        lat, lon = pt
        t = ref.pyproj.Transformer.from_crs(pc.geodetic_crs, pc, always_xy=True)
        X1, Y1 = t.transform(lon, lat)
        k = pc.axis_info[0].unit_conversion_factor      # PROJ answers in the CRS unit (US ft), we in metres
        X1, Y1 = X1 * k, Y1 * k
        X2, Y2 = ref.fwd(ours, d, lat, lon)
        if max(abs(X1 - X2), abs(Y1 - Y2)) > 1e-6:
            raise SystemExit("gcs case %s: our params disagree with PROJ's parse by %g m" % (c["id"], max(abs(X1 - X2), abs(Y1 - Y2))))
    return {"rules": {
        "wkt": "PROJCS: Transverse_Mercator and ESRI Gauss_Kruger (any params), Lambert_Conformal_Conic_1SP/_2SP and ESRI "
               "Lambert_Conformal_Conic (2SP when Standard_Parallel_2 present, else 1SP with Scale_Factor), Mercator_1SP, "
               "Mercator_2SP and ESRI Mercator (k0 from the standard parallel). GEOGCS alone = geographic. Parameter names "
               "case-insensitive, ignore AUTHORITY/AXIS nodes. Linear UNIT converts fe/fn to metres; the plane is always metres.",
        "parameters": "central meridian = central_meridian, else longitude_of_origin, else longitude_of_center (PROJ's "
                      "aliases, central_meridian wins when several are written); origin latitude = latitude_of_origin, else "
                      "latitude_of_center, else 0. lon0 = central meridian + PRIMEM, NOT wrapped. A supported projection "
                      "with no central meridian, or any PARAMETER that isn't a quoted name + finite number, is unreadableGcs "
                      "(fallback in the GEOGCS datum), never a silent 0",
        "primeMeridian": "PRIMEM value must be a finite number in [-180, 180], else status malformed (reject malformed). "
                         "GPTS longitudes are relative to it: lon + PRIMEM outside [-180, 180] is gptsOffEarth",
        "unreadableDatum": "no DATUM node, a DATUM without a quoted name or without a SPHEROID/ELLIPSOID node (known name "
                           "or not), or an unknown name whose SPHEROID isn't a plausible earth is an unreadable GCS: "
                           "fallbackLocalTM / unreadableGcs on WGS84 (datumAssumed), the PROJECTION is not used. A PROJCS "
                           "with a bad linear UNIT (not finite, <= 0) counts as an unreadable projection",
        "towgs84": "TOWGS84 counts only with exactly 3 values, or 7 with the last 4 all zero. Any other length is ignored",
        "gcsTypes": "/GCS that isn't a dictionary, /WKT that isn't a string or /EPSG that isn't a PDF integer: reject "
                    "malformed. No /GCS, or a /GCS with neither key: fallbackLocalTM / unreadableGcs",
        "epsg": "326zz/327zz WGS84 UTM, 269zz NAD83 UTM N, 267zz NAD27 UTM N, 283zz GDA94 MGA, 78zz (7846-7859) GDA2020 MGA, "
                "4326/4269/4283/7844 geographic. Anything else is unsupportedEpsg.",
        "datum": "DATUM name in the table (see datums.rules.wktNameMatch) wins; else TOWGS84 with zero rotations/scale -> custom; "
                 "else a SPHEROID within 1 m / 1e-6 invF of WGS84 or GRS80 -> custom zero shift on that spheroid, "
                 "datumAssumed; else unknownDatum (reject). The projection always runs on the datum's ellipsoid.",
        "fallbackLocalTM": "unsupported projection or unreadable GCS: crs = TM(lat0 = mean GPTS lat, lon0 = mean GPTS lon, "
                           "k0 = 1, fe = fn = 0) in the GEOGCS datum if it was readable, else WGS84 (datumAssumed). "
                           "GPTS are still (lat, lon) in that datum. Fit + gate as normal."},
        "cases": out,
        "fallbackExample": fallback_example(ref)}


WKT_GEOG_WGS84_BODY = 'DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]]'


def wkt_tm(params, geog=None, proj="Transverse_Mercator", unit='UNIT["metre",1]'):
    geog = geog or ('GEOGCS["WGS 84",%s,PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]]' % WKT_GEOG_WGS84_BODY)
    return 'PROJCS["test",%s,PROJECTION["%s"],%s,%s]' % (
        geog, proj, ",".join('PARAMETER["%s",%s]' % kv for kv in params), unit)


def gcs_parity_cases():
    """iOS/Android parity review (2026-10): one rule per branch, both builders converge on these"""
    tm_params = [("latitude_of_origin", "0"), ("scale_factor", "1"), ("false_easting", "500000"), ("false_northing", "0")]
    gk_pulkovo = ('PROJCS["Pulkovo_1942_GK_Zone_4",GEOGCS["GCS_Pulkovo_1942",DATUM["D_Pulkovo_1942",'
                  'SPHEROID["Krasovsky_1940",6378245.0,298.3]],PRIMEM["Greenwich",0.0],UNIT["Degree",0.0174532925199433]],'
                  'PROJECTION["Gauss_Kruger"],PARAMETER["False_Easting",4500000.0],PARAMETER["False_Northing",0.0],'
                  'PARAMETER["Central_Meridian",21.0],PARAMETER["Scale_Factor",1.0],PARAMETER["Latitude_Of_Origin",0.0],'
                  'UNIT["Meter",1.0]]')
    gk_cgcs = ('PROJCS["CGCS2000_3_Degree_GK_CM_117E",GEOGCS["GCS_China_Geodetic_Coordinate_System_2000",'
               'DATUM["D_China_2000",SPHEROID["CGCS2000",6378137.0,298.257222101]],PRIMEM["Greenwich",0.0],'
               'UNIT["Degree",0.0174532925199433]],PROJECTION["Gauss_Kruger"],PARAMETER["False_Easting",500000.0],'
               'PARAMETER["False_Northing",0.0],PARAMETER["Central_Meridian",117.0],PARAMETER["Scale_Factor",1.0],'
               'PARAMETER["Latitude_Of_Origin",0.0],UNIT["Meter",1.0]]')
    wgs = {"id": "WGS84"}
    return [
        ("gauss_kruger_esri_pulkovo_gk4", {"wkt": gk_pulkovo},
         {"status": "ok", "crs": TM(0.0, 21.0, 1.0, 4500000.0, 0.0), "datum": {"id": "SK42"},
          "note": "ESRI Gauss_Kruger is Transverse_Mercator (GDAL morphFromESRI, PROJ)"}),
        ("gauss_kruger_esri_cgcs2000", {"wkt": gk_cgcs},
         {"status": "ok", "crs": TM(0.0, 117.0, 1.0, 500000.0, 0.0), "datum": ZERO_SHIFT("GRS80"), "datumAssumed": True,
          "note": "D_China_2000 isn't in the table, GRS80-sized spheroid -> zero shift, assumed"}),
        ("tm_longitude_of_origin", {"wkt": wkt_tm(tm_params + [("longitude_of_origin", "21")])},
         {"status": "ok", "crs": TM(0.0, 21.0, 1.0, 500000.0, 0.0), "datum": wgs,
          "note": "longitude_of_origin is a central meridian alias (PROJ)"}),
        ("tm_longitude_of_center", {"wkt": wkt_tm(tm_params + [("longitude_of_center", "27")])},
         {"status": "ok", "crs": TM(0.0, 27.0, 1.0, 500000.0, 0.0), "datum": wgs}),
        ("tm_central_meridian_wins", {"wkt": wkt_tm(tm_params + [("longitude_of_origin", "30"), ("central_meridian", "21")])},
         {"status": "ok", "crs": TM(0.0, 21.0, 1.0, 500000.0, 0.0), "datum": wgs,
          "note": "central_meridian beats longitude_of_origin wherever it is written (PROJ)"}),
        ("tm_missing_central_meridian", {"wkt": wkt_tm(tm_params)},
         {"status": "fallbackLocalTM", "datum": wgs, "reason": "unreadableGcs",
          "note": "PROJ would default lon_0 = 0, a silent guess; treat it as an unreadable GCS on the readable GEOGCS datum"}),
        ("tm_parameter_not_a_number", {"wkt": wkt_tm(tm_params + [("central_meridian", '"21"')])},
         {"status": "fallbackLocalTM", "datum": wgs, "reason": "unreadableGcs"}),
        ("projection_without_name", {"wkt": wkt_tm(tm_params + [("central_meridian", "21")], proj="").replace(
            'PROJECTION[""]', "PROJECTION[]")},
         {"status": "fallbackLocalTM", "datum": wgs, "reason": "unreadableGcs"}),
        ("geogcs_without_datum", {"wkt": 'GEOGCS["no datum",PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]]'},
         {"status": "fallbackLocalTM", "datum": wgs, "datumAssumed": True, "reason": "unreadableGcs"}),
        ("geogcs_datum_without_spheroid_unknown_name",
         {"wkt": 'GEOGCS["x",DATUM["Mystery_Datum"],PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]]'},
         {"status": "fallbackLocalTM", "datum": wgs, "datumAssumed": True, "reason": "unreadableGcs"}),
        ("geogcs_datum_without_spheroid_known_name",
         {"wkt": 'GEOGCS["x",DATUM["North_American_Datum_1983"],PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]]'},
         {"status": "fallbackLocalTM", "datum": wgs, "datumAssumed": True, "reason": "unreadableGcs",
          "note": "a DATUM with no SPHEROID/ELLIPSOID isn't valid WKT (PROJ refuses it), even with a known name"}),
        ("projcs_unreadable_datum", {"wkt": wkt_tm(tm_params + [("central_meridian", "21")],
                                                    geog='GEOGCS["x",DATUM["Mystery_Datum"],PRIMEM["Greenwich",0],'
                                                         'UNIT["degree",0.0174532925199433]]')},
         {"status": "fallbackLocalTM", "datum": wgs, "datumAssumed": True, "reason": "unreadableGcs",
          "note": "unreadable GEOGCS datum: WGS84 assumed and the projection is dropped (local TM), per fallbackLocalTM"}),
        ("projcs_unit_not_positive", {"wkt": wkt_tm(tm_params + [("central_meridian", "21")], unit='UNIT["metre",0]')},
         {"status": "fallbackLocalTM", "datum": wgs, "reason": "unreadableGcs",
          "note": "the GEOGCS was readable, so its datum is kept and not flagged as assumed"}),
        ("primem_out_of_range",
         {"wkt": 'GEOGCS["WGS 84",%s,PRIMEM["Greenwich",200],UNIT["degree",0.0174532925199433]]' % WKT_GEOG_WGS84_BODY},
         {"status": "malformed", "note": "PRIMEM outside [-180, 180] (or not a number) moves every point, don't guess 0"}),
        ("primem_without_value",
         {"wkt": 'GEOGCS["WGS 84",%s,PRIMEM["Greenwich"],UNIT["degree",0.0174532925199433]]' % WKT_GEOG_WGS84_BODY},
         {"status": "malformed"}),
        ("towgs84_five_values",
         {"wkt": 'GEOGCS["x",DATUM["Local_survey_1927",SPHEROID["Clarke 1866",6378206.4,294.978698213898],'
                 'TOWGS84[-8,160,176,0,0]],PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]]'},
         {"status": "unknownDatum", "crs": {"kind": "geographic"},
          "note": "TOWGS84 needs exactly 3 values, or 7 with zero rotations/scale; 5 is ignored -> Clarke 1866 can't be placed"}),
        ("towgs84_three_values",
         {"wkt": 'GEOGCS["x",DATUM["Local_survey_1927",SPHEROID["Clarke 1866",6378206.4,294.978698213898],'
                 'TOWGS84[-8,160,176]],PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]]'},
         {"status": "ok", "crs": {"kind": "geographic"},
          "datum": {"id": "custom", "a": 6378206.4, "invF": 294.978698213898, "dx": -8.0, "dy": 160.0, "dz": 176.0}}),
    ]


def ZERO_SHIFT(ell):
    a, invf = ELLIPSOIDS[ell]
    return {"id": "custom", "a": a, "invF": invf, "dx": 0.0, "dy": 0.0, "dz": 0.0}


def datum_for_check(dj):
    if dj["id"] == "custom":
        return {"a": dj["a"], "invF": dj["invF"], "transform": "translation3", "dx": dj["dx"], "dy": dj["dy"], "dz": dj["dz"]}
    return dj["id"]


def fallback_example(ref):
    """a page that is a linear image of Web Mercator, declared with the ESRI aux sphere WKT.
    we don't support that projection so the parser must use the local TM fallback."""
    # 6 x 8 km area around SF, north-up, 1:25k in web mercator metres
    wm = ref.pyproj.Proj("+proj=merc +a=6378137 +b=6378137 +lat_ts=0 +lon_0=0 +x_0=0 +y_0=0 +k=1 +units=m +nadgrids=@null +no_defs")
    x0, y0 = wm(-122.478, 37.73)
    s = S25K / math.cos(math.radians(37.765))       # wm metres per point
    bbox = [72.0, 72.0, 72.0 + 680.0, 72.0 + 907.0]
    lpts = [0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0]
    gpts = []
    for i in range(4):
        px = bbox[0] + lpts[2 * i] * (bbox[2] - bbox[0])
        py = bbox[1] + lpts[2 * i + 1] * (bbox[3] - bbox[1])
        lo, la = wm(x0 + (px - 72.0) * s, y0 + (py - 72.0) * s, inverse=True)
        gpts += [num(la, DEG_DP), num(lo, DEG_DP)]
    lat0 = sum(gpts[0::2]) / 4
    lon0 = sum(gpts[1::2]) / 4
    crs = TM(lat0, lon0, 1.0, 0.0, 0.0)
    vp = {"bbox": bbox, "lpts": lpts, "gpts": gpts, "bounds": None}
    A, pairs = fit_vp(ref, vp, crs, "WGS84")
    g = {"crs": crs, "datum": "WGS84", "affine": A}
    stats = fit_stats(ref, A, pairs, crs, "WGS84")
    checks = []
    for px, py in [(72.0, 72.0), (412.0, 525.5), (752.0, 979.0), (200.0, 800.0)]:
        c = check_point(ref, g, "page %g %g" % (px, py), px, py, "fallback")
        lo, la = wm(x0 + (px - 72.0) * s, y0 + (py - 72.0) * s, inverse=True)
        c["constructionWgs84"] = [rdeg(la), rdeg(lo)]
        c["fallbackErrorMetres"] = round(ref.geod_m(la, lo, c["wgs84"][0], c["wgs84"][1]), 3)
        checks.append(c)
    return {"input": {"bbox": bbox, "lpts": lpts, "gpts": gpts, "gcs": {"type": "PROJCS", "wkt": WKT["webmerc_aux_esri"]}},
            "expected": {"crs": crs, "datum": {"id": "WGS84"}, "affine": [rsig(v) for v in A],
                         "affineTol": affine_tol(ref, g, vp_crop(vp)), "fit": stats, "checks": checks,
                         "tolerances": {"wgs84Metres": 0.01, "pagePoints": 0.001}}}


# ----------------------------------------------------------------------------
# rejections (and a couple of must-not-reject contrasts)
# ----------------------------------------------------------------------------

def vp_pdf_extras(vp, wkt, gcs_type="PROJCS"):
    def arr(vals):
        return " ".join(v if isinstance(v, str) else dec(v, DEG_DP) for v in vals)
    return ("/VP [<< /Type /Viewport /Name (Map Layers) /BBox [%s] /Measure << /Type /Measure /Subtype /GEO %s/GPTS [%s] "
            "/LPTS [%s] /GCS << /Type /%s /WKT %s >> >> >>]" % (
                arr(vp["bbox"]), ("/Bounds [%s] " % arr(vp["bounds"])) if vp.get("bounds") else "",
                arr(vp["gpts"]), arr(vp["lpts"]), gcs_type, pdf_str(wkt)))


def lgi_pdf_value(v):
    if isinstance(v, str):
        return "(%s)" % v
    if isinstance(v, dict):
        return "<< %s >>" % " ".join("/%s %s" % (k, lgi_pdf_value(x)) for k, x in v.items())
    return dec(v, DEG_DP)


def lgi_pdf_extras(entries):
    parts = []
    for e in entries:
        s = "<< /Type /LGIDict /Version (2.1) /Description (%s)" % e["description"]
        if "ctm" in e:
            s += " /CTM [%s]" % nums(e["ctm"], DEG_DP)
        if "registration" in e:
            s += " /Registration [%s]" % " ".join("[%s]" % nums(r, M_DP) for r in e["registration"])
        if "neatline" in e:
            s += " /Neatline [%s]" % nums(e["neatline"], PT_DP)
        if "projection" in e:
            s += " /Projection << /Type /Projection %s >>" % " ".join(
                "/%s %s" % (k, lgi_pdf_value(v)) for k, v in e["projection"].items())
        if "display" in e:
            s += " /Display << %s >>" % " ".join("/%s %s" % (k, lgi_pdf_value(v)) for k, v in e["display"].items())
        parts.append(s + " >>")
    return "/LGIDict [%s]" % " ".join(parts)


def rejections_section(ref, sheets, pdfdir=None):
    sf = sheets["sf"]
    wkt10 = wkt_utm_simple(10, False)
    _, base = iso_vp(sf, ("PROJCS", "wkt", wkt10))
    base = base["viewports"][0]
    crs10 = UTM(10, False)
    out = []

    def add(cid, vp, expected, media=None, wkt=wkt10, note=None, gcs_type="PROJCS"):
        c = {"id": cid, "kind": "adobeVP", "mediaBox": media or [rpt(v) for v in sf.media],
             "input": {"viewports": [dict(vp, gcs={"type": gcs_type, "wkt": wkt})]},
             "pdfPageExtras": vp_pdf_extras(vp, wkt, gcs_type), "expected": expected}
        if note:
            c["note"] = note
        out.append(c)

    # USGS shape with one LPTS pushed out to 1.6
    u = usgs_vp_parsed()
    bad = dict(bbox=u[0]["bbox"], lpts=list(u[0]["lpts"]), gpts=u[0]["gpts"], bounds=None)
    bad["lpts"][4] = 1.6
    add("lpts_outside_range", bad, {"outcome": "reject", "reason": "lptsOutOfRange"}, media=USGS_MEDIA,
        wkt=u[0]["wkt"], note="accepted range is [-0.5, 1.5] inclusive (plan s1, D1-01)")

    # LPTS exactly on the limits: bbox is the middle half of the neatline, so corners land on -0.5 / 1.5
    cs = sf.corners()
    xs, ys = [p[0] for p in cs], [p[1] for p in cs]
    w, h = max(xs) - min(xs), max(ys) - min(ys)
    bb = [num(min(xs) + w / 4, PT_DP), num(min(ys) + h / 4, PT_DP), num(max(xs) - w / 4, PT_DP), num(max(ys) - h / 4, PT_DP)]
    lp = []
    for x, y in cs:
        lp += [num((x - bb[0]) / (bb[2] - bb[0]), DEG_DP), num((y - bb[1]) / (bb[3] - bb[1]), DEG_DP)]
    edge = dict(bbox=bb, lpts=lp, gpts=None, bounds=None)
    gp = []
    for x, y in vp_page_pts(edge):
        la, lo = sf.latlon(x, y)
        gp += [num(la, DEG_DP), num(lo, DEG_DP)]
    edge["gpts"] = gp
    A, pairs = fit_vp(ref, edge, crs10, "WGS84")
    g = {"crs": crs10, "datum": "WGS84", "affine": A}
    exp = georef_json(ref, g, "adobeVP", {"kind": "viewport", "index": 0, "name": "Map Layers"}, vp_crop(edge),
                      {"fit": fit_stats(ref, A, pairs, crs10, "WGS84")})
    exp["outcome"] = "accept"
    exp["checks"] = [check_point(ref, g, "control %d" % (i + 1), x, y, "control") for i, (x, y, _, _) in enumerate(pairs)]
    add("lpts_boundary_inclusive", edge, exp, note="LPTS are exactly -0.5 and 1.5 (up to 12 dp); crop is the BBox only")

    nf = dict(base, gpts=["1" + "0" * 400 + ".0"] + [dec(v, DEG_DP) for v in base["gpts"][1:]])
    c_nf = {"id": "gpts_non_finite", "kind": "adobeVP", "mediaBox": [rpt(v) for v in sf.media],
            "input": {"viewports": [dict(base, gpts=["Infinity"] + base["gpts"][1:], gcs={"type": "PROJCS", "wkt": wkt10})]},
            "pdfPageExtras": vp_pdf_extras(nf, wkt10),
            "expected": {"outcome": "reject", "reason": "nonFinite"},
            "note": "json can't hold inf, so the structured input uses the string \"Infinity\" (Double(\"Infinity\")); "
                    "the pdf form is a 401 digit real that overflows a double. any number that doesn't parse to a "
                    "finite double is nonFinite"}
    out.append(c_nf)

    g91 = list(base["gpts"])
    g91[4] = 91.0
    add("gpts_lat_off_earth", dict(base, gpts=g91), {"outcome": "reject", "reason": "gptsOffEarth"})
    g181 = list(base["gpts"])
    g181[1] = -181.0
    add("gpts_lon_off_earth", dict(base, gpts=g181), {"outcome": "reject", "reason": "gptsOffEarth"})

    gr = list(base["gpts"])
    gr[4] = num(gr[4] + 0.004, DEG_DP)     # NE corner ~444 m north
    rvp = dict(base, gpts=gr)
    A, pairs = fit_vp(ref, rvp, crs10, "WGS84")
    st = fit_stats(ref, A, pairs, crs10, "WGS84")
    assert not st["passesGate"]
    add("rms_gate", rvp, {"outcome": "reject", "reason": "rmsGate", "fit": st},
        note="gate: plane RMS <= max(2 m, 0.002 * sheet diagonal); diagonal = control points' plane bbox diagonal")

    zb = dict(base, bbox=[base["bbox"][0], base["bbox"][1], base["bbox"][0], base["bbox"][3]])
    add("bbox_zero_width", zb, {"outcome": "reject", "reason": "degenerateViewport"})
    col = dict(base, lpts=[0.0, 0.0, 0.5, 0.5, 1.0, 1.0], gpts=base["gpts"][:6])
    add("lpts_collinear", col, {"outcome": "reject", "reason": "degenerateViewport",
                                "rule": "page-point covariance eigenvalue ratio < 1e-6, or < 3 points, or zero-area BBox"})
    few = dict(base, lpts=base["lpts"][:4], gpts=base["gpts"][:4])
    add("too_few_points", few, {"outcome": "reject", "reason": "degenerateViewport"})
    mm = dict(base, gpts=base["gpts"][:6])
    add("lpts_gpts_count_mismatch", mm, {"outcome": "reject", "reason": "malformed"})

    # unknown datum, non WGS-ish ellipsoid, no TOWGS84 -> loud failure
    add("unknown_datum", dict(base), {"outcome": "reject", "reason": "unknownDatum"},
        wkt=WKT["unknown_datum_intl_no_towgs84"], gcs_type="GEOGCS",
        note="GPTS are fine, the datum can't be placed on WGS84")

    # USGS three viewports with the main one broken: plan s1 says largest VALID viewport wins
    vps = []
    for i, v in enumerate(u):
        vv = dict(bbox=v["bbox"], lpts=list(v["lpts"]), gpts=v["gpts"], bounds=list(v["bounds"]))
        if i == 0:
            vv["lpts"][4] = 1.6
            vv["bounds"][4] = 1.6
        vps.append((v, vv))
    qv = vps[1][1]
    mcrs = MERC(0.0, 1.0, 0.0, 0.0)
    A, pairs = fit_vp(ref, qv, mcrs, "WGS84")
    g = {"crs": mcrs, "datum": "WGS84", "affine": A}
    exp = georef_json(ref, g, "adobeVP", {"kind": "viewport", "index": 1, "name": "Quadrangle Location",
                                          "rule": "largest BBox among viewports that yield a valid georef (plan s1)"},
                      vp_crop(qv), {"fit": fit_stats(ref, A, pairs, mcrs, "WGS84")})
    exp["outcome"] = "accept"
    exp["checks"] = [check_point(ref, g, "control %d" % (i + 1), x, y, "control") for i, (x, y, _, _) in enumerate(pairs)]
    extras = "/VP [%s]" % " ".join(
        vp_pdf_extras(vv, v["wkt"])[5:-1].replace("(Map Layers)", pdf_str(v["name"])) for v, vv in vps)
    out.append({"id": "largest_viewport_malformed_inset_valid", "kind": "adobeVP", "mediaBox": USGS_MEDIA,
                "input": {"viewports": [dict(vv, name=v["name"], gcs={"type": "PROJCS", "wkt": v["wkt"]}) for v, vv in vps]},
                "pdfPageExtras": extras, "expected": exp,
                "note": "follows plan s1 literally: the state locator inset gets picked and only its BBox is shown. "
                        "flagged for review, fail-closed might be the better call"})

    # --- LGIDict cases
    a, b, c, d, e, f = sf.truth_affine()
    ctm = [num(v, DEG_DP) for v in (a, d, b, e, c, f)]
    nl = []
    for p in sf.corners():
        nl += [num(p[0], PT_DP), num(p[1], PT_DP)]
    regs = []
    for p in sf.corners():
        X, Y = sf.plane_of(*p)
        regs.append([num(p[0], PT_DP), num(p[1], PT_DP), num(X, M_DP), num(Y, M_DP)])
    proj = {"ProjectionType": "UT", "Zone": 10, "Hemisphere": "N", "Datum": "WE"}

    def lgi_case(cid, entries, expected, note=None):
        c = {"id": cid, "kind": "lgiDict", "mediaBox": [rpt(v) for v in sf.media], "input": {"entries": entries},
             "pdfPageExtras": lgi_pdf_extras(entries), "expected": expected}
        if note:
            c["note"] = note
        out.append(c)

    def lgi_expect(A, crop, source, idx, desc, stats=None):
        g = {"crs": crs10, "datum": "WGS84", "affine": A}
        ex = georef_json(ref, g, "lgiDict", {"kind": "lgiEntry", "index": idx, "description": desc, "source": source},
                         crop, {"fit": stats} if stats else None)
        ex["outcome"] = "accept"
        ex["checks"] = [check_point(ref, g, "neatline %d" % (i + 1), x, y, "neatline") for i, (x, y) in enumerate(crop)]
        return ex

    crop = [(nl[2 * i], nl[2 * i + 1]) for i in range(4)]
    A_ctm = [ctm[0], ctm[2], ctm[4], ctm[1], ctm[3], ctm[5]]
    lgi_case("lgi_ctm_registration_agree",
             [{"description": "Layers", "ctm": ctm, "registration": regs, "neatline": nl, "projection": proj}],
             lgi_expect(A_ctm, crop, "ctm", 0, "Layers"),
             note="both present and CTM(page) is within 1 m of every Registration point -> CTM")
    ctm_bad = list(ctm)
    ctm_bad[4] = num(ctm_bad[4] + 50.0, DEG_DP)
    pairs = [tuple(r) for r in regs]
    A_reg = ref.fit(pairs)
    lgi_case("lgi_ctm_registration_conflict",
             [{"description": "Layers", "ctm": ctm_bad, "registration": regs, "neatline": nl, "projection": proj}],
             lgi_expect(A_reg, crop, "registration", 0, "Layers", fit_stats(ref, A_reg, pairs, crs10, "WGS84")),
             note="CTM is 50 m off the Registration -> plan s1 says use Registration (not a rejection). crop is still "
                  "the Neatline")
    col_regs = [regs[0], [num((regs[0][0] + regs[2][0]) / 2, PT_DP), num((regs[0][1] + regs[2][1]) / 2, PT_DP),
                          num((regs[0][2] + regs[2][2]) / 2, M_DP), num((regs[0][3] + regs[2][3]) / 2, M_DP)], regs[2]]
    lgi_case("lgi_registration_collinear",
             [{"description": "Layers", "registration": col_regs, "projection": proj}],
             {"outcome": "reject", "reason": "degenerateViewport"})
    lgi_case("lgi_unknown_projection",
             [{"description": "Layers", "ctm": ctm, "neatline": nl,
               "projection": {"ProjectionType": "XX", "Datum": "WE"}}],
             {"outcome": "reject", "reason": "unsupportedProjection",
              "note": "LGIDict has no lat/lon controls to fall back on, so an unknown ProjectionType is a loud failure"})
    lgi_case("lgi_unknown_datum_code",
             [{"description": "Layers", "ctm": ctm, "neatline": nl,
               "projection": {"ProjectionType": "UT", "Zone": 10, "Hemisphere": "N", "Datum": "ZZZ"}}],
             {"outcome": "reject", "reason": "unknownDatum"})
    # Layers wins even when another entry has a bigger neatline
    big_nl = [num(v, PT_DP) for v in (0, 0, sf.media[2], 0, sf.media[2], sf.media[3], 0, sf.media[3])]
    lgi_case("lgi_layers_beats_bigger_entry",
             [{"description": "Collar", "ctm": ctm_bad, "neatline": big_nl, "projection": proj},
              {"description": "Layers", "ctm": ctm, "neatline": nl, "projection": proj}],
             lgi_expect(A_ctm, crop, "ctm", 1, "Layers"),
             note="entry named Layers is taken first, only without one does the largest neatline win")
    lgi = dict(ctm=ctm, nl=nl, regs=regs, proj=proj, crop=crop, A_ctm=A_ctm)
    rejection_parity_cases(ref, sf, base, wkt10, crs10, out, lgi_case, lgi_expect, lgi)
    value_cases = lgi_value_cases(ref, sf, base, wkt10, crs10, lgi_expect, lgi)
    budget_cases = page_budget_cases(ref, pdfdir, sf, base, wkt10, crs10, lgi_expect, lgi) if pdfdir else []
    return {"reasons": {
        "lptsOutOfRange": "an LPTS coordinate outside [-0.5, 1.5]",
        "nonFinite": "any number that doesn't parse to a finite double",
        "gptsOffEarth": "lat outside [-90, 90], lon + PRIMEM outside [-180, 180], a GPTS the projection can't forward "
                        "(e.g. > 90 deg from a TM central meridian), or a georef that maps the crop off the earth",
        "rmsGate": "plane RMS > max(2 m, 0.002 * sheet diagonal)",
        "degenerateViewport": "zero-area BBox, < 3 control points, or page-point covariance eigen ratio < 1e-6",
        "malformed": "odd/mismatched array lengths, wrong types (incl. /GCS, /WKT, /EPSG, /Bounds, /LGIDict, "
                     "Registration rows that aren't 4 numbers, broken inline LGIDict datums), broken PRIMEM, "
                     "an LGIDict entry without /Projection, a declared /LGIDict with no dictionary in it",
        "unknownDatum": "datum can't be placed on WGS84 (see gcs.rules.datum)",
        "unsupportedProjection": "LGIDict ProjectionType or Units we can't do (Adobe path falls back to local TM instead)"},
        "lgiRules": {
            "projectionTypes": "UT/UTM (Zone + Hemisphere, also read from /Display), TC, LE/LC (StandardParallelTwo "
                               "defaults to One), geographic = GEOGRAPHIC/GEO/LL/LONGLAT. Anything else unsupportedProjection",
            "valueTypes": "ProjectionType, Hemisphere, Units and a Datum code are a name or a string (a bare integer "
                          "Datum reads as its decimal digits, any size; a real Datum is malformed); a ProjectionType "
                          "of any other type is malformed. Numeric "
                          "parameters are a pdf number or a numeric string, never a name. Zone/Hemisphere come from "
                          "/Projection when the key is there and from /Display only when it's missing: a present "
                          "/Zone that isn't a whole number in 1..60 (name, junk string, inf, 1e30, 10.5) or a present "
                          "/Hemisphere that isn't N/S/NORTH/SOUTH text is malformed. Range-check the double before "
                          "converting it to an int. Same for every optional key: present but unusable is malformed, "
                          "never swapped for a default (a junk or null StandardParallelTwo doesn't fall back to One). "
                          "Text values (types, codes, hemisphere) are trimmed of numericStrings.trim and compared "
                          "case-insensitively",
            "numericStrings": {
                "grammar": "^[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?$ (ascii only) after trimming",
                "trim": "fiduciaryFits.whiteSpaceCodePoints, both ends only",
                "rule": "anywhere a pdf number may come as a string (LGIDict /Zone and the TC/LE parameters, inline "
                        "datum numbers, every element of /CTM /Registration /Neatline and the /VP arrays) the string "
                        "has to match the grammar, else it's a wrong type: malformed. A match that overflows a double "
                        "is non-finite like an overflowing pdf real. Platform number parsers take more than this "
                        "(Java/Kotlin: 10d, 10f, hex floats, Infinity; Swift: hex floats, inf, nan), so gate on the "
                        "grammar before converting"},
            "nullValues": "a key whose value is the pdf null object is PRESENT with the wrong type, on every /VP, "
                          "/Measure, /GCS, /LGIDict, /Projection, /Display and inline /Datum key: malformed (Units: "
                          "unsupportedProjection), never treated as missing and never a /Display or default fallback. "
                          "ISO 32000 7.3.7 says null == absent; we don't follow it because every absent-key branch "
                          "here is a fallback (local TM, page-box crop, /Display zone, plain PDF) and a null where a "
                          "georef value belongs is a broken producer. PDFBox getDictionaryObject() hides null "
                          "(returns null), so read the raw item. /Display itself is only looked at when /Projection "
                          "lacks Zone or Hemisphere",
            "order": "entry structure (CTM, Neatline, Registration shape then finiteness) -> /Projection present -> "
                     "ProjectionType (missing malformed, unknown unsupportedProjection) -> Datum -> Units -> parameters "
                     "-> Registration fit / CTM precedence -> crop -> crop on the earth (gptsOffEarth)",
            "shortRegistration": "a Registration with < 3 rows or collinear page points is ignored next to a CTM (CTM "
                                 "alone, source ctm) and is degenerateViewport without one. As the crop (no Neatline) "
                                 "it needs >= 3 rows, else malformed",
            "units": "GDAL ParseProjDict: Units M (also METER/METERS/METRE/METRES) = 1, FT = 0.3048, USSF = 1200/3937, "
                     "case-insensitive, name or string. It converts FalseEasting/FalseNorthing ONLY: CTM and "
                     "Registration are always metres ('the false easting/northing of the SRS are expressed in the "
                     "unit, but the geotransform is expressed in meters'). Ignored for geographic. Any other Units, "
                     "or a non-metre Units on UT (zone false origin is fixed metres), is unsupportedProjection",
            "inlineDatum": "/Datum << /Ellipsoid << /SemiMajorAxis /InvFlattening >> /ToWGS84 << /dx /dy /dz >> >>: "
                           "Ellipsoid missing or not a plausible earth, or ToWGS84 present but not 3 numbers -> malformed. "
                           "No ToWGS84: WGS84/GRS80-sized (1 m / 1e-6 invF) -> zero shift, datumAssumed; else unknownDatum",
            "entries": "/LGIDict is a dictionary or an array; non-dictionary array members are skipped and the "
                       "selected index counts dictionaries only. Neither type, or no dictionary at all, is malformed",
            "registrationRows": "each row must be an array of exactly 4 numbers, at most 4096 rows; the row length is "
                                "checked before any value is read (hostile files point every row at one huge shared "
                                "array). Readers also cap the numbers read per page across all /VP and LGIDict arrays",
            "pageBudget": "65536 numbers per page across every /VP and LGIDict array (row lengths are checked first). "
                          "Running out while reading the /VP makes the whole /VP malformed (no viewport of it is "
                          "tried) and the LGIDict too; running out while reading the LGIDict makes the LGIDict "
                          "malformed. A /VP read in full before that still stands, and so does its rejection (the "
                          "first rejection is the one reported). Read order: page /VP (GEO viewports only, BBox LPTS "
                          "GPTS Bounds), else the catalog /VP on the same budget, then LGIDict entries in order (CTM, "
                          "Registration row by row, Neatline). An array is charged its full length before any value "
                          "is read; one over the 8192 cap is malformed and charges nothing; non-GEO viewports, page "
                          "boxes and /Projection values are never charged. Exactly 65536 is fine, 65537 runs out. "
                          "pageBudgetCases pin this with shared-array PDFs (the structured form can't express it)"},
        "note": "input.viewports / input.entries hold the parsed numbers (for core tests), pdfPageExtras the same thing as "
                "page-dictionary text (for parser tests: drop it into a one page PDF with the given MediaBox). "
                "pdfPageExtras strings are latin-1, one char per byte, same for sheets[].pdfPageExtras. "
                "Structured stand-ins for wrong PDF types: a viewport gcs that isn't an object = /GCS not a dictionary; "
                "gcs.wkt that isn't a string = /WKT not a string; gcs.epsg that isn't an integer = /EPSG not a PDF "
                "integer; input.entries that isn't an array = /LGIDict of the wrong type. Viewport/entry indexes count "
                "GEO viewports / LGIDict dictionaries only, so structured inputs leave the non-GEO and non-dictionary "
                "members out. valueTypeCases (parity round 4: null, number vs string, numeric string grammar) have "
                "the same shape and add stand-ins: {\"pdfNull\": true} is the pdf null object wherever a value can "
                "go (a gcs of the string \"malformed\" still stands for /GCS null), {\"pdfReal\": x} is a pdf real "
                "where a Datum code goes, a json string in a numeric slot is a pdf string that has to pass "
                "lgiRules.numericStrings, a json integer Datum is a pdf integer. pageBudgetCases are whole PDFs "
                "(file, opened at page 0) because the budget needs shared indirect arrays; objects 1-4 are "
                "catalog/pages/page/content and pdfObjects are 5, 6, ... like the platform test writers. Both "
                "extra arrays are part of the contract next to cases",
        "cases": out,
        "valueTypeCases": value_cases,
        "pageBudgetCases": budget_cases}


def vp_extras_custom(vp, gcs_pdf, name="Map Layers"):
    def arr(vals):
        return " ".join(v if isinstance(v, str) else dec(v, DEG_DP) for v in vals)
    return ("/VP [<< /Type /Viewport /Name %s /BBox [%s] /Measure << /Type /Measure /Subtype /GEO %s/GPTS [%s] "
            "/LPTS [%s] /GCS %s >> >>]" % (
                pdf_str(name), arr(vp["bbox"]), ("/Bounds [%s] " % arr(vp["bounds"])) if vp.get("bounds") else "",
                arr(vp["gpts"]), arr(vp["lpts"]), gcs_pdf))


def wrap_lon(lon):
    """same as both apps: only touch it when it's outside [-180, 180]"""
    if -180.0 <= lon <= 180.0:
        return lon
    return ((lon + 180.0) % 360.0) - 180.0


def rejection_parity_cases(ref, sf, base, wkt10, crs10, out, lgi_case, lgi_expect, lgi):
    """iOS/Android parity review (2026-10): every branch the two builders used to disagree on, one rule each"""
    media = [rpt(v) for v in sf.media]
    rej = lambda reason: {"outcome": "reject", "reason": reason}
    gcs10 = {"type": "PROJCS", "wkt": wkt10}
    vp0 = {k: v for k, v in base.items() if k != "gcs"}

    def vp_case(cid, vps, extras, expected, note=None):
        c = {"id": cid, "kind": "adobeVP", "mediaBox": media, "input": {"viewports": vps}, "pdfPageExtras": extras,
             "expected": expected}
        if note:
            c["note"] = note
        out.append(c)

    def vp_accept(vp_written, vp_greenwich, crs, datum, index=0, rule=None):
        A, pairs = fit_vp(ref, vp_greenwich, crs, datum)
        g = {"crs": crs, "datum": datum, "affine": A}
        sel = {"kind": "viewport", "index": index, "name": vp_written.get("name", "Map Layers")}
        if rule:
            sel["rule"] = rule
        ex = georef_json(ref, g, "adobeVP", sel, vp_crop(vp_written), {"fit": fit_stats(ref, A, pairs, crs, datum)})
        ex["outcome"] = "accept"
        ex["checks"] = [check_point(ref, g, "control %d" % (i + 1), x, y, "control") for i, (x, y, _, _) in enumerate(pairs)]
        return ex

    # ---- /GCS of the wrong type
    vp_case("gcs_not_a_dictionary", [dict(vp0, gcs="malformed")], vp_extras_custom(vp0, "5"), rej("malformed"),
            note="/GCS 5. structured: a gcs that isn't an object")
    vp_case("gcs_wkt_not_a_string", [dict(vp0, gcs={"type": "PROJCS", "wkt": 5})],
            vp_extras_custom(vp0, "<< /Type /PROJCS /WKT 5 >>"), rej("malformed"))
    vp_case("gcs_epsg_not_an_integer", [dict(vp0, gcs={"type": "PROJCS", "epsg": 32610.5})],
            vp_extras_custom(vp0, "<< /Type /PROJCS /EPSG 32610.5 >>"), rej("malformed"),
            note="EPSG has to be a PDF integer object")

    # ---- PRIMEM
    wkt_pm = wkt10.replace('PRIMEM["Greenwich",0]', 'PRIMEM["Greenwich",200]')
    vp_case("primem_out_of_range", [dict(vp0, gcs={"type": "PROJCS", "wkt": wkt_pm})], vp_pdf_extras(vp0, wkt_pm),
            rej("malformed"))
    geog_pm = ('GEOGCS["WGS 84 (made up meridian)",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],'
               'PRIMEM["Made_up",-60],UNIT["degree",0.0174532925199433]]')
    vp_case("gpts_lon_plus_primem_off_earth", [dict(vp0, gcs={"type": "GEOGCS", "wkt": geog_pm})],
            vp_pdf_extras(vp0, geog_pm, "GEOGCS"), rej("gptsOffEarth"),
            note="GPTS lon is relative to PRIMEM -60, so -122.4 means -182.4: off the earth, not wrapped")
    rel = list(vp0["gpts"])
    for i in range(1, len(rel), 2):
        rel[i] = num(rel[i] + 60.0, DEG_DP)
    vrel = dict(vp0, gpts=rel)
    vgrn = dict(vp0, gpts=[v if i % 2 == 0 else v + (-60.0) for i, v in enumerate(rel)])
    vp_case("gpts_relative_to_primem", [dict(vrel, gcs={"type": "GEOGCS", "wkt": geog_pm})],
            vp_pdf_extras(vrel, geog_pm, "GEOGCS"), vp_accept(vrel, vgrn, {"kind": "geographic"}, "WGS84"),
            note="lon = GPTS lon + PRIMEM, computed in double exactly like that")

    # ---- /Bounds
    vb = dict(vp0, bounds=[0.0, 0.0, 0.0, 1.0, 1.0])
    vp_case("bounds_odd_count", [dict(vb, gcs=gcs10)], vp_pdf_extras(vb, wkt10), rej("malformed"),
            note="a broken /Bounds rejects the viewport, it's never quietly swapped for the BBox")
    vnf = dict(vp0, bounds=["1" + "0" * 400 + ".0", 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0])
    vp_case("bounds_non_finite", [dict(vp0, bounds=["Infinity", 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0], gcs=gcs10)],
            vp_pdf_extras(vnf, wkt10), rej("nonFinite"))

    # ---- selection index counts GEO viewports only
    non_geo = ("<< /Type /Viewport /Name (Scale bar) /BBox [0 0 100 20] /Measure << /Type /Measure /Subtype /RL "
               "/R (1 in = 2000 ft) >> >> ")
    vp_case("non_geo_viewport_first", [dict(vp0, gcs=gcs10)],
            vp_pdf_extras(vp0, wkt10).replace("/VP [", "/VP [" + non_geo, 1),
            vp_accept(vp0, vp0, crs10, "WGS84", rule="index counts GEO viewports only"),
            note="the pdf has a non-GEO (/Subtype /RL) viewport first; it isn't a candidate and doesn't count")

    # ---- the projection can't forward the GPTS
    wkt40 = wkt_utm_simple(40, False)
    vp_case("gpts_projection_forward_fails", [dict(vp0, gcs={"type": "PROJCS", "wkt": wkt40})],
            vp_pdf_extras(vp0, wkt40), rej("gptsOffEarth"),
            note="UTM 40N has its CM ~179 deg from the sheet; TM forward is undefined past 90 deg from the CM")

    # ---- LGIDict
    ctm, nl, regs, proj, crop, A_ctm = (lgi[k] for k in ("ctm", "nl", "regs", "proj", "crop", "A_ctm"))

    def lgi_raw(cid, entries, extras, expected, note=None):
        c = {"id": cid, "kind": "lgiDict", "mediaBox": media, "input": {"entries": entries}, "pdfPageExtras": extras,
             "expected": expected}
        if note:
            c["note"] = note
        out.append(c)

    def lgi_accept(A, source, crs, datum, stats=None, assumed=None, checks=None):
        g = {"crs": crs, "datum": datum, "affine": A}
        ex = georef_json(ref, g, "lgiDict", {"kind": "lgiEntry", "index": 0, "description": "Layers", "source": source},
                         crop, {"fit": stats} if stats else None)
        ex["outcome"] = "accept"
        if assumed is not None:
            ex["datumAssumed"] = assumed
        ex["checks"] = checks or [check_point(ref, g, "neatline %d" % (i + 1), x, y, "neatline")
                                  for i, (x, y) in enumerate(crop)]
        return ex

    layers = {"description": "Layers", "ctm": ctm, "neatline": nl, "projection": proj}
    lgi_raw("lgi_empty_array", [], "/LGIDict []", rej("malformed"), note="declared but empty is a loud failure")
    lgi_raw("lgi_wrong_type", "malformed", "/LGIDict 5", rej("malformed"),
            note="structured: entries that isn't an array")
    lgi_raw("lgi_non_dictionary_member_skipped", [layers],
            lgi_pdf_extras([layers]).replace("/LGIDict [", "/LGIDict [5 (junk) ", 1),
            lgi_expect(A_ctm, crop, "ctm", 0, "Layers"),
            note="non-dictionary members are skipped, the index counts dictionaries only")
    lgi_case("lgi_no_projection", [{"description": "Layers", "ctm": ctm, "neatline": nl}], rej("malformed"),
             note="no /Projection is malformed, even when the numbers look like lon/lat")
    lgi_case("lgi_registration_row_wrong_length",
             [{"description": "Layers", "registration": [list(regs[0]) + [0.0, 0.0]] + regs[1:], "neatline": nl,
               "projection": proj}], rej("malformed"))
    ctm_off = list(ctm)
    ctm_off[4] = num(ctm_off[4] + 1e8, DEG_DP)
    lgi_case("lgi_ctm_off_planet", [dict(layers, ctm=ctm_off)], rej("gptsOffEarth"),
             note="the CTM throws the whole neatline 100,000 km east, nothing to place")

    # Units (GDAL): converts FalseEasting/FalseNorthing only, CTM + Registration stay metres
    def tc(fe_written, units):
        return {"ProjectionType": "TC", "OriginLatitude": 0.0, "CentralMeridian": -123.0, "ScaleFactor": 0.9996,
                "FalseEasting": fe_written, "FalseNorthing": 0.0, "Units": units, "Datum": "WE"}
    ft, usft = 0.3048, 1200.0 / 3937.0
    fe_ft = num(500000.0 / ft, DEG_DP)
    crs_ft = TM(0.0, -123.0, 0.9996, fe_ft * ft, 0.0)
    lgi_case("lgi_units_ft_tc", [dict(layers, projection=tc(fe_ft, "FT"))],
             lgi_accept(A_ctm, "ctm", crs_ft, "WGS84"),
             note="FalseEasting is feet (x 0.3048), the CTM is metres untouched")
    fe_us = num(500000.0 / usft, DEG_DP)
    crs_us = TM(0.0, -123.0, 0.9996, fe_us * usft, 0.0)
    pairs = [tuple(r) for r in regs]
    A_reg = ref.fit(pairs)
    lgi_case("lgi_units_ussf_tc_registration",
             [{"description": "Layers", "registration": regs, "neatline": nl, "projection": tc(fe_us, "USSF")}],
             lgi_accept(A_reg, "registration", crs_us, "WGS84", fit_stats(ref, A_reg, pairs, crs_us, "WGS84")),
             note="US survey feet (x 1200/3937) on FalseEasting only, Registration plane values stay metres")
    lgi_case("lgi_units_unknown", [dict(layers, projection=tc(fe_ft, "YD"))], rej("unsupportedProjection"))
    # check order: ProjectionType, then Datum, then Units, then the parameters
    lgi_case("lgi_unknown_type_and_datum",
             [dict(layers, projection={"ProjectionType": "XX", "Datum": "ZZZ"})], rej("unsupportedProjection"),
             note="an unknown ProjectionType is reported before the datum is looked at")
    lgi_case("lgi_units_ft_on_utm_unknown_datum", [dict(layers, projection=dict(proj, Units="FT", Datum="ZZZ"))],
             rej("unknownDatum"), note="the datum is checked before Units")
    # a Registration too short (or collinear) to fit sits fine next to a CTM, the CTM is used alone
    lgi_case("lgi_short_registration_beside_ctm", [dict(layers, registration=regs[:2])],
             lgi_expect(A_ctm, crop, "ctm", 0, "Layers"),
             note="2 Registration rows can't be fitted or cross-checked: CTM alone (without a CTM it's degenerateViewport)")
    lgi_case("lgi_short_registration_is_the_crop",
             [{"description": "Layers", "ctm": ctm, "registration": regs[:2], "projection": proj}], rej("malformed"),
             note="no Neatline, so the Registration page points are the crop and 2 aren't a polygon")
    lgi_case("lgi_units_ft_on_utm", [dict(layers, projection=dict(proj, Units="FT"))], rej("unsupportedProjection"),
             note="a UTM zone's false origin is fixed in metres, a foot Units on UT can't mean anything we'd trust")

    # GEO is geographic; Units ignored for geographic
    ll = []
    for x, y in crop:
        la, lo = sf.latlon(x, y)
        ll.append((x, y, lo, la))
    A_ll = ref.fit(ll)
    ctm_ll = [num(v, DEG_DP) for v in (A_ll[0], A_ll[3], A_ll[1], A_ll[4], A_ll[2], A_ll[5])]
    A_llw = [ctm_ll[0], ctm_ll[2], ctm_ll[4], ctm_ll[1], ctm_ll[3], ctm_ll[5]]
    lgi_case("lgi_projection_type_geo",
             [{"description": "Layers", "ctm": ctm_ll, "neatline": nl,
               "projection": {"ProjectionType": "GEO", "Datum": "WE", "Units": "FT"}}],
             lgi_accept(A_llw, "ctm", {"kind": "geographic"}, "WGS84"),
             note="GEO = geographic (like GEOGRAPHIC/LL/LONGLAT), Units ignored for geographic")

    # geographic sheet straddling the antimeridian: lon comes back wrapped, toPage stays on the sheet's branch
    xs, ys = [p[0] for p in crop], [p[1] for p in crop]
    cx, cy = (min(xs) + max(xs)) / 2.0, (min(ys) + max(ys)) / 2.0
    k = 0.6 / (max(xs) - min(xs))
    ctm_am = [num(v, DEG_DP) for v in (k, 0.0, 0.0, k, 180.0 - cx * k, -17.5 - cy * k)]
    A_am = [ctm_am[0], ctm_am[2], ctm_am[4], ctm_am[1], ctm_am[3], ctm_am[5]]
    centre_x = ref.apply(A_am, sum(xs) / len(xs), sum(ys) / len(ys))[0]

    def am_check(label, x, y):
        x, y = rpt(x), rpt(y)
        X, Y = ref.apply(A_am, x, y)
        lat, lon = rdeg(Y), rdeg(wrap_lon(X))
        lb = lon
        while lb - centre_x > 180.0:
            lb -= 360.0
        while lb - centre_x < -180.0:
            lb += 360.0
        px, py = ref.unapply(A_am, lb, lat)
        return {"label": label, "kind": "neatline", "page": [x, y], "plane": [rsig(X), rsig(Y)], "wgs84": [lat, lon],
                "toPage": [rpt(px), rpt(py)]}
    am_checks = [am_check("neatline %d" % (i + 1), x, y) for i, (x, y) in enumerate(crop)]
    am_checks.append(am_check("east of 180", min(xs) + 0.75 * (max(xs) - min(xs)), cy + 10.0))
    lgi_case("lgi_geographic_antimeridian",
             [{"description": "Layers", "ctm": ctm_am, "neatline": nl,
               "projection": {"ProjectionType": "GEOGRAPHIC", "Datum": "WE"}}],
             lgi_accept(A_am, "ctm", {"kind": "geographic"}, "WGS84", checks=am_checks),
             note="plane lon runs 179.7..180.3: toWGS84 wraps into [-180, 180], toPage unwraps onto the branch of "
                  "the crop centroid (vertex average) so the east half lands back on the page")

    # inline datums
    wgs_ell = {"SemiMajorAxis": 6378137.0, "InvFlattening": 298.257223563}
    lgi_case("lgi_inline_datum_modern_assumed", [dict(layers, projection=dict(proj, Datum={"Ellipsoid": wgs_ell}))],
             lgi_accept(A_ctm, "ctm", crs10, {"a": 6378137.0, "invF": 298.257223563, "transform": "translation3",
                                              "dx": 0.0, "dy": 0.0, "dz": 0.0}, assumed=True),
             note="no ToWGS84 on a WGS84-sized ellipsoid: zero shift, flagged datumAssumed (same rule as WKT)")
    lgi_case("lgi_inline_datum_broken",
             [dict(layers, projection=dict(proj, Datum={"Ellipsoid": {"SemiMajorAxis": 6378137.0}}))], rej("malformed"))
    lgi_case("lgi_inline_towgs84_broken",
             [dict(layers, projection=dict(proj, Datum={
                 "Ellipsoid": {"SemiMajorAxis": 6378206.4, "InvFlattening": 294.978698213898},
                 "ToWGS84": {"dx": -8.0, "dy": 160.0}}))], rej("malformed"))

    # ---- value types on /Projection (parity round 3). A /Zone that's there has to be a pdf number or a
    # numeric string holding a whole 1..60, checked as a double before any int conversion (a huge zone used
    # to trap iOS). Anything else is malformed and does NOT fall back to /Display, only a missing key does.
    # Same for /Hemisphere (name or string N/S/NORTH/SOUTH). Names are never numbers
    lgi_case("lgi_zone_huge_string", [dict(layers, projection=dict(proj, Zone="1e30"))], rej("malformed"),
             note="(1e30) parses to a finite double way past 60")
    lgi_case("lgi_zone_infinite_string", [dict(layers, projection=dict(proj, Zone="inf"))], rej("malformed"),
             note="(inf): Swift Double() reads infinity, Kotlin toDoubleOrNull reads nothing. malformed either way")
    huge = dict(layers, projection=dict(proj, Zone=1e30))
    lgi_raw("lgi_zone_huge_real", [huge],
            lgi_pdf_extras([huge]).replace("/Zone %s " % dec(1e30, DEG_DP), "/Zone %s.0 " % dec(1e30, DEG_DP), 1),
            rej("malformed"), note="a pdf real of 1e30, finite on both readers")
    zname = dict(layers, projection=dict(proj, Zone="ten"))
    lgi_raw("lgi_zone_name", [zname], lgi_pdf_extras([zname]).replace("/Zone (ten)", "/Zone /10", 1),
            rej("malformed"), note="/Zone /10 is a name, not a number. structured: a non-numeric string stands in")
    disp = {"Zone": 10.0, "Hemisphere": "N"}
    lgi_case("lgi_zone_junk_no_display_fallback",
             [dict(layers, projection=dict(proj, Zone="ten"), display=disp)], rej("malformed"),
             note="/Zone (ten) is present, so /Display /Zone 10 is never looked at")
    hnum = dict(layers, projection=dict(proj, Hemisphere="X"), display=disp)
    lgi_raw("lgi_hemisphere_number_no_display_fallback",
            [dict(hnum, projection=dict(proj, Hemisphere=1.0))],
            lgi_pdf_extras([hnum]).replace("/Hemisphere (X)", "/Hemisphere 1", 1), rej("malformed"),
            note="/Hemisphere 1 is present but not text, /Display /Hemisphere (N) is never looked at")
    ptn = dict(layers, projection=dict(proj, ProjectionType="X"))
    lgi_raw("lgi_projection_type_number", [dict(ptn, projection=dict(proj, ProjectionType={"number": 5}))],
            lgi_pdf_extras([ptn]).replace("/ProjectionType (X)", "/ProjectionType 5", 1), rej("malformed"),
            note="a ProjectionType that isn't a name or string is a wrong type (malformed), not an unknown "
                 "projection. structured: an object stands in for the pdf integer")


# ----------------------------------------------------------------------------
# parity round 4 (WP1 final review): null values, number vs string, page budget
# ----------------------------------------------------------------------------

class PdfRaw(str):
    """a pdf token written as is (null, a name, a real), only the round 4 cases use it"""


PDF_NULL = PdfRaw("null")
NULL_STANDIN = {"pdfNull": True}
NUMERIC_STRING = re.compile(r"[+-]?([0-9]+(\.[0-9]*)?|\.[0-9]+)([eE][+-]?[0-9]+)?")


def numeric_string_value(s):
    """lgiRules.numericStrings: None when it's not a number string at all (malformed)"""
    t = "".join(" " if ord(c) in WHITE_SPACE else c for c in s).strip(" ")
    if not NUMERIC_STRING.fullmatch(t):
        return None
    return float(t)


def _raw_item(v, nd):
    if isinstance(v, PdfRaw):
        return str(v)
    if isinstance(v, str):
        return "(%s)" % v
    return dec(v, nd)


def _raw_value(v):
    if isinstance(v, PdfRaw):
        return str(v)
    if isinstance(v, str):
        return "(%s)" % v
    if isinstance(v, dict):
        return "<< %s >>" % " ".join("/%s %s" % (k, _raw_value(x)) for k, x in v.items())
    return dec(v, DEG_DP)


def lgi_pdf_extras_raw(entries):
    """lgi_pdf_extras plus raw tokens and string array items, same layout otherwise"""
    parts = []
    for e in entries:
        s = "<< /Type /LGIDict /Version (2.1) /Description (%s)" % e["description"]
        for key, pkey, nd in (("ctm", "CTM", DEG_DP), ("registration", "Registration", M_DP),
                              ("neatline", "Neatline", PT_DP)):
            if key not in e:
                continue
            v = e[key]
            if isinstance(v, PdfRaw):
                s += " /%s %s" % (pkey, v)
            elif key == "registration":
                s += " /Registration [%s]" % " ".join("[%s]" % " ".join(_raw_item(x, nd) for x in r) for r in v)
            else:
                s += " /%s [%s]" % (pkey, " ".join(_raw_item(x, nd) for x in v))
        for key, pkey in (("projection", "Projection"), ("display", "Display")):
            if key not in e:
                continue
            v = e[key]
            if isinstance(v, PdfRaw):
                s += " /%s %s" % (pkey, v)
                continue
            body = " ".join("/%s %s" % (k, _raw_value(x)) for k, x in v.items())
            s += (" /Projection << /Type /Projection %s >>" % body) if key == "projection" else (" /Display << %s >>" % body)
        parts.append(s + " >>")
    return "/LGIDict [%s]" % " ".join(parts)


def lgi_value_cases(ref, sf, base, wkt10, crs10, lgi_expect, lgi):
    """
    The LGIDict/VP value types the WP1 final review saw the two readers disagree on, one outcome each,
    fail closed when it could go either way. pdf = what goes in the page, structured = the parsed stand-in
    """
    media = [rpt(v) for v in sf.media]
    ctm, nl, regs, proj, crop, A_ctm = (lgi[k] for k in ("ctm", "nl", "regs", "proj", "crop", "A_ctm"))
    rej = lambda reason: {"outcome": "reject", "reason": reason}
    ok_ctm = lambda: lgi_expect(A_ctm, crop, "ctm", 0, "Layers")
    layers = {"description": "Layers", "ctm": ctm, "neatline": nl, "projection": proj}
    disp = {"Zone": 10.0, "Hemisphere": "N"}
    out = []

    def case(cid, structured, pdf, expected, note):
        out.append({"id": cid, "kind": "lgiDict", "mediaBox": media, "input": {"entries": structured},
                    "pdfPageExtras": pdf if isinstance(pdf, str) else lgi_pdf_extras_raw(pdf),
                    "expected": expected, "note": note})

    def proj_case(cid, key, structured_value, pdf_value, expected, note, display=None, base_proj=None):
        bp = dict(base_proj or proj)
        s_entry = dict(layers, projection=dict(bp, **{key: structured_value}))
        p_entry = dict(layers, projection=dict(bp, **{key: pdf_value}))
        if display is not None:
            s_entry["display"] = display
            p_entry["display"] = display
        case(cid, [s_entry], [p_entry], expected, note)

    # ---- the pdf null object: present, wrong type, never "missing"
    proj_case("lgi_zone_null_no_display_fallback", "Zone", NULL_STANDIN, PDF_NULL, rej("malformed"),
              "/Zone null next to /Display /Zone 10. iOS read it as present junk, Android (PDFBox hides null) "
              "fell back to /Display. Fail closed: lgiRules.nullValues", display=disp)
    proj_case("lgi_hemisphere_null_no_display_fallback", "Hemisphere", NULL_STANDIN, PDF_NULL, rej("malformed"),
              "/Hemisphere null next to /Display /Hemisphere (N), same rule", display=disp)
    proj_case("lgi_datum_null", "Datum", NULL_STANDIN, PDF_NULL, rej("malformed"),
              "/Datum null is a datum of the wrong type (malformed), not a missing datum (unknownDatum)")
    tc = {"ProjectionType": "TC", "OriginLatitude": 0.0, "CentralMeridian": -123.0, "ScaleFactor": 0.9996,
          "FalseEasting": 500000.0, "FalseNorthing": 0.0, "Datum": "WE"}
    proj_case("lgi_units_null", "Units", NULL_STANDIN, PDF_NULL, rej("unsupportedProjection"),
              "/Units null is a Units we can't use (unsupportedProjection), not metres by default", base_proj=tc)
    le = {"ProjectionType": "LE", "StandardParallelOne": 33.0, "OriginLatitude": 37.5, "CentralMeridian": -122.6,
          "FalseEasting": 0.0, "FalseNorthing": 0.0, "Datum": "WE"}
    proj_case("lgi_sp2_null_no_default", "StandardParallelTwo", NULL_STANDIN, PDF_NULL, rej("malformed"),
              "a null StandardParallelTwo is present junk, it doesn't default to StandardParallelOne. Both readers "
              "used to default it", base_proj=le)
    proj_case("lgi_sp2_junk_no_default", "StandardParallelTwo", "abc", "abc", rej("malformed"),
              "(abc) isn't a number string, same as a junk /Zone: malformed, no default. Both readers used to "
              "default it", base_proj=le)
    case("lgi_neatline_null", [dict(layers, neatline=NULL_STANDIN)], [dict(layers, neatline=PDF_NULL)],
         rej("malformed"), "/Neatline null is malformed, not a page-box crop")
    case("lgi_ctm_null_beside_registration",
         [{"description": "Layers", "ctm": NULL_STANDIN, "registration": regs, "neatline": nl, "projection": proj}],
         [{"description": "Layers", "ctm": PDF_NULL, "registration": regs, "neatline": nl, "projection": proj}],
         rej("malformed"), "/CTM null is malformed even though the Registration alone would fit")
    case("lgi_registration_null_beside_ctm", [dict(layers, registration=NULL_STANDIN)],
         [dict(layers, registration=PDF_NULL)], rej("malformed"),
         "/Registration null is malformed, the CTM isn't used on its own. structured: an object where the rows go")
    case("lgi_projection_null", [dict(layers, projection=NULL_STANDIN)], [dict(layers, projection=PDF_NULL)],
         rej("malformed"), "/Projection null is the same as no usable /Projection")
    case("lgi_display_null_not_needed", [dict(layers, display=NULL_STANDIN)], [dict(layers, display=PDF_NULL)],
         ok_ctm(), "/Projection has Zone and Hemisphere so /Display (null here) is never looked at")
    nozone = {k: v for k, v in proj.items() if k != "Zone"}
    case("lgi_display_null_needed", [dict(layers, projection=nozone, display=NULL_STANDIN)],
         [dict(layers, projection=nozone, display=PDF_NULL)], rej("malformed"),
         "/Projection has no Zone and /Display is null, nowhere to read a zone from")
    out.append({"id": "lgidict_null", "kind": "lgiDict", "mediaBox": media, "input": {"entries": NULL_STANDIN},
                "pdfPageExtras": "/LGIDict null", "expected": rej("malformed"),
                "note": "/LGIDict null is declared-but-junk (malformed), not a plain PDF. structured: entries that "
                        "isn't an array, as for lgi_wrong_type"})
    vp0 = {k: v for k, v in base.items() if k != "gcs"}
    out.append({"id": "vp_gcs_null", "kind": "adobeVP", "mediaBox": media,
                "input": {"viewports": [dict(vp0, gcs="malformed")]},
                "pdfPageExtras": vp_extras_custom(vp0, "null"), "expected": rej("malformed"),
                "note": "/GCS null is a /GCS that isn't a dictionary (malformed), never the local TM fallback a "
                        "missing /GCS gets. structured: the gcs_not_a_dictionary stand-in"})
    vb = dict(vp0, bounds=["B"])
    out.append({"id": "vp_bounds_null", "kind": "adobeVP", "mediaBox": media,
                "input": {"viewports": [dict(vp0, bounds=NULL_STANDIN, gcs={"type": "PROJCS", "wkt": wkt10})]},
                "pdfPageExtras": vp_pdf_extras(vb, wkt10).replace("/Bounds [B]", "/Bounds null", 1),
                "expected": rej("malformed"), "note": "/Bounds null is malformed, the BBox isn't used instead"})

    # ---- number vs string: the numeric string grammar (lgiRules.numericStrings)
    for cid, text, outcome, note in (
            ("lgi_zone_string_type_suffix_d", "10d", "malformed", "(10d): Kotlin toDouble reads a Java type suffix"),
            ("lgi_zone_string_float_suffix_f", "10f", "malformed", "(10f): same, f suffix"),
            ("lgi_zone_string_hex", "0x1.4p3", "malformed", "(0x1.4p3) is 10 as a hex float on both readers, "
                                                            "not a number string here"),
            ("lgi_zone_string_nan", "NaN", "malformed", "(NaN) isn't in the grammar"),
            ("lgi_zone_string_comma_decimal", "10,0", "malformed", "(10,0): no decimal comma in pdf number strings"),
            ("lgi_zone_string_padded", " 10 ", "accept", "( 10 ): trimmed, then 10"),
            ("lgi_zone_string_plus", "+10", "accept", "(+10): a leading + is fine, pdf numbers allow it too"),
            ("lgi_zone_string_whole_real", "10.0", "accept", "(10.0) is a whole number"),
            ("lgi_zone_string_exponent", "1e1", "accept", "(1e1): exponents are in the string grammar (pdf "
                                                         "numbers don't have them, Java/C producers write them)")):
        assert (numeric_string_value(text) is not None) == (outcome == "accept"), cid
        proj_case(cid, "Zone", text, text, ok_ctm() if outcome == "accept" else rej(outcome), note)
    proj_case("lgi_hemisphere_lowercase_padded", "Hemisphere", " n ", " n ", ok_ctm(),
              "( n ): text values are trimmed and case-insensitive")
    proj_case("lgi_projection_type_lowercase_padded", "ProjectionType", " ut ", " ut ", ok_ctm(), "( ut ) is UT")
    proj_case("lgi_datum_code_padded", "Datum", " WE ", " WE ", ok_ctm(), "( WE ) is WE")
    proj_case("lgi_datum_real", "Datum", {"pdfReal": 5.0}, PdfRaw("5.0"), rej("malformed"),
              "/Datum 5.0: a real isn't a datum code (only a pdf integer reads as its digits). iOS used to read it "
              "as code 5 (unknownDatum)")
    proj_case("lgi_datum_integer_large", "Datum", 99999999999, PdfRaw("99999999999"), rej("unknownDatum"),
              "/Datum 99999999999: an integer reads as its digits whatever its size, no such code. iOS used to cap "
              "it at 1e9 and call it malformed")
    tc_cm = dict(tc, CentralMeridian="-123d")
    case("lgi_tc_parameter_string_suffix", [dict(layers, projection=tc_cm)], [dict(layers, projection=tc_cm)],
         rej("malformed"), "TC /CentralMeridian (-123d) isn't a number string")

    # array elements as strings: plain number strings are fine (ADF/AUSLIG write them), the rest is junk
    reg_s = [[dec(v, PT_DP if i < 2 else M_DP) for i, v in enumerate(r)] for r in regs]
    pairs = [tuple(r) for r in regs]
    A_reg = ref.fit(pairs)
    reg_entry = {"description": "Layers", "registration": reg_s, "neatline": nl, "projection": proj}
    assert all(numeric_string_value(x) == v for r, rs in zip(regs, reg_s) for x, v in zip(rs, r))
    case("lgi_registration_number_strings", [reg_entry], [reg_entry],
         lgi_expect(A_reg, crop, "registration", 0, "Layers", fit_stats(ref, A_reg, pairs, crs10, "WGS84")),
         "every Registration value is a number string like (546000): read as the numbers")
    reg_d = [[x + "d" for x in r] for r in reg_s]
    bad_reg = dict(reg_entry, registration=reg_d)
    case("lgi_registration_string_type_suffix", [bad_reg], [bad_reg], rej("malformed"),
         "Registration values (546000d): Kotlin read them as numbers, Swift didn't. Not number strings: malformed")
    hexed = [float(ctm[0]).hex()] + ctm[1:]
    assert float.fromhex(hexed[0]) == ctm[0]
    case("lgi_ctm_string_hex", [dict(layers, ctm=hexed)], [dict(layers, ctm=hexed)], rej("malformed"),
         "a /CTM element written as the exact hex float of the right value: both readers took it, the grammar "
         "doesn't")
    expo = ["%.12e" % ctm[0]] + ctm[1:]
    assert numeric_string_value(expo[0]) == ctm[0]
    case("lgi_ctm_string_exponent", [dict(layers, ctm=expo)], [dict(layers, ctm=expo)], ok_ctm(),
         "a /CTM element as (8.819444444496e+00): in the grammar, same number")
    return out


def write_pdf_objects(path, media, page_extras, catalog_extras="", objects=()):
    """one page, objects 1-4 catalog/pages/page/content then the extra objects as 5, 6, ... (same layout as the
    platform hostile-pdf writers)"""
    content = b"q 1 1 1 rg 0 0 10 10 re f Q"
    cat = "<< /Type /Catalog /Pages 2 0 R%s >>" % ((" " + catalog_extras) if catalog_extras else "")
    page = ("<< /Type /Page /Parent 2 0 R /MediaBox [%s] /Resources << >> /Contents 4 0 R %s >>"
            % (nums(media, 3), page_extras))
    objs = [cat.encode("latin-1"), b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>", page.encode("latin-1"),
            b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream"]
    objs += [o.encode("latin-1") for o in objects]
    buf = bytearray(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offs = []
    for i, o in enumerate(objs):
        offs.append(len(buf))
        buf += b"%d 0 obj\n" % (i + 1) + o + b"\nendobj\n"
    x = len(buf)
    buf += b"xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)
    for o in offs:
        buf += b"%010d 00000 n \n" % o
    buf += b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, x)
    with open(path, "wb") as fh:
        fh.write(bytes(buf))
    return hashlib.sha256(bytes(buf)).hexdigest(), len(buf)


PAGE_BUDGET = 65536
CONTROL_CAP = 8192


def page_budget_cases(ref, pdfdir, sf, base, wkt10, crs10, lgi_expect, lgi):
    """lgiRules.pageBudget as whole PDFs. Every case says how many numbers it charges"""
    media = [rpt(v) for v in sf.media]
    ctm, nl, proj, crop, A_ctm = (lgi[k] for k in ("ctm", "nl", "proj", "crop", "A_ctm"))
    layers = {"description": "Layers", "ctm": ctm, "neatline": nl, "projection": proj}
    layers_pdf = lgi_pdf_extras([layers])[len("/LGIDict ["):-1]
    proj_pdf = "/Projection << /Type /Projection /ProjectionType (UT) /Zone 10 /Hemisphere (N) /Datum (WE) >>"
    inset = lambda ref_: "<< /Type /LGIDict /Version (2.1) /Description (Inset) /Neatline %s %s >>" % (ref_, proj_pdf)
    zeros = lambda n: "[%s]" % " ".join(["0"] * n)
    vp0 = {k: v for k, v in base.items() if k != "gcs"}
    good_vp = vp_pdf_extras(vp0, wkt10)[len("/VP ["):-1]
    junk_vp = ("<< /Type /Viewport /BBox [0 0 10 10] /Measure << /Type /Measure /Subtype /GEO /LPTS 5 0 R "
               "/GPTS 5 0 R /GCS << /Type /PROJCS /EPSG 32610 >> >> >>")
    rej = lambda reason: {"outcome": "reject", "reason": reason}
    out = []

    def vp_georef(vp):
        A, pairs = fit_vp(ref, vp, crs10, "WGS84")
        g = {"crs": crs10, "datum": "WGS84", "affine": A}
        ex = georef_json(ref, g, "adobeVP", {"kind": "viewport", "index": 0, "name": "Map Layers"}, vp_crop(vp),
                         {"fit": fit_stats(ref, A, pairs, crs10, "WGS84")})
        ex["outcome"] = "accept"
        ex["checks"] = [check_point(ref, g, "control %d" % (i + 1), x, y, "control")
                        for i, (x, y, _, _) in enumerate(pairs)]
        return ex

    def add(cid, page_extras, objects, charged, expected, note, catalog_extras="", spec=None):
        fname = "tacmap_budget_%s.pdf" % cid
        sha, size = write_pdf_objects(os.path.join(pdfdir, fname), sf.media, page_extras, catalog_extras,
                                      [o for o, _ in objects])
        c = {"id": cid, "kind": "pageBudget", "file": "geopdf/" + fname, "sha256": sha, "bytes": size,
             "mediaBox": media, "charged": charged, "pdfObjects": [dict(s, object=5 + i) for i, (_, s) in
                                                                    enumerate(objects)],
             "expected": expected, "note": note}
        print("wrote %-34s %6d bytes" % (fname, size))
        out.append(c)

    shared = lambda n: (zeros(n), {"numberArray": {"count": n, "value": "0"}})
    # numbers the good pieces cost
    vp_cost = 4 + len(vp0["lpts"]) + len(vp0["gpts"]) + (len(vp0["bounds"]) if vp0.get("bounds") else 0)
    layers_cost = 6 + 8

    add("vp_runs_out_lgidict_too",
        "/VP [%s %s] /LGIDict [%s]" % (good_vp, " ".join([junk_vp] * 8), layers_pdf),
        [shared(CONTROL_CAP)], "good viewport %d, then 4 + 2 x 8192 per junk viewport: runs out on the 4th" % vp_cost,
        rej("malformed"),
        "out of budget inside the /VP: the whole /VP is malformed (the good first viewport included) and the "
        "perfectly good LGIDict after it isn't read")
    add("lgidict_runs_out_after_layers",
        "/LGIDict [%s %s]" % (layers_pdf, " ".join([inset("5 0 R")] * 9)),
        [shared(CONTROL_CAP)], "Layers %d, then 8192 per inset: runs out on the 8th" % layers_cost, rej("malformed"),
        "the Layers entry was read fine and would be picked, but the LGIDict ran out: malformed")
    add("vp_read_in_full_then_lgidict_runs_out",
        "/VP [%s] /LGIDict [%s]" % (good_vp, " ".join([inset("5 0 R")] * 9)),
        [shared(CONTROL_CAP)], "viewport %d, then 8192 per inset: runs out on the 8th" % vp_cost, vp_georef(vp0),
        "the /VP was read in full before the budget ran out, it still stands")
    gr = list(base["gpts"])
    gr[4] = num(gr[4] + 0.004, DEG_DP)
    rvp = dict(vp0, gpts=gr)
    A, pairs = fit_vp(ref, rvp, crs10, "WGS84")
    st = fit_stats(ref, A, pairs, crs10, "WGS84")
    assert not st["passesGate"]
    add("vp_rejected_then_lgidict_runs_out",
        "%s /LGIDict [%s]" % (vp_pdf_extras(rvp, wkt10), " ".join([inset("5 0 R")] * 9)),
        [shared(CONTROL_CAP)], "viewport %d, then 8192 per inset: runs out on the 8th" % vp_cost,
        {"outcome": "reject", "reason": "rmsGate", "fit": st},
        "the /VP (rejections.cases rms_gate) was read in full and rejected; the LGIDict then runs out. The first "
        "rejection is the one reported: rmsGate, not malformed")
    last = PAGE_BUDGET - layers_cost - 7 * CONTROL_CAP
    for cid, n, expected, note in (
            ("exactly_at_limit", last, None, "Layers + 7 x 8192 + %d = 65536 exactly: nothing ran out" % last),
            ("one_over_limit", last + 1, rej("malformed"), "Layers + 7 x 8192 + %d = 65537: runs out on the last "
                                                           "inset" % (last + 1))):
        add(cid, "/LGIDict [%s %s %s]" % (layers_pdf, " ".join([inset("5 0 R")] * 7), inset("6 0 R")),
            [shared(CONTROL_CAP), shared(n)], "%d + 7 x 8192 + %d = %d" % (layers_cost, n,
                                                                         layers_cost + 7 * CONTROL_CAP + n),
            expected or lgi_expect(A_ctm, crop, "ctm", 0, "Layers"), note)
    big = ("<< /Type /Viewport /Name (Map Layers) /BBox [0 0 10 10] /Measure << /Type /Measure /Subtype /GEO "
           "/LPTS [0 0 1 0 1 1 0 1] /GPTS 5 0 R /GCS << /Type /PROJCS /EPSG 32610 >> >> >>")
    add("oversized_array_not_charged",
        "/VP [%s] /LGIDict [%s %s]" % (big, layers_pdf, " ".join([inset("6 0 R")] * 7)),
        [shared(CONTROL_CAP + 1), shared(CONTROL_CAP)],
        "viewport 4 + 8 (its 8193 GPTS is over the cap: malformed, not charged), Layers %d, 7 x 8192 = %d. "
        "Charging the 8193 would make it %d and run out" % (layers_cost, 12 + layers_cost + 7 * CONTROL_CAP,
                                                           12 + layers_cost + 7 * CONTROL_CAP + CONTROL_CAP + 1),
        lgi_expect(A_ctm, crop, "ctm", 0, "Layers"),
        "the viewport is malformed (GPTS over the 8192 cap) without touching the budget, so the LGIDict reads "
        "fine and lands the sheet")
    rl = ("<< /Type /Viewport /Name (Scale bar) /BBox 5 0 R /Measure << /Type /Measure /Subtype /RL "
          "/R (1 in = 2000 ft) >> >>")
    add("non_geo_viewports_not_charged",
        "/VP [%s] /LGIDict [%s]" % (" ".join([rl] * 9), layers_pdf), [shared(CONTROL_CAP)],
        "9 non-GEO viewports with 8192-number BBoxes aren't read; Layers %d" % layers_cost,
        lgi_expect(A_ctm, crop, "ctm", 0, "Layers"), "only GEO viewports are read (and charged)")
    scale_bar = ("<< /Type /Viewport /Name (Scale bar) /BBox [0 0 100 20] /Measure << /Type /Measure /Subtype /RL "
                 "/R (1 in = 2000 ft) >> >>")
    add("catalog_vp_runs_out",
        "/VP [%s] /LGIDict [%s]" % (scale_bar, layers_pdf), [shared(CONTROL_CAP)],
        "page /VP has no GEO viewport; catalog /VP: 4 + 2 x 8192 per junk viewport, runs out on the 4th",
        rej("malformed"),
        "the catalog /VP is read on the same page budget; running out there takes the page LGIDict down too",
        catalog_extras="/VP [%s]" % " ".join([junk_vp] * 9))
    row = "[%s]" % " ".join(dec(v, M_DP) for v in lgi["regs"][0])
    reg_entry = ("<< /Type /LGIDict /Version (2.1) /Description (Inset) /Registration [%s] %s >>"
                 % (" ".join(["6 0 R"] * 2048), proj_pdf))
    add("registration_rows_charged",
        "/LGIDict [%s %s %s]" % (layers_pdf, " ".join([inset("5 0 R")] * 7), reg_entry),
        [shared(CONTROL_CAP), (row, {"text": row})],
        "Layers %d + 7 x 8192 + 2048 rows x 4 = %d: runs out inside the Registration"
        % (layers_cost, layers_cost + 7 * CONTROL_CAP + 2048 * 4), rej("malformed"),
        "Registration rows are charged 4 each as they're read, after the row length check")
    return out


# ----------------------------------------------------------------------------
# fiduciary fits
# ----------------------------------------------------------------------------

# Unicode White_Space plus the U+001C-001F info separators (Java/Kotlin isWhitespace counts them, so does
# python's str.isspace), spelled out so every parser agrees. ICU and Java disagree on what \s means
WHITE_SPACE = set(range(0x09, 0x0E)) | set(range(0x1C, 0x20)) | {0x20, 0x85, 0xA0, 0x1680} | \
    set(range(0x2000, 0x200B)) | {0x2028, 0x2029, 0x202F, 0x205F, 0x3000}
assert WHITE_SPACE == {c for c in range(0x10000) if chr(c).isspace()}


def normalise_ref_text(s):
    """every White_Space char -> ascii space, then trim spaces. the grammar below is ascii only"""
    return "".join(" " if ord(c) in WHITE_SPACE else c for c in s).strip(" ")


def parse_ref_string(ref, s, ell_datum_id):
    """what the calibration input parser must produce (only for the formats we generate)"""
    t = normalise_ref_text(s)
    m = re.fullmatch(r" *(-?[0-9]+(?:\.[0-9]+)?) *[, ] *(-?[0-9]+(?:\.[0-9]+)?) *", t)
    if m:
        return {"kind": "latlon", "lat": float(m.group(1)), "lon": float(m.group(2))}
    m = re.fullmatch(r"([0-9]{1,2})([C-HJ-NP-X]) +([0-9]+(?:\.[0-9]+)?) +([0-9]+(?:\.[0-9]+)?)", t.upper())
    if m:
        z, band = int(m.group(1)), m.group(2)
        return {"kind": "utm", "zone": z, "band": band, "hemisphere": "N" if band >= "N" else "S",
                "easting": float(m.group(3)), "northing": float(m.group(4))}
    u = t.replace(" ", "").upper()
    m = re.fullmatch(r"([0-9]{1,2})([C-HJ-NP-X])([A-HJ-NP-Z])([A-HJ-NP-V])([0-9]{4}|[0-9]{6}|[0-9]{8}|[0-9]{10})", u)
    if not m:
        raise ValueError("unparseable test input %r" % s)
    z, band, col, row, dig = int(m.group(1)), m.group(2), m.group(3), m.group(4), m.group(5)
    half = len(dig) // 2
    unit = 10 ** (5 - half)        # 4 digits = 1 km, 6 = 100 m, 8 = 10 m, 10 = 1 m
    E = (LETTERS_COL[(z - 1) % 3].index(col) + 1) * 100000 + int(dig[:half]) * unit
    nbase = ((LETTERS_ROW.index(row) - (5 if z % 2 == 0 else 0)) % 20) * 100000 + int(dig[half:]) * unit
    south = band < "N"
    crs = UTM(z, south)
    lo_lat = -80 + 8 * BANDS.index(band)
    hi_lat = lo_lat + (12 if band == "X" else 8)
    N = None
    for k in range(0, 6):
        cand = nbase + k * 2000000
        la = ref.inv(crs, ell_datum_id, E, cand)[0]
        if lo_lat - 1.0 <= la <= hi_lat + 1.0:
            N = cand
            break
    if N is None:
        raise ValueError("no band match for %r" % s)
    return {"kind": "mgrs", "zone": z, "band": band, "hemisphere": "S" if south else "N",
            "easting": float(E), "northing": float(N)}


def fid_fit(ref, name, points, datum, crop_bbox, note=None, extra_check=None):
    """points: [(page_x, page_y, input_string)] -> expected block (plan s1 fiduciaries)"""
    parsed = [parse_ref_string(ref, s, datum) for _, _, s in points]
    p0 = parsed[0]
    if p0["kind"] in ("mgrs", "utm"):
        zone, hemi = p0["zone"], p0["hemisphere"]
    else:
        zone = int(math.floor((p0["lon"] + 180) / 6)) + 1
        hemi = "N" if p0["lat"] >= 0 else "S"
    crs = UTM(zone, hemi == "S")
    plane = []
    for p in parsed:
        if p["kind"] in ("mgrs", "utm") and p["zone"] == zone and p["hemisphere"] == hemi:
            plane.append((p["easting"], p["northing"]))
            continue
        if p["kind"] == "latlon":
            la, lo = p["lat"], p["lon"]
        else:
            la, lo = ref.inv(UTM(p["zone"], p["hemisphere"] == "S"), datum, p["easting"], p["northing"])
        plane.append(ref.fwd(crs, datum, la, lo))
    pts = [(x, y) for x, y, _ in points]
    ratio = ref.eigen_ratio(pts)
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    span = [(max(xs) - min(xs)) / (crop_bbox[2] - crop_bbox[0]), (max(ys) - min(ys)) / (crop_bbox[3] - crop_bbox[1])]
    degenerate = ratio < 0.02
    exp = {"zone": zone, "hemisphere": hemi, "crs": crs, "datum": {"id": datum} if isinstance(datum, str) else datum,
           "planePoints": [[rm(X), rm(Y)] for X, Y in plane],
           "eigenRatio": rsig(ratio), "degenerate": degenerate,
           "spanFraction": [round(span[0], 6), round(span[1], 6)], "spanWarning": min(span) < 0.25,
           "crossValidated": len(points) >= 4}
    if degenerate:
        exp["affine"] = None
        exp["note"] = "refuse the fit: points are (nearly) collinear"
    else:
        pairs = [(x, y, X, Y) for (x, y), (X, Y) in zip(pts, plane)]
        A = ref.fit(pairs)
        res = [math.hypot(*(a - b for a, b in zip(ref.apply(A, x, y), (X, Y)))) for x, y, X, Y in pairs]
        exp["affine"] = [rsig(v) for v in A]
        exp["residualsMetres"] = [rm(r) for r in res]
        exp["rmsMetres"] = rm(math.sqrt(sum(r * r for r in res) / len(res)))
        exp["maxResidualMetres"] = rm(max(res))
        if len(pairs) >= 4:
            loo, loo_rms = [], []
            for i in range(len(pairs)):
                rest = pairs[:i] + pairs[i + 1:]
                if ref.eigen_ratio([(q[0], q[1]) for q in rest]) < 0.02:
                    loo.append(None)            # the others alone can't define a fit
                    loo_rms.append(None)
                    continue
                Ai = ref.fit(rest)
                px, py = ref.apply(Ai, pairs[i][0], pairs[i][1])
                loo.append(math.hypot(px - pairs[i][2], py - pairs[i][3]))
                rr = [math.hypot(*(a - b for a, b in zip(ref.apply(Ai, x, y), (X, Y)))) for x, y, X, Y in rest]
                loo_rms.append(math.sqrt(sum(r * r for r in rr) / len(rr)))
            exp["leaveOneOutMetres"] = [None if v is None else rm(v) for v in loo]
            exp["leaveOneOutRmsMetres"] = [None if v is None else rm(v) for v in loo_rms]
            # WP4 contract rule 6.4: only when the whole fit is worse than tau, and only name a point when
            # dropping exactly that one makes the rest Good. tau off the crop box diagonals through the fit
            cb = crop_bbox
            ends = [Ref.apply(A, x, y) for x, y in ((cb[0], cb[1]), (cb[2], cb[3]), (cb[2], cb[1]), (cb[0], cb[3]))]
            diag = max(math.hypot(ends[0][0] - ends[1][0], ends[0][1] - ends[1][1]),
                       math.hypot(ends[2][0] - ends[3][0], ends[2][1] - ends[3][1]))
            tau = max(10.0, 0.0005 * diag)
            rms = math.sqrt(sum(r * r for r in res) / len(res))
            flagged = []
            if len(pairs) >= 5 and rms > tau:
                good = [i for i in range(len(pairs)) if loo_rms[i] is not None and loo_rms[i] <= tau]
                if len(good) == 1:
                    flagged = good
            exp["diagonalMetres"] = rm(diag)
            exp["toleranceMetres"] = rm(tau)
            exp["flaggedOutliers"] = flagged
        else:
            exp["exactFit"] = True
            exp["message"] = "exact fit - add a 4th point to check accuracy"
        g = {"crs": crs, "datum": datum, "affine": A}
        cx, cy = sum(xs) / len(xs), sum(ys) / len(ys)
        exp["checks"] = [check_point(ref, g, "points centroid", cx, cy, "centroid")]
        if extra_check:
            for lab, x, y in extra_check:
                exp["checks"].append(check_point(ref, g, lab, x, y, "extra"))
    out = {"name": name, "datum": datum if isinstance(datum, str) else "custom", "cropBBox": [rpt(v) for v in crop_bbox],
           "points": [{"page": [rpt(x), rpt(y)], "input": s, "parsed": pr} for (x, y, s), pr in zip(points, parsed)],
           "expected": exp}
    if note:
        out["note"] = note
    return out


def fiducial_section(ref, sheets):
    out = []
    r5 = sheets["rot5"]
    f = r5.fiducials()
    lat_of = lambda X, Y: r5.plane.inv(X, Y)[0]
    ins = [mgrs(10, False, f[0]["X"], f[0]["Y"], lat_of(f[0]["X"], f[0]["Y"]), spaced=False),
           "10S EG %05d %05d" % (f[1]["X"] % 100000, f[1]["Y"] % 100000),
           mgrs(10, False, f[2]["X"], f[2]["Y"], lat_of(f[2]["X"], f[2]["Y"])).lower(),
           "10S %d %d" % (f[3]["X"], f[3]["Y"])]
    pts = [(fi["page"][0], fi["page"][1], s) for fi, s in zip(f, ins)]
    crop = r5.media
    out.append(fid_fit(ref, "rot5_plain_4_grid_fids", pts, "WGS84", crop,
                       note="the four ring targets on tacmap_grid_rot5_plain.pdf; MGRS no spaces, MGRS with spaces, "
                            "lowercase MGRS, UTM with band letter (S is a band here, not south)",
                       extra_check=[("sheet SW corner", *r5.corners()[0]), ("sheet NE corner", *r5.corners()[2])]))
    out.append(fid_fit(ref, "rot5_plain_3_exact", pts[:3], "WGS84", crop))

    sf = sheets["sf"]
    fs = sf.fiducials()
    typo_pts = [(fi["page"][0], fi["page"][1], fi["label"]) for fi in fs]
    cxy = (548000, 4179000)      # off both diagonals, so every 4-subset is in general position
    typo_pts.append((*sf.page(*cxy), mgrs(10, False, cxy[0], cxy[1], sf.plane.inv(*cxy)[0])))
    # F3 typed with one wrong digit: 51000 -> 51300 in the easting
    good = typo_pts[2][2]
    typo_pts[2] = (typo_pts[2][0], typo_pts[2][1], good.replace(" 51000 ", " 51300 "))
    out.append(fid_fit(ref, "sf_plain_typo_outlier", typo_pts, "WGS84", sf.media,
                       note="5 points on tacmap_grid_sf_plain.pdf, point index 2 has a typo (51000 -> 51300, 300 m). "
                            "flag rule (rules.outliers): RMS > tau and only dropping index 2 makes the rest Good"))

    # zone 55/56 boundary at 150E, sheet is a linear image of zone 55 (GDA94 MGA55 style)
    zc = Sheet("zc", Plane.utm(55, True, "GDA94"), 766000, 6250000, 20000, 20000, S50K)
    z55 = [(768000, 6252000), (784000, 6252000), (784000, 6268000), (770000, 6268000)]
    zpts = []
    la, lo = zc.plane.inv(*z55[0])
    zpts.append((*zc.page(*z55[0]), mgrs(55, True, z55[0][0], z55[0][1], la)))
    la, lo = ref.inv(UTM(55, True), "GDA94", *z55[1])
    E56, N56 = ref.fwd(UTM(56, True), "GDA94", la, lo)
    zpts.append((*zc.page(*z55[1]), mgrs(56, True, round(E56), round(N56), la)))
    la, lo = ref.inv(UTM(55, True), "GDA94", *z55[2])
    zpts.append((*zc.page(*z55[2]), "%.7f, %.7f" % (la, lo)))
    zpts.append((*zc.page(*z55[3]), "55H %d %d" % z55[3]))
    out.append(fid_fit(ref, "zone_crossing_55_56_gda94", zpts, "GDA94", zc.media,
                       note="first point is 55H so the plane is zone 55 S; point 2 is typed in zone 56 (east of 150E) and "
                            "must be converted through lat/lon on GRS80 into zone 55. MGRS rounding leaves sub-metre residuals"))

    # the D2-10 case: three taps along the bottom collar
    tA = [8.8, 0.3, 546000.0, -0.25, 8.8, 4176000.0]
    col = [(100.0, 50.0), (550.0, 53.0), (1000.0, 49.0)]
    cpts = []
    for x, y in col:
        X, Y = Ref.apply(tA, x, y)
        la = ref.inv(UTM(10, False), "WGS84", X, Y)[0]
        cpts.append((x, y, mgrs(10, False, round(X), round(Y), la)))
    out.append(fid_fit(ref, "near_collinear_d2_10", cpts, "WGS84", [0.0, 0.0, 1100.0, 1100.0],
                       note="eigen ratio ~1e-5, must be refused (plan s1: ratio < 0.02)"))
    for name, hgt in (("thin_rectangle_ratio_0p015", 98.0), ("thin_rectangle_ratio_0p030", 139.0)):
        rp = []
        for x, y in ((100.0, 400.0), (900.0, 400.0), (900.0, 400.0 + hgt), (100.0, 400.0 + hgt)):
            X, Y = Ref.apply(tA, x, y)
            la = ref.inv(UTM(10, False), "WGS84", X, Y)[0]
            rp.append((x, y, mgrs(10, False, round(X), round(Y), la)))
        out.append(fid_fit(ref, name, rp, "WGS84", [0.0, 0.0, 1000.0, 1000.0],
                           note="4 corners of a %g x 800 pt rectangle, covariance eigen ratio = (h/w)^2" % hgt))
    cl = []
    for x, y in ((450.0, 450.0), (560.0, 455.0), (555.0, 560.0), (452.0, 552.0)):
        X, Y = Ref.apply(tA, x, y)
        la = ref.inv(UTM(10, False), "WGS84", X, Y)[0]
        cl.append((x, y, mgrs(10, False, round(X), round(Y), la)))
    out.append(fid_fit(ref, "clustered_centre", cl, "WGS84", [0.0, 0.0, 1000.0, 1000.0],
                       note="good shape but only ~11% of the crop each way: not degenerate, spanWarning"))

    # ED50 sheet (D2-05): printed ED50 UTM 32U grid, one point typed as ED50 lat/lon
    ed = Sheet("ed50", Plane.utm(32, False, "ED50"), 495000, 5535000, 10000, 10000, S50K)
    ep = [(496000, 5536000), (504000, 5536000), (504000, 5544000), (497000, 5543000)]
    epts = [(*ed.page(*ep[0]), "32U %d %d" % ep[0]),
            (*ed.page(*ep[1]), mgrs(32, False, ep[1][0], ep[1][1], ed.plane.inv(*ep[1])[0])),
            (*ed.page(*ep[2]), "32U %d %d" % ep[2])]
    la, lo = ref.inv(UTM(32, False), "ED50", *ep[3])
    epts.append((*ed.page(*ep[3]), "%.7f, %.7f" % (la, lo)))
    out.append(fid_fit(ref, "ed50_utm32_d2_05", epts, "ED50", ed.media,
                       note="sheet datum ED50: MGRS/UTM/lat-lon are all ED50, the plane is ED50 UTM 32 on International "
                            "1924, toWGS84 applies (-87,-98,-121). the extra check is the D2-05 point 32U 500000 5540000",
                       extra_check=[("32U 500000 5540000", *ed.page(500000, 5540000))]))

    # first point typed as lat/lon: zone comes from the standard 6 degree formula
    cb = sheets["cbr50k"]
    cf = cb.fiducials()
    la, lo = cb.plane.inv(cf[0]["X"], cf[0]["Y"])
    lpts = [(cf[0]["page"][0], cf[0]["page"][1], "%.7f, %.7f" % (la, lo))]
    lpts += [(fi["page"][0], fi["page"][1], fi["label"]) for fi in cf[1:]]
    out.append(fid_fit(ref, "cbr50k_plain_latlon_first", lpts, "WGS84", cb.media,
                       note="tacmap_grid_cbr50k_plain.pdf ring targets; first point is decimal degrees so zone = "
                            "floor((lon+180)/6)+1 = 55, hemisphere from the sign of lat"))
    # MGRS at every precision both apps take (8/6/4 digits) plus a whitespace-only lat/lon
    r5f = r5.fiducials()
    prec = [mgrs(10, False, r5f[0]["X"], r5f[0]["Y"], lat_of(r5f[0]["X"], r5f[0]["Y"]), spaced=False),
            "10S EG %03d %03d" % (r5f[1]["X"] % 100000 // 100, r5f[1]["Y"] % 100000 // 100),
            "10seg%02d%02d" % (r5f[2]["X"] % 100000 // 1000, r5f[2]["Y"] % 100000 // 1000),
            "%.7f %.7f" % r5.plane.inv(r5f[3]["X"], r5f[3]["Y"])]
    prec[0] = prec[0][:5] + prec[0][5:9] + prec[0][10:14]          # 10 digits -> 8 (the targets sit on whole km)
    ppts = [(fi["page"][0], fi["page"][1], s) for fi, s in zip(r5f, prec)]
    out.append(fid_fit(ref, "rot5_plain_mgrs_precisions", ppts, "WGS84", crop,
                       note="same four targets typed as 8 digit MGRS, 6 digit with spaces, 4 digit lowercase, and "
                            "'lat lon' with only whitespace between. 0 or 2 digits is too coarse (parseCases)"))
    stored = stored_sets(ref, sheets)
    parse_cases = fiduciary_parse_cases(ref)
    return {"rules": {
        "zone": "zone + hemisphere of the FIRST point (MGRS/UTM give it, lat/lon uses floor((lon+180)/6)+1 and the "
                "sign of lat, no Norway/Svalbard exceptions); every other point goes through lat/lon on the sheet "
                "datum ellipsoid into that zone unless it's already in it",
        "utmInput": "'<zone><band letter> E N', the letter is an MGRS latitude band (C-M south, N-X north)",
        "fit": "least squares page -> plane affine; residual = plane distance in metres; RMS = sqrt(sum r^2 / n)",
        "degenerate": "eigenvalue ratio (min/max) of the page points' 2x2 covariance < 0.02 -> refuse",
        "spanWarning": "page points span < 25% of the crop bbox width or height -> warn (still fit)",
        "crossValidated": "n >= 4",
        "leaveOneOut": "leaveOneOut[i] = distance from point i's plane coords to the fit of the other n-1 points "
                       "(null when those are degenerate); leaveOneOutRms[i] = RMS of that n-1 fit on its own points",
        "outliers": "WP4 contract rule 6.4 (replaces the WP1 proposal, which false-flagged 45-83% of clean 5-6 "
                    "point fits): only when n >= 5 and RMS > tau, where tau = max(10 m, 0.0005 * D) and D is the "
                    "longer of the two cropBBox diagonals mapped through the fit (plane metres, diagonalMetres / "
                    "toleranceMetres). S = points whose leaveOneOutRms <= tau (nulls never count). flaggedOutliers "
                    "= [i] when S = {i}, else []. |S| >= 2 (ambiguous) and |S| = 0 (disagree) flag nothing here, "
                    "testdata/calibration_fit_report.json pins those"},
        "tolerance": {"planeMetres": 1e-3, "residualMetres": 1e-3, "eigenRatioRelative": 1e-6,
                      "wgs84Metres": 0.01, "pagePoints": 0.001},
        "sets": out,
        "parseRules": "input grammar both apps share: 'lat, lon' or 'lat lon' (decimal, optional leading '-', no '+'); "
                      "UTM '<zone><band> E N' (E in [100000, 900000], N in [0, 10000000]); MGRS any spacing/case with "
                      "4, 6, 8 or 10 digits (0/2 digit squares are too coarse, odd counts are junk). parsed = null "
                      "means the input must be refused. Before matching, every Unicode White_Space char (U+0009-000D, "
                      "U+0020, U+0085, U+00A0, U+1680, U+2000-200A, U+2028, U+2029, U+202F, U+205F, U+3000) and the "
                      "U+001C-001F info separators (Java isWhitespace) become an ascii space and spaces are trimmed; the grammar itself is ascii only (spaces, [0-9]). At most "
                      "64 UTF-16 units after trimming",
        "parseCases": parse_cases,
        "whiteSpaceCodePoints": sorted(WHITE_SPACE),
        "storedRules": "the production refit (iOS FiduciaryFitter.georeference(fromWGS84:), Android "
                       "FiduciaryFitter.refitStored): stored fiduciaries carry WGS84 lat/lon (shifted from the sheet "
                       "datum when typed) plus the text the user typed. Zone + hemisphere come off the FIRST point's "
                       "typed text when it parses as MGRS/UTM, else the standard zone of its lat/lon and the sign of "
                       "lat. Every point is forwarded into that zone on WGS84 and fitted like sets[]. truth[] is the "
                       "printed grid; the fit has to land it within truthToleranceMetres",
        "storedSets": stored}



def fiduciary_parse_cases(ref):
    ok = lambda s: {"input": s, "parsed": parse_ref_string(ref, s, "WGS84")}
    no = lambda s, why: {"input": s, "parsed": None, "note": why}
    return [ok("10SEG4700077000"), ok("10S EG 47000 77000"), ok("10SEG47007700"), ok("10seg 470 770"),
            ok("10SEG4777"), ok("-33.86, 151.2"), ok("-33.86 151.2"), ok("  37.7 ,  -122.45  "),
            ok("55H 768000 6252000"), ok("32v 310000 6690000"),
            no("10SEG47", "2 digits is a 10 km square, too coarse"), no("10SEG", "100 km square only"),
            no("10SEG4700077", "odd digit count"), no("10SEG470007700012", "12 digits"),
            no("+37.7, -122.45", "no leading +"), no("91, 10", "lat off the earth"), no("10, 181", "lon off the earth"),
            no("10S 47000 4177000", "UTM easting under 100000"), no("61S 547000 4177000", "zone 61"),
            no("10I EG 47000 77000", "I is not a band letter"), no("", "empty"),
            # pasted from web pages / PDFs: NBSP, thin space, ideographic space, tabs are all just spaces
            ok("37.7\u00a0-122.45"), ok("37.7,\u2009-122.45"), ok("55H\u00a0768000\u00a06252000"),
            ok("\u3000-33.86\t151.2\u2028"), ok("10S\u202fEG\u205f47000\u200a77000"), ok("37.7\u001f-122.45"),
            no("\u00a0\u2009\t", "only whitespace"),
            no("37.7\u200b-122.45", "zero width space isn't whitespace"),
            no("\uff13\uff17.7, -122.45", "fullwidth digits aren't ascii digits")]


def stored_fit(ref, name, sheet, targets, typed, note):
    """a calibration as the apps persist it: page point + WGS84 lat/lon + typed text, refit like production"""
    pl = sheet.plane
    fids = []
    truth = []
    for (X, Y), text in zip(targets, typed):
        x, y = sheet.page(X, Y)
        la, lo = ref.inv(UTM(pl.p["zone"], pl.p["south"]), pl.datum, X, Y)
        wl, wo = ref.to_wgs84(pl.datum, la, lo)
        fids.append({"pdfX": rpt(x), "pdfY": rpt(y), "mgrs": text, "latitude": rdeg(wl), "longitude": rdeg(wo)})
    try:
        p0 = parse_ref_string(ref, fids[0]["mgrs"], pl.datum)
    except ValueError:
        p0 = {"kind": "latlon", "lat": fids[0]["latitude"], "lon": fids[0]["longitude"]}
    std_zone = int(math.floor((fids[0]["longitude"] + 180) / 6)) + 1
    if p0["kind"] in ("mgrs", "utm"):
        zone, south = p0["zone"], p0["hemisphere"] == "S"
    else:
        zone, south = std_zone, fids[0]["latitude"] < 0

    def fit_in(z, s):
        crs = UTM(z, s)
        pairs = []
        for f in fids:
            X, Y = ref.fwd(crs, "WGS84", f["latitude"], f["longitude"])
            pairs.append((f["pdfX"], f["pdfY"], X, Y))
        return crs, pairs, ref.fit(pairs)

    # the printed grid: every 4 km intersection, sheet datum grid -> WGS84
    for X in sheet.gridvals("X", 4000.0):
        for Y in sheet.gridvals("Y", 4000.0):
            x, y = sheet.page(X, Y)
            la, lo = ref.inv(UTM(pl.p["zone"], pl.p["south"]), pl.datum, X, Y)
            wl, wo = ref.to_wgs84(pl.datum, la, lo)
            truth.append({"label": "%dmE %dmN" % (X, Y), "page": [rpt(x), rpt(y)], "wgs84": [rdeg(wl), rdeg(wo)]})

    def worst(crs, A):
        g = {"crs": crs, "datum": "WGS84", "affine": A}
        return max(ref.geod_m(*ref.to_wgs84_page(g, t["page"][0], t["page"][1])[0], *t["wgs84"]) for t in truth)

    crs, pairs, A = fit_in(zone, south)
    res = [math.hypot(*(a - b for a, b in zip(ref.apply(A, x, y), (X, Y)))) for x, y, X, Y in pairs]
    g = {"crs": crs, "datum": "WGS84", "affine": A}
    xs, ys = [f["pdfX"] for f in fids], [f["pdfY"] for f in fids]
    exp = {"zone": zone, "hemisphere": "S" if south else "N", "crs": crs, "datum": {"id": "WGS84"},
           "standardZoneOfFirstPoint": std_zone,
           "planePoints": [[rm(X), rm(Y)] for _, _, X, Y in pairs],
           "affine": [rsig(v) for v in A], "residualsMetres": [rm(r) for r in res],
           "rmsMetres": rm(math.sqrt(sum(r * r for r in res) / len(res))), "maxResidualMetres": rm(max(res)),
           "crossValidated": len(fids) >= 4,
           "checks": [check_point(ref, g, "points centroid", sum(xs) / len(xs), sum(ys) / len(ys), "centroid")],
           "truth": truth, "truthToleranceMetres": 0.05, "modelErrorMetres": round(worst(crs, A), 4)}
    if std_zone != zone:
        wcrs, _, wA = fit_in(std_zone, fids[0]["latitude"] < 0)
        exp["standardZoneModelErrorMetres"] = round(worst(wcrs, wA), 4)
    assert exp["modelErrorMetres"] <= exp["truthToleranceMetres"], (name, exp["modelErrorMetres"])
    m = sheet.media
    return {"name": name, "note": note, "sheetDatum": pl.datum, "cropBBox": [rpt(v) for v in m],
            "fiduciaries": fids, "expected": exp}


def stored_sets(ref, sheets):
    out = []
    # 1:25k, 20 km, printed on the GDA94 MGA55 grid but mostly east of 150E (the zone 56 side)
    e55 = Sheet("e55", Plane.utm(55, True, "GDA94"), 774000, 6250000, 20000, 20000, S25K)
    t55 = [(792000, 6252000), (776000, 6252000), (776000, 6268000), (792000, 6268000)]
    typed = [mgrs(55, True, X, Y, -33.8) for X, Y in t55[:3]] + ["55H %d %d" % t55[3]]
    out.append(stored_fit(ref, "stored_zone55_typed_east_of_150", e55, t55, typed,
                          "first point 55H at ~150.07E: the plane is zone 55 from its typed MGRS, not zone 56 from "
                          "its longitude. lat/lon are GDA94 shifted to WGS84 (the datum picker), the fit runs on WGS84"))
    # Norway: printed WGS84 UTM 32V grid west of 6E, where the standard zone formula says 31
    nv = Sheet("n32", Plane.utm(32, False, "WGS84"), 306000, 6688000, 20000, 20000, S25K)
    t32 = [(308000, 6690000), (324000, 6690000), (324000, 6706000), (308000, 6706000)]
    typed = [mgrs(32, False, X, Y, 60.3) for X, Y in t32]
    out.append(stored_fit(ref, "stored_norway_32v_west_of_6e", nv, t32, typed,
                          "32V MGRS at ~5.7E: zone 32 from the typed text, the 6 degree formula would say 31"))
    # first point typed as lat/lon (or anything that isn't MGRS/UTM): standard zone of its lat/lon
    t55l = [(776000, 6252000), (792000, 6252000), (792000, 6268000), (776000, 6268000)]
    la, lo = ref.inv(UTM(55, True), "GDA94", *t55l[0])
    typed = ["%.7f, %.7f" % (la, lo)] + [mgrs(55, True, X, Y, -33.8) for X, Y in t55l[1:]]
    out.append(stored_fit(ref, "stored_latlon_first_standard_zone", e55, t55l, typed,
                          "first point typed as lat/lon west of 150E: standard zone 55 from its longitude"))
    return out



# ----------------------------------------------------------------------------
# tile warp
# ----------------------------------------------------------------------------

def tile_of(lat, lon, z):
    n = 2 ** z
    x = (lon + 180) / 360 * n
    s = math.sin(math.radians(lat))
    y = (0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)) * n
    return int(x), int(y)


def tile_px_latlon(z, tx, ty, px, py):
    n = 256 * 2 ** z
    X = (tx * 256 + px) / n
    Y = (ty * 256 + py) / n
    lon = X * 360 - 180
    lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * Y))))
    return lat, lon


def tilewarp_section(ref, georefs):
    out = []
    for gid, g, crop in georefs:
        cx = sum(p[0] for p in crop) / len(crop)
        cy = sum(p[1] for p in crop) / len(crop)
        anchors = [("crop centroid", cx, cy), ("crop vertex 0", crop[0][0], crop[0][1])]
        for z in (12, 15, 18):
            seen = set()
            for alab, ax, ay in anchors:
                (la, lo), _ = ref.to_wgs84_page(g, ax, ay)
                tx, ty = tile_of(la, lo, z)
                if (tx, ty) in seen:
                    continue
                seen.add((tx, ty))
                samples = []
                for px, py in ((0, 0), (256, 0), (0, 256), (256, 256), (128, 128)):
                    la2, lo2 = tile_px_latlon(z, tx, ty, px, py)
                    x, y = ref.to_page(g, la2, lo2)
                    samples.append({"px": [px, py], "wgs84": [rdeg(la2), rdeg(lo2)], "page": [rpt(x), rpt(y)]})
                out.append({"georef": gid, "z": z, "x": tx, "y": ty, "anchor": alab, "samples": samples})
    return {"note": "XYZ tiles, 256 px, pixel y down, spherical Web Mercator on WGS84 lat/lon. sample px -> lat/lon -> "
                    "georef.toPage. georef ids point at sheets[].expected (usgs_sf_north included)",
            "tolerance": {"pagePoints": 1e-4, "degrees": 1e-11},
            "tiles": out}


# ----------------------------------------------------------------------------
# json writer: indented objects, flat arrays kept on one line
# ----------------------------------------------------------------------------

def _leafy(v):
    if isinstance(v, dict):
        return all(_leafy(w) and not isinstance(w, dict) for w in v.values())
    if isinstance(v, (list, tuple)):
        return all(not isinstance(w, (dict, list, tuple)) or
                   (isinstance(w, (list, tuple)) and all(not isinstance(q, (dict, list, tuple)) for q in w)) for w in v)
    return True


def dumps(o, ind=0):
    sp = "  " * ind
    if isinstance(o, dict):
        if not o:
            return "{}"
        if _leafy(o):
            # small record (a check, a parsed point...) stays on one line, keeps the file greppable
            line = "{" + ", ".join("%s: %s" % (json.dumps(k), dumps(v)) for k, v in o.items()) + "}"
            if len(line) <= 260:
                return line
        return "{\n" + ",\n".join("%s  %s: %s" % (sp, json.dumps(k), dumps(v, ind + 1)) for k, v in o.items()) + "\n" + sp + "}"
    if isinstance(o, (list, tuple)):
        if not o:
            return "[]"
        flat = all(not isinstance(v, (dict, list, tuple)) for v in o)
        pairs = all(isinstance(v, (list, tuple)) and all(not isinstance(w, (dict, list, tuple)) for w in v) for v in o)
        if flat:
            return "[" + ", ".join(dumps(v) for v in o) + "]"
        if pairs and len(o) <= 12:
            return "[" + ", ".join(dumps(v) for v in o) + "]"
        return "[\n" + ",\n".join("%s  %s" % (sp, dumps(v, ind + 1)) for v in o) + "\n" + sp + "]"
    if isinstance(o, float):
        if not math.isfinite(o):
            raise ValueError("non-finite float in json")
        if o == int(o) and abs(o) < 1e15:
            return repr(float(o))
        return repr(o)
    return json.dumps(o)


# ----------------------------------------------------------------------------
# --measure-usgs: re-measure the printed grid of the real sheet (pymupdf)
# ----------------------------------------------------------------------------

def measure_usgs(path):
    import pymupdf
    ref = Ref()
    g = usgs_georef(ref)
    np = ref.np
    doc = pymupdf.open(path)
    page = doc[0]
    H = page.mediabox.height
    segs = []
    for d in page.get_drawings():
        col, w = d.get("color"), d.get("width") or 0
        if not col or abs(col[0] - 1) > 0.01 or abs(col[1] - 0.667) > 0.01 or col[2] > 0.01 or abs(w - 0.18) > 0.005:
            continue
        for it in d["items"]:
            if it[0] != "l":
                continue
            a, b = it[1], it[2]
            segs.append((a.x, H - a.y, b.x, H - b.y))
    vert, horz = {}, {}
    for ax, ay, bx, by in segs:
        L = math.hypot(bx - ax, by - ay)
        if L < 15:
            continue
        ang = math.degrees(math.atan2(by - ay, bx - ax)) % 180
        E, N = Ref.apply(g["affine"], (ax + bx) / 2, (ay + by) / 2)
        if abs(ang - 90) < 2:
            key = int(round(E / 1000.0)) * 1000
            if abs(E - key) < 60:
                vert.setdefault(key, set()).update({(ax, ay), (bx, by)})
        elif ang < 2 or ang > 178:
            key = int(round(N / 1000.0)) * 1000
            if abs(N - key) < 60:
                horz.setdefault(key, set()).update({(ax, ay), (bx, by)})
    lines_v = {k: np.polyfit([p[1] for p in v], [p[0] for p in v], 1) for k, v in vert.items() if len(v) > 40}
    lines_h = {k: np.polyfit([p[0] for p in v], [p[1] for p in v], 1) for k, v in horz.items() if len(v) > 40}
    rows = []
    for E in sorted(lines_v):
        if E % 2000:
            continue
        for N in sorted(lines_h):
            if N % 2000:
                continue
            mv, cv = lines_v[E]
            mh, ch = lines_h[N]
            y = (mh * cv + ch) / (1 - mh * mv)
            x = mv * y + cv
            rows.append((E, N, round(float(x), 3), round(float(y), 3)))
    print("USGS_PRINTED_GRID = [")
    for r in rows:
        print("    (%d, %d, %.3f, %.3f)," % r)
    print("]")


# ----------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.path.join(REPO, "testdata"), help="testdata dir (default: repo testdata/)")
    ap.add_argument("--measure-usgs", metavar="PDF", help="measure the printed grid of the real USGS sheet and exit")
    args = ap.parse_args()
    if args.measure_usgs:
        measure_usgs(args.measure_usgs)
        return
    ref = Ref()
    pdfdir = os.path.join(args.out, "geopdf")
    os.makedirs(pdfdir, exist_ok=True)
    sheets = build_sheets()

    # quick sanity: the plain python math agrees with PROJ where it draws ink.
    # Kruger n^6 vs poder_engsager lands a couple of microns apart, anything
    # past 0.1 mm means a bug in the stdlib half
    worst = 0.0
    for key, sh in sheets.items():
        if sh.plane.kind == "geog":
            continue
        for X, Y in sh.frame_pts():
            la, lo = sh.plane.inv(X, Y)
            X2, Y2 = ref.fwd(sh.plane.crs(), sh.plane.datum, la, lo)
            worst = max(worst, abs(X - X2), abs(Y - Y2))
    if worst > 1e-4:
        raise SystemExit("plain python projection is off PROJ by %g m" % worst)
    print("stdlib projection vs PROJ at sheet corners: %.2g m" % worst)

    sheet_entries = []
    georefs = {}
    for stem, key, kind in PLAN:
        sh = sheets[key]
        fname, sha, size, written = write_sheet(pdfdir, stem, sh, kind)
        entry, g = sheet_expectation(ref, stem, sh, kind, fname, sha, size, written)
        sheet_entries.append(entry)
        if g:
            georefs[stem] = (g, [tuple(p) for p in entry["expected"]["crop"]])
        print("wrote %-34s %6d bytes" % (fname, size))
    fname, sha, size = write_usgs_standin(pdfdir, ref)
    ue, ug = usgs_sheet(ref, fname, sha, size)
    sheet_entries.append(ue)
    georefs["usgs_sf_north"] = (ug, [tuple(p) for p in ue["expected"]["crop"]])
    print("wrote %-34s %6d bytes" % (fname, size))

    doc = {
        "description": "Shared GeoPDF georeference fixtures (plans/02-pdf-import-revision.md s1 + s6). Expected values "
                       "from PROJ via pyproj + numpy, never from app code. Regenerate with scripts/gen_test_geopdfs.py.",
        "schemaVersion": 1,
        "generator": "scripts/gen_test_geopdfs.py",
        "reference": {"pyproj": ref.pyproj.__version__, "proj": ref.pyproj.proj_version_str, "numpy": ref.np.__version__},
        "conventions": {
            "pageSpace": "PDF default user space of the page, y up, raw coordinates INCLUDING any MediaBox/CropBox "
                         "origin offset and IGNORING /Rotate",
            "affine": "[a,b,c,d,e,f]: X = a*x + b*y + c ; Y = d*x + e*y + f (page -> plane)",
            "lgiCtm": "LGIDict /CTM [A B C D E F] is pdf matrix order: X = A*x + C*y + E ; Y = B*x + D*y + F, "
                      "so affine = [A, C, E, B, D, F]",
            "plane": "projected CRS: metres (x east, y north). geographic: x = lon, y = lat in degrees, in the datum",
            "gpts": "ISO GPTS are (lat, lon) pairs in the GCS's own geographic datum",
            "lpts": "page = (BBox[0] + lx*(BBox[2]-BBox[0]), BBox[1] + ly*(BBox[3]-BBox[1])) exactly as written, "
                    "BBox may be reversed",
            "crop": "ISO: /Bounds mapped through the BBox the same way, in the order written; no /Bounds -> "
                    "[x0,y0],[x2,y0],[x2,y3],[x0,y3] from the BBox as written. LGIDict: /Neatline points in order, "
                    "or the Registration page points in order when there is no /Neatline",
            "toWGS84": "datum.toWGS84(crs.inverse(affine(page)))",
            "toPage": "affine^-1(crs.forward(datum.fromWGS84(wgs84)))",
            "checks": "each check: page -> wgs84 (toWGS84) and wgs84 -> toPage. 'plane' is affine(page)",
            "fitSelection": "ISO: largest BBox area among GEO viewports that yield a valid georef. LGIDict: entry "
                            "described Layers, else the largest neatline; CTM when both CTM and Registration exist "
                            "and agree within 1 m at every Registration point, else Registration",
            "rounding": "json degrees 11 dp, metres 6 dp, points 7 dp, affine 15 sig figs"},
        "datums": datums_section(ref),
        "projections": projections_section(ref),
        "gcs": gcs_section(ref),
        "sheets": sheet_entries,
        "rejections": rejections_section(ref, sheets, pdfdir),
        "fiduciaryFits": fiducial_section(ref, sheets),
        "tileWarp": tilewarp_section(ref, [(k,) + georefs[k] for k in ("sf_iso", "rot5_iso", "usgs_sf_north")]),
    }
    with open(os.path.join(args.out, "pdf_georef.json"), "w") as fh:
        fh.write(dumps(doc) + "\n")
    print("wrote pdf_georef.json")


if __name__ == "__main__":
    main()
