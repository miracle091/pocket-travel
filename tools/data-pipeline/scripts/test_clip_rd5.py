#!/usr/bin/env python3
"""Test di clip_rd5.py su un .rd5 sintetico (nessun file di brouter.de necessario).

Uso: python test_clip_rd5.py
"""
import http.server
import random
import struct
import tempfile
import threading
import unittest
from pathlib import Path

from clip_rd5 import FileSource, HttpSource, brouter_crc, clip, overlaps

DIVISOR = 32
INDEX_SIZE = DIVISOR * DIVISOR * 4
TAIL = b"\x03extra"


def make_rd5(cells):
    """.rd5 con divisor 32; cells = {(sotto-tile, micro-cella): bytes}."""
    blocks, crcs = [], []
    for i in range(25):
        data, positions = bytearray(), []
        for sub in range(DIVISOR * DIVISOR):
            data += cells.get((i, sub), b"")
            positions.append(INDEX_SIZE + len(data))
        if not data:
            blocks.append(b""); crcs.append(0)
            continue
        index = struct.pack(f">{DIVISOR * DIVISOR}i", *positions)
        blocks.append(index + data); crcs.append(brouter_crc(index))
    ends, pos = [], 200
    for block in blocks:
        pos += len(block)
        ends.append((7 << 48) | pos)
    head = struct.pack(">25q", *ends)
    footer = struct.pack(">qI", 1234, brouter_crc(head) ^ 2) + struct.pack(">25I", *crcs) + TAIL
    return head + b"".join(blocks) + footer


def read_cells(rd5):
    """Micro-celle non vuote di un .rd5, controllando CRC dell'indice e degli indici dei blocchi."""
    head = rd5[:200]
    ends = [v & 0xFFFFFFFFFFFF for v in struct.unpack(">25q", head)]
    footer = rd5[ends[24]:]
    _, crc = struct.unpack(">qI", footer[:12])
    assert crc ^ 2 == brouter_crc(head), "CRC dell'indice"
    block_crcs = struct.unpack(">25I", footer[12:112])
    assert footer[112:] == TAIL, "coda"
    cells = {}
    for i in range(25):
        start = ends[i - 1] if i else 200
        if ends[i] == start:
            assert block_crcs[i] == 0
            continue
        block = rd5[start:ends[i]]
        assert block_crcs[i] == brouter_crc(block[:INDEX_SIZE]), f"CRC indice blocco {i}"
        prev = INDEX_SIZE
        for sub, end in enumerate(struct.unpack(f">{DIVISOR * DIVISOR}i", block[:INDEX_SIZE])):
            if end > prev:
                cells[(i, sub)] = block[prev:end]
            prev = end
        assert prev == len(block), f"lunghezza blocco {i}"
    return cells


class RangeHandler(http.server.BaseHTTPRequestHandler):
    data = b""
    honor_range = True

    def do_GET(self):
        spec = self.headers["Range"].removeprefix("bytes=")
        a, b = spec.split("-")
        a, b = int(a), (int(b) + 1 if b else len(self.data))
        body = self.data[a:b] if self.honor_range else self.data
        self.send_response(206 if self.honor_range else 200)
        if self.honor_range:
            self.send_header("Content-Range", f"bytes {a}-{b - 1}/{len(self.data)}")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class ClipRd5Test(unittest.TestCase):
    # Tile W10_S20: sotto-tile 1x1 da lon -10..-5, lat -20..-15 (verifica anche i nomi W/S).
    NAME, LON0, LAT0 = "W10_S20.rd5", -10, -20
    BBOX = (-8.9, -18.95, -7.3, -18.2)  # tocca 2 sotto-tile in lon (-9, -8) e 1 in lat (-19)

    def setUp(self):
        rnd = random.Random(1)
        self.cells = {(i, sub): rnd.randbytes(rnd.randint(1, 40))
                      for i in range(25) for sub in range(DIVISOR * DIVISOR) if rnd.random() < 0.3}
        self.rd5 = make_rd5(self.cells)
        self.dir = Path(tempfile.mkdtemp())
        (self.dir / self.NAME).write_bytes(self.rd5)

    def expected(self):
        cell = 1 / DIVISOR
        return {k: v for k, v in self.cells.items()
                if overlaps(self.LON0 + k[0] // 5 + (k[1] % DIVISOR) * cell,
                            self.LAT0 + k[0] % 5 + (k[1] // DIVISOR) * cell, cell, self.BBOX)}

    def test_tiene_solo_le_micro_celle_nel_riquadro(self):
        out, kept = clip(FileSource(self.dir / self.NAME), self.BBOX)
        want = self.expected()
        self.assertTrue(0 < len(want) < len(self.cells) / 10)
        self.assertEqual(kept, len(want))
        self.assertEqual(read_cells(out), want)
        self.assertEqual({k[0] for k in want}, {6, 11})  # sotto-tile (lon -9, lat -19) e (lon -8, lat -19)

    def test_riquadro_senza_dati_da_tile_vuota(self):
        out, kept = clip(FileSource(self.dir / self.NAME), (50, 50, 51, 51))
        self.assertEqual((kept, read_cells(out)), (0, {}))

    def test_via_http_uguale_al_file_locale_e_scarica_meno(self):
        local, _ = clip(FileSource(self.dir / self.NAME), self.BBOX)
        RangeHandler.data, RangeHandler.honor_range = self.rd5, True
        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), RangeHandler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            src = HttpSource(f"http://127.0.0.1:{server.server_port}/{self.NAME}", "test")
            remote, _ = clip(src, self.BBOX)
            self.assertEqual(remote, local)
            self.assertEqual(src.size, len(self.rd5))
            self.assertLess(src.fetched, len(self.rd5) / 5)
            RangeHandler.honor_range = False  # server senza Range: errore, non download dell'intera tile
            with self.assertRaises(OSError):
                clip(HttpSource(f"http://127.0.0.1:{server.server_port}/{self.NAME}", "test"), self.BBOX)
        finally:
            server.shutdown()


if __name__ == "__main__":
    unittest.main()
