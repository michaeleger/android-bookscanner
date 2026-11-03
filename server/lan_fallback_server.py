#!/usr/bin/env python3
"""
Linux LAN Fallback Receiver & OpenCV Processing Pipeline.

Accepts raw spread burst queues dumped by the Android client when the 60% storage/heap
safety threshold is reached, applies LAB L-channel CLAHE (preserving highlighters and
pencil marginalia without 1-bit binarization), and writes segmented volumes
(`[BookName]_PartXX.pdf`).
"""

from __future__ import annotations

import json
import re
from email.parser import BytesParser
from email.policy import HTTP
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
from typing import Dict, List, Tuple

try:
    import cv2
    import numpy as np
except ImportError:
    cv2 = None  # type: ignore
    np = None  # type: ignore

OUTPUT_ROOT = Path("./lan_fallback_output")
MAX_BODY_BYTES = 256 * 1024 * 1024  # 4 shots x ~5MB with generous headroom

_UNSAFE_NAME_CHARS = re.compile(r"[^A-Za-z0-9._-]")


def safe_name(raw: str, fallback: str) -> str:
    """Reduces a client-supplied name to one safe path component (no separators, no '..')."""
    cleaned = _UNSAFE_NAME_CHARS.sub("_", raw or "").strip("._")
    return cleaned[:100] or fallback


def parse_multipart(content_type: str, body: bytes) -> Tuple[Dict[str, str], List[Tuple[str, bytes]]]:
    """Parses multipart/form-data into (text fields, [(filename, bytes)]) without the removed cgi module."""
    message = BytesParser(policy=HTTP).parsebytes(
        b"Content-Type: " + content_type.encode("latin-1") + b"\r\n\r\n" + body
    )
    fields: Dict[str, str] = {}
    files: List[Tuple[str, bytes]] = []
    if not message.is_multipart():
        return fields, files
    for part in message.iter_parts():
        name = part.get_param("name", header="content-disposition")
        if not name:
            continue
        payload = part.get_payload(decode=True) or b""
        filename = part.get_filename()
        if filename is not None:
            files.append((filename, payload))
        else:
            fields[name] = payload.decode("utf-8", errors="replace")
    return fields, files


def apply_lab_luminance_clahe(bgr_img: "np.ndarray") -> "np.ndarray":
    """Applies CLAHE strictly on the LAB L-channel to preserve highlighter & pencil artifacts."""
    lab = cv2.cvtColor(bgr_img, cv2.COLOR_BGR2LAB)
    l_chan, a_chan, b_chan = cv2.split(lab)
    clahe = cv2.createCLAHE(clipLimit=2.0, tileGridSize=(8, 8))
    l_eq = clahe.apply(l_chan)
    merged = cv2.merge((l_eq, a_chan, b_chan))
    return cv2.cvtColor(merged, cv2.COLOR_LAB2BGR)


def process_uploaded_spread(saved_files: List[Path], out_dir: Path) -> List[Path]:
    """Fuses burst shots, splits at the spine gutter, and runs LAB L-channel CLAHE."""
    if cv2 is None or np is None or not saved_files:
        return saved_files

    frames = []
    for path in saved_files:
        img = cv2.imread(str(path), cv2.IMREAD_COLOR)
        if img is not None:
            frames.append(img.astype(np.float32))

    if not frames:
        return saved_files

    fused = np.mean(frames, axis=0).astype(np.uint8)
    h, w, _ = fused.shape
    band_start, band_end = int(w * 0.35), int(w * 0.65)
    gray_band = cv2.cvtColor(fused[:, band_start:band_end], cv2.COLOR_BGR2GRAY)
    gutter_x = band_start + int(np.argmin(np.mean(gray_band, axis=0)))

    left_page = apply_lab_luminance_clahe(fused[:, :gutter_x])
    right_page = apply_lab_luminance_clahe(fused[:, gutter_x:])

    left_out = out_dir / "left_enhanced.jpg"
    right_out = out_dir / "right_enhanced.jpg"
    cv2.imwrite(str(left_out), left_page)
    cv2.imwrite(str(right_out), right_page)
    return [left_out, right_out]


def _parse_jpeg_header(jpeg_bytes: bytes) -> Tuple[int, int, int]:
    """Extracts (width, height, components) from raw JPEG SOF markers without external libs."""
    n = len(jpeg_bytes)
    if n < 4 or jpeg_bytes[0] != 0xFF or jpeg_bytes[1] != 0xD8:
        return (1600, 2400, 3)
    idx = 2
    sof_markers = {
        0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
        0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
    }
    while idx + 1 < n:
        if jpeg_bytes[idx] != 0xFF:
            idx += 1
            continue
        while idx < n and jpeg_bytes[idx] == 0xFF:
            idx += 1
        if idx >= n:
            break
        marker = jpeg_bytes[idx]
        idx += 1
        if marker in (0xD8, 0xD9) or 0xD0 <= marker <= 0xD7:
            continue
        if idx + 2 > n:
            break
        seg_len = (jpeg_bytes[idx] << 8) | jpeg_bytes[idx + 1]
        if seg_len < 2 or idx + seg_len > n:
            break
        if marker in sof_markers and seg_len >= 8:
            height = (jpeg_bytes[idx + 3] << 8) | jpeg_bytes[idx + 4]
            width = (jpeg_bytes[idx + 5] << 8) | jpeg_bytes[idx + 6]
            components = jpeg_bytes[idx + 7]
            if width > 0 and height > 0:
                return (width, height, components)
        idx += seg_len
    return (1600, 2400, 3)


def _natural_sort_key(path: Path) -> List[object]:
    parts = re.split(r"(\d+)", path.name)
    return [int(p) if p.isdigit() else p.lower() for p in parts]


def collect_volume_page_jpegs(volume_dir: Path) -> List[Path]:
    """Collects ordered page JPEGs across all spread directories in `volume_dir`."""
    if not volume_dir.exists():
        return []
    pages: List[Path] = []
    spread_dirs = sorted(
        (d for d in volume_dir.iterdir() if d.is_dir()),
        key=_natural_sort_key,
    )
    for sdir in spread_dirs:
        left = sdir / "left_enhanced.jpg"
        right = sdir / "right_enhanced.jpg"
        if left.is_file() and right.is_file():
            pages.extend([left, right])
        else:
            raw_shots = sorted(
                (
                    f for f in sdir.iterdir()
                    if f.is_file() and f.suffix.lower() in (".jpg", ".jpeg")
                ),
                key=_natural_sort_key,
            )
            if raw_shots:
                pages.append(raw_shots[0])
    return pages


def assemble_volume_pdf(volume_dir: Path, pdf_path: Path) -> Path | None:
    """
    Assembles `[BookName]_PartXX.pdf` from all offloaded spreads in `volume_dir` by embedding
    the JPEG page streams directly via PDF 1.4 `/DCTDecode` XObjects (atomic `.tmp` + rename).

    Objects are written to the `.tmp` file as they are produced, one JPEG in memory at a time.
    The whole volume is rebuilt on every upload, so cost is O(volume) per spread.
    """
    page_files = collect_volume_page_jpegs(volume_dir)
    if not page_files:
        return None

    # Object 1: Catalog, Object 2: Pages, then (page, content, image) per page.
    num_pages = len(page_files)
    total_objects = 2 + num_pages * 3
    offsets: Dict[int, int] = {}
    page_obj_ids = [3 + i * 3 for i in range(num_pages)]
    kids_ref = " ".join(f"{pid} 0 R" for pid in page_obj_ids)

    pdf_path.parent.mkdir(parents=True, exist_ok=True)
    tmp_path = pdf_path.with_suffix(".pdf.tmp")
    try:
        with tmp_path.open("wb") as out:
            out.write(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")

            def emit_obj(obj_id: int, *parts: bytes) -> None:
                offsets[obj_id] = out.tell()
                out.write(f"{obj_id} 0 obj\n".encode("ascii"))
                for part in parts:
                    out.write(part)
                out.write(b"\nendobj\n")

            emit_obj(1, b"<< /Type /Catalog /Pages 2 0 R >>")
            emit_obj(2, f"<< /Type /Pages /Kids [{kids_ref}] /Count {num_pages} >>".encode("ascii"))

            for idx, jpeg_path in enumerate(page_files):
                page_id = 3 + idx * 3
                content_id = page_id + 1
                img_id = page_id + 2

                jpeg_bytes = jpeg_path.read_bytes()
                img_w, img_h, comps = _parse_jpeg_header(jpeg_bytes)
                color_space = {1: "/DeviceGray", 4: "/DeviceCMYK"}.get(comps, "/DeviceRGB")
                page_w_pt = 612.0
                page_h_pt = round(page_w_pt * (float(img_h) / float(max(1, img_w))), 2)

                emit_obj(
                    page_id,
                    (
                        f"<< /Type /Page /Parent 2 0 R "
                        f"/MediaBox [0 0 {page_w_pt:.2f} {page_h_pt:.2f}] "
                        f"/Resources << /XObject << /Im0 {img_id} 0 R >> >> "
                        f"/Contents {content_id} 0 R >>"
                    ).encode("ascii"),
                )

                content_stream = f"q {page_w_pt:.2f} 0 0 {page_h_pt:.2f} 0 0 cm /Im0 Do Q".encode("ascii")
                emit_obj(
                    content_id,
                    f"<< /Length {len(content_stream)} >>\nstream\n".encode("ascii"),
                    content_stream,
                    b"\nendstream",
                )

                emit_obj(
                    img_id,
                    (
                        f"<< /Type /XObject /Subtype /Image /Width {img_w} /Height {img_h} "
                        f"/ColorSpace {color_space} /BitsPerComponent 8 "
                        f"/Filter /DCTDecode /Length {len(jpeg_bytes)} >>\nstream\n"
                    ).encode("ascii"),
                    jpeg_bytes,
                    b"\nendstream",
                )
                del jpeg_bytes

            xref_offset = out.tell()
            out.write(f"xref\n0 {total_objects + 1}\n0000000000 65535 f \n".encode("ascii"))
            for obj_id in range(1, total_objects + 1):
                out.write(f"{offsets[obj_id]:010d} 00000 n \n".encode("ascii"))
            out.write(
                (
                    f"trailer\n<< /Size {total_objects + 1} /Root 1 0 R >>\n"
                    f"startxref\n{xref_offset}\n%%EOF\n"
                ).encode("ascii")
            )
        tmp_path.replace(pdf_path)
    except BaseException:
        tmp_path.unlink(missing_ok=True)
        raise
    return pdf_path


class LanFallbackHandler(BaseHTTPRequestHandler):
    def do_POST(self) -> None:
        if self.path != "/api/v1/ingest-spread":
            self.send_error(404, "Endpoint not found")
            return

        content_type = self.headers.get("Content-Type", "")
        try:
            content_length = int(self.headers.get("Content-Length", ""))
        except ValueError:
            self.send_error(411, "Content-Length required")
            return
        if not content_type.startswith("multipart/form-data"):
            self.send_error(415, "Expected multipart/form-data")
            return
        if content_length <= 0 or content_length > MAX_BODY_BYTES:
            self.send_error(413, "Request body too large")
            return

        fields, uploads = parse_multipart(content_type, self.rfile.read(content_length))

        book_name = safe_name(fields.get("book_name", ""), "BookScan")
        try:
            volume_part = int(fields.get("volume_part", "1"))
        except ValueError:
            volume_part = 1
        spread_dir_name = safe_name(fields.get("spread_dir", ""), "spread_unknown")

        volume_label = f"{book_name}_Part{volume_part:02d}"
        volume_dir = OUTPUT_ROOT / volume_label
        spread_out_dir = volume_dir / spread_dir_name
        spread_out_dir.mkdir(parents=True, exist_ok=True)

        saved_paths: List[Path] = []
        for idx, (raw_filename, data) in enumerate(uploads):
            filename = safe_name(Path(raw_filename).name, f"shot_{idx + 1}.jpg")
            dest = spread_out_dir / filename
            with open(dest, "wb") as f_out:
                f_out.write(data)
            saved_paths.append(dest)

        processed = process_uploaded_spread(saved_paths, spread_out_dir)
        volume_pdf_path = OUTPUT_ROOT / f"{volume_label}.pdf"
        assembled_pdf = assemble_volume_pdf(volume_dir, volume_pdf_path)

        payload = json.dumps(
            {
                "status": "ok",
                "volume": str(assembled_pdf) if assembled_pdf else f"{volume_label}.pdf",
                "spread": spread_dir_name,
                "received_shots": len(saved_paths),
                "processed_files": [str(p) for p in processed],
            }
        ).encode("utf-8")

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)


def main(host: str = "0.0.0.0", port: int = 8085) -> None:
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    server = HTTPServer((host, port), LanFallbackHandler)
    print(f"Linux LAN Fallback Server listening on http://{host}:{port}/api/v1/ingest-spread")
    server.serve_forever()


if __name__ == "__main__":
    main()
