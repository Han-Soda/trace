#!/usr/bin/env python3
"""Builds app/src/main/assets/land.bin from Natural Earth (public domain) 1:50m land and lakes.

    curl -O https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_land.geojson
    curl -O https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_50m_lakes.geojson
    python3 tools/make_land_asset.py ne_50m_land.geojson ne_50m_lakes.geojson app/src/main/assets/land.bin

Format: "LAND", version byte, ring count, then per ring a kind byte (0 land, 1 lake), a vertex
count and Web Mercator coordinates scaled to 2^22: the first absolute, the rest as zigzag
deltas. All counts and coordinates are unsigned LEB128 varints.
"""
import json
import math
import sys

SCALE = 1 << 22
MAX_LAT = 85.05112878


def varint(value, out):
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            out.append(byte | 0x80)
        else:
            out.append(byte)
            return


def zigzag(value):
    return (value << 1) ^ (value >> 63)


def project(lon, lat):
    lat = max(-MAX_LAT, min(MAX_LAT, lat))
    s = math.sin(math.radians(lat))
    x = (lon + 180.0) / 360.0
    y = 0.5 - math.log((1 + s) / (1 - s)) / (4 * math.pi)
    return round(x * SCALE), round(y * SCALE)


def rings(path):
    for feature in json.load(open(path))["features"]:
        geometry = feature["geometry"]
        polygons = [geometry["coordinates"]] if geometry["type"] == "Polygon" else geometry["coordinates"]
        for polygon in polygons:
            yield polygon[0]  # outer ring; holes are rare at this scale


def main(land_path, lakes_path, out_path):
    out = bytearray(b"LAND")
    out.append(1)
    all_rings = [(0, ring) for ring in rings(land_path)] + [(1, ring) for ring in rings(lakes_path)]
    varint(len(all_rings), out)
    for kind, ring in all_rings:
        points = []
        for lon, lat in ring:
            point = project(lon, lat)
            if not points or point != points[-1]:
                points.append(point)
        out.append(kind)
        varint(len(points), out)
        px, py = 0, 0
        for index, (x, y) in enumerate(points):
            if index == 0:
                varint(x, out)
                varint(y, out)
            else:
                varint(zigzag(x - px), out)
                varint(zigzag(y - py), out)
            px, py = x, y
    open(out_path, "wb").write(out)
    print(f"{out_path}: {len(all_rings)} rings, {len(out)} bytes")


if __name__ == "__main__":
    main(*sys.argv[1:4])
