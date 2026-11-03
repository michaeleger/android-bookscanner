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
        spread_out_dir = OUTPUT_ROOT / volume_label / spread_dir_name
        spread_out_dir.mkdir(parents=True, exist_ok=True)

        saved_paths: List[Path] = []
        for idx, (raw_filename, data) in enumerate(uploads):
            filename = safe_name(Path(raw_filename).name, f"shot_{idx + 1}.jpg")
            dest = spread_out_dir / filename
            with open(dest, "wb") as f_out:
                f_out.write(data)
            saved_paths.append(dest)

        processed = process_uploaded_spread(saved_paths, spread_out_dir)

        payload = json.dumps(
            {
                "status": "ok",
                "volume": f"{volume_label}.pdf",
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
