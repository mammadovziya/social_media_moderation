#!/usr/bin/env python3
"""Generate the deterministic text-bearing PNG embedded by the k6 harness."""

from __future__ import annotations

import base64
import binascii
import struct
import zlib


WIDTH = 800
HEIGHT = 600

FONT = {
    "0": ("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
    "1": ("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
    "2": ("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
    "5": ("11111", "10000", "11110", "00001", "00001", "10001", "01110"),
    "6": ("00110", "01000", "10000", "11110", "10001", "10001", "01110"),
    "A": ("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
    "B": ("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
    "C": ("01111", "10000", "10000", "10000", "10000", "10000", "01111"),
    "E": ("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
    "G": ("01111", "10000", "10000", "10111", "10001", "10001", "01111"),
    "I": ("11111", "00100", "00100", "00100", "00100", "00100", "11111"),
    "K": ("10001", "10010", "10100", "11000", "10100", "10010", "10001"),
    "L": ("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
    "M": ("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
    "N": ("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
    "O": ("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
    "R": ("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
    "S": ("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
    "T": ("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
    "V": ("10001", "10001", "10001", "10001", "10001", "01010", "00100"),
    "Y": ("10001", "10001", "01010", "00100", "00100", "00100", "00100"),
    "Z": ("11111", "00001", "00010", "00100", "01000", "10000", "11111"),
}


def fill(pixels: bytearray, x: int, y: int, width: int, height: int, color: tuple[int, int, int]) -> None:
    for row in range(max(0, y), min(HEIGHT, y + height)):
        for column in range(max(0, x), min(WIDTH, x + width)):
            offset = (row * WIDTH + column) * 3
            pixels[offset : offset + 3] = bytes(color)


def text(pixels: bytearray, value: str, x: int, y: int, scale: int, color: tuple[int, int, int]) -> None:
    cursor = x
    for character in value:
        if character == " ":
            cursor += 4 * scale
            continue
        glyph = FONT[character]
        for glyph_y, row in enumerate(glyph):
            for glyph_x, enabled in enumerate(row):
                if enabled == "1":
                    fill(
                        pixels,
                        cursor + glyph_x * scale,
                        y + glyph_y * scale,
                        scale,
                        scale,
                        color,
                    )
        cursor += 6 * scale


def chunk(kind: bytes, payload: bytes) -> bytes:
    return (
        struct.pack(">I", len(payload))
        + kind
        + payload
        + struct.pack(">I", binascii.crc32(kind + payload) & 0xFFFFFFFF)
    )


def png() -> bytes:
    pixels = bytearray((244, 247, 251)) * (WIDTH * HEIGHT)
    fill(pixels, 48, 42, 704, 516, (255, 255, 255))
    fill(pixels, 48, 42, 704, 92, (11, 42, 74))
    fill(pixels, 72, 160, 656, 2, (203, 213, 225))
    text(pixels, "ABB INVESTISIYA", 78, 65, 7, (255, 255, 255))
    text(pixels, "BAZAR ICMALI 2026", 80, 195, 6, (17, 24, 39))
    text(pixels, "MEBLEG 1 250 AZN", 80, 290, 6, (17, 24, 39))
    text(pixels, "RISK SEVIYYESI ORTA", 80, 390, 5, (180, 83, 9))
    raw = b"".join(b"\x00" + pixels[row * WIDTH * 3 : (row + 1) * WIDTH * 3] for row in range(HEIGHT))
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", WIDTH, HEIGHT, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, level=9))
        + chunk(b"IEND", b"")
    )


if __name__ == "__main__":
    print(base64.b64encode(png()).decode("ascii"))
