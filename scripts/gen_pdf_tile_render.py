#!/usr/bin/env python3
"""Shared WP2 tile render fixture for the iOS + Android PDF tile source.

Writes (default: the repo's testdata/):
  pdf_tile_render.json             everything plans/WP2-render-shared_contract.md says is pinned
  geopdf/tacmap_render_markers.pdf rotated + offset sheet with red markers and an OFF-by-default OCG
  geopdf/tacmap_render_blank.pdf   georeferenced page with nothing visible on it
  geopdf/tacmap_render_dense.pdf   the J3 dense linework sheet (thin diagonals + red grid)
  geopdf/tacmap_render_blank_corner.pdf  /Bounds quad with black marks outside it, still blank

Independent of app code AND of scripts/gen_test_geopdfs.py: the georefs (crs, datum,
affine, crop) are read out of testdata/pdf_georef.json and pushed through one PROJ
pipeline per sheet (affine -> inverse projection -> cart/helmert datum shift -> WGS84),
built here from scratch. The rest is plain python geometry. Needs pyproj + numpy:

  python3 scripts/gen_pdf_tile_render.py            # regenerate testdata/
  python3 scripts/gen_pdf_tile_render.py --check    # exit 1 if anything would change
  python3 scripts/gen_pdf_tile_render.py --out /tmp/x

Deterministic: no dates, PDFs uncompressed with fixed decimal numbers, json floats
rounded so a PROJ patch bump doesnt churn the file. Every borderline decision (a
tile that nearly touches the footprint, a split error sitting on 0.25 px, a ceil
right on an integer) is measured and the script refuses to write if one is too close
to call, so the platforms can match exactly.
"""

import argparse
import hashlib
import json
import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)

# ----------------------------------------------------------------------------
# the numbers from the shared contract. everything here lands in `constants`
# ----------------------------------------------------------------------------

R = 6378137.0
MPP0 = 2 * math.pi * R / 256          # 156543.03392804097, merc metres per 256-unit at z0
GIB = 1024 ** 3
MIB = 1024 ** 2

TILE_UNITS = 256
DENSITY_CAP = 3.0
TILE_PX_QUANTUM = 16
CAMERA_ZOOM = (2.0, 22.0)
FLYTO_ZOOM = (2.0, 19.0)
MAX_UNDERZOOM = 2.0
VISIBLE_INFLATE = 1.0
MAX_ANCESTOR_LEVELS = 4
DENSIFY = 32
CLIP_MIN_AREA = 1.0
CLIP_DEDUPE_EPS = 1e-9
DETAIL_OVERSAMPLE = 4.0
DETAIL_HYST = 0.05
MAX_ZOOM_CAP = 22
BUDGET_NORMAL = 6000000
BUDGET_LOW = 3000000
BASE_MAX_SIDE = 4096
BASE_MAX_SCALE = 4.0
MIP_STOP = 256
UPSAMPLE_TOL = 1.0
STAGED_OVERSAMPLE = 1.25
STAGED_MAX_FACTOR = 2.0
WARP_MAX_ERR = 0.25
WARP_BASE_DEPTH = 4
WARP_MIN_CELL = 32
WARP_ROOT_PAD = 2
SETTLE_MS = 150
EWMA_ALPHA = 0.3
HEAVY_MS = 60.0
JOB_MAX_TILES = 6
JOB_MAX_SIDE = 3
BAKE_BLOCK = (3, 2)
ORPHAN_TILES = 8
RENDER_ERROR_RUN = 3
BLANK_STRIDE = 4
BLANK_WHITE_MAX = 250
PREPARING_DELAY_MS = 300
BAKE_MAX_TILES = 6000
BAKE_SPACE_FACTOR = 2.0
BAKE_BYTES_SAFETY = 1.2
BAKE_SAMPLE_TILES = 3
BAKE_COMMIT_EVERY = 64
RENDERER_VERSION = 1
GUARD_MAX_TOKENS = 16
# bake estimate fallbacks when no sample tile came back (amendment 2026-10-02, J2). these were
# androids numbers, now shared. raise FALLBACK_TILE_BYTES here (never in the json) if a platform
# measures a bigger mean on samples/USGS_SF_North.pdf (J3)
BAKE_FALLBACK_JOB_MS = 100.0
BAKE_FALLBACK_ENCODE_MS = 5.0
# J3: android bakes lossless webp now, measured on USGS_SF_North at 768 px z15 (3 J2 samples + 10
# inside tiles): mean 142855 / max 198856 bytes. ios bakes jpeg 2000 q0.9 (plain jpeg cant make
# 35 dB), same 13 tiles: mean 248349 / max 316438, the bigger one wins, rounded up to the next 10000
BAKE_FALLBACK_TILE_BYTES = 250000
PSNR_GATE_DB = 35.0            # J3, both platforms ship this as a production constant (r1 D4)
PSNR_TILEPX = [768, 512]
# r1 F2: MBTiles metadata name, never the PDF display name
BAKE_MBTILES_NAME = "TacMap offline tiles"

# the synthetic sheets (markers, blank, wide warp) are all UTM 10N on WGS84
WKT_UTM10N = ('PROJCS["WGS 84 / UTM zone 10N",GEOGCS["WGS 84",DATUM["WGS_1984",SPHEROID["WGS 84",6378137,298.257223563]],'
              'PRIMEM["Greenwich",0],UNIT["degree",0.0174532925199433]],PROJECTION["Transverse_Mercator"],'
              'PARAMETER["latitude_of_origin",0],PARAMETER["central_meridian",-123],PARAMETER["scale_factor",0.9996],'
              'PARAMETER["false_easting",500000],PARAMETER["false_northing",0],UNIT["metre",1]]')
UTM10N = {"kind": "transverseMercator", "lat0": 0.0, "lon0": -123.0, "k0": 0.9996, "fe": 500000.0, "fn": 0.0,
          "utmZone": 10, "hemisphere": "N"}

# fixture sheets (contract O). usgs_sf_north is the stand-in that carries the real /VP dicts
SHEETS = ["sf_iso", "rot5_iso", "offset_iso", "rot90_iso", "cbr50k_iso", "lcc_lgile", "geog_iso", "usgs_sf_north"]
TILEPX_POLICY = [512, 672, 768]                  # pxPerPt rows, per contract
TILEPX_BASEMAX = [256, 384, 512, 672, 704, 768]  # baseMaxZoom rows, cheap so do the common ones

# margins a platform has to be able to resolve the same way we did
# 1 z0 world unit is ~156.5 km of mercator, so 1e-7 is ~1.6 cm. thats above WP1's 1 cm toWGS84 gate,
# so even a platform sitting right on that gate classifies every pinned tile the same way
EPS_WORLD = 1e-7      # z0 world units, footprint vs tile edges
EPS_PAGE = 1e-3       # page points (WP1's toPage gate), warp cell quad vs clip polygon
EPS_ERR = 1e-4        # px, warp split error vs the 0.25 bound
EPS_CEIL = 1e-7       # base raster ceil inputs: plain double math on parsed numbers, ulps are ~1e-12 here
EPS_ROOT = 1e-3       # px, warp root floor/ceil. iOS measured ~6e-5 px off PROJ at z16, usgs z9 sits at 5e-3
EPS_REL = 1e-7        # relative, baseMaxZoom pxPerPt vs base density


# ----------------------------------------------------------------------------
# small number helpers
# ----------------------------------------------------------------------------

def rsig(v, sig=13):
    v = float(v)
    if v == 0 or not math.isfinite(v):
        return 0.0 if v == 0 else v
    return float("%.*g" % (sig, v))


def rpt(v):
    return round(float(v), 7)


def rdeg(v):
    return round(float(v), 11)


def near_int(v, eps=EPS_CEIL):
    return abs(v - round(v)) < eps


def pdfnum(v, nd=6):
    s = ("%.*f" % (nd, float(v))).rstrip("0").rstrip(".")
    return "0" if s in ("", "-0") else s


# ----------------------------------------------------------------------------
# georef model through PROJ. one pipeline: page -> WGS84 lon/lat degrees
# ----------------------------------------------------------------------------

class Georef:
    """toWGS84 / toPage for one sheet, built only from the pdf_georef.json numbers"""

    def __init__(self, gid, crs, datum, affine, crop, datums):
        import pyproj
        self.id, self.crs, self.datum, self.affine = gid, crs, datum, list(affine)
        self.crop = [tuple(map(float, p)) for p in crop]
        d = datums[datum["id"]] if datum.get("id") != "custom" else datum
        a, rf = d["a"], d["invF"]
        A = self.affine
        steps = ["+proj=pipeline",
                 "+step +proj=affine +xoff=%r +yoff=%r +s11=%r +s12=%r +s21=%r +s22=%r" % (A[2], A[5], A[0], A[1], A[3], A[4])]
        k = crs["kind"]
        ell = "+a=%r +rf=%r" % (a, rf)
        if k == "geographic":
            steps.append("+step +proj=unitconvert +xy_in=deg +xy_out=rad")
        elif k == "transverseMercator":
            steps.append("+step +inv +proj=tmerc +lat_0=%r +lon_0=%r +k_0=%r +x_0=%r +y_0=%r %s" % (
                crs["lat0"], crs["lon0"], crs["k0"], crs["fe"], crs["fn"], ell))
        elif k == "lambertConformalConic2SP":
            steps.append("+step +inv +proj=lcc +lat_1=%r +lat_2=%r +lat_0=%r +lon_0=%r +x_0=%r +y_0=%r %s" % (
                crs["lat1"], crs["lat2"], crs["lat0"], crs["lon0"], crs["fe"], crs["fn"], ell))
        else:
            raise ValueError("no pipeline for crs %s" % k)
        t = d.get("transform", "identity")
        if t != "identity":
            h = "+proj=helmert +x=%r +y=%r +z=%r" % (d["dx"], d["dy"], d["dz"])
            if t == "helmert7":
                h += " +rx=%r +ry=%r +rz=%r +s=%r +convention=coordinate_frame" % (
                    d["rxArcsec"], d["ryArcsec"], d["rzArcsec"], d["scalePpm"])
            steps += ["+step +proj=cart %s" % ell, "+step " + h, "+step +inv +proj=cart +ellps=WGS84"]
        steps.append("+step +proj=unitconvert +xy_in=rad +xy_out=deg")
        self.pipeline = " ".join(steps)
        self._t = pyproj.Transformer.from_pipeline(self.pipeline)
        self._inv = pyproj.enums.TransformDirection.INVERSE

    def to_wgs84(self, x, y):
        # h = 0 in, height dropped out, same as WP1
        lon, lat, _ = self._t.transform(float(x), float(y), 0.0)
        if not (math.isfinite(lat) and math.isfinite(lon)) or abs(lat) > 90 or abs(lon) > 180:
            return None
        return lat, lon

    def to_page(self, lat, lon):
        x, y, _ = self._t.transform(float(lon), float(lat), 0.0, direction=self._inv)
        if not (math.isfinite(x) and math.isfinite(y)):
            return None
        return x, y


def merc_world0(lat, lon):
    """WGS84 lat/lon -> spherical web mercator world units at z0 (0..256, y down)"""
    s = math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))
    return (lon + 180.0) / 360.0 * 256.0, (1.0 - s / math.pi) / 2.0 * 256.0


def merc_metres(lat, lon):
    return R * math.radians(lon), R * math.log(math.tan(math.pi / 4 + math.radians(lat) / 2))


def job_px_to_latlon(z, x0, y0, tile_px, u, v):
    """contract A: wx = x0*256 + u*256/tilePx at zoom z, then spherical mercator"""
    n = 2 ** z
    wx = x0 * 256.0 + u * 256.0 / tile_px
    wy = y0 * 256.0 + v * 256.0 / tile_px
    lon = wx / (256.0 * n) * 360.0 - 180.0
    lat = math.degrees(math.atan(math.sinh(math.pi * (1.0 - 2.0 * wy / (256.0 * n)))))
    return lat, lon


def latlon_to_tile_px(z, tile_px, lat, lon):
    X, Y = merc_world0(lat, lon)
    wx, wy = X * 2 ** z, Y * 2 ** z
    tx, ty = int(math.floor(wx / 256.0)), int(math.floor(wy / 256.0))
    return (tx, ty), ((wx - tx * 256.0) * tile_px / 256.0, (wy - ty * 256.0) * tile_px / 256.0)


# ----------------------------------------------------------------------------
# plane geometry
# ----------------------------------------------------------------------------

def shoelace(poly):
    a = 0.0
    for i in range(len(poly)):
        x0, y0 = poly[i]
        x1, y1 = poly[(i + 1) % len(poly)]
        a += x0 * y1 - x1 * y0
    return a / 2.0


def sutherland_hodgman(subject, box):
    """crop polygon clipped to box [x0,y0,x1,y1]. edges in order left, right, bottom, top.
    on the edge counts as inside, an intersection is emitted only when S and E are on
    opposite sides, then consecutive duplicates (incl. wrap) within 1e-9 pt are dropped.
    the mean of these vertices feeds mercMetresPerPoint so the dedupe matters"""
    x0, y0, x1, y1 = box
    edges = [(lambda p: p[0] >= x0, lambda s, e: (x0, s[1] + (e[1] - s[1]) * (x0 - s[0]) / (e[0] - s[0]))),
             (lambda p: p[0] <= x1, lambda s, e: (x1, s[1] + (e[1] - s[1]) * (x1 - s[0]) / (e[0] - s[0]))),
             (lambda p: p[1] >= y0, lambda s, e: (s[0] + (e[0] - s[0]) * (y0 - s[1]) / (e[1] - s[1]), y0)),
             (lambda p: p[1] <= y1, lambda s, e: (s[0] + (e[0] - s[0]) * (y1 - s[1]) / (e[1] - s[1]), y1))]
    out = list(subject)
    for inside, cut in edges:
        if not out:
            break
        src, out = out, []
        s = src[-1]
        for e in src:
            if inside(e):
                if not inside(s):
                    out.append(cut(s, e))
                out.append(e)
            elif inside(s):
                out.append(cut(s, e))
            s = e
    ded = []
    for p in out:
        if ded and abs(p[0] - ded[-1][0]) <= CLIP_DEDUPE_EPS and abs(p[1] - ded[-1][1]) <= CLIP_DEDUPE_EPS:
            continue
        ded.append(p)
    while len(ded) > 1 and abs(ded[0][0] - ded[-1][0]) <= CLIP_DEDUPE_EPS and abs(ded[0][1] - ded[-1][1]) <= CLIP_DEDUPE_EPS:
        ded.pop()
    return ded


def is_convex(poly):
    sgn = 0
    n = len(poly)
    for i in range(n):
        ax, ay = poly[i]
        bx, by = poly[(i + 1) % n]
        cx, cy = poly[(i + 2) % n]
        cr = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
        if abs(cr) < 1e-12:
            continue
        s = 1 if cr > 0 else -1
        if sgn and s != sgn:
            return False
        sgn = s
    return True


def densify(poly, n=DENSIFY):
    """contract C: n segments per edge, vertex k of edge i = P_i + (P_i+1 - P_i) * k/n, k = 0..n-1"""
    out = []
    for i in range(len(poly)):
        ax, ay = poly[i]
        bx, by = poly[(i + 1) % len(poly)]
        for k in range(n):
            t = k / n
            out.append((ax + (bx - ax) * t, ay + (by - ay) * t))
    return out


def point_in_poly(p, poly):
    x, y = p
    inside = False
    n = len(poly)
    for i in range(n):
        x0, y0 = poly[i]
        x1, y1 = poly[(i + 1) % n]
        if (y0 > y) != (y1 > y):
            xi = x0 + (y - y0) * (x1 - x0) / (y1 - y0)
            if x < xi:
                inside = not inside
    return inside


def seg_hits_rect(p, q, rect):
    """closed segment vs closed axis-aligned rect, liang-barsky"""
    x0, y0, x1, y1 = rect
    if x1 < x0 or y1 < y0:
        return False
    dx, dy = q[0] - p[0], q[1] - p[1]
    t0, t1 = 0.0, 1.0
    for pp, qq in ((-dx, p[0] - x0), (dx, x1 - p[0]), (-dy, p[1] - y0), (dy, y1 - p[1])):
        if pp == 0:
            if qq < 0:
                return False
        else:
            r = qq / pp
            if pp < 0:
                if r > t1:
                    return False
                t0 = max(t0, r)
            else:
                if r < t0:
                    return False
                t1 = min(t1, r)
    return t0 <= t1


def convex_signed_dist(p, poly, ccw):
    """distance to the nearest edge line, positive inside. exact for inside points of a convex poly"""
    best = float("inf")
    n = len(poly)
    for i in range(n):
        ax, ay = poly[i]
        bx, by = poly[(i + 1) % n]
        ex, ey = bx - ax, by - ay
        L = math.hypot(ex, ey)
        cr = (ex * (p[1] - ay) - ey * (p[0] - ax)) / L
        best = min(best, cr if ccw else -cr)
    return best


def sat_separation(a, b):
    """max gap over all edge normals of two convex polys. > 0 means disjoint"""
    best = -float("inf")
    for poly in (a, b):
        n = len(poly)
        for i in range(n):
            ax, ay = poly[i]
            bx, by = poly[(i + 1) % n]
            nx, ny = -(by - ay), bx - ax
            L = math.hypot(nx, ny)
            nx, ny = nx / L, ny / L
            pa = [nx * x + ny * y for x, y in a]
            pb = [nx * x + ny * y for x, y in b]
            gap = max(min(pb) - max(pa), min(pa) - max(pb))
            best = max(best, gap)
    return best


# ----------------------------------------------------------------------------
# footprint + tile coverage
# ----------------------------------------------------------------------------

class Footprint:
    def __init__(self, g, page_box):
        self.g = g
        self.page_box = page_box
        clip = sutherland_hodgman(g.crop, page_box)
        area = abs(shoelace(clip)) if len(clip) >= 3 else 0.0
        if area < CLIP_MIN_AREA:
            raise ValueError("%s: pageGeometry, clip area %r" % (g.id, area))
        if not is_convex(clip):
            raise ValueError("%s: clip polygon isnt convex, the cell classifier here assumes it is" % g.id)
        self.clip = clip
        self.ccw = shoelace(clip) > 0
        self.mean = (sum(p[0] for p in clip) / len(clip), sum(p[1] for p in clip) / len(clip))
        xs, ys = [p[0] for p in clip], [p[1] for p in clip]
        self.bbox = (min(xs), min(ys), max(xs), max(ys))
        world = []
        for x, y in densify(clip):
            ll = g.to_wgs84(x, y)
            if ll is None:
                raise ValueError("%s: footprint vertex off the earth" % g.id)
            world.append(merc_world0(*ll))
        self.world = world
        wx, wy = [p[0] for p in world], [p[1] for p in world]
        self.wbbox = (min(wx), min(wy), max(wx), max(wy))
        self._levels = {}

    def classify_level(self, z):
        """{(x, y): 'inside'|'edge'} for every tile that intersects the footprint, plus the
        list of tiles whose edge-ness flips when the square grows/shrinks by EPS_WORLD"""
        n = 2 ** z
        ts = 256.0 / n
        poly = self.world
        nominal, grown, shrunk = set(), set(), set()
        for i in range(len(poly)):
            p, q = poly[i], poly[(i + 1) % len(poly)]
            ix0 = max(0, int(math.floor((min(p[0], q[0]) - EPS_WORLD) / ts)))
            ix1 = min(n - 1, int(math.floor((max(p[0], q[0]) + EPS_WORLD) / ts)))
            iy0 = max(0, int(math.floor((min(p[1], q[1]) - EPS_WORLD) / ts)))
            iy1 = min(n - 1, int(math.floor((max(p[1], q[1]) + EPS_WORLD) / ts)))
            for ix in range(ix0, ix1 + 1):
                for iy in range(iy0, iy1 + 1):
                    r = (ix * ts, iy * ts, (ix + 1) * ts, (iy + 1) * ts)
                    if seg_hits_rect(p, q, (r[0] - EPS_WORLD, r[1] - EPS_WORLD, r[2] + EPS_WORLD, r[3] + EPS_WORLD)):
                        grown.add((ix, iy))
                        if seg_hits_rect(p, q, r):
                            nominal.add((ix, iy))
                        if seg_hits_rect(p, q, (r[0] + EPS_WORLD, r[1] + EPS_WORLD, r[2] - EPS_WORLD, r[3] - EPS_WORLD)):
                            shrunk.add((ix, iy))
        out = {}
        x0, y0, x1, y1 = self.wbbox
        for ix in range(max(0, int(math.floor(x0 / ts))), min(n - 1, int(math.floor(x1 / ts))) + 1):
            for iy in range(max(0, int(math.floor(y0 / ts))), min(n - 1, int(math.floor(y1 / ts))) + 1):
                if (ix, iy) in nominal:
                    out[(ix, iy)] = "edge"
                elif point_in_poly(((ix + 0.5) * ts, (iy + 0.5) * ts), poly):
                    out[(ix, iy)] = "inside"
        return out, sorted(grown - shrunk)

    def level(self, z):
        if z not in self._levels:
            self._levels[z] = self.classify_level(z)
        return self._levels[z]

    def classify_tile(self, z, x, y):
        cov, _ = self.level(z)
        return cov.get((x, y), "outside")

    def classify_quad(self, quad):
        """warp cell page quad vs the (convex) clip polygon -> (coverage, robust?)"""
        q = list(quad)
        if shoelace(q) < 0:
            q = q[::-1]
        margin = min(convex_signed_dist(v, self.clip, self.ccw) for v in q)
        sep = sat_separation(q, self.clip)
        if margin > EPS_PAGE:
            return "inside", True
        if sep > EPS_PAGE:
            return "outside", True
        if margin < -EPS_PAGE and sep < -EPS_PAGE:
            return "edge", True
        return ("inside" if margin > 0 else "outside" if sep > 0 else "edge"), False


# ----------------------------------------------------------------------------
# zoom policy + base raster
# ----------------------------------------------------------------------------

def merc_metres_per_point(g, c):
    def f(x, y):
        ll = g.to_wgs84(x, y)
        return merc_metres(*ll)
    fxp, fxm = f(c[0] + 1, c[1]), f(c[0] - 1, c[1])
    fyp, fym = f(c[0], c[1] + 1), f(c[0], c[1] - 1)
    J = [[(fxp[0] - fxm[0]) / 2, (fyp[0] - fym[0]) / 2],
         [(fxp[1] - fxm[1]) / 2, (fyp[1] - fym[1]) / 2]]
    det = J[0][0] * J[1][1] - J[0][1] * J[1][0]
    return math.sqrt(abs(det)), J


def px_per_pt(mmpp, z, tile_px):
    return (tile_px / 256.0) * mmpp * (2.0 ** z) / MPP0


def base_plan(fp, budget, detail_zoom, mmpp, problems, tag):
    x0, y0, x1, y1 = fp.bbox
    w, h = x1 - x0, y1 - y0
    s = min(math.sqrt(budget / (w * h)), BASE_MAX_SIDE / max(w, h), BASE_MAX_SCALE)
    exact = s == BASE_MAX_SCALE      # times 4 is exact in binary so an integer product is a real integer
    if not exact and (near_int(w * s) or near_int(h * s)):
        problems.append("%s: base raster size %r x %r is too close to an integer for ceil" % (tag, w * s, h * s))
    W, H = max(1, int(math.ceil(w * s))), max(1, int(math.ceil(h * s)))
    density = min(W / w, H / h)
    mips = [[W, H]]
    while max(mips[-1]) > MIP_STOP:
        mips.append([int(math.ceil(mips[-1][0] / 2)), int(math.ceil(mips[-1][1] / 2))])
    bmz = []
    for tp in TILEPX_BASEMAX:
        best = None
        for z in range(0, detail_zoom + 1):
            p = px_per_pt(mmpp, z, tp)
            if abs(p / density - UPSAMPLE_TOL) < EPS_REL:
                problems.append("%s: pxPerPt(z%d, %d) is a hair off the base density" % (tag, z, tp))
            if p <= density * UPSAMPLE_TOL:
                best = z
        bmz.append({"tilePx": tp, "baseMaxZoom": best})
    return {"budgetPx": budget, "region": [rpt(v) for v in fp.bbox], "w": rpt(w), "h": rpt(h), "s": rsig(s, 15),
            "W": W, "H": H, "densityPxPerPt": rsig(density, 15), "mips": mips, "baseMaxZoom": bmz}


def zoom_policy(sid, g, fp, problems, extra=None):
    mmpp, J = merc_metres_per_point(g, fp.mean)
    raw = math.log2(DETAIL_OVERSAMPLE * MPP0 / mmpp)
    if near_int(raw - DETAIL_HYST, 1e-5):
        problems.append("%s: detailZoomRaw %r sits on the ceil boundary" % (sid, raw))
    dz = int(min(max(math.ceil(raw - DETAIL_HYST), 0), MAX_ZOOM_CAP))
    out = {"sheet": sid}
    if extra:
        out.update(extra)
    out.update({
        "pageBox": [rpt(v) for v in fp.page_box],
        "clipPolygon": [[rpt(x), rpt(y)] for x, y in fp.clip],
        "clipMean": [rpt(fp.mean[0]), rpt(fp.mean[1])],
        "clipArea": rpt(abs(shoelace(fp.clip))),
        "footprintBboxWorld": [rsig(v, 12) for v in fp.wbbox],
        "jacobian": [[rsig(v) for v in row] for row in J],
        "mercMetresPerPoint": rsig(mmpp),
        "detailZoomRaw": rsig(raw),
        "detailZoom": dz,
        "minZoom": 0,
        "maxZoom": dz,
        "pxPerPt": [{"tilePx": tp, "z": z, "value": rsig(px_per_pt(mmpp, z, tp))}
                    for tp in TILEPX_POLICY for z in range(10, 19)],
        "basePlan": {"normal": base_plan(fp, BUDGET_NORMAL, dz, mmpp, problems, sid + " normal"),
                     "lowRam": base_plan(fp, BUDGET_LOW, dz, mmpp, problems, sid + " lowRam")},
    })
    return out, mmpp, dz


# ----------------------------------------------------------------------------
# warp planner (contract D)
# ----------------------------------------------------------------------------

def plan_warp(g, fp, job, tile_px, problems, tag):
    z, x0, y0, cols, rows = job
    n = 2 ** z
    W, H = cols * tile_px, rows * tile_px
    k = tile_px / 256.0
    us = [(X * n - x0 * 256.0) * k for X, _ in fp.world]
    vs = [(Y * n - y0 * 256.0) * k for _, Y in fp.world]
    for v in (min(us), max(us), min(vs), max(vs)):
        if 0 <= v <= max(W, H) and near_int(v, EPS_ROOT):
            problems.append("%s: footprint pixel bbox edge %r is on an integer" % (tag, v))
    l = max(0, int(math.floor(min(us))) - WARP_ROOT_PAD)
    r = min(W, int(math.ceil(max(us))) + WARP_ROOT_PAD)
    t = max(0, int(math.floor(min(vs))) - WARP_ROOT_PAD)
    b = min(H, int(math.ceil(max(vs))) + WARP_ROOT_PAD)
    max_depth = WARP_BASE_DEPTH + int(math.ceil(math.log2(max(cols, rows))))
    if r <= l or b <= t:
        return None, max_depth, [], 0

    cache = {}

    def page(u, v):
        key = (u, v)
        if key not in cache:
            lat, lon = job_px_to_latlon(z, x0, y0, tile_px, u, v)
            cache[key] = g.to_page(lat, lon)
        return cache[key]

    leaves, dropped = [], [0]

    def rec(l, t, r, b, depth):
        w, h = r - l, b - t
        P = {"TL": page(l, t), "TR": page(r, t), "BL": page(l, b), "BR": page(r, b), "C": page((l + r) / 2.0, (t + b) / 2.0)}
        can_split = depth < max_depth and w >= WARP_MIN_CELL and h >= WARP_MIN_CELL
        ok = all(p is not None for p in P.values())
        inv = None
        err = None
        if ok:
            a11 = (P["TR"][0] - P["TL"][0]) / w
            a12 = (P["BL"][0] - P["TL"][0]) / h
            a21 = (P["TR"][1] - P["TL"][1]) / w
            a22 = (P["BL"][1] - P["TL"][1]) / h
            ox = P["TL"][0] - a11 * l - a12 * t
            oy = P["TL"][1] - a21 * l - a22 * t
            det = a11 * a22 - a12 * a21
            if det != 0 and math.isfinite(det):
                ia, ib, id_, ie = a22 / det, -a12 / det, -a21 / det, a11 / det
                inv = [ia, ib, -(ia * ox + ib * oy), id_, ie, -(id_ * ox + ie * oy)]

                def fwd(p):
                    return inv[0] * p[0] + inv[1] * p[1] + inv[2], inv[3] * p[0] + inv[4] * p[1] + inv[5]
                ubr = fwd(P["BR"])
                uc = fwd(P["C"])
                err = max(math.hypot(ubr[0] - r, ubr[1] - b), math.hypot(uc[0] - (l + r) / 2.0, uc[1] - (t + b) / 2.0))
        if inv is None:
            if can_split:
                split(l, t, r, b, depth)
            else:
                dropped[0] += 1
            return
        if can_split and abs(err - WARP_MAX_ERR) < EPS_ERR:
            problems.append("%s: split error %r at rect %r is on the 0.25 bound" % (tag, err, (l, t, r, b)))
        if err > WARP_MAX_ERR and can_split:
            split(l, t, r, b, depth)
            return
        leaves.append({"rect": [l, t, r, b], "pageToPx": inv, "errorPx": err, "depth": depth,
                       "quad": [P["TL"], P["TR"], P["BR"], P["BL"]]})

    def split(l, t, r, b, depth):
        mx = l + (r - l) // 2
        my = t + (b - t) // 2
        for rr in ((l, t, mx, my), (mx, t, r, my), (l, my, mx, b), (mx, my, r, b)):
            rec(rr[0], rr[1], rr[2], rr[3], depth + 1)

    rec(l, t, r, b, 0)
    for lf in leaves:
        cov, robust = fp.classify_quad(lf["quad"])
        if not robust:
            problems.append("%s: cell %r coverage %s is too close to call" % (tag, lf["rect"], cov))
        lf["coverage"] = cov
    return [l, t, r, b], max_depth, leaves, dropped[0]


def warp_entry(sid, g, fp, job, tile_px, kind_hint, problems):
    z, x0, y0, cols, rows = job
    tag = "%s z%d %d/%d %dx%d @%d" % (sid, z, x0, y0, cols, rows, tile_px)
    tiles = [fp.classify_tile(z, x, y) for y in range(y0, y0 + rows) for x in range(x0, x0 + cols)]
    if all(c == "inside" for c in tiles):
        kind = "inside"
    elif all(c == "outside" for c in tiles):
        kind = "outside"
    else:
        kind = "clipped"
    root, max_depth, leaves, dropped = plan_warp(g, fp, job, tile_px, problems, tag)
    emitted = [lf for lf in leaves if lf["coverage"] != "outside"]
    max_err = max((lf["errorPx"] for lf in emitted), default=0.0)
    # at a split limit a leaf can keep err > 0.25, record it instead of pretending
    bound_met = all(lf["errorPx"] <= WARP_MAX_ERR for lf in emitted)
    required = 0.0
    for lf in emitted:
        a, b_, _, d, e, _ = lf["pageToPx"]
        required = max(required, math.hypot(a, d), math.hypot(b_, e))
    out = {"sheet": sid, "label": kind_hint, "job": {"z": z, "x0": x0, "y0": y0, "cols": cols, "rows": rows},
           "tilePx": tile_px, "kind": kind, "tileCoverage": tiles, "maxDepth": max_depth, "root": root,
           "leafCount": len(leaves), "cellCount": len(emitted),
           "insideCells": sum(1 for lf in emitted if lf["coverage"] == "inside"),
           "edgeCells": sum(1 for lf in emitted if lf["coverage"] == "edge"),
           "outsideLeaves": len(leaves) - len(emitted), "droppedCells": dropped,
           "maxErrorPx": round(max_err, 6), "errorBoundMet": bound_met,
           "requiredPxPerPt": rsig(required, 12)}
    if kind == "inside":
        out["cells"] = [{"rect": lf["rect"], "pageToPx": [rsig(v, 12) for v in lf["pageToPx"]],
                         "errorPx": round(lf["errorPx"], 6), "coverage": lf["coverage"],
                         "pageTL": [rpt(v) for v in lf["quad"][0]], "pageTR": [rpt(v) for v in lf["quad"][1]],
                         "pageBR": [rpt(v) for v in lf["quad"][2]], "pageBL": [rpt(v) for v in lf["quad"][3]]}
                        for lf in leaves]
    return out


def anchor_tile(g, p, z):
    lat, lon = g.to_wgs84(*p)
    X, Y = merc_world0(lat, lon)
    n = 2 ** z
    return int(math.floor(X * n / 256.0)), int(math.floor(Y * n / 256.0))


# one made up small-scale sheet, inline in the fixture, only for warp vectors. the real sheets are
# all <= 20 km so the planner never gets past depth 2 on them. 1:1M TM, ~212 x 282 km, centred 200 km
# east of the zone 10 CM so it reaches ~3.9 deg off it (same range WP1 already pins for TM)
WIDE_SCALE = 1e6 * 0.0254 / 72.0
WIDE_ROT = math.radians(2.0)
WIDE = {"id": "wide_tm_1m", "crs": UTM10N, "datum": {"id": "WGS84"},
        "mediaBox": [0.0, 0.0, 700.0, 900.0], "cropBox": None, "rotate": 0,
        "crop": [[50.0, 50.0], [50.0, 850.0], [650.0, 850.0], [650.0, 50.0]],
        "affine": [rsig(v, 12) for v in (
            WIDE_SCALE * math.cos(WIDE_ROT), -WIDE_SCALE * math.sin(WIDE_ROT),
            700000.0 - WIDE_SCALE * (math.cos(WIDE_ROT) * 350.0 - math.sin(WIDE_ROT) * 450.0),
            WIDE_SCALE * math.sin(WIDE_ROT), WIDE_SCALE * math.cos(WIDE_ROT),
            5000000.0 - WIDE_SCALE * (math.sin(WIDE_ROT) * 350.0 + math.cos(WIDE_ROT) * 450.0))]}


def wide_warp(datums, problems):
    g = Georef(WIDE["id"], WIDE["crs"], WIDE["datum"], WIDE["affine"], WIDE["crop"], datums)
    fp = Footprint(g, tuple(WIDE["mediaBox"]))
    out = []
    c = fp.mean
    jobs = [((z, ) + anchor_tile(g, c, z) + (1, 1), 768, "centre") for z in range(3, 11)]
    jobs += [((z, ) + anchor_tile(g, c, z) + (1, 1), tp, "centre") for tp in (512, 672) for z in (6, 8)]
    for z in (7, 8, 9):
        tx, ty = anchor_tile(g, c, z)
        jobs.append(((z, tx - 1, ty, 3, 2), 768, "heavy block"))
    for z in (7, 9):
        tx, ty = anchor_tile(g, fp.clip[0], z)
        jobs.append(((z, tx, ty, 1, 1), 768, "clip vertex 0"))
        jobs.append(((z, tx - 1, ty - 1, 3, 2), 768, "clip vertex 0 block"))
    for job, tp, label in jobs:
        out.append(warp_entry(WIDE["id"], g, fp, job, tp, label, problems))
    return out


def warp_section(sheets, problems):
    out = []
    for sid, g, fp, dz in sheets:
        jobs = []
        c = fp.mean
        for z in range(6, dz + 1):
            tx, ty = anchor_tile(g, c, z)
            jobs.append(((z, tx, ty, 1, 1), 768, "centre"))
        for tp in (512, 672):
            for z in sorted({12, 14, dz}):
                tx, ty = anchor_tile(g, c, z)
                jobs.append(((z, tx, ty, 1, 1), tp, "centre"))
        for z in sorted({dz - 2, dz - 1}):
            tx, ty = anchor_tile(g, c, z)
            jobs.append(((z, tx - 1, ty, 3, 2), 768, "heavy block"))
        tx, ty = anchor_tile(g, c, dz)
        jobs.append(((dz, tx - 1, ty - 1, 3, 2), 512, "heavy block"))
        for z in sorted({14, dz}):
            tx, ty = anchor_tile(g, fp.clip[0], z)
            jobs.append(((z, tx, ty, 1, 1), 768, "clip vertex 0"))
        tx, ty = anchor_tile(g, fp.clip[0], dz - 1)
        jobs.append(((dz - 1, tx - 1, ty - 1, 3, 2), 768, "clip vertex 0 block"))
        z = 14
        cx, cy = anchor_tile(g, c, z)
        n = 2 ** z
        span = int(math.ceil((fp.wbbox[2] - fp.wbbox[0]) * n / 256.0)) + 3
        jobs.append(((z, cx + span, cy, 1, 1), 768, "off sheet"))
        for job, tp, label in jobs:
            out.append(warp_entry(sid, g, fp, job, tp, label, problems))
    return out


# ----------------------------------------------------------------------------
# coverage + bake options
# ----------------------------------------------------------------------------

def runs_of(tiles):
    rows = {}
    for x, y in tiles:
        rows.setdefault(y, []).append(x)
    out = []
    for y in sorted(rows):
        xs = sorted(rows[y])
        start = prev = xs[0]
        for x in xs[1:]:
            if x != prev + 1:
                out.append([y, start, prev])
                start = x
            prev = x
        out.append([y, start, prev])
    return out


def tile_list_sha(z, tiles):
    s = "\n".join("%d/%d/%d" % (z, x, y) for x, y in tiles)
    return hashlib.sha256(s.encode("ascii")).hexdigest()


def coverage_section(sheets, problems):
    out, counts = [], {}
    for sid, g, fp, dz in sheets:
        levels = []
        counts[sid] = {}
        for z in range(0, dz + 1):
            cov, border = fp.level(z)
            if border:
                problems.append("%s z%d: tiles %r are within %g world units of the footprint" % (sid, z, border[:5], EPS_WORLD))
            tiles = sorted(cov, key=lambda t: (t[1], t[0]))
            inside = [t for t in tiles if cov[t] == "inside"]
            counts[sid][z] = len(tiles)
            lv = {"z": z, "count": len(tiles), "insideCount": len(inside), "edgeCount": len(tiles) - len(inside),
                  "sha256": tile_list_sha(z, tiles), "runs": runs_of(tiles), "insideRuns": runs_of(inside) if inside else []}
            if z <= 14:
                lv["tiles"] = [[x, y] for x, y in tiles]
            levels.append(lv)
        out.append({"sheet": sid, "levels": levels})
    return out, counts


def bake_options(sid, dz, counts):
    cands = sorted({min(max(m, 0), 22) for m in (dz - 2, dz - 1, dz)})
    opts = []
    for m in cands:
        tiles = sum(counts[z] for z in range(0, m + 1))
        opts.append({"maxZoom": m, "tiles": tiles, "kept": tiles <= BAKE_MAX_TILES})
    kept = [o["maxZoom"] for o in opts if o["kept"]]
    default = (dz - 1) if (dz - 1) in kept else (max(kept) if kept else None)
    return {"sheet": sid, "detailZoom": dz, "candidates": opts, "options": kept, "default": default,
            "tooLarge": not kept}


# ----------------------------------------------------------------------------
# job formation (contract E)
# ----------------------------------------------------------------------------

def grow_job(seed, pending):
    """heavy job: round robin right, down, left, up, until a whole pass adds nothing.
    an addition needs every tile to be a pending VISIBLE tile at seed z, no world wrap"""
    z, sx, sy = seed
    n = 2 ** z
    vis = {(t["x"], t["y"]) for t in pending if t["z"] == z and t["band"] == "visible"}
    x0, y0, cols, rows = sx, sy, 1, 1

    def ok(tiles, nc, nr):
        return nc <= JOB_MAX_SIDE and nr <= JOB_MAX_SIDE and nc * nr <= JOB_MAX_TILES and all(t in vis for t in tiles)

    grew = True
    while grew:
        grew = False
        if x0 + cols < n and ok([(x0 + cols, y) for y in range(y0, y0 + rows)], cols + 1, rows):
            cols += 1
            grew = True
        if y0 + rows < n and ok([(x, y0 + rows) for x in range(x0, x0 + cols)], cols, rows + 1):
            rows += 1
            grew = True
        if x0 > 0 and ok([(x0 - 1, y) for y in range(y0, y0 + rows)], cols + 1, rows):
            x0 -= 1
            cols += 1
            grew = True
        if y0 > 0 and ok([(x, y0 - 1) for x in range(x0, x0 + cols)], cols, rows + 1):
            y0 -= 1
            rows += 1
            grew = True
    return {"z": z, "x0": x0, "y0": y0, "cols": cols, "rows": rows}


def bake_block(t, heavy):
    z, x, y = t
    if not heavy:
        return {"z": z, "x0": x, "y0": y, "cols": 1, "rows": 1}
    n = 2 ** z
    bx, by = BAKE_BLOCK
    x0, y0 = (x // bx) * bx, (y // by) * by
    return {"z": z, "x0": x0, "y0": y0, "cols": min(bx, n - x0), "rows": min(by, n - y0)}


def pend(z, xys, band="visible"):
    return [{"z": z, "x": x, "y": y, "band": band} for x, y in xys]


def job_formation_section():
    Z = 16
    bx, by = 10480, 25330
    grid = lambda xs, ys: [(x, y) for y in ys for x in xs]
    cases = [
        ("light is always 1x1", (Z, bx, by), pend(Z, grid(range(bx - 2, bx + 3), range(by - 2, by + 3))), False),
        ("heavy, everything pending: right, down, left then stop at 6", (Z, bx, by),
         pend(Z, grid(range(bx - 2, bx + 3), range(by - 2, by + 3))), True),
        ("heavy, only a strip to the right: second pass takes the 3rd column", (Z, bx, by),
         pend(Z, [(bx, by), (bx + 1, by), (bx + 2, by), (bx + 3, by)]), True),
        ("heavy, column strip down: rows grow to 3", (Z, bx, by),
         pend(Z, [(bx, by + i) for i in range(5)]), True),
        ("heavy, right blocked so it grows down/left/up into 2x3", (Z, bx, by),
         pend(Z, grid(range(bx - 1, bx + 1), range(by - 2, by + 3))), True),
        ("heavy, a hole in the row below stops down for good", (Z, bx, by),
         pend(Z, [(bx, by), (bx + 1, by), (bx + 2, by), (bx, by + 1), (bx + 2, by + 1)]), True),
        ("heavy, fallback band and other zooms dont count", (Z, bx, by),
         pend(Z, [(bx, by)]) + pend(Z, [(bx + 1, by)], "fallback") + pend(Z - 1, [(bx // 2, by // 2)]) +
         pend(Z, [(bx, by + 1)], "bake"), True),
        ("heavy, east world edge, no wrap", (4, 15, 7), pend(4, grid([0, 13, 14, 15], [6, 7, 8])), True),
        ("heavy, north world edge", (3, 2, 0), pend(3, grid([1, 2, 3], [0, 1])), True),
        ("heavy, single pending tile", (Z, bx, by), pend(Z, [(bx, by)]), True),
    ]
    out = []
    for label, seed, pending, heavy in cases:
        job = grow_job(seed, pending) if heavy else {"z": seed[0], "x0": seed[1], "y0": seed[2], "cols": 1, "rows": 1}
        out.append({"label": label, "mode": "live", "heavy": heavy, "seed": list(seed), "pending": pending, "job": job})
    for t, heavy in [((16, 10480, 25330), True), ((16, 10482, 25331), True), ((16, 10483, 25331), True),
                     ((16, 10480, 25330), False), ((0, 0, 0), True), ((1, 1, 1), True), ((2, 3, 2), True),
                     ((3, 7, 7), True), ((14, 2620, 6333), True)]:
        out.append({"label": "bake block", "mode": "bake", "heavy": heavy, "tile": list(t), "job": bake_block(t, heavy)})
    return out


# ----------------------------------------------------------------------------
# bake job formation per level (amendment 2026-10-02, E2)
# ----------------------------------------------------------------------------

def bake_level_jobs(z, tiles, base_max_zoom, heavy_at_start):
    """tiles row major (y then x). raster sampled levels are always 1x1, above that heavy is
    read once when the level starts. jobs come out in first encounter order"""
    raster = base_max_zoom is not None and z <= base_max_zoom
    heavy = (not raster) and heavy_at_start
    wanted = set(tiles)
    jobs, seen = [], set()
    for x, y in tiles:
        b = bake_block((z, x, y), heavy)
        k = (b["x0"], b["y0"], b["cols"], b["rows"])
        if k in seen:
            continue
        seen.add(k)
        n = sum(1 for xx in range(b["x0"], b["x0"] + b["cols"]) for yy in range(b["y0"], b["y0"] + b["rows"])
                if (xx, yy) in wanted)
        jobs.append(dict(b, wanted=n))
    return jobs, ("raster" if raster else "vector"), heavy


def bake_job_formation_section():
    def rect(xs, ys):
        return [(x, y) for y in ys for x in xs]
    cases = [
        ("raster sampled levels stay 1x1 even when heavy", 12, [
            (11, rect(range(326, 328), range(791, 793)), True, True),
            (12, rect(range(654, 657), range(1582, 1584)), True, True)]),
        ("heavy above baseMaxZoom: aligned 3x2 blocks in first encounter order", 12, [
            (13, rect(range(1307, 1311), range(3165, 3168)), True, True)]),
        ("light above baseMaxZoom is 1x1", 12, [
            (13, rect(range(1307, 1310), range(3165, 3167)), False, False)]),
        ("heavy is read once per level, a flip mid level waits for the next level", 12, [
            (13, rect(range(1308, 1311), range(3166, 3168)), False, True),
            (14, rect(range(2616, 2619), range(6332, 6334)), True, False),
            (15, rect(range(5232, 5235), range(12664, 12666)), False, False)]),
        ("ragged level: only intersecting tiles are wanted, a block can hold just one", 12, [
            (14, [(2617, 6331), (2616, 6332), (2617, 6332), (2618, 6332), (2619, 6332), (2620, 6333)], True, True)]),
        ("world edge: blocks are clamped, baseMaxZoom 0", 0, [
            (0, [(0, 0)], True, True),
            (1, rect(range(0, 2), range(0, 2)), True, True),
            (2, rect(range(0, 4), range(0, 4)), True, True)]),
        ("no base raster (baseMaxZoom null): every level is vector", None, [
            (0, [(0, 0)], True, True),
            (1, [(1, 0), (1, 1)], True, True)]),
    ]
    out = []
    for label, bmz, levels in cases:
        lv = []
        for z, tiles, h0, h1 in levels:
            tiles = sorted(tiles, key=lambda t: (t[1], t[0]))
            jobs, path, heavy = bake_level_jobs(z, tiles, bmz, h0)
            lv.append({"z": z, "tiles": [[x, y] for x, y in tiles], "heavyAtLevelStart": h0,
                       "heavyAfterFirstJob": h1, "path": path, "heavyUsed": heavy, "jobs": jobs})
        out.append({"label": label, "baseMaxZoom": bmz, "levels": lv})
    return out


# ----------------------------------------------------------------------------
# draw plan (contract B fallback)
# ----------------------------------------------------------------------------

def ancestor(t, k):
    z, x, y = t
    return (z - k, x >> k, y >> k)


def plan_draw(visible, state, fz):
    """visible: [(z,x,y,col,row)] in visible order. state: {(z,x,y): 'image'|'empty'}"""
    st = lambda t: state.get(t, "missing")
    anc, child, own = [], [], []
    req_fb, req_vis = [], []
    for vi, (z, x, y, col, row) in enumerate(visible):
        t = (z, x, y)
        dest = {"z": z, "x": x, "y": y, "col": col, "row": row}
        s = st(t)
        if s == "image":
            own.append({"kind": "own", "source": list(t), "dest": dest, "unitRect": [0.0, 0.0, 1.0, 1.0],
                        "destRect": [0.0, 0.0, 1.0, 1.0]})
            continue
        if s == "empty":
            continue
        req_vis.append(list(t))
        use_fz = fz is not None and 0 <= fz < z
        if use_fz:
            fa = ancestor(t, z - fz)
            if st(fa) == "missing" and list(fa) not in req_fb:
                req_fb.append(list(fa))
        max_up = max(MAX_ANCESTOR_LEVELS, z - fz) if use_fz else MAX_ANCESTOR_LEVELS
        found = False
        for k in range(1, max_up + 1):
            if z - k < 0:
                break
            a = ancestor(t, k)
            sa = st(a)
            if sa == "image":
                m = 2 ** k
                anc.append((a[0], vi, {"kind": "ancestor", "source": list(a), "dest": dest,
                                       "unitRect": [(x % m) / m, (y % m) / m, 1.0 / m, 1.0 / m],
                                       "destRect": [0.0, 0.0, 1.0, 1.0]}))
                found = True
                break
            if sa == "empty":
                found = True
                break
        if found:
            continue
        for dy in (0, 1):
            for dx in (0, 1):
                c = (z + 1, 2 * x + dx, 2 * y + dy)
                if st(c) == "image":
                    child.append({"kind": "child", "source": list(c), "dest": dest, "unitRect": [0.0, 0.0, 1.0, 1.0],
                                  "destRect": [dx / 2.0, dy / 2.0, 0.5, 0.5]})
    items = [it for _, _, it in sorted(anc, key=lambda e: (e[0], e[1]))] + child + own
    for i, it in enumerate(items):
        it["order"] = i
    return items, req_fb + req_vis


def draw_plan_section():
    def vis_grid(z, x0, y0, cols, rows, col0=0, row0=0):
        n = 2 ** z
        out = []
        cx, cy = (cols - 1) / 2.0, (rows - 1) / 2.0
        cells = [(i, j) for j in range(rows) for i in range(cols)]
        # centre distance order, ties by row then col so the list is deterministic
        cells.sort(key=lambda c: ((c[0] - cx) ** 2 + (c[1] - cy) ** 2, c[1], c[0]))
        for i, j in cells:
            out.append((z, (x0 + i) % n, y0 + j, col0 + i, row0 + j))
        return out

    Z, X, Y = 15, 5240, 12664
    v3 = vis_grid(Z, X, Y, 3, 3, col0=X, row0=Y)
    allimg = lambda vis: {(z, x, y): "image" for z, x, y, _, _ in vis}
    centre = (Z, X + 1, Y + 1)
    cases = []

    s = allimg(v3)
    cases.append(("everything loaded", v3, s, None))

    s = allimg(v3)
    del s[centre]
    s[ancestor(centre, 1)] = "image"
    cases.append(("centre missing, parent loaded", v3, s, None))

    s = dict(s)
    cases.append(("centre missing, parent loaded, base ancestor at fz 12 requested first", v3, s, 12))

    s = allimg(v3)
    del s[centre]
    s[ancestor(centre, 1)] = "image"
    s[ancestor(centre, 3)] = "image"
    cases.append(("fz ancestor already loaded is not requested again", v3, s, 12))

    s = allimg(v3)
    del s[centre]
    s[ancestor(centre, 6)] = "image"
    s[(Z + 1, 2 * centre[1], 2 * centre[2])] = "image"
    s[(Z + 1, 2 * centre[1] + 1, 2 * centre[2] + 1)] = "image"
    s[(Z + 1, 2 * centre[1] + 1, 2 * centre[2])] = "empty"
    cases.append(("6 levels up is past the 4 level search, loaded children fill in", v3, s, None))
    cases.append(("same but fz 9 makes the search 6 deep so the ancestor wins", v3, dict(s), 9))

    # 3x3 at even x/y: the top-left 2x2 share parent (14, X/2, Y/2), so pick tiles whose ancestors differ
    s = allimg(v3)
    del s[centre]                                   # parent EMPTY: search stops, its loaded child is ignored
    s[ancestor(centre, 1)] = "empty"
    s[(Z + 1, 2 * centre[1], 2 * centre[2])] = "image"
    del s[(Z, X, Y + 2)]                            # parent (14, X/2, (Y+2)/2) loaded -> z14 item
    s[ancestor((Z, X, Y + 2), 1)] = "image"
    del s[(Z, X + 2, Y + 2)]                        # later in visible order but its z12 ancestor draws first
    s[ancestor((Z, X + 2, Y + 2), 3)] = "image"
    s[(Z, X + 2, Y)] = "empty"                      # EMPTY own tile: no item, no request
    cases.append(("mixed: EMPTY own, EMPTY parent stops the search, coarse ancestor drawn first", v3, s, 13))

    v_none = vis_grid(Z, X, Y, 2, 2, col0=X, row0=Y)
    cases.append(("nothing cached, fz shared by all four is requested once", v_none, {}, 12))
    cases.append(("nothing cached, no fz", v_none, {}, None))

    # antimeridian: unwrapped cols -1..1 at z3 -> x 7, 0, 1
    v_am = vis_grid(3, 7, 3, 3, 1, col0=-1, row0=3)
    s = {(3, 0, 3): "image", (2, 3, 1): "image", (1, 0, 0): "image"}
    cases.append(("antimeridian: wrapped x uses its own ancestor quadrant", v_am, s, None))

    v_z1 = vis_grid(1, 0, 0, 2, 1, col0=0, row0=0)
    cases.append(("tz 1, fz 0", v_z1, {(1, 1, 0): "image"}, 0))
    v_z0 = [(0, 0, 0, 0, 0)]
    cases.append(("tz 0, fz -1 means none", v_z0, {(1, 1, 1): "image"}, -1))

    out = []
    for label, vis, state, fz in cases:
        items, reqs = plan_draw(vis, state, fz)
        out.append({"label": label,
                    "visible": [{"z": z, "x": x, "y": y, "col": c, "row": r} for z, x, y, c, r in vis],
                    "cache": [{"tile": list(t), "state": v} for t, v in sorted(state.items())],
                    "fallbackZoom": fz, "items": items, "requests": reqs})
    return out


# ----------------------------------------------------------------------------
# crash guard reducer (contract I)
# ----------------------------------------------------------------------------

KINDS_ORDER = ["base", "vector"]


class Guard:
    def __init__(self, initial=None):
        self.in_progress = None
        self.bake = None       # own slot since amendment 2026-10-02 (K1), {"token": uuid}
        self.suspect = None
        self.verified = []     # [(token, [kinds])], oldest first
        if initial:
            # a file from an older build, read as is (bakeInProgress may be missing)
            self.in_progress = initial.get("inProgress")
            self.bake = initial.get("bakeInProgress")
            self.suspect = initial.get("suspect")
            self.verified = [(t, list(k)) for t, k in initial.get("verifiedOrdered", [])]

    def _verified_kinds(self, tok):
        for t, k in self.verified:
            if t == tok:
                return k
        return []

    def _verify(self, tok, kind):
        kinds = self._verified_kinds(tok)
        self.verified = [(t, k) for t, k in self.verified if t != tok]
        kinds = [k for k in KINDS_ORDER if k in kinds or k == kind]
        self.verified.append((tok, kinds))
        while len(self.verified) > GUARD_MAX_TOKENS:
            self.verified.pop(0)

    def state(self):
        return {"v": 1, "inProgress": self.in_progress, "bakeInProgress": self.bake, "suspect": self.suspect,
                "verified": {t: k for t, k in self.verified}}

    def step(self, ev):
        op = ev["op"]
        if op == "arm":
            kind, tok = ev["kind"], ev["token"]
            if kind == "bake":
                # own slot, nothing else ever overwrites or clears it
                self.bake = {"token": tok}
                return {"armed": True}
            if kind == "import":
                ip = {"kind": kind, "token": tok}
                if ev.get("opKey"):
                    ip["op"] = ev["opKey"]
                self.in_progress = ip
                return {"armed": True}
            if not ev.get("foreground", True) or kind in self._verified_kinds(tok) or self.in_progress is not None:
                return {"armed": False}
            self.in_progress = {"kind": kind, "token": tok}
            return {"armed": True}
        if op == "complete":
            kind, tok = ev["kind"], ev["token"]
            if kind == "bake":
                if self.bake and self.bake["token"] == tok:
                    self.bake = None
                return {}
            if kind == "import":
                self._verify(tok, "base")
            elif kind in ("base", "vector"):
                self._verify(tok, kind)
            ip = self.in_progress
            if ip and ip["kind"] == kind and ip["token"] == tok:
                self.in_progress = None
            return {}
        if op == "disarmBackground":
            if self.in_progress and self.in_progress["kind"] in ("base", "vector"):
                self.in_progress = None
            return {}
        if op == "launch":
            rt = ev.get("restoredToken")
            ip = self.in_progress
            # the bake notice is separate from the decision, both can fire on one launch.
            # a legacy inProgress.kind == bake still counts as an interrupted bake
            baked = self.bake is not None or bool(ip and ip["kind"] == "bake")
            res = {"decision": "none"}
            if ip and ip["kind"] == "import":
                res = {"decision": "importInterrupted"}
                if ip.get("op"):
                    res["op"] = ip["op"]
            elif ip and ip["kind"] in ("base", "vector") and rt is not None and ip["token"] == rt:
                self.suspect = rt
                res = {"decision": "suppress"}
            elif rt is not None and self.suspect == rt:
                res = {"decision": "suppress"}
            res["bakeInterrupted"] = baked
            self.in_progress = None
            self.bake = None
            return res
        if op == "resolve":
            if ev["choice"] in ("openAnyway", "deleted"):
                self.suspect = None
            return {}
        raise ValueError(op)


def tok(i):
    return "00000000-0000-4000-8000-%012d" % i


def crash_guard_section():
    A, B, C = tok(1), tok(2), tok(3)
    cases = [
        ("import ok, then a clean launch", [
            {"op": "arm", "kind": "import", "token": A, "opKey": "import-op-1"},
            {"op": "complete", "kind": "import", "token": A},
            {"op": "launch", "restoredToken": A}]),
        ("crash during the import probe", [
            {"op": "arm", "kind": "import", "token": A, "opKey": "import-op-1"},
            {"op": "launch", "restoredToken": None},
            {"op": "launch", "restoredToken": None}]),
        ("first vector render crashes, Not Now keeps the suspect, Open Anyway clears it", [
            {"op": "arm", "kind": "import", "token": A},
            {"op": "complete", "kind": "import", "token": A},
            {"op": "arm", "kind": "base", "token": A, "foreground": True},
            {"op": "arm", "kind": "vector", "token": A, "foreground": True},
            {"op": "launch", "restoredToken": A},
            {"op": "resolve", "choice": "notNow"},
            {"op": "launch", "restoredToken": A},
            {"op": "resolve", "choice": "openAnyway"},
            {"op": "arm", "kind": "vector", "token": A, "foreground": True},
            {"op": "complete", "kind": "vector", "token": A},
            {"op": "launch", "restoredToken": A},
            {"op": "arm", "kind": "vector", "token": A, "foreground": True}]),
        ("Delete Map clears the suspect", [
            {"op": "arm", "kind": "vector", "token": A, "foreground": True},
            {"op": "launch", "restoredToken": A},
            {"op": "resolve", "choice": "deleted"},
            {"op": "launch", "restoredToken": None}]),
        ("background never arms, entering background disarms without verifying", [
            {"op": "arm", "kind": "base", "token": A, "foreground": False},
            {"op": "arm", "kind": "base", "token": A, "foreground": True},
            {"op": "disarmBackground"},
            {"op": "launch", "restoredToken": A},
            {"op": "arm", "kind": "base", "token": A, "foreground": True}]),
        ("crash in a render of another map does not suspect the restored one", [
            {"op": "arm", "kind": "vector", "token": B, "foreground": True},
            {"op": "launch", "restoredToken": A},
            {"op": "launch", "restoredToken": B}]),
        ("bake always arms, a bake crash is reported and nothing is suppressed", [
            {"op": "arm", "kind": "import", "token": A},
            {"op": "complete", "kind": "import", "token": A},
            {"op": "arm", "kind": "vector", "token": A, "foreground": True},
            {"op": "complete", "kind": "vector", "token": A},
            {"op": "arm", "kind": "bake", "token": A},
            {"op": "disarmBackground"},
            {"op": "launch", "restoredToken": A}]),
        ("a finished bake clears its marker and verifies nothing", [
            {"op": "arm", "kind": "bake", "token": A},
            {"op": "complete", "kind": "bake", "token": A},
            {"op": "launch", "restoredToken": A}]),
        ("base/vector arm next to an in-flight bake marker and never touch it", [
            {"op": "arm", "kind": "bake", "token": A},
            {"op": "arm", "kind": "vector", "token": A, "foreground": True},
            {"op": "complete", "kind": "vector", "token": A},
            {"op": "launch", "restoredToken": A}]),
        ("import replaces a base/vector marker, complete only clears its own marker", [
            {"op": "arm", "kind": "vector", "token": A, "foreground": True},
            {"op": "arm", "kind": "import", "token": B, "opKey": "import-op-7"},
            {"op": "complete", "kind": "vector", "token": A},
            {"op": "launch", "restoredToken": A}]),
        ("table order: an interrupted import wins over a standing suspect", [
            {"op": "arm", "kind": "vector", "token": A, "foreground": True},
            {"op": "launch", "restoredToken": A},
            {"op": "arm", "kind": "import", "token": C, "opKey": "import-op-9"},
            {"op": "launch", "restoredToken": A},
            {"op": "launch", "restoredToken": A}]),
    ]
    lru = [{"op": "complete", "kind": "import", "token": tok(100 + i)} for i in range(16)]
    lru += [{"op": "complete", "kind": "vector", "token": tok(100)},
            {"op": "complete", "kind": "import", "token": tok(116)},
            {"op": "complete", "kind": "import", "token": tok(117)},
            {"op": "arm", "kind": "base", "token": tok(101), "foreground": True},
            {"op": "arm", "kind": "base", "token": tok(100), "foreground": True}]
    cases.append(("LRU: 16 tokens max, verifying touches, oldest goes first", lru))
    # amendment 2026-10-02 (K1): the bake marker has its own slot
    cases += [
        ("import during a bake: the bake marker survives, a later crash still says bakeInterrupted", [
            {"op": "arm", "kind": "bake", "token": A},
            {"op": "arm", "kind": "import", "token": B, "opKey": "import-op-3"},
            {"op": "complete", "kind": "import", "token": B},
            {"op": "launch", "restoredToken": A},
            {"op": "launch", "restoredToken": A}]),
        ("crash in an import probe while a bake runs: both notices", [
            {"op": "arm", "kind": "bake", "token": A},
            {"op": "arm", "kind": "import", "token": B, "opKey": "import-op-4"},
            {"op": "launch", "restoredToken": A}]),
        ("crash in a first vector render during a bake: suppress and bakeInterrupted", [
            {"op": "arm", "kind": "bake", "token": A},
            {"op": "arm", "kind": "vector", "token": B, "foreground": True},
            {"op": "launch", "restoredToken": B},
            {"op": "launch", "restoredToken": B}]),
        ("complete(bake) only clears its own token, a second bake arm replaces the slot", [
            {"op": "arm", "kind": "bake", "token": A},
            {"op": "complete", "kind": "bake", "token": B},
            {"op": "arm", "kind": "bake", "token": B},
            {"op": "complete", "kind": "bake", "token": A},
            {"op": "complete", "kind": "bake", "token": B},
            {"op": "launch", "restoredToken": A}]),
        ("a standing suspect is still suppressed when the bake was interrupted too", [
            {"op": "arm", "kind": "vector", "token": A, "foreground": True},
            {"op": "launch", "restoredToken": A},
            {"op": "resolve", "choice": "notNow"},
            {"op": "arm", "kind": "bake", "token": A},
            {"op": "launch", "restoredToken": A}]),
        ("base/vector still never replace an in-flight import marker", [
            {"op": "arm", "kind": "import", "token": B, "opKey": "import-op-5"},
            {"op": "arm", "kind": "vector", "token": A, "foreground": True},
            {"op": "launch", "restoredToken": A}]),
    ]
    legacy = [
        ("legacy file: a bake marker in inProgress reads as an interrupted bake",
         {"v": 1, "inProgress": {"kind": "bake", "token": A}, "suspect": None, "verified": {A: ["base"]}},
         [(A, ["base"])],
         [{"op": "launch", "restoredToken": A}]),
    ]
    out = []
    for label, events in cases:
        g = Guard()
        steps = []
        for ev in events:
            res = g.step(ev)
            st = g.state()
            steps.append({"event": ev, "result": res, "state": st, "verifiedOrder": [t for t, _ in g.verified]})
        out.append({"label": label, "steps": steps})
    for label, file_state, order, events in legacy:
        g = Guard(dict(file_state, verifiedOrdered=order))
        steps = []
        for ev in events:
            res = g.step(ev)
            steps.append({"event": ev, "result": res, "state": g.state(), "verifiedOrder": [t for t, _ in g.verified]})
        out.append({"label": label, "initialState": file_state, "initialVerifiedOrder": [t for t, _ in order],
                    "steps": steps})
    return out


# ----------------------------------------------------------------------------
# failure accounting (amendment 2026-10-02, G2)
# ----------------------------------------------------------------------------

STICKY_NOW = ("cannotOpen", "passwordProtected", "pageMissing", "pageGeometry")


class FailureRun:
    def __init__(self):
        self.failed = None
        self.run = 0

    def step(self, ev):
        op = ev["op"]
        if op == "retry":
            self.failed, self.run = None, 0
            return
        if self.failed is not None:
            return                     # sticky, only Try Again gets out
        if op == "documentFailure":
            self._stick(ev["reason"])
        elif op == "blankVerdict":
            self._stick("blank")
        elif op == "jobOk":
            self.run = 0
        elif op == "jobFailed":
            r = ev["reason"]
            if r in STICKY_NOW:
                self._stick(r)
                return
            self.run += 1
            if self.run >= RENDER_ERROR_RUN:
                self._stick("outOfMemory" if r == "outOfMemory" else "renderError")
        elif op in ("jobCancelled", "bakeJobOk", "bakeJobFailed"):
            pass
        else:
            raise ValueError(op)

    def _stick(self, reason):
        self.failed, self.run = reason, 0

    def state(self):
        return {"failed": self.failed, "consecutiveFailures": self.run}


class BaseLifecycle:
    """R1 (amendment 2026-10-02 r1): when the live source starts a base raster attempt.
    first non-ignored wanted callback (or the platforms init kick, same thing) starts
    attempt 1 at any z. after that only a failed base gets re-attempted, and only when a
    tile at z <= baseMaxZoom is wanted and nothing is in flight. results go through G2"""

    def __init__(self, fr, bmz):
        self.fr, self.bmz = fr, bmz
        self.status, self.attempts = "notStarted", 0

    def step(self, ev):
        """-> (startsBaseAttempt, replan)"""
        op = ev["op"]
        if op == "wanted":
            if self.fr.failed is not None:
                return False, False
            go = self.status == "notStarted" or (self.status == "failed" and ev["z"] <= self.bmz)
            if go:
                self.status, self.attempts = "inFlight", self.attempts + 1
            return go, False
        if op == "baseDone":
            if self.status != "inFlight":
                raise ValueError("baseDone with nothing in flight")
            r = ev["result"]
            if r == "ok":
                self.status = "ready"
                self.fr.step({"op": "jobOk", "path": "baseRaster"})
                return False, True
            if r == "blank":
                self.status = "blank"
                self.fr.step({"op": "blankVerdict"})
                return False, False
            self.status = "failed"
            self.fr.step({"op": "jobFailed", "path": "baseRaster", "reason": r})
            return False, False
        self.fr.step(ev)
        return False, False

    def state(self):
        return {"status": self.status, "attempts": self.attempts}


def feeds_ewma(ev):
    """E1 + R2: a started vector/staged job that delivered at least one image feeds the EWMA,
    waiters or not. failed, cancelled-before-start, raster and all-EMPTY jobs dont"""
    return (ev["op"] == "jobOk" and ev["path"] in ("vector", "staged") and not ev.get("allEmpty", False))


def failure_accounting_section():
    def ok(path="vector", waiters=None, all_empty=None):
        e = {"op": "jobOk", "path": path}
        if waiters is not None:
            e["waiters"] = waiters
        if all_empty is not None:
            e["allEmpty"] = all_empty
        return e

    def bad(reason="renderError", path="vector", waiters=None):
        e = {"op": "jobFailed", "path": path, "reason": reason}
        if waiters is not None:
            e["waiters"] = waiters
        return e

    def wanted(z):
        return {"op": "wanted", "z": z}

    def done(result):
        return {"op": "baseDone", "result": result}
    cases = [
        ("three render errors in a row go sticky as renderError", [bad(), bad("renderError", "staged"), bad()]),
        ("a successful job resets the run", [bad(), bad(), ok(), bad(), bad(), bad()]),
        ("the 3rd failure names the reason: outOfMemory", [bad(), bad(), bad("outOfMemory")]),
        ("outOfMemory earlier in the run doesnt win, the 3rd does", [bad("outOfMemory"), bad("outOfMemory"), bad()]),
        ("an unknown error counts as renderError", [bad("other"), bad("other", "staged"), bad("other")]),
        ("raster sample and base raster failures count, a raster sample success resets",
         [bad("renderError", "rasterSample"), bad("outOfMemory", "baseRaster"), ok("rasterSample"),
          bad("renderError", "rasterSample"), bad("renderError", "rasterSample"), bad("outOfMemory", "rasterSample")]),
        ("passwordProtected from a job is sticky at once", [bad(), bad("passwordProtected")]),
        ("pageMissing from a job is sticky at once", [bad("pageMissing", "baseRaster")]),
        ("pageGeometry from a job is sticky at once", [bad("pageGeometry", "staged")]),
        ("document level failures are sticky at once",
         [{"op": "documentFailure", "reason": "cannotOpen"}, {"op": "retry"},
          {"op": "documentFailure", "reason": "passwordProtected"}, {"op": "retry"},
          {"op": "documentFailure", "reason": "pageMissing"}, {"op": "retry"},
          {"op": "documentFailure", "reason": "pageGeometry"}]),
        ("the blank verdict is sticky at once, even mid run", [bad(), bad(), {"op": "blankVerdict"}]),
        ("cancelled jobs and bake/estimate jobs neither count nor reset",
         [bad(), {"op": "jobCancelled"}, {"op": "bakeJobFailed"}, {"op": "bakeJobOk"}, bad(), bad()]),
        ("sticky ignores everything until Try Again, which starts clean",
         [bad(), bad(), bad(), ok(), bad("outOfMemory"), {"op": "blankVerdict"},
          {"op": "documentFailure", "reason": "pageMissing"}, {"op": "retry"}, bad(), ok()]),
        # r1 amendment R1: a good base raster is a jobOk, it resets the run like any other job
        ("R1: a successful base raster resets the run", [bad(), bad(), ok("baseRaster"), bad(), bad()]),
    ]
    # R2: a started job counts whether or not anyone still waits on it (waiters 0 = abandoned)
    ewma_cases = [
        ("R2: started jobs abandoned by every waiter still count",
         [bad(waiters=0), bad("renderError", "staged", waiters=0), bad("outOfMemory", "rasterSample", waiters=0)]),
        ("R2: an abandoned job that succeeds still resets the run and feeds the EWMA",
         [bad(), bad(), ok(waiters=0), bad(), ok("staged", waiters=0), ok("rasterSample", waiters=0),
          ok("vector", waiters=1, all_empty=True)]),
        ("R2: a job cancelled before it started has no effect on the run or the EWMA",
         [bad(), bad(), {"op": "jobCancelled"}, {"op": "jobCancelled"}, bad()]),
    ]
    # R1 base raster lifecycle, baseMaxZoom 12
    base_cases = [
        ("R1: started once, a failure counts once, re-attempted only for z <= baseMaxZoom", 12,
         [wanted(14), wanted(15), done("renderError"), wanted(14), wanted(15), wanted(13), wanted(12), wanted(11),
          done("outOfMemory"), wanted(13), wanted(10), done("ok"), wanted(9), wanted(12), bad(), ok()]),
        ("R1: three failed base attempts in a row go sticky, then nothing starts", 12,
         [wanted(12), done("renderError"), wanted(12), done("renderError"), wanted(11), done("outOfMemory"),
          wanted(10), {"op": "retry"}]),
        ("R1: base failures and job failures share the one run", 12,
         [wanted(16), bad(), done("outOfMemory"), wanted(15), bad("renderError", "staged")]),
        ("R1: a blank base raster is sticky blank, nothing re-starts", 12,
         [wanted(16), done("blank"), wanted(5)]),
        ("R1: a document-level failure from the base raster is sticky at once", 12,
         [wanted(3), done("pageGeometry"), wanted(3)]),
    ]
    out = []
    for label, events in cases:
        fr = FailureRun()
        steps = []
        for ev in events:
            fr.step(ev)
            steps.append({"event": ev, "state": fr.state()})
        out.append({"label": label, "steps": steps})
    for label, events in ewma_cases:
        fr = FailureRun()
        steps = []
        for ev in events:
            fr.step(ev)
            steps.append({"event": ev, "state": fr.state(), "feedsEwma": feeds_ewma(ev)})
        out.append({"label": label, "steps": steps})
    for label, bmz, events in base_cases:
        fr = FailureRun()
        bl = BaseLifecycle(fr, bmz)
        steps = []
        for ev in events:
            if ev["op"] == "retry":
                # Try Again builds a new source (G1), so the base starts over too
                fr.step(ev)
                bl = BaseLifecycle(fr, bmz)
                go, replan = False, False
            else:
                go, replan = bl.step(ev)
            steps.append({"event": ev, "state": fr.state(), "base": bl.state(), "startsBaseAttempt": go,
                          "replan": replan})
        out.append({"label": label, "baseMaxZoom": bmz, "steps": steps})
    return out


# ----------------------------------------------------------------------------
# staged region (contract C, G3 + r1 R5) and the blank check corner case (D5)
# ----------------------------------------------------------------------------

STAGED_SHRINK = 0.99
SPECIAL_NUM = {"NaN": float("nan"), "Infinity": float("inf"), "-Infinity": float("-inf")}


def staged_region(cell_bbox, required, clip_bbox, job_px, problems, tag):
    """same steps, same double ops, as the contract text. None inputs/outputs = renderError"""
    req = SPECIAL_NUM[required] if isinstance(required, str) else float(required)
    if not (math.isfinite(req) and req > 0):
        return {"result": "renderError", "why": "required <= 0 or not finite"}
    if cell_bbox is None:
        return {"result": "renderError", "why": "no emitted cells"}
    pad = 2.0 / req
    x0 = max(cell_bbox[0] - pad, clip_bbox[0])
    y0 = max(cell_bbox[1] - pad, clip_bbox[1])
    x1 = min(cell_bbox[2] + pad, clip_bbox[2])
    y1 = min(cell_bbox[3] + pad, clip_bbox[3])
    if not (x1 > x0 and y1 > y0):
        return {"result": "renderError", "why": "region empty or zero width/height", "pad": pad}
    rw, rh = x1 - x0, y1 - y0
    cap = math.sqrt(STAGED_MAX_FACTOR * job_px / (rw * rh))
    over = STAGED_OVERSAMPLE * req
    d = min(over, cap)
    d_from = "oversample" if over <= cap else "cap"
    if abs(over - cap) < 1e-9 * cap:
        problems.append("%s: oversample vs cap is a tie" % tag)
    max_px = int(math.floor(STAGED_MAX_FACTOR * job_px))

    def sides(d):
        for v in (rw * d, rh * d):
            if v > 0.5 and near_int(v):
                problems.append("%s: staged side %r too close to an integer for ceil" % (tag, v))
        return max(1, int(math.ceil(rw * d))), max(1, int(math.ceil(rh * d)))
    W, H = sides(d)
    first = (W, H)
    steps = 0
    while W * H > max_px and d > 0:
        d *= STAGED_SHRINK
        W, H = sides(d)
        steps += 1
    return {"result": "ok", "pad": pad, "region": [x0, y0, x1, y1], "rw": rw, "rh": rh, "cap": cap,
            "dFrom": d_from, "d0": min(over, cap), "W0": first[0], "H0": first[1], "maxPx": max_px,
            "shrinkSteps": steps, "d": d, "W": W, "H": H}


def staged_region_section(sheets, wide, problems):
    geo = {sid: (g, fp) for sid, g, fp, _ in sheets}
    geo[wide[0].id] = wide
    out = []

    def add(label, cell_bbox, required, clip_bbox, cols, rows, tile_px, src=None):
        jp = cols * rows * tile_px * tile_px
        exp = staged_region(cell_bbox, required, clip_bbox, jp, problems, "stagedRegion " + label)
        e = {"kind": "region", "label": label}
        if src:
            e["fromWarp"] = src
        e.update({"cellBbox": cell_bbox, "requiredPxPerPt": required, "clipBBox": list(clip_bbox),
                  "cols": cols, "rows": rows, "tilePx": tile_px, "jobPixels": jp, "expected": exp})
        out.append(e)

    # real staged jobs off the warp section (z > baseMaxZoom, more than one cell) + the wide sheet
    real = [("sf_iso", (14, 2618, 6333, 3, 2), 768), ("sf_iso", (15, 5237, 12666, 3, 2), 768),
            ("rot5_iso", (14, 2618, 6333, 3, 2), 768), ("cbr50k_iso", (13, 7488, 4955, 1, 1), 768),
            ("cbr50k_iso", (14, 14971, 9916, 3, 2), 768), ("usgs_sf_north", (13, 1310, 3165, 1, 1), 768),
            ("usgs_sf_north", (15, 5240, 12663, 3, 2), 768), ("wide_tm_1m", (5, 5, 11, 1, 1), 768),
            ("wide_tm_1m", (7, 20, 45, 3, 2), 768), ("wide_tm_1m", (8, 42, 91, 1, 1), 512)]
    for sid, job, tp in real:
        g, fp = geo[sid]
        tag = "%s z%d %d/%d %dx%d @%d" % ((sid,) + job + (tp,))
        _, _, leaves, _ = plan_warp(g, fp, job, tp, problems, tag)
        emitted = [lf for lf in leaves if lf["coverage"] != "outside"]
        if len(emitted) < 2:
            problems.append("%s: not a staged job any more" % tag)
            continue
        xs = [p[0] for lf in emitted for p in lf["quad"]]
        ys = [p[1] for lf in emitted for p in lf["quad"]]
        req = 0.0
        for lf in emitted:
            a, b_, _, d, e, _ = lf["pageToPx"]
            req = max(req, math.hypot(a, d), math.hypot(b_, e))
        z, x0, y0, cols, rows = job
        add("%s %s" % (sid, tag.split(" ", 1)[1]), [min(xs), min(ys), max(xs), max(ys)], req, fp.bbox, cols, rows, tp,
            {"sheet": sid, "job": {"z": z, "x0": x0, "y0": y0, "cols": cols, "rows": rows}, "tilePx": tp,
             "cellCount": len(emitted)})

    # synthetic, pure numbers. clip bbox 0..1000 x 0..800 unless said otherwise
    clip = (0.0, 0.0, 1000.0, 800.0)
    add("pad 2/required grows the cell bbox, oversample wins", [100.25, 200.5, 400.75, 450.125], 1.7, clip, 1, 1, 768)
    add("pad is cut back to the clip bbox on every side", [0.5, 0.25, 999.75, 799.5], 1.0371, clip, 3, 2, 768)
    add("cell bbox hanging past the clip bbox is cut to it", [-50.0, 600.0, 300.0, 900.0], 2.5, clip, 1, 1, 512)
    add("cap wins and the ceil pushes W x H over, one 0.99 step", [10.3, 20.7, 610.9, 420.1], 4.0, clip, 1, 1, 768)
    add("cap wins on a heavy block", [5.5, 5.5, 990.0, 790.0], 3.2, clip, 3, 2, 768)
    add("thin region, the max(1, ceil) side forces several 0.99 steps", [100.0, 399.99, 900.0, 400.01], 50.0, clip,
        1, 1, 256)
    add("tiny job, big required: cap wins", [300.0, 300.0, 300.5, 300.25], 900.0, clip, 1, 1, 256)
    # R5 degenerate inputs, all renderError (counted by the live source)
    add("R5: no emitted cells", None, 1.5, clip, 1, 1, 768)
    add("R5: cell bbox entirely outside the clip bbox", [1200.0, 100.0, 1300.0, 200.0], 1.5, clip, 1, 1, 768)
    add("R5: padded bbox only touches the clip bbox edge, zero width", [1001.0, 100.0, 1100.0, 200.0], 2.0, clip,
        1, 1, 768)
    add("R5: required 0", [100.0, 100.0, 200.0, 200.0], 0.0, clip, 1, 1, 768)
    add("R5: required negative", [100.0, 100.0, 200.0, 200.0], -1.5, clip, 1, 1, 768)
    add("R5: required NaN", [100.0, 100.0, 200.0, 200.0], "NaN", clip, 1, 1, 768)
    add("R5: required +Infinity", [100.0, 100.0, 200.0, 200.0], "Infinity", clip, 1, 1, 768)
    if not any(c["expected"].get("shrinkSteps", 0) >= 2 for c in out):
        problems.append("stagedRegion: no case takes more than one shrink step")
    if not any(c["expected"].get("shrinkSteps", 0) == 1 for c in out):
        problems.append("stagedRegion: no case takes exactly one shrink step")
    return out


# ----------------------------------------------------------------------------
# bake estimate (amendment 2026-10-02, J2)
# ----------------------------------------------------------------------------

EPS_SAMPLE_D2 = 1e-6    # squared tile units, sample ranking near ties


def sample_selection(g, fp, z, problems, tag):
    cov, _ = fp.level(z)
    tiles = sorted(cov, key=lambda t: (t[1], t[0]))
    inside = [t for t in tiles if cov[t] == "inside"]
    pool, kind = (inside, "inside") if inside else (tiles, "all")
    ll = g.to_wgs84(*fp.mean)
    X, Y = merc_world0(*ll)
    n = 2 ** z
    cx, cy = X * n / 256.0, Y * n / 256.0

    def d2(t):
        dx, dy = t[0] + 0.5 - cx, t[1] + 0.5 - cy
        return dx * dx + dy * dy
    ranked = sorted(pool, key=lambda t: (d2(t), t[1], t[0]))
    for a, b in zip(ranked[:BAKE_SAMPLE_TILES], ranked[1:BAKE_SAMPLE_TILES + 1]):
        gap = d2(b) - d2(a)
        if 0 < gap < EPS_SAMPLE_D2:
            problems.append("%s: sample tiles %r / %r are a near tie (%g)" % (tag, a, b, gap))
    picks = ranked[:BAKE_SAMPLE_TILES]
    return {"z": z, "centreTile": [rsig(cx, 15), rsig(cy, 15)], "pool": kind, "poolCount": len(pool),
            "tiles": [[x, y] for x, y in picks], "d2": [rsig(d2(t), 12) for t in picks]}


def bake_estimate(fp, bmz, options, default, samples, ewma, free, problems, tag):
    enc = [s for s in samples if s["result"] == "image"]
    if ewma is not None:
        job_ms, job_from = ewma, "ewma"
    elif enc:
        job_ms, job_from = sum(s["jobMs"] for s in enc) / len(enc), "samples"
    else:
        job_ms, job_from = BAKE_FALLBACK_JOB_MS, "fallback"
    if enc:
        enc_ms, enc_from = sum(s["encodeMs"] for s in enc) / len(enc), "samples"
        mean_b, b_from = float(sum(s["bytes"] for s in enc)) / len(enc), "samples"
    else:
        enc_ms, enc_from = BAKE_FALLBACK_ENCODE_MS, "fallback"
        mean_b, b_from = float(BAKE_FALLBACK_TILE_BYTES), "fallback"
    heavy = ewma is not None and ewma > HEAVY_MS
    opts = []
    for m, tiles in options:
        per = []
        for z in range(0, m + 1):
            cov, _ = fp.level(z)
            lt = sorted(cov, key=lambda t: (t[1], t[0]))
            per.append(len(bake_level_jobs(z, lt, bmz, heavy)[0]))
        jobs = sum(per)
        raw_b = tiles * mean_b * BAKE_BYTES_SAFETY
        if 0 < raw_b - math.floor(raw_b) < 1e-6 or 0 < math.ceil(raw_b) - raw_b < 1e-6:
            problems.append("%s z%d: bytes %r too close to an integer for ceil" % (tag, m, raw_b))
        b = int(math.ceil(raw_b))
        need = int(BAKE_SPACE_FACTOR * b)
        ms = jobs * job_ms + tiles * enc_ms
        q = ms / 60000.0
        if abs(q - round(q)) < 1e-9 and q != round(q):
            problems.append("%s z%d: minutes %r on a ceil boundary" % (tag, m, q))
        opts.append({"maxZoom": m, "tiles": tiles, "jobsPerLevel": per, "jobs": jobs, "bytes": b,
                     "neededBytes": need, "enoughSpace": free is None or free >= need,
                     "estimatedMs": ms, "minutes": max(1, int(math.ceil(q)))})
    fits = [o["maxZoom"] for o in opts if o["enoughSpace"]]
    initial = default if default in fits else (max(fits) if fits else default)
    return {"heavy": heavy, "jobMs": job_ms, "jobMsFrom": job_from, "encodeMs": enc_ms, "encodeMsFrom": enc_from,
            "meanTileBytes": mean_b, "meanTileBytesFrom": b_from, "options": opts,
            "initialSelection": initial, "generateEnabled": initial in fits}


def bake_estimate_section(sheets, policies, bake, problems):
    pol = {p["sheet"]: p for p in policies}
    bk = {b["sheet"]: b for b in bake}
    geo = {sid: (g, fp) for sid, g, fp, _ in sheets}

    def bmz(sid, tp):
        rows = pol[sid]["basePlan"]["normal"]["baseMaxZoom"]
        return next(r["baseMaxZoom"] for r in rows if r["tilePx"] == tp)

    sel = []
    for sid, g, fp, _ in sheets:
        d = bk[sid]["default"]
        if d is None:
            continue
        sel.append(dict({"sheet": sid}, **sample_selection(g, fp, d, problems, sid)))

    def s(job_ms, b=None, enc=None, result=None):
        return {"result": result or ("image" if b is not None else "empty"), "jobMs": job_ms, "bytes": b,
                "encodeMs": enc}
    usgs3 = [s(230.0, 61234, 6.5), s(241.0, 58876, 7.0), s(219.0, 64002, 6.0)]
    GIB10 = 10 * GIB
    cases = [
        ("heavy sheet, three samples, room for everything", "usgs_sf_north", 768, usgs3, 225.5, GIB10),
        ("light: EWMA at exactly 60 ms is not heavy", "usgs_sf_north", 768, usgs3, 60.0, GIB10),
        ("light, 512 px, baseMaxZoom 13", "usgs_sf_north", 512,
         [s(41.0, 30500, 3.25), s(39.5, 29872, 3.0), s(44.0, 31120, 3.5)], 41.0, GIB10),
        ("raster sampled default: no EWMA, jobMs is the sample mean", "sf_iso", 256,
         [s(12.0, 18200, 1.5), s(14.5, 17650, 1.25), s(11.0, 19001, 1.75)], None, GIB10),
        ("one sample EMPTY, one failed: means over the one that delivered", "cbr50k_iso", 768,
         [s(180.0, 70210, 8.0), s(15.0), s(0.0, result="failed")], 180.0, GIB10),
        ("no samples at all: every number falls back", "cbr50k_iso", 768, [s(80.0), s(0.0, result="failed"), s(75.0)], None, GIB10),
        ("no samples but a live EWMA: jobMs from the EWMA, the rest falls back", "geog_iso", 768,
         [s(90.0), s(0.0, result="failed")], 95.0, GIB10),
        ("free space fits only D-2: it becomes the initial selection", "usgs_sf_north", 768, usgs3, 225.5,
         50000000),
        ("no option fits: all disabled, the default stays selected, Generate disabled", "usgs_sf_north", 768,
         usgs3, 225.5, 5000000),
        ("free space unknown: every option is enabled", "lcc_lgile", 672,
         [s(150.0, 52000, 5.0), s(161.0, 50404, 5.5), s(149.0, 49999, 4.5)], 150.0, None),
    ]
    out = []
    for label, sid, tp, samples, ewma, free in cases:
        g, fp = geo[sid]
        b = bk[sid]
        options = [(o["maxZoom"], o["tiles"]) for o in b["candidates"] if o["kept"]]
        base = bmz(sid, tp)
        exp = bake_estimate(fp, base, options, b["default"], samples, ewma, free, problems, "%s %s" % (sid, label))
        out.append({"label": label, "sheet": sid, "tilePx": tp, "baseMaxZoom": base,
                    "detailZoom": b["detailZoom"], "defaultMaxZoom": b["default"],
                    "options": [{"maxZoom": m, "tiles": t} for m, t in options],
                    "samples": samples, "ewmaMs": ewma, "freeBytes": free, "expected": exp})
    return {"fallback": {"jobMs": BAKE_FALLBACK_JOB_MS, "encodeMs": BAKE_FALLBACK_ENCODE_MS,
                         "tileBytes": BAKE_FALLBACK_TILE_BYTES},
            "sampleSelection": sel, "cases": out}


# ----------------------------------------------------------------------------
# bake UI text (r1 OD-F7) and the J3 PSNR gate inputs (r1 D1)
# ----------------------------------------------------------------------------

BAKE_FORMAT_LOCALES = {"en": (",", "."), "de": (".", ",")}     # (grouping, decimal)


def group_int(n, sep):
    s = str(abs(n))
    parts = []
    while len(s) > 3:
        parts.insert(0, s[-3:])
        s = s[:-3]
    parts.insert(0, s)
    return ("-" if n < 0 else "") + sep.join(parts)


def bake_size_text(b, loc):
    """MB = 10^6 bytes. integer half-up rounding so no platform float can disagree"""
    grp, dec = BAKE_FORMAT_LOCALES[loc]
    tenths_mb = (b + 50000) // 100000
    if b > 0 and tenths_mb == 0:
        tenths_mb = 1
    if tenths_mb < 1000:
        return "%s%s%d MB" % (group_int(tenths_mb // 10, grp), dec, tenths_mb % 10)
    whole_mb = (b + 500000) // 1000000
    if whole_mb < 1000:
        return "%s MB" % group_int(whole_mb, grp)
    tenths_gb = (b + 50000000) // 100000000
    return "%s%s%d GB" % (group_int(tenths_gb // 10, grp), dec, tenths_gb % 10)


def bake_format_section(problems):
    tiles = [0, 7, 999, 1000, 1646, 5999, 6000, 12345, 1234567]
    sizes = [0, 1, 49999, 50000, 149999, 150000, 1234567, 9950000, 99949999, 99950000, 100000000, 123456789,
             395737600, 999499999, 999500000, 1000000000, 1234567890, 25000000000, 1234567890123]
    out = []
    for i in range(max(len(tiles), len(sizes))):
        t = tiles[i % len(tiles)]
        b = sizes[i]
        out.append({"tiles": t, "bytes": b,
                    "en": {"tiles": group_int(t, ","), "size": bake_size_text(b, "en")},
                    "de": {"tiles": group_int(t, "."), "size": bake_size_text(b, "de")}})
    return {"rules": {
        "tiles": "locale grouped integer, no decimals (en 1,646 / de 1.646)",
        "megabyte": 1000000,
        "size": "tenthsMB = floor((bytes + 50000) / 100000), at least 1 when bytes > 0. tenthsMB < 1000 -> "
                "'<tenthsMB / 10 grouped><decimal><tenthsMB % 10> MB'. else wholeMB = floor((bytes + 500000) / "
                "1000000); wholeMB < 1000 -> '<wholeMB grouped> MB'. else tenthsGB = floor((bytes + 50000000) / "
                "100000000) -> '<tenthsGB / 10 grouped><decimal><tenthsGB % 10> GB'. integer math, one U+0020 "
                "space before the unit, units MB/GB in every locale",
        "locales": {k: {"grouping": v[0], "decimal": v[1]} for k, v in BAKE_FORMAT_LOCALES.items()}},
        "cases": out}


def psnr_section(entries, problems):
    sheets = []
    for sid, path, fp, zp in entries:
        rows = {r["tilePx"]: r["baseMaxZoom"] for r in zp["basePlan"]["normal"]["baseMaxZoom"]}
        dz = zp["detailZoom"]
        per_tp = []
        for tp in PSNR_TILEPX:
            bmz = rows[tp]
            zs = []
            for z in range(max(0, bmz if bmz is not None else 0), dz + 1):
                cov, _ = fp.level(z)
                inside = sorted((t for t, c in cov.items() if c == "inside"), key=lambda t: (t[1], t[0]))
                if not inside:
                    continue
                t = inside[len(inside) // 2]
                zs.append({"z": z, "path": "raster" if bmz is not None and z <= bmz else "vector", "tile": list(t),
                           "insideCount": len(inside)})
            nv = sum(1 for e in zs if e["path"] == "vector")
            if nv < 1:
                problems.append("psnr: %s @%d has no vector zoom" % (sid, tp))
            per_tp.append({"tilePx": tp, "baseMaxZoom": bmz, "detailZoom": dz, "zooms": zs, "vectorZooms": nv})
        sheets.append({"sheet": sid, "file": path, "checks": per_tp})
    return {
        "gateDb": PSNR_GATE_DB,
        "definition": "RGB, 8 bit, peak 255, over the pixels with alpha 255 in the live tile; MSE 0 counts as 99.0 "
                      "dB; the gate is the minimum over every tile checked",
        "tilePx": PSNR_TILEPX,
        "zoomRule": "for each tilePx: z = baseMaxZoom..detailZoom (normal budget), one tile per z = the middle "
                    "INSIDE tile in row-major order, inside[floor(n / 2)]; a z with no INSIDE tile is skipped. at "
                    "least one vector zoom (z > baseMaxZoom) must be checked",
        "sheets": sheets}


# ----------------------------------------------------------------------------
# camera zoom + memory tiers
# ----------------------------------------------------------------------------

def round_half_up(v):
    return int(math.floor(v + 0.5))


def camera_zoom_section():
    lo, hi = CAMERA_ZOOM
    pinch = []
    for z, sc in [(18.0, 1.01), (15.0, 1.02), (10.0, 4.0), (12.3, 0.5), (21.9, 1.2), (22.0, 1.5), (2.1, 0.5),
                  (2.0, 0.9), (16.0, 8.0), (3.0, 1.0 / 1.5)]:
        pinch.append({"zoom": z, "scale": sc, "result": rsig(min(max(z + math.log2(sc), lo), hi), 15)})
    target = [{"target": t, "result": min(max(t, lo), hi)} for t in (0.0, 1.5, 2.0, 12.25, 19.5, 22.0, 25.0)]
    tz = []
    for cz, mn, mx in [(12.5, 0, 16), (12.49, 0, 16), (18.2, 0, 16), (16.6, 0, 16), (7.9, 10, 18), (8.0, 10, 18),
                       (8.4, 10, 18), (2.0, 0, 22), (2.0, 5, 14), (3.0, 5, 14), (21.5, 0, 22), (9.5, 0, 9)]:
        t = min(max(round_half_up(cz), mn), mx)
        tz.append({"cameraZoom": cz, "minZoom": mn, "maxZoom": mx, "tileZoom": t,
                   "underzoomHidden": (t - cz) > MAX_UNDERZOOM})
    return {"pinch": pinch, "target": target, "tileZoom": tz}


def tile_px(d):
    return round_half_up(256 * min(d, DENSITY_CAP) / TILE_PX_QUANTUM) * TILE_PX_QUANTUM


def memory_cases():
    out = []
    for ram, low in [(2 * GIB, False), (3 * GIB, False), (int(3.5 * GIB) - 1, False), (int(3.5 * GIB), False),
                     (4 * GIB, True), (4 * GIB, False), (6 * GIB - 1, False), (6 * GIB, False), (8 * GIB, False),
                     (12 * GIB, True)]:
        if low or ram < 3.5 * GIB:
            cache, budget = 64 * MIB, BUDGET_LOW
        elif ram < 6 * GIB:
            cache, budget = 128 * MIB, BUDGET_NORMAL
        else:
            cache, budget = 192 * MIB, BUDGET_NORMAL
        out.append({"physicalMemory": ram, "lowRamDevice": low, "tileCacheBytes": cache, "baseBudgetPx": budget,
                    "iosVectorLanes": 2 if ram >= 3.5 * GIB else 1})
    return out


def constants():
    return {
        "tileLogicalSize": TILE_UNITS,
        "tilePx": {"densityCap": DENSITY_CAP, "quantum": TILE_PX_QUANTUM,
                   "formula": "round(256 * min(density, 3) / 16) * 16, ties round up (half away from zero)"},
        "cameraZoomMin": CAMERA_ZOOM[0], "cameraZoomMax": CAMERA_ZOOM[1],
        "iosFlyToZoomRange": list(FLYTO_ZOOM),
        "maxUnderzoomLevels": MAX_UNDERZOOM,
        "visibleTileInflateUnits": VISIBLE_INFLATE,
        "maxAncestorLevels": MAX_ANCESTOR_LEVELS,
        "tileCache": {"tiers": [{"below": int(3.5 * GIB), "bytes": 64 * MIB, "orLowRamDevice": True},
                                {"below": 6 * GIB, "bytes": 128 * MIB},
                                {"below": None, "bytes": 192 * MIB}],
                      "unit": "bytes, MB in the contract means MiB",
                      "emptyEntryCostBytes": 64},
        "earthRadius": R,
        "metresPerUnitZ0": MPP0,
        "footprintSegmentsPerEdge": DENSIFY,
        "clipMinAreaPt2": CLIP_MIN_AREA,
        "clipDedupeEpsPt": CLIP_DEDUPE_EPS,
        "jacobianStepPt": 1.0,
        "detailOversample": DETAIL_OVERSAMPLE,
        "detailZoomHysteresis": DETAIL_HYST,
        "maxZoomCap": MAX_ZOOM_CAP,
        "baseRaster": {"budgetPx": BUDGET_NORMAL, "lowRamBudgetPx": BUDGET_LOW, "lowRamBelowBytes": int(3.5 * GIB),
                       "maxSide": BASE_MAX_SIDE, "maxScale": BASE_MAX_SCALE, "mipStopSide": MIP_STOP,
                       "upsampleTolerance": UPSAMPLE_TOL},
        "staged": {"oversample": STAGED_OVERSAMPLE, "maxPixelsFactor": STAGED_MAX_FACTOR,
                   "shrinkFactor": STAGED_SHRINK, "padPx": 2.0},
        "warp": {"maxErrorPx": WARP_MAX_ERR, "baseDepth": WARP_BASE_DEPTH, "minCellPx": WARP_MIN_CELL,
                 "rootPadPx": WARP_ROOT_PAD},
        "paperWhite": "#FFFFFF",
        "scheduling": {"vectorSettleMs": SETTLE_MS, "ewmaAlpha": EWMA_ALPHA, "heavyThresholdMs": HEAVY_MS,
                       "jobMaxTiles": JOB_MAX_TILES, "jobMaxSide": JOB_MAX_SIDE,
                       "bakeBlockCols": BAKE_BLOCK[0], "bakeBlockRows": BAKE_BLOCK[1], "maxBakeJobsInFlight": 1,
                       "orphanCacheTiles": ORPHAN_TILES, "iosVectorLanesMinRamBytes": int(3.5 * GIB),
                       "androidPdfiumThreads": 1},
        "status": {"preparingLabelDelayMs": PREPARING_DELAY_MS, "renderErrorConsecutiveFailures": RENDER_ERROR_RUN,
                   "blankSampleStride": BLANK_STRIDE, "blankMinChannelMax": BLANK_WHITE_MAX,
                   "preparingColor": "#FFC247", "failedColor": "#FF5A5A", "readyColor": "#74E38A",
                   "failureReasons": ["cannotOpen", "passwordProtected", "pageMissing", "pageGeometry", "blank",
                                      "outOfMemory", "renderError"]},
        "hiddenBackground": {"android": "#121212", "iosWhite": 0.07},
        "visibilityKey": {"android": "importedMapVisible", "ios": "layers.importedMapVisible", "default": True},
        "bake": {"maxTiles": BAKE_MAX_TILES, "freeSpaceFactor": BAKE_SPACE_FACTOR, "bytesSafety": BAKE_BYTES_SAFETY,
                 "sampleTiles": BAKE_SAMPLE_TILES, "commitEvery": BAKE_COMMIT_EVERY, "rendererVersion": RENDERER_VERSION,
                 "bakeKeyPrefix": "tacmap-bake-v1|", "iosJpeg2000Quality": 0.9, "iosMbtilesFormat": "jp2",
                 "androidWebpQuality": 100,
                 "androidWebpLossless": True, "androidMbtilesFormat": "webp", "psnrGateDb": PSNR_GATE_DB,
                 "mbtilesName": BAKE_MBTILES_NAME,
                 "minZoom": 0, "candidateOffsets": [-2, -1, 0], "defaultOffset": -1},
        "crashGuard": {"maxVerifiedTokens": GUARD_MAX_TOKENS, "fileVersion": 1},
        "doubleTap": {"zoomDelta": 1, "twoFingerZoomDelta": -1, "animationMs": 250},
        "memoryCases": memory_cases(),
    }


# ----------------------------------------------------------------------------
# synthetic PDFs: markers (rotate 90, offset media, inset crop box, OCG off) + blank
# ----------------------------------------------------------------------------

S25K = 25000 * 0.0254 / 72.0

MARKERS = {
    "media": [100.0, 100.0, 700.0, 500.0],
    "cropBox": [120.0, 115.0, 680.0, 490.0],
    "rotate": 90,
    "bbox": [110.0, 130.0, 670.0, 480.0],      # left 10 pt of the neatline hangs outside the CropBox on purpose
    "E0": 549000.0, "N0": 4180000.0, "mapRotDeg": 3.0,
    # labels are geographic. north runs along raw -x and east along raw +y, so small x small y is NW
    "markers": [("centre", 395.0, 305.0), ("nw", 145.0, 155.0), ("sw", 645.0, 155.0), ("se", 645.0, 455.0),
                ("ne", 145.0, 455.0), ("a", 300.0, 420.0), ("b", 520.0, 200.0)],
    "clippedMarker": ("outside crop box", 115.0, 305.0),
    "hidden": (470.0, 330.0, 12.0),
    "inside": [("paper 1", 250.0, 250.0), ("paper 2", 600.0, 400.0), ("paper 3", 200.0, 350.0)],
    "outside": [("crop box strip", 115.0, 200.0), ("east of neatline", 690.0, 300.0),
                ("south of neatline", 395.0, 122.0)],
}
BLANK = {"media": [0.0, 0.0, 400.0, 300.0], "bbox": [20.0, 20.0, 380.0, 280.0], "E0": 551000.0, "N0": 4181000.0}
# D1 (r1): the one dense linework sheet both J3 gates use. same drawing iOS had inline before
# (41 black 0.25 pt diagonals + a red 0.3 pt grid every 12 pt), now a georeferenced file
DENSE = {"media": [0.0, 0.0, 612.0, 792.0], "bbox": [36.0, 36.0, 577.0, 757.0], "E0": 548500.0, "N0": 4179000.0,
         "diagonals": 41, "diagonalWidthPt": 0.25, "gridStepPt": 12.0, "gridWidthPt": 0.3}
# D5 (r1): a /Bounds quad inside its BBox, black marks in the BBox corners outside the quad.
# the blank check must never see them, so the import fails with blank. 1/8 lpts keep it exact
BLANK_CORNER = {"media": [0.0, 0.0, 600.0, 480.0], "bbox": [40.0, 40.0, 560.0, 440.0], "E0": 550500.0,
                "N0": 4182500.0, "lpts": [0.125, 0.0, 1.0, 0.125, 0.875, 1.0, 0.0, 0.875],
                "marks": [("sw corner", 44.0, 44.0, 56.0, 56.0), ("se corner", 544.0, 44.0, 556.0, 56.0),
                          ("ne corner", 544.0, 424.0, 556.0, 436.0), ("nw corner", 44.0, 424.0, 56.0, 436.0)]}


def construction_plane(cfg, x, y, rot90):
    """page -> UTM plane, the construction truth. rot90 sheets have north along raw -x
    so a viewer that applies /Rotate 90 shows them north up"""
    bx0, by0, bx1, by1 = cfg["bbox"]
    cx, cy = (bx0 + bx1) / 2.0, (by0 + by1) / 2.0
    u, v = x - cx, y - cy
    east, north = (v, -u) if rot90 else (u, v)
    th = math.radians(cfg.get("mapRotDeg", 0.0))
    return (cfg["E0"] + S25K * (math.cos(th) * east - math.sin(th) * north),
            cfg["N0"] + S25K * (math.sin(th) * east + math.cos(th) * north))


def iso_vp_numbers(cfg, rot90, lpts=None):
    """the /VP numbers as written: bbox, LPTS (unit corners unless given), GPTS at 12 dp"""
    import pyproj
    proj = pyproj.Proj("+proj=utm +zone=10 +ellps=WGS84 +datum=WGS84 +units=m +no_defs")
    bx0, by0, bx1, by1 = cfg["bbox"]
    lpts = list(lpts) if lpts else [0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0]
    gpts = []
    for i in range(len(lpts) // 2):
        x = bx0 + lpts[2 * i] * (bx1 - bx0)
        y = by0 + lpts[2 * i + 1] * (by1 - by0)
        E, N = construction_plane(cfg, x, y, rot90)
        lon, lat = proj(E, N, inverse=True)
        gpts += [float(pdfnum(lat, 12)), float(pdfnum(lon, 12))]
    return lpts, gpts


def fit_affine_from_vp(cfg, lpts, gpts):
    """what a WP1 parser derives: least squares page -> plane over the written control points"""
    import numpy as np
    import pyproj
    proj = pyproj.Proj("+proj=tmerc +lat_0=0 +lon_0=-123 +k_0=0.9996 +x_0=500000 +y_0=0 +a=6378137 +rf=298.257223563 "
                       "+units=m +no_defs")
    bx0, by0, bx1, by1 = cfg["bbox"]
    P, Q = [], []
    for i in range(len(lpts) // 2):
        P.append((bx0 + lpts[2 * i] * (bx1 - bx0), by0 + lpts[2 * i + 1] * (by1 - by0)))
        Q.append(proj(gpts[2 * i + 1], gpts[2 * i]))
    P, Q = np.array(P, dtype=float), np.array(Q, dtype=float)
    pm, qm = P.mean(0), Q.mean(0)
    sol = np.linalg.lstsq(np.column_stack([P - pm, np.ones(len(P))]), Q - qm, rcond=None)[0]
    a, d = sol[0]
    b, e = sol[1]
    c = qm[0] + sol[2][0] - a * pm[0] - b * pm[1]
    f = qm[1] + sol[2][1] - d * pm[0] - e * pm[1]
    return [float(v) for v in (a, b, c, d, e, f)]


def vp_extras(cfg, lpts, gpts, bounds=None):
    b = cfg["bbox"]
    bnd = " ".join(pdfnum(v, 12) for v in bounds) if bounds else "0 0 0 1 1 1 1 0"
    return ("/VP [<< /Type /Viewport /Name (Map Layers) /BBox [%s] /Measure << /Type /Measure /Subtype /GEO "
            "/Bounds [%s] /GPTS [%s] /LPTS [%s] /GCS << /Type /PROJCS /WKT (%s) >> >> >>]" % (
                " ".join(pdfnum(v, 6) for v in b), bnd, " ".join(pdfnum(v, 12) for v in gpts),
                " ".join(pdfnum(v, 12) for v in lpts), WKT_UTM10N))


def write_pdf_bytes(media, content, page_extras, crop=None, rotate=0, resources="", catalog_extras="", extra_objs=()):
    data = content.encode("latin-1")
    page = "<< /Type /Page /Parent 2 0 R /MediaBox [%s]" % " ".join(pdfnum(v, 3) for v in media)
    if crop:
        page += " /CropBox [%s]" % " ".join(pdfnum(v, 3) for v in crop)
    if rotate:
        page += " /Rotate %d" % rotate
    page += " /Resources << %s >> /Contents 4 0 R %s >>" % (resources, page_extras)
    objs = [("<< /Type /Catalog /Pages 2 0 R%s >>" % catalog_extras).encode("latin-1"),
            b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            page.encode("latin-1"),
            b"<< /Length %d >>\nstream\n" % len(data) + data + b"\nendstream"]
    objs += [o.encode("latin-1") for o in extra_objs]
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
    return bytes(buf)


def markers_pdf(cfg, lpts, gpts):
    m = cfg["media"]
    b = cfg["bbox"]
    ops = ["q 1 1 1 rg %s %s %s %s re f Q" % (pdfnum(m[0]), pdfnum(m[1]), pdfnum(m[2] - m[0]), pdfnum(m[3] - m[1])),
           "q 0 0 0 RG 0.6 w %s %s %s %s re S Q" % (pdfnum(b[0]), pdfnum(b[1]), pdfnum(b[2] - b[0]), pdfnum(b[3] - b[1]))]
    for _, x, y in cfg["markers"] + [cfg["clippedMarker"]]:
        ops.append("q 1 0 0 rg %s %s 4 4 re f Q" % (pdfnum(x - 2), pdfnum(y - 2)))
    hx, hy, hs = cfg["hidden"]
    ops.append("/OC /oc1 BDC q 0 0 1 rg %s %s %s %s re f Q EMC" % (pdfnum(hx - hs / 2), pdfnum(hy - hs / 2), pdfnum(hs), pdfnum(hs)))
    return write_pdf_bytes(m, "\n".join(ops), vp_extras(cfg, lpts, gpts), crop=cfg["cropBox"], rotate=cfg["rotate"],
                           resources="/Properties << /oc1 5 0 R >>",
                           catalog_extras=" /OCProperties << /OCGs [5 0 R] /D << /Name (Default) /Order [5 0 R] "
                                          "/ON [] /OFF [5 0 R] >> >>",
                           extra_objs=["<< /Type /OCG /Name (TacMap hidden test layer) >>"])


def blank_pdf(cfg, lpts, gpts):
    m = cfg["media"]
    # all white plus a 254/255 grey box: still blank by the min(R,G,B) <= 250 rule
    ops = ["q 1 1 1 rg %s %s %s %s re f Q" % (pdfnum(m[0]), pdfnum(m[1]), pdfnum(m[2] - m[0]), pdfnum(m[3] - m[1])),
           "q 0.995 0.995 0.995 rg 60 60 120 90 re f Q"]
    return write_pdf_bytes(m, "\n".join(ops), vp_extras(cfg, lpts, gpts))


def dense_pdf(cfg, lpts, gpts):
    m = cfg["media"]
    x0, y0, x1, y1 = m
    w, h = x1 - x0, y1 - y0
    ops = ["q 1 1 1 rg %s %s %s %s re f Q" % (pdfnum(x0), pdfnum(y0), pdfnum(w), pdfnum(h)),
           "q 0 0 0 RG %s w 0 J" % pdfnum(cfg["diagonalWidthPt"])]
    n = cfg["diagonals"] - 1
    for i in range(n + 1):
        x = x0 + w * i / n
        ops.append("%s %s m %s %s l" % (pdfnum(x - w / 2), pdfnum(y0), pdfnum(x + w / 2), pdfnum(y1)))
    ops += ["S Q", "q 1 0 0 RG %s w 0 J" % pdfnum(cfg["gridWidthPt"])]
    g = 0.0
    while g <= max(w, h):
        if g <= w:
            ops.append("%s %s m %s %s l" % (pdfnum(x0 + g), pdfnum(y0), pdfnum(x0 + g), pdfnum(y1)))
        if g <= h:
            ops.append("%s %s m %s %s l" % (pdfnum(x0), pdfnum(y0 + g), pdfnum(x1), pdfnum(y0 + g)))
        g += cfg["gridStepPt"]
    ops.append("S Q")
    return write_pdf_bytes(m, "\n".join(ops), vp_extras(cfg, lpts, gpts))


def blank_corner_pdf(cfg, lpts, gpts):
    m = cfg["media"]
    ops = ["q 1 1 1 rg %s %s %s %s re f Q" % (pdfnum(m[0]), pdfnum(m[1]), pdfnum(m[2] - m[0]), pdfnum(m[3] - m[1]))]
    for _, a, b, c, d in cfg["marks"]:
        ops.append("q 0 0 0 rg %s %s %s %s re f Q" % (pdfnum(a), pdfnum(b), pdfnum(c - a), pdfnum(d - b)))
    return write_pdf_bytes(m, "\n".join(ops), vp_extras(cfg, lpts, gpts, bounds=cfg["lpts"]))


def vp_crop(cfg, lpts):
    """WP1 ISO crop: LPTS/Bounds through the BBox, in written order"""
    bx0, by0, bx1, by1 = cfg["bbox"]
    return [[bx0 + lpts[2 * i] * (bx1 - bx0), by0 + lpts[2 * i + 1] * (by1 - by0)] for i in range(len(lpts) // 2)]


def blank_check_numbers(fp, plan, marks, problems):
    """which stride samples of the full-res base raster each mark covers, and that none of them
    is inside the clip polygon. pixel (i, j) centre maps to page x0 + (i+0.5)*w/W, y1 - (j+0.5)*h/H"""
    x0, y0, x1, y1 = plan["region"]
    W, H = plan["W"], plan["H"]
    sx, sy = W / (x1 - x0), H / (y1 - y0)
    out = []
    for lab, a, b, c, d in marks:
        n, inside, worst = 0, 0, float("inf")
        for j in range(0, H, BLANK_STRIDE):
            py = y1 - (j + 0.5) / sy
            if not (b <= py <= d):
                continue
            for i in range(0, W, BLANK_STRIDE):
                px = x0 + (i + 0.5) / sx
                if not (a <= px <= c):
                    continue
                n += 1
                dist = clip_distance_pt(fp, (px, py)) * min(sx, sy)
                worst = min(worst, -dist)
                if dist > 0:
                    inside += 1
        if n == 0 or inside or worst < 2.0:
            problems.append("blankCorner: mark %s covers %d samples, %d inside, margin %r px" % (lab, n, inside, worst))
        out.append({"label": lab, "pageRect": [a, b, c, d], "strideSamplesCovered": n, "samplesInsideClip": inside,
                    "minDistanceOutsideClipPx": round(worst, 3)})
    return out


def tile_px_expect(g, page_pt, zs, tps):
    lat, lon = g.to_wgs84(*page_pt)
    out = []
    for tp in tps:
        for z in zs:
            (tx, ty), (u, v) = latlon_to_tile_px(z, tp, lat, lon)
            out.append({"tilePx": tp, "z": z, "tile": [tx, ty], "px": [round(u, 4), round(v, 4)]})
    return {"wgs84": [rdeg(lat), rdeg(lon)], "expected": out}


def clip_distance_pt(fp, page_pt):
    """page point to the clip polygon boundary in points, positive inside"""
    sd = convex_signed_dist(page_pt, fp.clip, fp.ccw)
    if sd < 0:
        # outside: true distance to the convex polygon boundary
        best = float("inf")
        n = len(fp.clip)
        for i in range(n):
            ax, ay = fp.clip[i]
            bx, by = fp.clip[(i + 1) % n]
            ex, ey = bx - ax, by - ay
            t = max(0.0, min(1.0, ((page_pt[0] - ax) * ex + (page_pt[1] - ay) * ey) / (ex * ex + ey * ey)))
            best = min(best, math.hypot(page_pt[0] - ax - t * ex, page_pt[1] - ay - t * ey))
        sd = -best
    return sd


def synthetic_sheets(datums, problems):
    """build the markers + blank PDFs in memory and their fixture entries"""
    res, files = {}, {}

    lpts, gpts = iso_vp_numbers(MARKERS, rot90=True)
    aff = fit_affine_from_vp(MARKERS, lpts, gpts)
    b = MARKERS["bbox"]
    crop = [[b[0], b[1]], [b[0], b[3]], [b[2], b[3]], [b[2], b[1]]]
    g = Georef("render_markers", UTM10N, {"id": "WGS84"}, aff, crop, datums)
    cb = MARKERS["cropBox"]
    m = MARKERS["media"]
    pbox = (max(cb[0], m[0]), max(cb[1], m[1]), min(cb[2], m[2]), min(cb[3], m[3]))
    fp = Footprint(g, pbox)
    zp, mmpp, dz = zoom_policy("render_markers", g, fp, problems)
    bmz = {r["tilePx"]: r["baseMaxZoom"] for r in zp["basePlan"]["normal"]["baseMaxZoom"]}
    zs = list(range(max(0, min(bmz[512], bmz[768]) - 1), dz + 1))
    for lab, x, y in MARKERS["markers"]:
        if convex_signed_dist((x, y), fp.clip, fp.ccw) < 4:
            problems.append("markers: %s is too close to the clip edge" % lab)
    data = markers_pdf(MARKERS, lpts, gpts)
    files["tacmap_render_markers.pdf"] = data
    samples = []
    for kind, pts, alpha in (("inside", MARKERS["inside"], 255), ("outside", MARKERS["outside"], 0)):
        for lab, x, y in pts:
            for tp in (768, 512):
                for z in zs:
                    d = clip_distance_pt(fp, (x, y)) * px_per_pt(mmpp, z, tp)
                    if abs(d) < 2.0:
                        continue      # too close to the antialiased neatline at this zoom, skip it
                    lat, lon = g.to_wgs84(x, y)
                    (tx, ty), (u, v) = latlon_to_tile_px(z, tp, lat, lon)
                    samples.append({"label": lab, "page": [x, y], "tilePx": tp, "z": z, "tile": [tx, ty],
                                    "px": [round(u, 4), round(v, 4)], "alpha": alpha,
                                    "distancePx": round(d, 3)})
    res["markers"] = {
        "file": "geopdf/tacmap_render_markers.pdf",
        "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data),
        "mediaBox": MARKERS["media"], "cropBox": MARKERS["cropBox"], "rotate": MARKERS["rotate"],
        "georef": {"origin": "adobeVP", "crs": UTM10N, "datum": {"id": "WGS84"}, "crop": crop,
                   "affine": [rsig(v, 15) for v in aff],
                   "note": "what a WP1 parse of the /VP gives (least squares over the written GPTS/LPTS); "
                           "compare like pdf_georef.json sheets (0.01 m, 1e-3 pt)"},
        "written": {"bbox": b, "lpts": lpts, "gpts": gpts},
        "zoomPolicy": zp,
        "markerSizePt": 4.0,
        "markerColor": "#FF0000",
        "markers": [dict({"label": lab, "page": [x, y]}, **tile_px_expect(g, (x, y), zs, (768, 512)))
                    for lab, x, y in MARKERS["markers"]],
        "clippedMarker": dict({"label": MARKERS["clippedMarker"][0], "page": list(MARKERS["clippedMarker"][1:]),
                               "note": "inside the neatline but outside the CropBox: must NOT be drawn (alpha 0)"},
                              **tile_px_expect(g, MARKERS["clippedMarker"][1:], zs, (768, 512))),
        "hiddenOcgSquare": dict({"page": [MARKERS["hidden"][0], MARKERS["hidden"][1]], "sizePt": MARKERS["hidden"][2],
                                 "color": "#0000FF",
                                 "note": "in optional content group oc1, OFF in the default config. paper white expected"},
                                **tile_px_expect(g, MARKERS["hidden"][:2], zs, (768, 512))),
        "alphaSamples": samples,
    }

    lpts2, gpts2 = iso_vp_numbers(BLANK, rot90=False)
    aff2 = fit_affine_from_vp(BLANK, lpts2, gpts2)
    b2 = BLANK["bbox"]
    data2 = blank_pdf(BLANK, lpts2, gpts2)
    files["tacmap_render_blank.pdf"] = data2
    res["blank"] = {
        "file": "geopdf/tacmap_render_blank.pdf",
        "sha256": hashlib.sha256(data2).hexdigest(), "bytes": len(data2),
        "mediaBox": BLANK["media"], "cropBox": None, "rotate": 0,
        "georef": {"origin": "adobeVP", "crs": UTM10N, "datum": {"id": "WGS84"},
                   "crop": [[b2[0], b2[1]], [b2[0], b2[3]], [b2[2], b2[3]], [b2[2], b2[1]]],
                   "affine": [rsig(v, 15) for v in aff2]},
        "expectedFailure": "blank",
        "note": "white page plus a 254/255 grey box. every base raster sample is either transparent or has "
                "min(R,G,B) > 250, so the import fails with blank",
    }

    extra = {}
    lpts3, gpts3 = iso_vp_numbers(DENSE, rot90=False)
    aff3 = fit_affine_from_vp(DENSE, lpts3, gpts3)
    crop3 = vp_crop(DENSE, [0.0, 0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 0.0])
    g3 = Georef("render_dense", UTM10N, {"id": "WGS84"}, aff3, crop3, datums)
    fp3 = Footprint(g3, tuple(DENSE["media"]))
    zp3, _, _ = zoom_policy("render_dense", g3, fp3, problems)
    data3 = dense_pdf(DENSE, lpts3, gpts3)
    files["tacmap_render_dense.pdf"] = data3
    res["dense"] = {
        "file": "geopdf/tacmap_render_dense.pdf",
        "sha256": hashlib.sha256(data3).hexdigest(), "bytes": len(data3),
        "mediaBox": DENSE["media"], "cropBox": None, "rotate": 0,
        "georef": {"origin": "adobeVP", "crs": UTM10N, "datum": {"id": "WGS84"}, "crop": crop3,
                   "affine": [rsig(v, 15) for v in aff3]},
        "written": {"bbox": DENSE["bbox"], "lpts": lpts3, "gpts": gpts3},
        "linework": {"diagonals": DENSE["diagonals"], "diagonalWidthPt": DENSE["diagonalWidthPt"],
                     "diagonalColor": "#000000", "gridStepPt": DENSE["gridStepPt"],
                     "gridWidthPt": DENSE["gridWidthPt"], "gridColor": "#FF0000",
                     "note": "white paper over the MediaBox, then diagonal i = 0..40 from (x0 + w*i/40 - w/2, y0) to "
                             "(x0 + w*i/40 + w/2, y1), then vertical and horizontal grid lines every 12 pt from the "
                             "MediaBox origin. stroked with butt caps"},
        "zoomPolicy": zp3,
    }
    extra["dense"] = (g3, fp3, zp3)

    lp4 = BLANK_CORNER["lpts"]
    lpts4, gpts4 = iso_vp_numbers(BLANK_CORNER, rot90=False, lpts=lp4)
    aff4 = fit_affine_from_vp(BLANK_CORNER, lpts4, gpts4)
    crop4 = vp_crop(BLANK_CORNER, lp4)
    g4 = Georef("render_blank_corner", UTM10N, {"id": "WGS84"}, aff4, crop4, datums)
    fp4 = Footprint(g4, tuple(BLANK_CORNER["media"]))
    zp4, _, _ = zoom_policy("render_blank_corner", g4, fp4, problems)
    data4 = blank_corner_pdf(BLANK_CORNER, lpts4, gpts4)
    files["tacmap_render_blank_corner.pdf"] = data4
    res["blankCorner"] = {
        "kind": "blankCheck",
        "label": "marks outside the clip polygon but inside its bbox never count toward non-blank",
        "file": "geopdf/tacmap_render_blank_corner.pdf",
        "sha256": hashlib.sha256(data4).hexdigest(), "bytes": len(data4),
        "mediaBox": BLANK_CORNER["media"], "cropBox": None, "rotate": 0,
        "georef": {"origin": "adobeVP", "crs": UTM10N, "datum": {"id": "WGS84"}, "crop": crop4,
                   "affine": [rsig(v, 15) for v in aff4]},
        "written": {"bbox": BLANK_CORNER["bbox"], "lpts": lpts4, "bounds": lp4, "gpts": gpts4},
        "clipPolygon": zp4["clipPolygon"], "clipBBox": zp4["basePlan"]["normal"]["region"],
        "basePlan": {k: zp4["basePlan"]["normal"][k] for k in ("region", "s", "W", "H")},
        "blankSampleStride": BLANK_STRIDE,
        "marks": blank_check_numbers(fp4, zp4["basePlan"]["normal"], BLANK_CORNER["marks"], problems),
        "markColor": "#000000",
        "expected": {"blank": True, "importFailure": "blank"},
        "note": "white paper over the MediaBox plus four black squares in the BBox corners, all outside the /Bounds "
                "quad. the base raster is clipped to the quad before paper fill + draw (G3), so every covered "
                "stride sample stays alpha 0 and the import fails with blank. if the marks counted it wouldnt",
    }
    return res, files, extra


def ring_targets(georefs, raw_sheets):
    """WP1 sheets draw a black 4 pt ring (0.8 pt stroke) at each truth.fiducialTargets page point.
    the label text starts 6 pt up and right, so a dark-pixel centroid in a 6 pt radius window is clean"""
    out = []
    for sid in ("rot5_iso", "offset_iso", "rot90_iso"):
        g, fp, dz = georefs[sid]
        sh = raw_sheets[sid]
        for f in sh["truth"]["fiducialTargets"]:
            x, y = f["page"]
            out.append(dict({"sheet": sid, "id": f["id"], "page": [x, y]},
                            **tile_px_expect(g, (x, y), list(range(13, dz + 1)), (768, 512))))
    return out


# ----------------------------------------------------------------------------
# self checks against pdf_georef.json
# ----------------------------------------------------------------------------

def self_check(gref, raw, sheets_by_id, problems):
    worst_deg, worst_pt = 0.0, 0.0
    for sid, (g, _, _) in sheets_by_id.items():
        for c in raw[sid]["expected"]["checks"]:
            ll = g.to_wgs84(*c["page"])
            worst_deg = max(worst_deg, abs(ll[0] - c["wgs84"][0]), abs(ll[1] - c["wgs84"][1]))
            p = g.to_page(*c["wgs84"])
            worst_pt = max(worst_pt, abs(p[0] - c["toPage"][0]), abs(p[1] - c["toPage"][1]))
    for t in gref["tileWarp"]["tiles"]:
        if t["georef"] not in sheets_by_id:
            continue
        g = sheets_by_id[t["georef"]][0]
        for s in t["samples"]:
            lat, lon = job_px_to_latlon(t["z"], t["x"], t["y"], 256, s["px"][0], s["px"][1])
            p = g.to_page(lat, lon)
            worst_pt = max(worst_pt, abs(p[0] - s["page"][0]), abs(p[1] - s["page"][1]))
    if worst_deg > 1e-9 or worst_pt > 1e-5:
        problems.append("PROJ pipeline disagrees with pdf_georef.json: %g deg, %g pt" % (worst_deg, worst_pt))
    return {"maxDegrees": float("%.3g" % worst_deg), "maxPagePoints": float("%.3g" % worst_pt)}


# ----------------------------------------------------------------------------
# json writer: indented objects, flat arrays kept on one line (same look as pdf_georef.json)
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
            line = "{" + ", ".join("%s: %s" % (json.dumps(k), dumps(v)) for k, v in o.items()) + "}"
            if len(line) <= 260:
                return line
        return "{\n" + ",\n".join("%s  %s: %s" % (sp, json.dumps(k), dumps(v, ind + 1)) for k, v in o.items()) + "\n" + sp + "}"
    if isinstance(o, (list, tuple)):
        if not o:
            return "[]"
        flat = all(not isinstance(v, (dict, list, tuple)) for v in o)
        pairs = all(isinstance(v, (list, tuple)) and all(not isinstance(w, (dict, list, tuple)) for w in v) for v in o)
        if flat or (pairs and len(o) <= 12):
            line = "[" + ", ".join(dumps(v) for v in o) + "]"
            if len(line) <= 400 or flat:
                return line
        if pairs:
            # long tile lists: a few pairs per line so the diff stays readable
            chunks = [", ".join(dumps(v) for v in o[i:i + 10]) for i in range(0, len(o), 10)]
            return "[\n" + ",\n".join("%s  %s" % (sp, c) for c in chunks) + "\n" + sp + "]"
        return "[\n" + ",\n".join("%s  %s" % (sp, dumps(v, ind + 1)) for v in o) + "\n" + sp + "]"
    if isinstance(o, bool) or o is None:
        return json.dumps(o)
    if isinstance(o, float):
        if not math.isfinite(o):
            raise ValueError("non-finite float in json")
        if o == int(o) and abs(o) < 1e15:
            return repr(float(o))
        return repr(o)
    return json.dumps(o)


# ----------------------------------------------------------------------------

def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--out", default=os.path.join(REPO, "testdata"), help="testdata dir to write into")
    ap.add_argument("--georef", default=os.path.join(REPO, "testdata", "pdf_georef.json"))
    ap.add_argument("--check", action="store_true", help="dont write, exit 1 if the outputs would change")
    args = ap.parse_args()

    try:
        import numpy
        import pyproj
    except ImportError:
        sys.exit("need pyproj + numpy: pip install pyproj numpy")

    with open(args.georef) as fh:
        gref = json.load(fh)
    datums = {d["id"]: d for d in gref["datums"]["table"]}
    raw = {s["id"]: s for s in gref["sheets"]}
    problems = []

    sheets, by_id = [], {}
    for sid in SHEETS:
        s = raw[sid]
        e = s["expected"]
        g = Georef(sid, e["crs"], e["datum"], e["affine"], e["crop"], datums)
        m = s["mediaBox"]
        cb = s.get("cropBox") or m
        pbox = (max(cb[0], m[0]), max(cb[1], m[1]), min(cb[2], m[2]), min(cb[3], m[3]))
        fp = Footprint(g, pbox)
        sheets.append([sid, g, fp, None])
        by_id[sid] = (g, fp, None)
    check = self_check(gref, raw, by_id, problems)

    policies = []
    for row in sheets:
        sid, g, fp, _ = row
        zp, mmpp, dz = zoom_policy(sid, g, fp, problems, {"file": raw[sid]["file"], "rotate": raw[sid]["rotate"]})
        row[3] = dz
        by_id[sid] = (g, fp, dz)
        policies.append(zp)

    coverage, counts = coverage_section(sheets, problems)
    bake = [bake_options(sid, dz, counts[sid]) for sid, _, _, dz in sheets]
    warp = warp_section(sheets, problems) + wide_warp(datums, problems)
    synth, pdfs, synth_geo = synthetic_sheets(datums, problems)
    wide_g = Georef(WIDE["id"], WIDE["crs"], WIDE["datum"], WIDE["affine"], WIDE["crop"], datums)
    wide = (wide_g, Footprint(wide_g, tuple(WIDE["mediaBox"])))
    staged = staged_region_section(sheets, wide, problems) + [synth["blankCorner"]]
    pol_by = {p["sheet"]: p for p in policies}
    psnr = psnr_section([("rot5_iso", raw["rot5_iso"]["file"], by_id["rot5_iso"][1], pol_by["rot5_iso"]),
                         ("dense", synth["dense"]["file"], synth_geo["dense"][1], synth_geo["dense"][2])], problems)

    doc = {
        "description": "Shared WP2 PDF tile render fixture (plans/WP2-render-shared_contract.md). Georefs come from "
                       "pdf_georef.json; every expected number is computed by scripts/gen_pdf_tile_render.py with a "
                       "PROJ pipeline + plain python geometry, never from app code. Regenerate, dont hand edit.",
        "schemaVersion": 1,
        "generator": "scripts/gen_pdf_tile_render.py",
        "reference": {"pyproj": pyproj.__version__, "proj": pyproj.proj_version_str, "numpy": numpy.__version__,
                      "selfCheckVsPdfGeoref": check},
        "conventions": {
            "pageSpace": "WP1 raw user space: y up, box origins included, /Rotate ignored",
            "georef": "toWGS84/toPage exactly as pdf_georef.json conventions; georefs looked up by sheet id in "
                      "pdf_georef.json sheets[].expected (crs, datum, affine, crop)",
            "pageBox": "CropBox intersect MediaBox (MediaBox when there is no CropBox)",
            "clipPolygon": "Sutherland-Hodgman of the crop (as written, in order) against pageBox, clip edges in "
                           "order x >= x0, x <= x1, y >= y0, y <= y1. a point on a clip edge is inside; an "
                           "intersection is emitted only when S and E are on opposite sides. then drop a vertex "
                           "equal (|dx|,|dy| <= 1e-9) to the previous one, wrap included. clipMean is the plain "
                           "mean of these vertices, so duplicates change it; compare the polygon as a cyclic "
                           "sequence (same orientation, any start)",
            "footprint": "clip polygon densified to 32 segments per edge (vertex k of edge i = P_i + (P_i+1 - P_i) * "
                         "k/32, k = 0..31), each through toWGS84 then spherical web mercator z0 world units: "
                         "X = (lon + 180) / 360 * 256, Y = (1 - ln(tan(pi/4 + lat/2)) / pi) / 2 * 256, y down",
            "tileClassification": "tile (z,x,y) square = [x, x+1] x [y, y+1] * 256 / 2^z in z0 units, closed. EDGE "
                                  "when the footprint boundary touches the square, INSIDE when it doesnt and the "
                                  "square centre is inside the footprint, otherwise OUTSIDE",
            "tileLists": "sorted by y then x, entries [x, y]. sha256 = hex sha256 of the ascii lines 'z/x/y' joined "
                         "by \\n (no trailing newline) in that order. runs = [y, xFirst, xLast] row major",
            "jobPixels": "job pixel (u, v), y down, origin top-left of tile (x0, y0): wx = x0*256 + u*256/tilePx, "
                         "wy = y0*256 + v*256/tilePx at zoom z, lon = wx / (256*2^z) * 360 - 180, "
                         "lat = atan(sinh(pi * (1 - 2*wy / (256*2^z)))), then georef.toPage",
            "warp": "rects are [l, t, r, b] in job px, r and b exclusive. pageToPx [a,b,c,d,e,f]: u = a*x + b*y + c, "
                    "v = d*x + e*y + f. root = job rect intersect (floor(min u) - 2, floor(min v) - 2, ceil(max u) + 2, "
                    "ceil(max v) + 2) of the footprint in job px; null when empty. cells listed in depth first "
                    "order, children TL, TR, BL, BR. cellCount counts emitted (non OUTSIDE) leaves; leafCount "
                    "includes OUTSIDE leaves; droppedCells = non-finite/singular leaves at the split limit. cell "
                    "coverage is the page quad TL, TR, BR, BL against the clip polygon",
            "zoom": "tileZoom = clamp(round(cameraZoom), minZoom, maxZoom), round = ties up (camera zoom is > 0). "
                    "pinch: clamp(zoom + log2(scale), 2, 22)",
        },
        "tolerances": {
            "clipPolygonPt": 1e-6, "clipMeanPt": 1e-6, "mercMetresPerPointRel": 1e-6, "detailZoomRawAbs": 1e-6,
            "pxPerPtRel": 1e-9, "pxPerPtRelNote": "1e-9 holds when pxPerPt is computed from the fixture "
                                                  "mercMetresPerPoint; from a platform's own value use 1e-6",
            "footprintWorld": 1e-9, "warpPageToPxRel": 1e-6, "warpPageToPxAbsFloor": 1e-6, "warpPagePt": 1e-4,
            "warpErrorPx": 1e-4, "requiredPxPerPtRel": 1e-6, "tilePxExpectPx": 0.01,
            "markerCentroidPx": 0.5, "cameraZoom": 1e-9,
            "bakeEstimateCentreTile": 1e-9, "bakeEstimateD2": 1e-9, "bakeEstimateMeansRel": 1e-12,
            "stagedRegionPt": 1e-9, "stagedDensityRel": 1e-12,
            "stagedNote": "stagedRegion pad/region/rw/rh 1e-9 pt (abs), cap/d0/d 1e-12 rel; W, H, maxPx, "
                          "shrinkSteps, result exact",
            "exact": "W, H, mips, detailZoom, baseMaxZoom, tile sets, counts, rects, "
                                             "job formation, draw plans, crash guard, tilePx",
        },
        "margins": {
            "note": "the generator refuses to write if any discrete decision is closer than these, so a correct "
                    "platform implementation can't legitimately land on the other side",
            "footprintVsTileWorld": EPS_WORLD, "cellQuadVsClipPt": EPS_PAGE, "splitErrorVs025Px": EPS_ERR,
            "baseRasterCeilInputs": EPS_CEIL, "warpRootFloorCeilPx": EPS_ROOT, "baseMaxZoomRel": EPS_REL},
        "constants": constants(),
        "tilePx": [{"density": d, "tilePx": tile_px(d)} for d in
                   (0.75, 1.0, 1.3125, 1.33125, 1.5, 1.53125, 1.75, 2.0, 2.25, 2.625, 2.75, 3.0, 3.5, 4.0)],
        "zoomPolicy": policies,
        "coverage": coverage,
        "bakeOptions": bake,
        "warpSyntheticGeorefs": [dict(WIDE, note="not a PDF. build the georef straight from these numbers "
                                                 "(crs, datum, affine, crop) and use mediaBox as the page box")],
        "warp": warp,
        "jobFormation": job_formation_section(),
        "drawPlan": draw_plan_section(),
        "crashGuard": crash_guard_section(),
        "bakeJobFormation": bake_job_formation_section(),
        "failureAccounting": failure_accounting_section(),
        "stagedRegion": staged,
        "bakeEstimate": bake_estimate_section(sheets, policies, bake, problems),
        "bakeFormat": bake_format_section(problems),
        "psnr": psnr,
        "cameraZoom": camera_zoom_section(),
        "markers": synth["markers"],
        "blank": synth["blank"],
        "dense": synth["dense"],
        "ringTargets": ring_targets(by_id, raw),
    }

    if problems:
        for p in problems:
            print("PROBLEM:", p, file=sys.stderr)
        sys.exit("refusing to write: %d borderline/consistency problems" % len(problems))

    text = dumps(doc) + "\n"
    outputs = {os.path.join(args.out, "pdf_tile_render.json"): text.encode("utf-8")}
    for name, data in pdfs.items():
        outputs[os.path.join(args.out, "geopdf", name)] = data
    changed = []
    for path, data in outputs.items():
        old = open(path, "rb").read() if os.path.exists(path) else None
        if old != data:
            changed.append(path)
            if not args.check:
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "wb") as fh:
                    fh.write(data)
    for path in outputs:
        print("%s %s (%d bytes)" % ("changed" if path in changed else "same   ", os.path.relpath(path, REPO),
                                    len(outputs[path])))
    if args.check and changed:
        sys.exit(1)


if __name__ == "__main__":
    main()
