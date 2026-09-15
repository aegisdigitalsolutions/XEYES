#!/usr/bin/env python3
"""Draws the app icon.

A committed PNG with no provenance is a file nobody can change with any confidence, so the icon is
generated from this script and the script is what gets reviewed. It writes a single 1024x1024 image,
which is all Xcode 14 and later need for an iOS app icon; the system derives every other size.

No dependencies: the PNG is assembled with zlib and struct so this runs on any machine with Python,
including one with no image libraries installed.

    python3 ios/Tools/make-app-icon.py

The mark is an emitter with three arcs opening upward. It is deliberately not a map pin or a
crosshair: this app records what it can hear, and pretending to precision it has not measured is the
failure mode the whole system is built to avoid.
"""

from __future__ import annotations

import math
import struct
import zlib
from pathlib import Path

SIZE = 1024

# A vertical gradient, so the field is not a flat block of colour.
BACKGROUND_TOP = (11, 22, 42)
BACKGROUND_BOTTOM = (7, 14, 28)
ARC = (86, 176, 255)
EMITTER = (240, 248, 255)

# Positioned so the drawn shape, not the canvas, is what looks centred: the arcs rise from the
# emitter, so the emitter itself has to sit below the midpoint.
CENTRE_X = 0.50
CENTRE_Y = 0.66
EMITTER_RADIUS = 0.055
ARC_RADII = (0.170, 0.280, 0.390)
ARC_STROKE = 0.038

# Measured from straight up. Wider than a Wi-Fi glyph's, which keeps the arcs from looking pinched
# at this size.
ARC_HALF_ANGLE = math.radians(54)


def coverage(distance: float, edge: float) -> float:
    """Antialiased coverage of a pixel whose centre is `distance` from a shape's edge."""
    return max(0.0, min(1.0, edge - distance + 0.5))


def render() -> bytes:
    centre_x, centre_y = CENTRE_X * SIZE, CENTRE_Y * SIZE
    emitter_radius = EMITTER_RADIUS * SIZE
    arc_radii = [radius * SIZE for radius in ARC_RADII]
    half_stroke = ARC_STROKE * SIZE * 0.5

    scanlines = []
    for y in range(SIZE):
        ratio = y / (SIZE - 1)
        background = tuple(
            round(BACKGROUND_TOP[i] + (BACKGROUND_BOTTOM[i] - BACKGROUND_TOP[i]) * ratio)
            for i in range(3)
        )
        row = bytearray()
        for x in range(SIZE):
            dx, dy = x - centre_x, y - centre_y
            distance = math.hypot(dx, dy)

            ink, alpha = ARC, 0.0
            emitter_alpha = coverage(distance, emitter_radius)
            if emitter_alpha > 0.0:
                ink, alpha = EMITTER, emitter_alpha
            elif abs(math.atan2(dx, -dy)) <= ARC_HALF_ANGLE:
                alpha = max(
                    coverage(abs(distance - radius), half_stroke) for radius in arc_radii
                )

            red, green, blue = background
            if alpha > 0.0:
                red = round(red + (ink[0] - red) * alpha)
                green = round(green + (ink[1] - green) * alpha)
                blue = round(blue + (ink[2] - blue) * alpha)
            row += bytes((red, green, blue))
        scanlines.append(bytes(row))

    return b"".join(b"\x00" + line for line in scanlines)


def png(raw: bytes) -> bytes:
    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data))
        )

    header = struct.pack(">IIBBBBB", SIZE, SIZE, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", header)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def main() -> None:
    destination = (
        Path(__file__).resolve().parent.parent
        / "RFMapperCollector/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png"
    )
    destination.write_bytes(png(render()))
    print(f"wrote {destination} ({destination.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
