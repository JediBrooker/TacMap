#!/usr/bin/env python3
"""Cut a store preview video out of a raw screen recording.

Takes real screen captures only (App Store guideline 2.3.4: previews are
screen captures of the app, text overlays are fine, no device frames or
footage that isn't the app). Each beat is a time range from the raw recording
plus a caption that sits in a pill near the bottom.

    python3 scripts/build_preview_video.py <spec.json>

spec = {
  "src": "/abs/raw.mp4",            # or per-beat "src"
  "out": "/abs/out.mp4",
  "W": 886, "H": 1920,              # App Store: 886x1920 iPhone, 1200x1600 iPad
  "fps": 30,
  "mode": "fill",                   # fill = scale+crop to WxH, "frame" = landscape
                                    # canvas with the portrait clip on one side
  "beats": [{"t0": 52.0, "t1": 56.0, "caption": "Your tactical picture", "speed": 1.0}],
  "audio": "silent",                # App Store wants an audio track, silence is fine
  "webm": "/abs/out.webm"           # optional extra encode (website loop)
}
"""
import json, os, subprocess, sys, tempfile
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
FONTS = os.path.join(HERE, "fonts")
INK = (233, 240, 232)
GREEN = (116, 227, 138)
BG = (11, 14, 11)


def font(name, size):
    return ImageFont.truetype(os.path.join(FONTS, name), size)


def caption_png(text, W, H, path, frame_mode=False):
    """Transparent overlay the size of the output with one caption pill."""
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    size = int(W * (0.032 if frame_mode else 0.052))
    f = font("Archivo-ExtraBold.ttf", size)
    tw = d.textlength(text, font=f)
    padx, pady = int(size * 0.8), int(size * 0.55)
    bw, bh = int(tw + 2 * padx), int(size + 2 * pady)
    if frame_mode:
        # right-hand text column next to the phone
        x, y = int(W * 0.52), int(H * 0.44)
        d.text((x, y), text, font=f, fill=INK)
        d.rectangle([x, y + int(size * 1.35), x + int(W * 0.06), y + int(size * 1.35) + 6], fill=GREEN)
    else:
        x, y = (W - bw) // 2, int(H * 0.84)
        d.rounded_rectangle([x, y, x + bw, y + bh], radius=bh // 2, fill=(8, 10, 8, 225),
                            outline=(*GREEN, 255), width=3)
        d.text((x + padx, y + pady - int(size * 0.12)), text, font=f, fill=INK)
    img.save(path)


def frame_background(W, H, path):
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    f = font("Archivo-ExtraBold.ttf", int(H * 0.06))
    x, y = int(W * 0.52), int(H * 0.16)
    d.text((x, y), "Tac", font=f, fill=INK)
    d.text((x + d.textlength("Tac", font=f), y), "Map", font=f, fill=GREEN)
    sub = font("Archivo-Medium.ttf", int(H * 0.028))
    d.text((x, y + int(H * 0.085)), "Offline field maps. Pay once.", font=sub, fill=(182, 192, 179))
    img.save(path)


def main():
    spec = json.load(open(sys.argv[1]))
    W, H, fps = spec["W"], spec["H"], spec.get("fps", 30)
    frame_mode = spec.get("mode") == "frame"
    tmp = tempfile.mkdtemp()
    # simctl/screenrecord only emit a frame when the screen changes, so a
    # static hold can have no frames at all inside a beat. Re-time each raw to
    # CFR once up front so every cut has real frames in it.
    cfr = {}
    for b in spec["beats"]:
        src = b.get("src", spec.get("src"))
        if src not in cfr:
            out = os.path.join(tmp, f"cfr{len(cfr)}.mp4")
            subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", src, "-vf", f"fps={fps}",
                            "-c:v", "libx264", "-crf", "12", "-preset", "fast", "-an", out], check=True)
            cfr[src] = out
    inputs, filters, labels = [], [], []
    t = 0.0
    for i, b in enumerate(spec["beats"]):
        src = cfr[b.get("src", spec.get("src"))]
        speed = b.get("speed", 1.0)
        dur = (b["t1"] - b["t0"]) / speed
        # No -ss on the input: simctl recordings are sparse VFR and input
        # seeking lands seconds off. trim in the graph is slower but exact.
        inputs += ["-i", src]
        cap = os.path.join(tmp, f"cap{i}.png")
        caption_png(b.get("caption", ""), W, H, cap, frame_mode)
        inputs += ["-loop", "1", "-t", f"{dur:.3f}", "-i", cap]
        v, c = 2 * i, 2 * i + 1
        if frame_mode:
            ph = int(H * 0.9)
            scale = f"scale=-2:{ph}"
            filters.append(f"[{v}:v]trim=start={b['t0']}:end={b['t1']},setpts=(PTS-STARTPTS)/{speed},fps={fps},{scale},setsar=1[s{i}]")
            filters.append(f"[bg{i}][s{i}]overlay=x={int(W * 0.1)}:y=(H-h)/2[p{i}]")
            filters.append(f"[p{i}][{c}:v]overlay=0:0,format=yuv420p[b{i}]")
        else:
            filters.append(f"[{v}:v]trim=start={b['t0']}:end={b['t1']},setpts=(PTS-STARTPTS)/{speed},fps={fps},"
                           f"scale={W}:{H}:force_original_aspect_ratio=increase,crop={W}:{H},setsar=1[s{i}]")
            filters.append(f"[s{i}][{c}:v]overlay=0:0,format=yuv420p[b{i}]")
        labels.append(f"[b{i}]")
        t += dur
    if frame_mode:
        bgp = os.path.join(tmp, "bg.png")
        frame_background(W, H, bgp)
        n = len(spec["beats"])
        # one background input per beat keeps the filter graph simple
        pre = []
        for i, b in enumerate(spec["beats"]):
            dur = (b["t1"] - b["t0"]) / b.get("speed", 1.0)
            inputs += ["-loop", "1", "-t", f"{dur:.3f}", "-i", bgp]
            pre.append(f"[{2 * n + i}:v]fps={fps},setsar=1[bg{i}]")
        filters = pre + filters
    filters.append("".join(labels) + f"concat=n={len(labels)}:v=1:a=0[vout]")
    cmd = ["ffmpeg", "-y", "-v", "error"] + inputs
    audio_idx = len([x for x in inputs if x == "-i"])
    cmd += ["-f", "lavfi", "-t", f"{t:.3f}", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000"]
    cmd += ["-filter_complex", ";".join(filters), "-map", "[vout]", "-map", f"{audio_idx}:a",
            "-c:v", "libx264", "-profile:v", "high", "-level", "4.0", "-pix_fmt", "yuv420p",
            "-b:v", "11M", "-maxrate", "12M", "-bufsize", "24M", "-r", str(fps),
            "-c:a", "aac", "-b:a", "256k", "-ar", "48000", "-ac", "2",
            "-movflags", "+faststart", "-shortest", spec["out"]]
    subprocess.run(cmd, check=True)
    print("wrote", spec["out"], f"{t:.1f}s")
    if spec.get("webm"):
        subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", spec["out"], "-an", "-c:v", "libvpx-vp9",
                        "-b:v", "0", "-crf", "36", "-row-mt", "1", spec["webm"]], check=True)
        print("wrote", spec["webm"])


if __name__ == "__main__":
    main()
