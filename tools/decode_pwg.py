#!/usr/bin/env python3
"""Decode a PWG raster (image/pwg-raster) or Apple raster (image/urf) file and report what a printer would see.

    python tools/decode_pwg.py job-1.pwg            # print each page's header summary
    python tools/decode_pwg.py job-1.pwg --png out  # also write out-1.png, out-2.png, ...
    python tools/decode_pwg.py job-1.pwg --probe 72 72 144 144   # sizes of the first dark box found, in points

Written from the reference reader in CUPS (cupsRasterReadPixels), so it checks our encoder against an
independent implementation of the format.
"""
import argparse
import struct
import sys
import zlib

HEADER = 1796


def read_lines(data, pos, height, per_line, bpp):
    """Decode `height` rows of CUPS-style PackBits rows starting at `pos`; returns (rows, new_pos)."""
    rows = []
    while len(rows) < height:
        repeat = data[pos] + 1
        pos += 1
        line = bytearray()
        while len(line) < per_line:
            n = data[pos]
            pos += 1
            if n == 128:  # clear to end of line (white for rgb/gray)
                line.extend(b"\xff" * (per_line - len(line)))
            elif n & 128:  # literal pixels
                count = (257 - n) * bpp
                line.extend(data[pos:pos + count])
                pos += count
            else:  # repeat one pixel
                px = data[pos:pos + bpp]
                pos += bpp
                line.extend(px * (n + 1))
        rows.extend([bytes(line[:per_line])] * repeat)
    return rows[:height], pos


def read_urf_pages(data):
    """Apple raster: "UNIRAST\\0", page count, then 32-byte page headers (see CUPS's Apple writer)."""
    count = struct.unpack(">I", data[8:12])[0]
    pos = 12
    for _ in range(count):
        h = data[pos:pos + 32]
        pos += 32
        bits, cspace, duplex, quality = h[0], h[1], h[2], h[3]
        width, height, dpi = struct.unpack(">III", h[12:24])
        bpp = bits // 8
        hdr = dict(media_class="URF", dpi=(dpi, dpi), page_pts=(round(width * 72 / dpi), round(height * 72 / dpi)),
                   width=width, height=height, bits_per_color=8, bits_per_pixel=bits, bytes_per_line=width * bpp,
                   color_space=cspace, num_colors=bpp, total_pages=count, media="quality %d, duplex %d" % (quality, duplex))
        rows, pos = read_lines(data, pos, height, width * bpp, bpp)
        yield hdr, rows, bpp


def read_pages(data):
    if data[:8] == b"UNIRAST\x00":
        yield from read_urf_pages(data)
        return
    if data[:4] != b"RaS2":
        raise SystemExit("not a PWG raster (RaS2) or Apple raster (UNIRAST) file")
    pos = 4
    while pos < len(data):
        h = data[pos:pos + HEADER]
        if len(h) < HEADER:
            raise SystemExit("truncated page header")
        pos += HEADER
        u = lambda off: struct.unpack(">I", h[off:off + 4])[0]
        hdr = dict(
            media_class=h[0:64].split(b"\0")[0].decode(),
            dpi=(u(276), u(280)), page_pts=(u(352), u(356)), width=u(372), height=u(376),
            bits_per_color=u(384), bits_per_pixel=u(388), bytes_per_line=u(392),
            color_space=u(400), num_colors=u(420), total_pages=u(452),
            media=h[1732:1796].split(b"\0")[0].decode(),
        )
        bpp = max(1, (hdr["bits_per_pixel"] + 7) // 8)
        per_line = hdr["bytes_per_line"]
        rows, pos = read_lines(data, pos, hdr["height"], per_line, bpp)
        yield hdr, rows, bpp


def write_png(path, hdr, rows, bpp):
    w, h = hdr["width"], hdr["height"]
    ctype = 0 if bpp == 1 else 2
    raw = b"".join(b"\0" + r for r in rows)

    def chunk(tag, body):
        c = struct.pack(">I", len(body)) + tag + body
        return c + struct.pack(">I", zlib.crc32(tag + body) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, ctype, 0, 0, 0)
    open(path, "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(raw, 6)) + chunk(b"IEND", b""))


def dark_box(rows, bpp, width):
    """Bounding box (x0, y0, x1, y1) of the first run of near-black pixels, or None."""
    def dark(row, x):
        return all(row[x * bpp + c] < 60 for c in range(bpp))
    for y, row in enumerate(rows):
        for x in range(width):
            if dark(row, x):
                x1 = x
                while x1 + 1 < width and dark(row, x1 + 1):
                    x1 += 1
                y1 = y
                while y1 + 1 < len(rows) and dark(rows[y1 + 1], x):
                    y1 += 1
                return x, y, x1, y1
    return None


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file")
    ap.add_argument("--png", help="write PNGs with this prefix")
    ap.add_argument("--probe", nargs=4, type=float, metavar=("X_PT", "Y_PT", "W_PT", "H_PT"),
                    help="expected first dark box in points, to compare with what is in the raster")
    args = ap.parse_args()
    data = open(args.file, "rb").read()
    for i, (hdr, rows, bpp) in enumerate(read_pages(data), start=1):
        dpi = hdr["dpi"][0]
        w_in, h_in = hdr["width"] / dpi, hdr["height"] / dpi
        print(f"page {i}: {hdr['width']}x{hdr['height']} px @ {dpi} dpi = {w_in:.2f} x {h_in:.2f} in "
              f"(header page size {hdr['page_pts'][0]}x{hdr['page_pts'][1]} pt, {hdr['bits_per_pixel']} bpp, "
              f"cspace {hdr['color_space']}, media {hdr['media']!r}, pages in job {hdr['total_pages']})")
        if args.probe:
            box = dark_box(rows, bpp, hdr["width"])
            if box:
                x0, y0, x1, y1 = box
                k = dpi / 72.0
                print(f"        first dark box: x={x0 / k:.1f} y={y0 / k:.1f} w={(x1 - x0 + 1) / k:.1f} h={(y1 - y0 + 1) / k:.1f} pt "
                      f"(expected {args.probe[0]:g}, {args.probe[1]:g}, {args.probe[2]:g} x {args.probe[3]:g})")
            else:
                print("        no dark box found")
        if args.png:
            write_png(f"{args.png}-{i}.png", hdr, rows, bpp)
