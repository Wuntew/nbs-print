#!/usr/bin/env python3
"""A tiny fake IPP printer for testing NBS Print without real hardware.

It answers Get-Printer-Attributes, Print-Job, Get-Job-Attributes and Cancel-Job, and saves every
document it receives (plus the job attributes as JSON) into --out so you can inspect exactly what
the app sent.

    python tools/mock_ipp_printer.py --port 8631 --out scratch/jobs

From the Android emulator the host machine is 10.0.2.2, so add the printer in the app as
"10.0.2.2:8631". Flags simulate awkward printers: --jpeg-only, --raster-only, --urf-only, --engine-dpi 1200, --ipp11, --no-scaling-attr,
--reason media-empty-error, --abort-jobs, --polls 30 (slow), --report-pages, --one-at-a-time.
"""
import argparse
import json
import os
import struct
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TAG_OP, TAG_JOB, TAG_END, TAG_PRINTER = 0x01, 0x02, 0x03, 0x04
INT, BOOL, ENUM, RES, RANGE = 0x21, 0x22, 0x23, 0x32, 0x33
BEGIN_COL, END_COL, MEMBER = 0x34, 0x37, 0x4A
TEXT, NAME, KEYWORD, URI, CHARSET, LANG, MIME = 0x41, 0x42, 0x44, 0x45, 0x47, 0x48, 0x49


def parse_request(data):
    """Returns (major, minor, op, request_id, groups{tag: {name: [values]}}, body_offset)."""
    major, minor, op, rid = struct.unpack(">BBHI", data[:8])
    pos, groups, group, last = 8, {}, None, None
    while True:
        tag = data[pos]
        pos += 1
        if tag == TAG_END:
            break
        if tag <= 0x0F:
            group = groups.setdefault(tag, {})
            continue
        (nlen,) = struct.unpack(">H", data[pos:pos + 2])
        pos += 2
        name = data[pos:pos + nlen].decode()
        pos += nlen
        (vlen,) = struct.unpack(">H", data[pos:pos + 2])
        pos += 2
        raw = data[pos:pos + vlen]
        pos += vlen
        if tag in (INT, ENUM):
            value = struct.unpack(">i", raw)[0]
        elif tag == BOOL:
            value = bool(raw[0])
        elif tag >= 0x40:
            value = raw.decode("utf-8", "replace")
        else:
            value = raw
        if name:
            last = name
            group.setdefault(name, []).append(value)
        else:
            group[last].append(value)
    return major, minor, op, rid, groups, pos


def attr(tag, name, value):
    n = name.encode()
    if isinstance(value, str):
        value = value.encode()
    return struct.pack(">BH", tag, len(n)) + n + struct.pack(">H", len(value)) + value


def multi(tag, name, values):
    out = b""
    for i, v in enumerate(values):
        out += attr(tag, name if i == 0 else "", v)
    return out


def i32(v):
    return struct.pack(">i", v)


class Printer:
    def __init__(self, args):
        self.args = args
        self.lock = threading.Lock()
        self.next_job = 1
        self.jobs = {}  # id -> {"polls": n, "canceled": bool}
        os.makedirs(args.out, exist_ok=True)

    def printer_attributes(self):
        a = self.args
        if a.jpeg_only:
            formats = ["image/jpeg", "image/urf"]
        elif a.urf_only:
            formats = ["image/urf", "image/jpeg"]
        elif a.raster_only:
            formats = ["image/pwg-raster", "image/jpeg", "image/urf"]
        else:
            formats = ["application/pdf", "image/jpeg", "image/urf", "image/pwg-raster"]
        out = attr(NAME, "printer-name", "Mock Laser 9000")
        out += attr(TEXT, "printer-make-and-model", "NBS Mock IPP Printer")
        out += attr(ENUM, "printer-state", i32(3))
        out += multi(KEYWORD, "printer-state-reasons", [a.reason or "none"])
        out += attr(BOOL, "printer-is-accepting-jobs", b"\x01")
        out += multi(MIME, "document-format-supported", formats)
        out += attr(RANGE, "copies-supported", i32(1) + i32(99))
        out += attr(BOOL, "color-supported", b"\x01")
        out += multi(KEYWORD, "print-color-mode-supported", ["auto", "color", "monochrome"])
        out += multi(KEYWORD, "sides-supported", ["one-sided", "two-sided-long-edge", "two-sided-short-edge"])
        # Deliberately no 4x6 / A3 so the app has to hide those paper sizes.
        out += multi(KEYWORD, "media-supported", ["na_letter_8.5x11in", "iso_a4_210x297mm", "na_legal_8.5x14in"])
        if not a.no_scaling_attr:
            out += multi(KEYWORD, "print-scaling-supported", ["auto", "fill", "fit", "none"])
        out += attr(RES, "printer-resolution-supported", i32(a.engine_dpi) + i32(a.engine_dpi) + b"\x03")
        if "image/urf" in formats and not a.jpeg_only:
            out += multi(KEYWORD, "urf-supported", ["V1.4", "CP1", "PQ3-4-5", "RS300-600", "SRGB24", "W8", "DM1"])
        if "image/pwg-raster" in formats and not a.jpeg_only:
            out += multi(KEYWORD, "pwg-raster-document-type-supported", ["sgray_8", "srgb_8"])
            out += attr(RES, "pwg-raster-document-resolution-supported", i32(300) + i32(300) + b"\x03")
            out += attr(RES, "", i32(600) + i32(600) + b"\x03")
            out += multi(KEYWORD, "pwg-raster-document-sheet-back", ["normal"])
        for side in ("left", "right", "top", "bottom"):
            out += multi(INT, f"media-{side}-margin-supported", [i32(0), i32(423)])
        # A nested collection, as real printers send, to exercise the app's decoder.
        out += attr(BEGIN_COL, "media-col-default", b"")
        out += attr(MEMBER, "", b"media-size")
        out += attr(BEGIN_COL, "", b"")
        out += attr(MEMBER, "", b"x-dimension") + attr(INT, "", i32(21590))
        out += attr(MEMBER, "", b"y-dimension") + attr(INT, "", i32(27940))
        out += attr(END_COL, "", b"")
        out += attr(END_COL, "", b"")
        return out

    def handle(self, data):
        major, minor, op, rid, groups, body_at = parse_request(data)
        header = lambda status: struct.pack(">BBHI", min(major, 2), minor if major < 2 else 0, status, rid)
        opgrp = bytes([TAG_OP]) + attr(CHARSET, "attributes-charset", "utf-8") + \
            attr(LANG, "attributes-natural-language", "en")
        if self.args.ipp11 and major >= 2:
            return header(0x0503) + opgrp + bytes([TAG_END])

        opattrs = groups.get(TAG_OP, {})
        if op == 0x000B:  # Get-Printer-Attributes
            return header(0) + opgrp + bytes([TAG_PRINTER]) + self.printer_attributes() + bytes([TAG_END])

        if op == 0x0002:  # Print-Job
            if self.args.one_at_a_time:
                # Like many consumer inkjets: refuse a new job while an earlier one is still printing.
                with self.lock:
                    busy = any(not j["canceled"] and j["polls"] < self.args.polls for j in self.jobs.values())
                if busy:
                    print("[mock] busy: refusing a job while another is printing", flush=True)
                    refusal = bytes([TAG_OP]) + attr(CHARSET, "attributes-charset", "utf-8") + \
                        attr(LANG, "attributes-natural-language", "en") + \
                        attr(TEXT, "status-message", "Not allowed to print")
                    return header(0x0507) + refusal + bytes([TAG_END])
            fmt = opattrs.get("document-format", ["application/octet-stream"])[0]
            ext = {"application/pdf": "pdf", "image/jpeg": "jpg", "image/pwg-raster": "pwg", "image/urf": "urf"}.get(fmt, "bin")
            with self.lock:
                job_id = self.next_job
                self.next_job += 1
                self.jobs[job_id] = {"polls": 0, "canceled": False}
            path = os.path.join(self.args.out, f"job-{job_id}.{ext}")
            with open(path, "wb") as f:
                f.write(data[body_at:])
            meta = {"job-id": job_id, "ipp-version": f"{major}.{minor}", "document-format": fmt,
                    "bytes": len(data) - body_at, "operation-attributes": opattrs,
                    "job-attributes": groups.get(TAG_JOB, {})}
            with open(os.path.join(self.args.out, f"job-{job_id}.json"), "w") as f:
                json.dump(meta, f, indent=2)
            print(f"[mock] job {job_id}: {fmt}, {meta['bytes']} bytes, attrs={meta['job-attributes']}", flush=True)
            job = bytes([TAG_JOB]) + attr(INT, "job-id", i32(job_id)) + \
                attr(URI, "job-uri", f"ipp://localhost/jobs/{job_id}") + attr(ENUM, "job-state", i32(3))
            return header(0) + opgrp + job + bytes([TAG_END])

        if op == 0x0009:  # Get-Job-Attributes
            job_id = opattrs.get("job-id", [0])[0]
            job = self.jobs.get(job_id)
            if job is None:
                return header(0x0406) + opgrp + bytes([TAG_END])
            job["polls"] += 1
            if self.args.abort_jobs:
                state, reasons = 8, ["aborted-by-system"]
            elif job["canceled"]:
                state, reasons = 7, ["job-canceled-by-user"]
            elif job["polls"] < self.args.polls:
                state, reasons = 5, ["job-printing"]
            else:
                state, reasons = 9, ["job-completed-successfully"]
            body = bytes([TAG_JOB]) + attr(ENUM, "job-state", i32(state)) + multi(KEYWORD, "job-state-reasons", reasons)
            if self.args.report_pages:  # many real printers omit this; some report it
                body += attr(INT, "job-impressions-completed", i32(job["polls"] - 1 if state == 5 else 99))
            return header(0) + opgrp + body + bytes([TAG_END])

        if op == 0x0008:  # Cancel-Job
            job = self.jobs.get(opattrs.get("job-id", [0])[0])
            if job:
                job["canceled"] = True
            print(f"[mock] cancel-job {opattrs.get('job-id')}", flush=True)
            return header(0) + opgrp + bytes([TAG_END])

        return header(0x0501) + opgrp + bytes([TAG_END])  # operation-not-supported


def make_handler(printer):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def do_POST(self):
            length = int(self.headers.get("Content-Length", 0))
            data = self.rfile.read(length)
            try:
                reply = printer.handle(data)
            except Exception as e:  # noqa: BLE001 - a test tool; report and carry on
                print(f"[mock] error: {e!r}", flush=True)
                self.send_response(500)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            self.send_response(200)
            self.send_header("Content-Type", "application/ipp")
            self.send_header("Content-Length", str(len(reply)))
            self.end_headers()
            self.wfile.write(reply)

        def log_message(self, *a):
            pass

    return Handler


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=8631)
    ap.add_argument("--out", default="scratch/jobs")
    ap.add_argument("--jpeg-only", action="store_true", help="only advertise JPEG (and URF), no PDF or PWG raster")
    ap.add_argument("--raster-only", action="store_true", help="advertise PWG raster and JPEG but no PDF (like many Brother and Epson printers)")
    ap.add_argument("--urf-only", action="store_true", help="advertise AirPrint raster (URF) and JPEG but no PDF or PWG raster (like Canon PIXMA inkjets)")
    ap.add_argument("--engine-dpi", type=int, default=600, help="the printer's native resolution, e.g. 1200")
    ap.add_argument("--ipp11", action="store_true", help="reject IPP 2.0 requests, accept 1.1 only")
    ap.add_argument("--no-scaling-attr", action="store_true", help="don't advertise print-scaling-supported")
    ap.add_argument("--reason", help="printer-state-reasons to report, e.g. media-empty-error")
    ap.add_argument("--polls", type=int, default=2, help="status polls before a job reports completed (slow printer)")
    ap.add_argument("--one-at-a-time", action="store_true",
                    help="answer 0x0507 (busy) to a new job while an earlier one is still printing (use with --polls)")
    ap.add_argument("--report-pages", action="store_true", help="report job-impressions-completed while printing")
    ap.add_argument("--abort-jobs", action="store_true", help="report every job as aborted")
    args = ap.parse_args()
    server = ThreadingHTTPServer(("0.0.0.0", args.port), make_handler(Printer(args)))
    print(f"[mock] IPP printer on :{args.port}, saving jobs to {args.out}", flush=True)
    server.serve_forever()
