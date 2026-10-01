#!/usr/bin/env python3
# Builds testdata/mgrs_grid.json, the shared contract for the MGRS grid overlay
# (plan 02 section 3). Geometry comes from PROJ via pyproj (etmerc UTM), the
# 100 km letters from the plain AA-scheme MGRS math, LOD + label placement from
# the policy constants below. Nothing here reads app code, so it's an
# independent third implementation both platforms get checked against.
#
# needs pyproj + numpy:  python3 scripts/gen_mgrs_grid_fixture.py [out.json]
# (defaults to testdata/mgrs_grid.json next to this script's repo)

import json
import math
import os
import sys
from functools import lru_cache

import numpy as np
from pyproj import Transformer

# ---- policy (keep in sync with the "policy" block written out below) ----

EARTH_R = 6378137.0
TILE_DP = 256.0
MERC_LAT_LIMIT = 85.0511287798066
LEVELS = [("100km", 100000), ("10km", 10000), ("1km", 1000)]  # coarse -> fine
LINE_MIN_DP = 32.0
LABEL_MIN_DP = 40.0
LABEL_INSET_DP = 16.0
SQUARE_LABEL_MIN_DP = 40.0
SQUARE_LABEL_OFFSET_DP = 32.0
SQUARE_ANCHOR_TOL_DP = 2.0
MAX_SAGITTA_PX = 0.25
VERTEX_TOL_M = 0.005
CLIP_EPS_DEG = 1e-9
GRID_LAT_MIN, GRID_LAT_MAX = -80.0, 84.0

BANDS = "CDEFGHJKLMNPQRSTUVWX"
# GZD cells whose longitude span isn't the plain 6 degree strip. None = cell doesn't exist
SPAN_EXCEPTIONS = {
    "31V": (0.0, 3.0), "32V": (3.0, 12.0),
    "31X": (0.0, 9.0), "32X": None, "33X": (9.0, 21.0), "34X": None,
    "35X": (21.0, 33.0), "36X": None, "37X": (33.0, 42.0),
}
COL_SETS = {1: "ABCDEFGH", 2: "JKLMNPQR", 0: "STUVWXYZ"}
ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV"

# rough label boxes (dp, w x h on screen) only used to flag pairs that might
# collide depending on the platform font. deliberately a bit generous
BOX_EASTING = (20.0, 24.0)   # rotated -90 so taller than wide
BOX_NORTHING = (24.0, 20.0)
BOX_SQUARE = (36.0, 24.0)
BOX_PAD = 2.0


# ---- small helpers ----

def rnd(v, n):
    r = round(float(v), n)
    return 0.0 if r == 0 else r


def band_lat(b):
    i = BANDS.index(b)
    s = -80.0 + 8.0 * i
    return s, (84.0 if b == "X" else s + 8.0)


def zone_lon(z, b):
    key = f"{z}{b}"
    if key in SPAN_EXCEPTIONS:
        return SPAN_EXCEPTIONS[key]
    return 6.0 * z - 186.0, 6.0 * z - 180.0


def hemi_of(b):
    return "N" if b >= "N" else "S"


class Cell:
    def __init__(self, zone, band):
        self.zone, self.band = zone, band
        self.lonW, self.lonE = zone_lon(zone, band)
        self.latS, self.latN = band_lat(band)
        self.hemi = hemi_of(band)

    @property
    def gzd(self):
        return f"{self.zone}{self.band}"


def cells_in(box):
    s, w, n, e = box
    out = []
    for b in BANDS:
        bs, bn = band_lat(b)
        if not (bs < n and bn > s):
            continue
        for z in range(1, 61):
            sp = zone_lon(z, b)
            if sp is None:
                continue
            if sp[0] < e and sp[1] > w:
                out.append(Cell(z, b))
    return out


@lru_cache(None)
def tf(zone, hemi):
    epsg = (32600 if hemi == "N" else 32700) + zone
    fwd = Transformer.from_crs("EPSG:4326", f"EPSG:{epsg}", always_xy=True)
    inv = Transformer.from_crs(f"EPSG:{epsg}", "EPSG:4326", always_xy=True)
    return fwd, inv


def to_utm(zone, hemi, lat, lon):
    e, n = tf(zone, hemi)[0].transform(np.asarray(lon, float), np.asarray(lat, float))
    return np.asarray(e), np.asarray(n)


def to_ll(zone, hemi, e, n):
    lon, lat = tf(zone, hemi)[1].transform(np.asarray(e, float), np.asarray(n, float))
    return np.asarray(lat), np.asarray(lon)


def merc(lat, lon):
    lat = np.clip(np.asarray(lat, float), -MERC_LAT_LIMIT, MERC_LAT_LIMIT)
    x = EARTH_R * np.radians(lon)
    y = EARTH_R * np.log(np.tan(math.pi / 4 + np.radians(lat) / 2))
    return x, y


def metres_per_dp(lat, zoom):
    lat = max(-MERC_LAT_LIMIT, min(MERC_LAT_LIMIT, lat))
    return math.cos(math.radians(lat)) * 2 * math.pi * EARTH_R / (TILE_DP * 2 ** zoom)


def spacing_dp(metres, lat, zoom):
    return metres / metres_per_dp(lat, zoom)


def lod(zoom, lat):
    sp = {name: spacing_dp(m, lat, zoom) for name, m in LEVELS}
    drawn = [name for name, _ in LEVELS if sp[name] >= LINE_MIN_DP]
    labelled = [name for name, _ in LEVELS if sp[name] >= LABEL_MIN_DP]
    return sp, drawn, labelled


def level_metres(name):
    return dict(LEVELS)[name]


def owner_level(value, drawn):
    # coarsest drawn level whose spacing divides the value
    for name, m in LEVELS:
        if name in drawn and value % m == 0:
            return name
    return None


def square_letters(zone, e0, n0):
    col = COL_SETS[zone % 3][int(e0 // 100000) - 1]
    row_i = int(n0 // 100000) % 20
    if zone % 2 == 0:
        row_i = (row_i + 5) % 20
    return col + ROW_LETTERS[row_i]


def line_text(value):
    return "%02d" % ((int(value) % 100000) // 1000)


# ---- region / line clipping (reference, brute force but exact enough) ----

def region(cell, box, lon_shrink=0.0):
    # lon_shrink pulls the zone edges in (labels only, so they don't straddle a zone junction)
    s, w, n, e = box
    return dict(
        latS=max(cell.latS, s, GRID_LAT_MIN), latN=min(cell.latN, n, GRID_LAT_MAX),
        lonW=max(cell.lonW + lon_shrink, w), lonE=min(cell.lonE - lon_shrink, e),
        # cell north/east edges are half open, box edges closed
        northOpen=cell.latN <= n, eastOpen=cell.lonE <= e,
    )


def region_extent(cell, reg):
    k = 257
    lats = np.concatenate([np.full(k, reg["latS"]), np.full(k, reg["latN"]),
                           np.linspace(reg["latS"], reg["latN"], k), np.linspace(reg["latS"], reg["latN"], k)])
    lons = np.concatenate([np.linspace(reg["lonW"], reg["lonE"], k), np.linspace(reg["lonW"], reg["lonE"], k),
                           np.full(k, reg["lonW"]), np.full(k, reg["lonE"])])
    e, n = to_utm(cell.zone, cell.hemi, lats, lons)
    return float(e.min()), float(e.max()), float(n.min()), float(n.max())


def line_ll(cell, axis, value, t):
    t = np.asarray(t, float)
    if axis == "easting":
        return to_ll(cell.zone, cell.hemi, np.full_like(t, value), t)
    return to_ll(cell.zone, cell.hemi, t, np.full_like(t, value))


def inside(reg, lat, lon, eps=1e-12):
    if lat < reg["latS"] - eps or lon < reg["lonW"] - eps:
        return False
    if reg["northOpen"]:
        if lat >= reg["latN"] - eps:
            return False
    elif lat > reg["latN"] + eps:
        return False
    if reg["eastOpen"]:
        if lon >= reg["lonE"] - eps:
            return False
    elif lon > reg["lonE"] + eps:
        return False
    return True


def line_pieces(cell, reg, axis, value, t0, t1, samples=600):
    """Parameter intervals [a, b] of the line inside reg. t is northing for
    easting lines and easting for northing lines."""
    ts = np.linspace(t0, t1, samples)
    lat, lon = line_ll(cell, axis, value, ts)

    def g(latv, lonv):
        return np.array([latv - reg["latS"], reg["latN"] - latv, lonv - reg["lonW"], reg["lonE"] - lonv])

    G = g(lat, lon)
    crit = [t0, t1]
    for k in range(4):
        gk = G[k]
        for i in range(samples - 1):
            if gk[i] == 0:
                crit.append(ts[i])
            elif gk[i] * gk[i + 1] < 0:
                a, b = ts[i], ts[i + 1]
                ga = gk[i]
                for _ in range(80):
                    m = 0.5 * (a + b)
                    la, lo = line_ll(cell, axis, value, [m])
                    gm = g(la[0], lo[0])[k]
                    if gm == 0:
                        a = b = m
                        break
                    if (gm < 0) == (ga < 0):
                        a, ga = m, gm
                    else:
                        b = m
                crit.append(0.5 * (a + b))
    crit = sorted(set(crit))
    out = []
    for a, b in zip(crit, crit[1:]):
        if b - a < 1e-6:
            continue
        m = 0.5 * (a + b)
        la, lo = line_ll(cell, axis, value, [m])
        if inside(reg, float(la[0]), float(lo[0])):
            if out and abs(out[-1][1] - a) < 1e-6:
                out[-1][1] = b
            else:
                out.append([a, b])
    return out


def region_lines(cell, reg, drawn, only_spacing=None):
    """Every (axis, value, owner level, pieces) for the drawn levels in reg."""
    emin, emax, nmin, nmax = region_extent(cell, reg)
    values = {"easting": set(), "northing": set()}
    for name, m in LEVELS:
        if name not in drawn:
            continue
        if only_spacing is not None and m != only_spacing:
            continue
        for v in range(int(math.ceil(emin / m)) * m, int(math.floor(emax / m)) * m + 1, m):
            values["easting"].add(v)
        for v in range(int(math.ceil(nmin / m)) * m, int(math.floor(nmax / m)) * m + 1, m):
            values["northing"].add(v)
    out = []
    for axis in ("easting", "northing"):
        lo, hi = (nmin, nmax) if axis == "easting" else (emin, emax)
        for v in sorted(values[axis]):
            # equator belongs to the northern hemisphere (N=0) only
            if axis == "northing" and cell.hemi == "S" and v == 10000000:
                continue
            ps = line_pieces(cell, reg, axis, v, lo - 2000, hi + 2000)
            if ps:
                out.append((axis, v, owner_level(v, drawn), ps))
    return out


def ground_dist_to_chord(p_lat, p_lon, a, b):
    # distance from point p to segment a-b in web mercator metres, scaled by cos(lat_p)
    px, py = merc(p_lat, p_lon)
    ax, ay = merc(*a)
    bx, by = merc(*b)
    dx, dy = bx - ax, by - ay
    L2 = dx * dx + dy * dy
    t = 0.0 if L2 == 0 else max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / L2))
    d = math.hypot(px - (ax + t * dx), py - (ay + t * dy))
    return d * math.cos(math.radians(p_lat))


def sample_piece(cell, axis, value, a, b, max_err):
    n = 4
    while True:
        ts = np.linspace(a, b, n + 1)
        lat, lon = line_ll(cell, axis, value, ts)
        mids = 0.5 * (ts[:-1] + ts[1:])
        mlat, mlon = line_ll(cell, axis, value, mids)
        worst = max(ground_dist_to_chord(float(mlat[i]), float(mlon[i]), (lat[i], lon[i]), (lat[i + 1], lon[i + 1]))
                    for i in range(n))
        if worst <= max_err or n >= 1024:
            return [[rnd(la, 10), rnd(lo, 10)] for la, lo in zip(lat, lon)], worst
        n *= 2


# ---- camera (north-up, dp) ----

class Camera:
    def __init__(self, lat, lon, zoom, w, h):
        self.lat, self.lon, self.zoom, self.w, self.h = lat, lon, zoom, w, h
        self.size = TILE_DP * 2 ** zoom
        self.cx, self.cy = self.world(lat, lon)

    def world(self, lat, lon):
        lat = np.clip(np.asarray(lat, float), -MERC_LAT_LIMIT, MERC_LAT_LIMIT)
        x = (np.asarray(lon, float) + 180.0) / 360.0 * self.size
        s = np.sin(np.radians(lat))
        y = (0.5 - np.log((1 + s) / (1 - s)) / (4 * math.pi)) * self.size
        return x, y

    def screen(self, lat, lon):
        x, y = self.world(lat, lon)
        return x - self.cx + self.w / 2, y - self.cy + self.h / 2

    def coord(self, sx, sy):
        x = sx - self.w / 2 + self.cx
        y = sy - self.h / 2 + self.cy
        lon = x / self.size * 360.0 - 180.0
        nn = math.pi - 2 * math.pi * y / self.size
        lat = math.degrees(math.atan(math.sinh(nn)))
        return lat, lon

    def box(self, inset=0.0):
        nlat, wlon = self.coord(inset, inset)
        slat, elon = self.coord(self.w - inset, self.h - inset)
        return (slat, wlon, nlat, elon)


def clip_poly_rect(poly, x0, y0, x1, y1):
    def clip(pts, inside_fn, inter_fn):
        out = []
        n = len(pts)
        for i in range(n):
            cur, prev = pts[i], pts[i - 1]
            ci, pi_ = inside_fn(cur), inside_fn(prev)
            if ci:
                if not pi_:
                    out.append(inter_fn(prev, cur))
                out.append(cur)
            elif pi_:
                out.append(inter_fn(prev, cur))
        return out

    def ix(xc):
        return lambda p, q: (xc, p[1] + (q[1] - p[1]) * (xc - p[0]) / (q[0] - p[0]))

    def iy(yc):
        return lambda p, q: (p[0] + (q[0] - p[0]) * (yc - p[1]) / (q[1] - p[1]), yc)

    pts = list(poly)
    for fn, it in ((lambda p: p[0] >= x0, ix(x0)), (lambda p: p[0] <= x1, ix(x1)),
                   (lambda p: p[1] >= y0, iy(y0)), (lambda p: p[1] <= y1, iy(y1))):
        if not pts:
            break
        pts = clip(pts, fn, it)
    return pts


def poly_area_centroid(pts):
    a = cx = cy = 0.0
    n = len(pts)
    for i in range(n):
        x0, y0 = pts[i]
        x1, y1 = pts[(i + 1) % n]
        c = x0 * y1 - x1 * y0
        a += c
        cx += (x0 + x1) * c
        cy += (y0 + y1) * c
    a *= 0.5
    if abs(a) < 1e-12:
        return 0.0, None
    return abs(a), (cx / (6 * a), cy / (6 * a))


def point_in_poly(p, pts):
    x, y = p
    c = False
    n = len(pts)
    for i in range(n):
        x0, y0 = pts[i]
        x1, y1 = pts[i - 1]
        if (y0 > y) != (y1 > y):
            xi = x0 + (y - y0) * (x1 - x0) / (y1 - y0)
            if x < xi:
                c = not c
    return c


def line_anchor_tol(cam, cell, axis, value, t, sx, sy, shrink):
    # same story as end_tol but on screen: a platform that densifies to 0.25 px at
    # 1 px/dp and clips linearly can be 0.25/sin(angle) dp off along the edge it hits
    la, lo = line_ll(cell, axis, value, [t - 1.0, t + 1.0])
    ax, ay = cam.screen(la[0], lo[0])
    bx, by = cam.screen(la[1], lo[1])
    dx, dy = float(bx - ax), float(by - ay)
    L = math.hypot(dx, dy)
    ins = LABEL_INSET_DP
    xs = [ins, cam.w - ins] + [float(cam.screen(0.0, x)[0]) for x in (cell.lonW + shrink, cell.lonE - shrink)]
    ys = [ins, cam.h - ins] + [float(cam.screen(y, cell.lonW)[1]) for y in (cell.latS, min(cell.latN, 85.0))]
    sines = []
    if min(abs(sx - x) for x in xs) < 1e-6:
        sines.append(abs(dx) / L)
    if min(abs(sy - y) for y in ys) < 1e-6:
        sines.append(abs(dy) / L)
    if not sines:
        return 0.5
    return 0.5 + MAX_SAGITTA_PX / max(max(sines), 0.02)


def poly_edge_dist(p, pts):
    return min(seg_dist_2d(p, pts[i - 1], pts[i]) for i in range(len(pts)))


def seg_dist_2d(p, a, b):
    dx, dy = b[0] - a[0], b[1] - a[1]
    L2 = dx * dx + dy * dy
    t = 0.0 if L2 == 0 else max(0.0, min(1.0, ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / L2))
    return math.hypot(p[0] - a[0] - t * dx, p[1] - a[1] - t * dy)


def visible_labels(cam):
    sp, drawn, labelled = lod(cam.zoom, cam.lat)
    ins = LABEL_INSET_DP
    ibox = cam.box(ins)
    labels = []

    finest = None
    for name in ("1km", "10km"):
        if name in labelled:
            finest = name
            break

    cells = cells_in(ibox)
    shrink = LABEL_INSET_DP * 360.0 / cam.size
    if finest is not None:
        m = level_metres(finest)
        by_key = {}
        for cell in cells:
            reg = region(cell, ibox, shrink)
            if reg["latS"] >= reg["latN"] or reg["lonW"] >= reg["lonE"]:
                continue
            for axis, v, owner, ps in region_lines(cell, reg, drawn, only_spacing=None):
                if v % m != 0:
                    continue
                key = (cell.zone, cell.hemi, axis, v)
                ent = by_key.setdefault(key, {"owner": owner, "ends": []})
                for a, b in ps:
                    la, lo = line_ll(cell, axis, v, [a, b])
                    ent["ends"] += [(float(la[0]), float(lo[0]), cell, a), (float(la[1]), float(lo[1]), cell, b)]
        for (zone, hemi, axis, v), ent in sorted(by_key.items()):
            ends = ent["ends"]
            if axis == "easting":
                la, lo, acell, at = max(ends, key=lambda p: p[0])
                far = min(ends, key=lambda p: p[0])
            else:
                la, lo, acell, at = min(ends, key=lambda p: p[1])
                far = max(ends, key=lambda p: p[1])
            sx, sy = cam.screen(la, lo)
            fx, fy = cam.screen(far[0], far[1])
            vis_len = math.hypot(float(fx - sx), float(fy - sy))
            labels.append(dict(kind="line", zone=zone, hemisphere=hemi, axis=axis, value=v, level=ent["owner"],
                               text=line_text(v), anchor=[float(sx), float(sy)], anchorLatLon=[la, lo],
                               visibleLengthDp=vis_len,
                               anchorTolDp=line_anchor_tol(cam, acell, axis, v, at, float(sx), float(sy), shrink)))

    if "100km" in labelled:
        x0, y0, x1, y1 = ins, ins, cam.w - ins, cam.h - ins
        for cell in cells:
            reg = region(cell, ibox, shrink)
            if reg["latS"] >= reg["latN"] or reg["lonW"] >= reg["lonE"]:
                continue
            emin, emax, nmin, nmax = region_extent(cell, reg)
            cxw, cyn = cam.screen(cell.latN, cell.lonW + shrink)
            cxe, cys = cam.screen(cell.latS, cell.lonE - shrink)
            rx0, ry0 = max(x0, float(cxw)), max(y0, float(cyn))
            rx1, ry1 = min(x1, float(cxe)), min(y1, float(cys))
            if rx0 >= rx1 or ry0 >= ry1:
                continue
            for e0 in range(int(emin // 100000) * 100000, int(emax) + 1, 100000):
                for n0 in range(int(nmin // 100000) * 100000, int(nmax) + 1, 100000):
                    if e0 < 100000 or e0 >= 900000:
                        continue
                    k = 128
                    es = np.concatenate([np.linspace(e0, e0 + 1e5, k, endpoint=False), np.full(k, e0 + 1e5),
                                         np.linspace(e0 + 1e5, e0, k, endpoint=False), np.full(k, e0)])
                    ns = np.concatenate([np.full(k, n0), np.linspace(n0, n0 + 1e5, k, endpoint=False),
                                         np.full(k, n0 + 1e5), np.linspace(n0 + 1e5, n0, k, endpoint=False)])
                    lat, lon = to_ll(cell.zone, cell.hemi, es, ns)
                    sx, sy = cam.screen(lat, lon)
                    poly = list(zip(sx.tolist(), sy.tolist()))
                    P = clip_poly_rect(poly, rx0, ry0, rx1, ry1)
                    if len(P) < 3:
                        continue
                    area, cen = poly_area_centroid(P)
                    if area <= 0 or cen is None:
                        continue
                    xs = [p[0] for p in P]
                    ys = [p[1] for p in P]
                    bw, bh = max(xs) - min(xs), max(ys) - min(ys)
                    if bw < SQUARE_LABEL_MIN_DP - 2 or bh < SQUARE_LABEL_MIN_DP - 2:
                        continue
                    borderline = bw < SQUARE_LABEL_MIN_DP + 2 or bh < SQUARE_LABEL_MIN_DP + 2
                    t = min(P, key=lambda p: p[0] + p[1])
                    a = (t[0] + SQUARE_LABEL_OFFSET_DP, t[1] + SQUARE_LABEL_OFFSET_DP)
                    rule = "offset"
                    # offset point sitting right on the polygon edge could go either way on
                    # a platform with different densification, so don't pin that label
                    if poly_edge_dist(a, P) < 1.0:
                        borderline = True
                    if not point_in_poly(a, P):
                        gx, gy = cen
                        d = math.hypot(gx - t[0], gy - t[1])
                        f = min(1.0, SQUARE_LABEL_OFFSET_DP * math.sqrt(2) / d) if d > 0 else 0.0
                        a = (t[0] + (gx - t[0]) * f, t[1] + (gy - t[1]) * f)
                        rule = "towardCentroid"
                    alat, alon = cam.coord(*a)
                    labels.append(dict(kind="square", zone=cell.zone, band=cell.band, hemisphere=cell.hemi,
                                       easting=e0, northing=n0, text=square_letters(cell.zone, e0, n0),
                                       anchor=[a[0], a[1]], anchorLatLon=[alat, alon], rule=rule,
                                       visibleBox=[min(xs), min(ys), max(xs), max(ys)], anchorTolDp=SQUARE_ANCHOR_TOL_DP,
                                       borderline=borderline))

    # flag pairs that might collide depending on font metrics
    def box_of(l):
        if l["kind"] == "square":
            return BOX_SQUARE
        return BOX_EASTING if l["axis"] == "easting" else BOX_NORTHING

    for l in labels:
        l["mayDrop"] = bool(l.get("borderline")) or (l["kind"] == "line" and l["visibleLengthDp"] < 2.0)
    for i in range(len(labels)):
        for j in range(i + 1, len(labels)):
            a, b = labels[i], labels[j]
            wa, ha = box_of(a)
            wb, hb = box_of(b)
            if (abs(a["anchor"][0] - b["anchor"][0]) < (wa + wb) / 2 + BOX_PAD and
                    abs(a["anchor"][1] - b["anchor"][1]) < (ha + hb) / 2 + BOX_PAD):
                a["mayDrop"] = b["mayDrop"] = True
    return sp, drawn, labelled, labels


# ---- fixture sections ----

def box_dict(box):
    s, w, n, e = box
    return {"south": s, "west": w, "north": n, "east": e}


def lod_cases():
    pts = [
        ("world view", 0.0, 0.0),
        ("iOS start zoom, nothing drawn (D4-13)", 4.0, 38.0),
        ("100 km just under the line threshold", 5.29, 38.0),
        ("100 km drawn, not yet labelled", 5.32, 38.0),
        ("100 km labelled", 6.0, 38.0),
        ("10 km just under the line threshold", 8.6, 38.0),
        ("10 km drawn and labelled", 9.0, 38.0),
        ("D6-06 Pixel camera, no 1 km", 10.2, 37.8),
        ("D4-10 camera, 100 km + 10 km only", 10.5, 37.8),
        ("old iOS 1 km switch-on zoom, now still off", 11.5, 38.0),
        ("1 km drawn, not labelled", 12.0, 38.0),
        ("1 km labelled", 12.3, 38.0),
        ("equator z12, 1 km off", 12.0, 0.0),
        ("60N z12, 1 km labelled", 12.0, 60.0),
        ("Sydney z12, 1 km just under the line threshold", 12.0, -33.87),
        ("Sydney z12.05, 1 km drawn", 12.05, -33.87),
        ("Svalbard z11", 11.0, 78.0),
        ("48N z16, everything", 16.0, 48.0),
        ("48N z22 (max), everything", 22.0, 48.0),
    ]
    out = []
    for name, z, lat in pts:
        sp, drawn, labelled = lod(z, lat)
        for v in sp.values():
            for thr in (LINE_MIN_DP, LABEL_MIN_DP):
                assert abs(v - thr) > 0.05, (name, v)
        out.append({"name": name, "zoom": z, "lat": lat,
                    "spacingDp": {k: rnd(v, 4) for k, v in sp.items()},
                    "drawn": drawn, "labelled": labelled})
    return out


def label_text_cases():
    rows = [
        # (zone, hemi, axis, value, finest labelled level, why)
        (10, "N", "northing", 4183000, "1km", "D4-04: printed 4183 line, old code said 82"),
        (10, "N", "northing", 4181000, "1km", "D6-02: old code said 80"),
        (10, "N", "easting", 551000, "1km", "east of CM"),
        (10, "N", "easting", 449000, "1km", "west of CM"),
        (10, "N", "northing", 4170000, "1km", "10 km line while 1 km is labelled"),
        (10, "N", "northing", 4170000, "10km", "D6-02: 10 km line, old code said 6"),
        (10, "N", "northing", 4200000, "1km", "100 km line while 1 km is labelled"),
        (10, "N", "northing", 4200000, "10km", "100 km line while 10 km is labelled"),
        (10, "N", "northing", 4183000, "10km", "1 km line drawn but 1 km not labelled"),
        (10, "N", "northing", 4170000, "100km", "only 100 km labelled -> square labels, no line labels"),
        (56, "S", "easting", 400000, "10km", "D4-04: 10 km easting west of CM, old code said 9"),
        (56, "S", "easting", 334000, "1km", "southern, west of CM"),
        (56, "S", "northing", 6250000, "10km", "southern northing"),
        (56, "S", "northing", 6249000, "1km", "southern northing"),
        (56, "S", "northing", 9999000, "1km", "last km before the equator (southern)"),
        (31, "N", "northing", 0, "1km", "equator, owned by the northern hemisphere"),
        (31, "N", "northing", 1000, "1km", "first km north of the equator"),
        (32, "N", "easting", 166000, "1km", "32V far west of CM"),
        (33, "N", "northing", 8660000, "1km", "Svalbard 33X"),
    ]
    out = []
    for zone, hemi, axis, v, finest, why in rows:
        m = level_metres(finest)
        labelled = finest in ("1km", "10km") and v % m == 0
        out.append({"zone": zone, "hemisphere": hemi, "axis": axis, "value": v,
                    "finestLabelledLevel": finest, "text": line_text(v) if labelled else None, "note": why})
    return out


def square_cases():
    rows = [
        (10, "N", 500000, 4100000, "SF, testdata/mgrs_samples.json 10SEG"),
        (30, "N", 600000, 5700000, "London 30UXC"),
        (56, "S", 300000, 6200000, "Sydney 56HLH"),
        (32, "N", 700000, 5300000, "32T, even zone row shift"),
        (33, "N", 200000, 5300000, "33T first column (zone 33 set is S..Z)"),
        (31, "N", 400000, 6200000, "31V"),
        (32, "N", 100000, 6600000, "32V far west column"),
        (33, "N", 300000, 8600000, "33X Svalbard"),
        (31, "N", 400000, 0, "first row north of the equator, odd zone"),
        (32, "N", 400000, 0, "first row north of the equator, even zone"),
        (31, "S", 400000, 9900000, "last row south of the equator, odd zone"),
        (60, "N", 800000, 4600000, "zone 60, last column"),
        (1, "S", 200000, 1200000, "zone 1, far south"),
    ]
    return [{"zone": z, "hemisphere": h, "easting": e, "northing": n,
             "text": square_letters(z, e, n), "note": why} for z, h, e, n, why in rows]


def geometry_case(name, box, zoom, lod_lat, px_per_dp, zone, hemi, axis, value, note):
    sp, drawn, labelled = lod(zoom, lod_lat)
    level = owner_level(value, drawn)
    assert level is not None, name
    pieces = []
    for cell in cells_in(box):
        if cell.zone != zone or cell.hemi != hemi:
            continue
        reg = region(cell, box)
        emin, emax, nmin, nmax = region_extent(cell, reg)
        lo, hi = (nmin, nmax) if axis == "easting" else (emin, emax)
        for a, b in line_pieces(cell, reg, axis, value, lo - 2000, hi + 2000):
            pieces.append((cell, a, b))
    assert pieces, name
    # strictest tolerance along the line = smallest ground metres per px
    max_lat = 0.0
    for cell, a, b in pieces:
        la, _ = line_ll(cell, axis, value, np.linspace(a, b, 33))
        max_lat = max(max_lat, float(np.abs(la).max()))
    sag_m = MAX_SAGITTA_PX / px_per_dp * metres_per_dp(max_lat, zoom)
    tol = sag_m + VERTEX_TOL_M
    out_pieces = []
    for cell, a, b in pieces:
        samples, err = sample_piece(cell, axis, value, a, b, tol / 20)
        out_pieces.append({"band": cell.band, "lengthMetres": rnd(b - a, 3), "samples": samples})
    # what an undensified NGA-style chord (one per grid cell) would miss by, for the README
    step = level_metres(level)
    worst_chord = 0.0
    for cell, a, b in pieces:
        t = math.floor(a / step) * step
        while t < b:
            ts = np.linspace(t, t + step, 65)
            la, lo = line_ll(cell, axis, value, ts)
            for i in range(1, 64):
                worst_chord = max(worst_chord, ground_dist_to_chord(float(la[i]), float(lo[i]),
                                                                    (la[0], lo[0]), (la[-1], lo[-1])))
            t += step
    return {"name": name, "note": note, "bounds": box_dict(box), "zoom": zoom, "lodLat": lod_lat,
            "pxPerDp": px_per_dp,
            "line": {"zone": zone, "hemisphere": hemi, "axis": axis, "value": value, "level": level},
            "tolMetres": rnd(tol, 4), "maxSagittaMetres": rnd(sag_m, 4),
            "singleChordErrorMetres": rnd(worst_chord, 3),
            "pieces": out_pieces}


def geometry_cases():
    cs = [
        ("100 km northing, 32T, curves ~216 m off its 100 km chord", (47.6, 7.5, 48.4, 11.5), 9.0, 48.0, 3.0,
         32, "N", "northing", 5300000, "D4-05 thick 100 km line"),
        ("100 km easting near SF", (37.0, -125.6, 39.0, -124.9), 11.0, 38.0, 3.0,
         10, "N", "easting", 300000, "vertical 100 km line, west of CM"),
        ("100 km easting hugging the zone 33 west edge", (47.5, 12.0, 48.5, 12.5), 11.0, 48.0, 3.0,
         33, "N", "easting", 300000, "D4-12 first column; no diagonal back to 12E"),
        ("10 km northing, 32V far west of CM, z16", (59.8, 3.0, 60.0, 4.5), 16.0, 60.0, 3.0,
         32, "N", "northing", 6650000, "D4-05 10 km ghost line at 60N"),
        ("1 km northing at 60N, z19", None, 19.0, 60.0, 3.0,
         32, "N", "northing", 6651000, "1 km chords are 3.4 cm off at 60N, 0.25 px is 1.2 cm here"),
        ("10 km northing, Sydney (southern), z15", (-33.95, 150.8, -33.8, 151.3), 15.0, -33.87, 3.0,
         56, "S", "northing", 6250000, "southern hemisphere bow flips"),
        ("10 km northing, Svalbard 33X, z13", None, 13.0, 78.0, 3.0,
         33, "N", "northing", 8650000, "high latitude, 6 deg from CM"),
        ("equator, N=0 (straight)", (-0.02, 2.5, 0.02, 3.5), 13.0, 0.0, 2.0,
         31, "N", "northing", 0, "equator is straight in both projections, sanity"),
    ]
    out = []
    for name, box, zoom, lod_lat, ppd, zone, hemi, axis, value, note in cs:
        if box is None:
            if zone == 32:
                # small box around N=6651000 near 9.4E
                la, lo = to_ll(32, "N", [520000.0], [6651000.0])
                box = (round(float(la[0]) - 0.003, 4), 9.3, round(float(la[0]) + 0.003, 4), 9.33)
            else:
                la, lo = to_ll(33, "N", [390000.0], [8650000.0])
                box = (round(float(la[0]) - 0.05, 3), 9.0, round(float(la[0]) + 0.05, 3), 10.5)
        out.append(geometry_case(name, box, zoom, lod_lat, ppd, zone, hemi, axis, value, note))
    return out


def end_tol(cell, box, axis, value, t, lat, lon, zoom, px_per_dp):
    # a linear clip between densified vertices lands on the chord, which is up to
    # one sagitta off the curve; along a shallow crossing that turns into
    # sagitta / sin(angle) along the edge. so the allowed miss per endpoint is that
    sag = MAX_SAGITTA_PX / px_per_dp * metres_per_dp(lat, zoom) + VERTEX_TOL_M
    la, lo = line_ll(cell, axis, value, [t - 1.0, t + 1.0])
    dx = (lo[1] - lo[0]) * math.cos(math.radians(lat))
    dy = la[1] - la[0]
    s, w, n, e = box
    on_lon = min(abs(lon - x) for x in (cell.lonW, cell.lonE, w, e)) < 1e-9
    on_lat = min(abs(lat - y) for y in (cell.latS, cell.latN, s, n)) < 1e-9
    sines = []
    if on_lon:
        sines.append(abs(dx) / math.hypot(dx, dy))   # crossing a meridian
    if on_lat:
        sines.append(abs(dy) / math.hypot(dx, dy))   # crossing a parallel
    if not sines:
        return sag
    # on both: either a corner or the line runs along one edge (the equator), and
    # then it's the other edge that actually cuts it
    return sag / max(max(sines), 0.01)


def clip_case(name, box, zoom, lod_lat, note, absent=(), key_bounds=()):
    sp, drawn, labelled = lod(zoom, lod_lat)
    cells = cells_in(box)
    expected = []
    for cell in cells:
        reg = region(cell, box)
        if reg["latS"] >= reg["latN"] or reg["lonW"] >= reg["lonE"]:
            continue
        for axis, v, owner, ps in region_lines(cell, reg, drawn):
            for a, b in ps:
                if b - a < 1.0:
                    continue
                la, lo = line_ll(cell, axis, v, [a, b])
                tols = [end_tol(cell, box, axis, v, t, float(la[i]), float(lo[i]), zoom, 3.0)
                        for i, t in enumerate((a, b))]
                expected.append({"zone": cell.zone, "band": cell.band, "hemisphere": cell.hemi, "axis": axis,
                                 "value": v, "level": owner,
                                 "from": [rnd(la[0], 10), rnd(lo[0], 10)], "to": [rnd(la[1], 10), rnd(lo[1], 10)],
                                 "fromTolMetres": rnd(tols[0], 4), "toTolMetres": rnd(tols[1], 4)})
    counts = {}
    for p in expected:
        counts[p["level"]] = counts.get(p["level"], 0) + 1
    return {"name": name, "note": note, "bounds": box_dict(box), "zoom": zoom, "lodLat": lod_lat,
            "pxPerDp": 3.0, "drawn": drawn,
            "cells": [{"gzd": c.gzd, "zone": c.zone, "band": c.band, "hemisphere": c.hemi,
                       "lon": [c.lonW, c.lonE], "lat": [c.latS, c.latN]} for c in cells],
            "expectedPieceCounts": counts,
            "expectedPieces": expected,
            "absentKeys": list(absent),
            "keyBounds": list(key_bounds)}


def clip_cases():
    return [
        clip_case("zone 32/33 boundary at 12E, also crosses band T/U at 48N", (47.8, 11.5, 48.2, 12.5), 12.0, 48.0,
                  "D4-12 + verifier: no zone 33 line west of 12E, no first-column diagonals, 1 km drawn but not labelled"),
        clip_case("Norway 32V as asked (56-64N 3-12E)", (56.0, 3.0, 64.0, 12.0), 5.0, 60.0,
                  "100 km only. Everything here is 32V; zone 31 lines never enter 3-12E above 56N"),
        clip_case("Norway corner where 31U, 31V and 32V meet", (55.8, 2.8, 56.2, 3.2), 11.0, 56.0,
                  "31V is only 0-3E; 32V starts at 3E above 56N; below 56N it's all 31U",
                  key_bounds=[{"zone": 31, "hemisphere": "N", "axis": "easting", "value": 500000,
                               "latMax": 56.0, "why": "31's CM is 3E, the 31V/32V edge; it belongs to 31U only"}]),
        clip_case("band edge 48N inside zone 32", (47.95, 8.5, 48.1, 8.7), 13.0, 48.0,
                  "verifier: NGA emits the band-edge rows twice; each piece exactly once here"),
        clip_case("Svalbard 31X/33X at 9E", (77.8, 8.5, 78.2, 9.5), 11.0, 78.0,
                  "32X doesn't exist; 31X runs to 9E and 33X starts there"),
        clip_case("equator in zone 31", (-0.05, 2.9, 0.05, 3.1), 13.0, 0.0,
                  "N=0 (north) is drawn once; the southern N=10000000 duplicate never is",
                  absent=[{"zone": 31, "hemisphere": "S", "axis": "northing", "value": 10000000}]),
    ]


def label_case(name, lat, lon, zoom, w, h, note):
    cam = Camera(lat, lon, zoom, w, h)
    sp, drawn, labelled, labels = visible_labels(cam)
    out = []
    for l in labels:
        d = {"kind": l["kind"], "zone": l["zone"], "hemisphere": l["hemisphere"]}
        if l["kind"] == "line":
            d.update(axis=l["axis"], value=l["value"], level=l["level"])
        else:
            d.update(band=l["band"], easting=l["easting"], northing=l["northing"], placement=l["rule"])
        d.update(text=l["text"], anchor=[rnd(l["anchor"][0], 3), rnd(l["anchor"][1], 3)],
                 anchorLatLon=[rnd(l["anchorLatLon"][0], 9), rnd(l["anchorLatLon"][1], 9)],
                 anchorTolDp=rnd(l["anchorTolDp"], 3), mayDrop=l["mayDrop"])
        out.append(d)
    kind_order = {"square": 0, "line": 1}
    out.sort(key=lambda d: (kind_order[d["kind"]], d.get("axis", ""), d["zone"], d["hemisphere"],
                            d.get("value", 0), d.get("band", ""), d.get("easting", 0), d.get("northing", 0)))
    return {"name": name, "note": note,
            "camera": {"lat": lat, "lon": lon, "zoom": zoom, "widthDp": w, "heightDp": h, "headingDegrees": 0.0},
            "drawn": drawn, "labelled": labelled,
            "insetRectDp": [LABEL_INSET_DP, LABEL_INSET_DP, w - LABEL_INSET_DP, h - LABEL_INSET_DP],
            "labels": out}


def label_cases():
    return [
        label_case("SF portrait z13 (D4-06)", 37.80, -122.45, 13.0, 393.0, 852.0,
                   "old code showed 1 of 7 easting and 1 of 15 northing labels here"),
        label_case("zone 32/33 + band T/U at z12.5", 48.0, 12.0, 12.5, 411.0, 914.0,
                   "zone 33 northings are labelled at their west end, on the 12E boundary"),
        label_case("Sydney z11, 10 km labelled", -33.86, 151.21, 11.0, 393.0, 852.0,
                   "southern hemisphere, 1 km not drawn"),
        label_case("central US z6.5, 100 km squares only", 38.0, -100.0, 6.5, 393.0, 852.0,
                   "square labels cut by the 102W/96W zone edges and the 40N band edge"),
        label_case("Norway corner z9", 56.0, 3.0, 9.0, 411.0, 914.0,
                   "31U / 31V / 32V; 10 km labelled"),
    ]


def policy():
    return {
        "levels": [{"name": n, "metres": m} for n, m in LEVELS],
        "tileSizeDp": TILE_DP,
        "earthRadiusMetres": EARTH_R,
        "mercatorLatLimit": MERC_LAT_LIMIT,
        "lineMinDp": LINE_MIN_DP,
        "labelMinDp": LABEL_MIN_DP,
        "labelInsetDp": LABEL_INSET_DP,
        "squareLabelMinDp": SQUARE_LABEL_MIN_DP,
        "squareLabelOffsetDp": SQUARE_LABEL_OFFSET_DP,
        "maxSagittaPx": MAX_SAGITTA_PX,
        "vertexTolMetres": VERTEX_TOL_M,
        "clipEpsilonDegrees": CLIP_EPS_DEG,
        "gridLatRange": [GRID_LAT_MIN, GRID_LAT_MAX],
        "bands": BANDS,
        "bandHeightDegrees": 8.0,
        "bandXLat": [72.0, 84.0],
        "zoneSpanRule": "zone z spans lon [6z-186, 6z-180] except the cells listed in zoneSpanExceptions",
        "zoneSpanExceptions": {k: (list(v) if v else None) for k, v in SPAN_EXCEPTIONS.items()},
        "columnLetters": {"zoneMod3Is1": COL_SETS[1], "zoneMod3Is2": COL_SETS[2], "zoneMod3Is0": COL_SETS[0]},
        "rowLetters": ROW_LETTERS,
        "evenZoneRowShift": 5,
        "style": {
            "lineWidthDp": {"100km": 2.0, "10km": 1.3, "1km": 0.8},
            "labelTextSize": {"100km": 14.0, "10km": 12.0, "1km": 11.0},
            "labelTextSizeUnit": "pt on iOS, sp on Android (unchanged from today)",
        },
        "fixtureOnly": {
            "note": "rough label boxes used to set mayDrop on labels that could collide depending on font metrics; not a rendering rule",
            "eastingLabelBoxDp": list(BOX_EASTING), "northingLabelBoxDp": list(BOX_NORTHING),
            "squareLabelBoxDp": list(BOX_SQUARE), "boxPadDp": BOX_PAD,
            "clipEndpointTol": "per endpoint: (0.25 px at pxPerDp in metres + vertexTolMetres) / max(sin(crossing angle), 0.01)",
            "lineAnchorTolDp": "0.5 + 0.25 / max(sin(crossing angle), 0.02) where the anchor sits on an edge, else 0.5",
            "squareAnchorTolDp": SQUARE_ANCHOR_TOL_DP,
        },
        "cache": {
            "rebuildZoomDelta": 0.5,
            "densifyForZoom": "buildZoom + rebuildZoomDelta",
            "rebuildCentreMoveDp": 64.0,
            "coverageMarginDp": 32.0,
            "coverageHalfSideDp": "(hypot(widthDp, heightDp) / 2 + rebuildCentreMoveDp) * 2^rebuildZoomDelta + coverageMarginDp, in dp at buildZoom",
        },
    }


def _scalar(v):
    return v is None or isinstance(v, (bool, int, float, str))


def _flat(v):
    return _scalar(v) or (isinstance(v, list) and all(_scalar(x) for x in v))


def dump(v, ind=0):
    # plain json.dump(indent=2) puts every lat/lon on its own line and the file
    # balloons. keep scalar arrays and small flat rows on one line instead
    pad = "  " * ind
    if isinstance(v, list):
        if all(_scalar(x) for x in v):
            return json.dumps(v)
        if all(isinstance(x, list) and all(_scalar(y) for y in x) for x in v):
            per = 4
            rows = [", ".join(json.dumps(x) for x in v[i:i + per]) for i in range(0, len(v), per)]
            return "[\n" + ",\n".join(pad + "  " + r for r in rows) + "\n" + pad + "]"
        return "[\n" + ",\n".join(pad + "  " + dump(x, ind + 1) for x in v) + "\n" + pad + "]"
    if isinstance(v, dict):
        if all(_flat(x) for x in v.values()):
            one = json.dumps(v)
            if len(one) <= 250:
                return one
        items = [pad + "  " + json.dumps(k) + ": " + dump(x, ind + 1) for k, x in v.items()]
        return "{\n" + ",\n".join(items) + "\n" + pad + "}"
    return json.dumps(v)


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out_path = sys.argv[1] if len(sys.argv) > 1 else os.path.normpath(os.path.join(here, "..", "testdata", "mgrs_grid.json"))
    doc = {
        "schemaVersion": 1,
        "description": (
            "MGRS grid overlay contract shared by iOS (MGRSGridRenderer / MGRSGridOverlayView) and Android "
            "(MgrsGridRenderer / MgrsGridCanvas). Levels, LOD, label text, label placement, densification and "
            "zone/band clipping. See testdata/README.md 'MGRS grid overlay contract' for the rules in words. "
            "Geometry from PROJ (pyproj etmerc UTM), letters from the AA-scheme MGRS math, generated by "
            "scripts/gen_mgrs_grid_fixture.py."),
        "policy": policy(),
        "lod": lod_cases(),
        "lineLabels": label_text_cases(),
        "squareLabels": square_cases(),
        "geometry": geometry_cases(),
        "clip": clip_cases(),
        "visibleLabels": label_cases(),
    }
    with open(out_path, "w") as f:
        f.write(dump(doc))
        f.write("\n")
    print("wrote", out_path)


if __name__ == "__main__":
    main()
