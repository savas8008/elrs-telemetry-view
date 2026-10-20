#!/usr/bin/env python3
"""
Generates the app's line-icon set as Android VectorDrawables (app/src/main/res/drawable/ic_*.xml)
and an HTML preview sheet. All icons share one style: 24x24 grid, 2 px round-capped strokes.

    python3 tools/icons.py [preview.html]
"""
import math
import os
import sys

WHITE = "#FFFFFFFF"
ACCENT = "#FF00C8FF"
YELLOW = "#FFFFD600"
PINK = "#FFFF40A0"
GREEN = "#FF1EC85A"
DARK = "#FF14161A"


def circle(cx, cy, r):
    return f"M{cx - r:g},{cy:g} a{r:g},{r:g} 0 1,0 {2 * r:g},0 a{r:g},{r:g} 0 1,0 {-2 * r:g},0"


def rrect(x, y, w, h, r):
    return (f"M{x + r:g},{y:g} H{x + w - r:g} A{r:g},{r:g} 0 0 1 {x + w:g},{y + r:g} V{y + h - r:g} "
            f"A{r:g},{r:g} 0 0 1 {x + w - r:g},{y + h:g} H{x + r:g} A{r:g},{r:g} 0 0 1 {x:g},{y + h - r:g} "
            f"V{y + r:g} A{r:g},{r:g} 0 0 1 {x + r:g},{y:g} Z")


def arc(cx, cy, r, a0, a1):
    """Arc from angle a0 to a1 (degrees, 0 = east, clockwise on screen)."""
    x0, y0 = cx + r * math.cos(math.radians(a0)), cy + r * math.sin(math.radians(a0))
    x1, y1 = cx + r * math.cos(math.radians(a1)), cy + r * math.sin(math.radians(a1))
    large = 1 if (a1 - a0) % 360 > 180 else 0
    return f"M{x0:.2f},{y0:.2f} A{r:g},{r:g} 0 {large} 1 {x1:.2f},{y1:.2f}"


def gear(cx, cy, teeth=8, r_out=9.6, r_in=7.2, tooth=0.42):
    pts = []
    step = 360 / teeth
    for k in range(teeth):
        a = k * step
        half_top = step * tooth / 2
        half_base = step * (tooth + 0.18) / 2
        for ang, r in ((a - half_base, r_in), (a - half_top, r_out), (a + half_top, r_out), (a + half_base, r_in)):
            pts.append((cx + r * math.cos(math.radians(ang)), cy + r * math.sin(math.radians(ang))))
    d = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + " Z"
    return d


def _rot(x, y, cx, cy, deg):
    c, sn = math.cos(math.radians(deg)), math.sin(math.radians(deg))
    return cx + (x - cx) * c - (y - cy) * sn, cy + (x - cx) * sn + (y - cy) * c


def poly(points, cx=12, cy=12, deg=0):
    pts = [_rot(x, y, cx, cy, deg) for x, y in points]
    return "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in pts) + " Z"


def seg(segments, cx=12, cy=12, deg=0):
    out = []
    for (x0, y0), (x1, y1) in segments:
        a, b = _rot(x0, y0, cx, cy, deg), _rot(x1, y1, cx, cy, deg)
        out.append(f"M{a[0]:.2f},{a[1]:.2f} L{b[0]:.2f},{b[1]:.2f}")
    return " ".join(out)


# Each icon: (viewport, [(pathData, stroke, strokeWidth, fill)])
S = 2.0
ICONS = {
    # map follow / centre on aircraft
    "ic_follow": (24, [
        (circle(12, 12, 6.5), WHITE, S, None),
        ("M12,2.5 V5.5 M12,18.5 V21.5 M2.5,12 H5.5 M18.5,12 H21.5", WHITE, S, None),
        (circle(12, 12, 1.8), None, 0, WHITE),
    ]),
    # map layers
    "ic_layers": (24, [
        ("M12,3.5 L21,8.25 L12,13 L3,8.25 Z", WHITE, S, None),
        ("M3,12.25 L12,17 L21,12.25", WHITE, S, None),
        ("M3,16.25 L12,21 L21,16.25", WHITE, S, None),
    ]),
    # reset home
    "ic_home": (24, [
        ("M3,11.5 L12,3.8 L21,11.5", WHITE, S, None),
        ("M5.5,9.6 V20 H18.5 V9.6", WHITE, S, None),
        ("M10,20 V14.5 H14 V20", WHITE, S, None),
    ]),
    # clear flight track
    "ic_clear_track": (24, [
        ("M4,6.5 H20", WHITE, S, None),
        ("M9,6.5 V4.2 H15 V6.5", WHITE, S, None),
        ("M6.3,6.5 L7.3,20 H16.7 L17.7,6.5", WHITE, S, None),
        ("M10.2,10.5 V16 M13.8,10.5 V16", WHITE, S, None),
    ]),
    # FPV goggles
    "ic_goggles": (24, [
        ("M3,10 C3,8 4.3,7 6.2,7 H17.8 C19.7,7 21,8 21,10 V13.8 C21,15.9 19.8,17 17.8,17 H15.3 "
         "C14.2,17 13.6,15.2 12,15.2 C10.4,15.2 9.8,17 8.7,17 H6.2 C4.2,17 3,15.9 3,13.8 Z", WHITE, S, None),
        (circle(8.1, 11.9, 2.1), WHITE, 1.6, None),
        (circle(15.9, 11.9, 2.1), WHITE, 1.6, None),
        ("M3,11 H1.2 M21,11 H22.8", WHITE, S, None),
    ]),
    # show/hide side panels
    "ic_panels": (24, [
        (rrect(3, 5, 18, 14, 2.2), WHITE, S, None),
        ("M8.2,5 V19 M15.8,5 V19", WHITE, S, None),
    ]),
    # flight summary
    "ic_summary": (24, [
        ("M4,20 H20", WHITE, S, None),
        ("M7.5,20 V13 M12,20 V7 M16.5,20 V10.5", WHITE, 2.6, None),
    ]),
    # settings
    "ic_settings": (24, [
        (gear(12, 12), WHITE, 1.8, None),
        (circle(12, 12, 3.1), WHITE, 1.8, None),
    ]),
    # close (video window)
    "ic_close": (24, [
        ("M6.5,6.5 L17.5,17.5 M17.5,6.5 L6.5,17.5", WHITE, 2.4, None),
    ]),
    # resize grip (video window)
    "ic_resize": (24, [
        ("M20,9 L9,20 M20,14.5 L14.5,20", WHITE, 2.2, None),
        (circle(19.2, 19.2, 1.3), None, 0, WHITE),
    ]),
    # fullscreen / picture-in-picture
    "ic_fullscreen": (24, [
        ("M4,9 V4 H9 M15,4 H20 V9 M20,15 V20 H15 M9,20 H4 V15", WHITE, S, None),
    ]),
    "ic_pip": (24, [
        (rrect(3, 5, 18, 14, 2.2), WHITE, S, None),
        (rrect(12, 11.5, 6.5, 5, 1), None, 0, WHITE),
    ]),
    # top bar: flight timer
    "ic_timer": (24, [
        (circle(12, 13.5, 7.2), WHITE, S, None),
        ("M12,13.5 V9.6 M9.6,3 H14.4 M12,3 V6.3 M18.2,6.8 L19.6,5.4", WHITE, S, None),
    ]),
    # top bar: GPS satellites (body, two solar panels, signal below)
    "ic_satellite": (24, [
        (rrect(9.6, 6.5, 4.8, 7, 1.2), WHITE, 1.8, None),
        (rrect(2.4, 7.5, 5.4, 5, 0.8), WHITE, 1.6, None),
        (rrect(16.2, 7.5, 5.4, 5, 0.8), WHITE, 1.6, None),
        ("M7.8,10 H9.6 M14.4,10 H16.2 M5.1,7.5 V12.5 M18.9,7.5 V12.5", WHITE, 1.4, None),
        (arc(12, 13.5, 3.6, 40, 140), WHITE, 1.7, None),
        (arc(12, 13.5, 7.0, 55, 125), WHITE, 1.7, None),
    ]),
    # offline map: folded map with a download arrow
    "ic_offline_map": (24, [
        ("M2.8,6.2 L8.4,4 L15.6,6.2 L21.2,4 V17.8 L15.6,20 L8.4,17.8 L2.8,20 Z", WHITE, 1.8, None),
        ("M8.4,4 V8 M15.6,6.2 V9 M8.4,16.2 V17.8 M15.6,18 V20", WHITE, 1.5, None),
        ("M12,7.8 V15.2 M9.2,12.6 L12,15.4 L14.8,12.6", ACCENT, 2.1, None),
    ]),
    # waiting screen: ELRS radio (64x64), waves drawn separately so they can pulse
    "ic_radio": (64, [
        (rrect(7, 25, 50, 30, 9), WHITE, 2.6, None),
        (rrect(24.5, 30, 15, 9, 1.8), WHITE, 2.0, None),
        (circle(18.5, 44.5, 6.2), WHITE, 2.2, None),
        (circle(45.5, 44.5, 6.2), WHITE, 2.2, None),
        (circle(18.5, 44.5, 1.9), None, 0, WHITE),
        (circle(45.5, 44.5, 1.9), None, 0, WHITE),
        ("M14,25 V20.5 M19,25 V22", WHITE, 2.2, None),
        ("M51.5,25 L55.5,8", WHITE, 3.2, None),
        (circle(55.8, 6.8, 1.7), None, 0, WHITE),
    ]),
    "ic_radio_waves": (64, [
        (arc(32, 21, 4.5, 225, 315), ACCENT, 2.4, None),
        (arc(32, 21, 9.0, 225, 315), ACCENT, 2.4, None),
        (arc(32, 21, 13.5, 225, 315), ACCENT, 2.4, None),
    ]),
}

# Launcher icon foreground (adaptive icon, 108x108, content inside the 66 dp safe circle)
PLANE_HALF = [(0, -1), (0.09, -0.86), (0.1, -0.32), (0.98, 0.02), (0.98, 0.17), (0.1, 0.06),
              (0.07, 0.6), (0.36, 0.78), (0.36, 0.9), (0, 0.84)]


def plane_path(cx, cy, s, rot):
    pts = PLANE_HALF + [(-x, y) for x, y in reversed(PLANE_HALF[1:-1])]
    c, sn = math.cos(math.radians(rot)), math.sin(math.radians(rot))
    out = []
    for x, y in pts:
        xr, yr = x * c - y * sn, x * sn + y * c
        out.append((cx + xr * s, cy + yr * s))
    return "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in out) + " Z"


def _launcher():
    # Everything stays inside the adaptive-icon safe zone (radius ~30 around 54,54)
    cx, cy, size, rot = 64, 43, 19, 45
    c, sn = math.cos(math.radians(rot)), math.sin(math.radians(rot))
    tail = (cx - 0.74 * sn * size, cy + 0.74 * c * size)  # under the tail so the track joins it
    home = (32, 68)
    # S-curve from home that enters the tail along the aircraft's heading (north-east)
    track = (f"M{home[0]},{home[1]} C{home[0] + 18},{home[1] + 12} {tail[0] - 10:.2f},{tail[1] + 9:.2f} "
             f"{tail[0]:.2f},{tail[1]:.2f}")
    return (108, [
        (track, PINK, 3.4, None),
        (circle(home[0], home[1], 4.0), None, 0, GREEN),
        (plane_path(cx, cy, size, rot), DARK, 2.2, YELLOW),
    ])


LAUNCHER_FG = _launcher()


def vector_xml(viewport, paths, size_dp=24):
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- Generated by tools/icons.py — edit the generator, not this file -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             f'    android:width="{size_dp}dp"', f'    android:height="{size_dp}dp"',
             f'    android:viewportWidth="{viewport}"', f'    android:viewportHeight="{viewport}">']
    for d, stroke, sw, fill in paths:
        attrs = [f'android:pathData="{d}"']
        if fill:
            attrs.append(f'android:fillColor="{fill}"')
        if stroke:
            attrs += [f'android:strokeColor="{stroke}"', f'android:strokeWidth="{sw:g}"',
                      'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
        lines.append("    <path " + "\n        ".join(attrs) + " />")
    lines.append("</vector>")
    return "\n".join(lines) + "\n"


def svg(viewport, paths, px):
    def col(c):
        return "none" if not c else "#" + c[3:]
    body = "".join(
        f'<path d="{d}" fill="{col(fill)}" stroke="{col(stroke)}" stroke-width="{sw:g}" '
        f'stroke-linecap="round" stroke-linejoin="round"/>' for d, stroke, sw, fill in paths)
    return f'<svg width="{px}" height="{px}" viewBox="0 0 {viewport} {viewport}">{body}</svg>'


def main():
    root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
    out = os.path.join(root, "drawable")
    os.makedirs(out, exist_ok=True)
    for name, (vp, paths) in ICONS.items():
        size = 96 if vp == 64 else 24
        with open(os.path.join(out, name + ".xml"), "w") as f:
            f.write(vector_xml(vp, paths, size))
    with open(os.path.join(out, "ic_launcher_foreground.xml"), "w") as f:
        f.write(vector_xml(LAUNCHER_FG[0], LAUNCHER_FG[1], 108))
    os.makedirs(os.path.join(root, "mipmap-anydpi-v26"), exist_ok=True)
    adaptive = ('<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@color/ic_launcher_background" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                '    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n'
                '</adaptive-icon>\n')
    for n in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(root, "mipmap-anydpi-v26", n), "w") as f:
            f.write(adaptive)
    with open(os.path.join(root, "values", "colors.xml"), "w") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                '    <color name="ic_launcher_background">#FF101419</color>\n</resources>\n')

    if len(sys.argv) > 1:
        cells = []
        for name, (vp, paths) in ICONS.items():
            px = 96 if vp == 64 else 48
            if name == "ic_radio_waves":
                continue
            extra = svg(64, ICONS["ic_radio_waves"][1], px) if name == "ic_radio" else ""
            icon = (f'<div style="position:relative;width:{px}px;height:{px}px">'
                    f'<div style="position:absolute">{svg(vp, paths, px)}</div>'
                    f'<div style="position:absolute">{extra}</div></div>')
            cells.append(f'<div class="c"><div class="btn">{icon}</div><span>{name[3:]}</span></div>')
        launcher = (f'<div class="c"><div style="width:108px;height:108px;border-radius:24px;overflow:hidden;'
                    f'background:#101419">{svg(108, LAUNCHER_FG[1], 108)}</div><span>launcher</span></div>')
        small = "".join(f'<span class="s">{svg(vp, p, 24)}</span>' for n, (vp, p) in ICONS.items() if vp == 24)
        html = ('<html><body style="background:#2a2e35;font:13px sans-serif;color:#ccd;margin:20px">'
                '<style>.c{display:inline-flex;flex-direction:column;align-items:center;margin:10px;width:120px}'
                '.btn{background:rgba(12,15,20,.85);border-radius:50%;padding:14px;display:flex;'
                'align-items:center;justify-content:center;border:1px solid #ffffff28}'
                '.s{background:#0c0f14;padding:8px;margin:3px;display:inline-block;border-radius:22px}</style>'
                + "".join(cells) + launcher + '<div style="margin-top:14px">24 px:</div>' + small
                + '</body></html>')
        with open(sys.argv[1], "w") as f:
            f.write(html)


if __name__ == "__main__":
    main()
