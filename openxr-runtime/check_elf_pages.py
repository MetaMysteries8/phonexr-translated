#!/usr/bin/env python3
"""Reject native runtime binaries whose ELF PT_LOAD segments cannot load on 16 KiB pages."""

import argparse
import struct
import zipfile


def bad_segments(binary: bytes):
    if binary[:4] != b"\x7fELF" or binary[4] != 2 or binary[5] != 1:
        raise ValueError("Expected 64-bit little-endian ELF")
    phoff = struct.unpack_from("<Q", binary, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", binary, 54)
    if phentsize < 56 or phoff + phentsize * phnum > len(binary):
        raise ValueError("Invalid ELF program headers")
    return [hex(struct.unpack_from("<Q", binary, phoff + i * phentsize + 48)[0])
            for i in range(phnum)
            if struct.unpack_from("<I", binary, phoff + i * phentsize)[0] == 1
            and struct.unpack_from("<Q", binary, phoff + i * phentsize + 48)[0] < 16384]


def check(apk: str):
    with zipfile.ZipFile(apk) as archive:
        libraries = [name for name in archive.namelist()
                     if name.startswith("lib/arm64-v8a/") and name.endswith(".so")]
        if not libraries:
            raise ValueError("No arm64 runtime libraries in APK")
        failures = [(name, bad_segments(archive.read(name))) for name in libraries]
    failures = [(name, alignments) for name, alignments in failures if alignments]
    for name, alignments in failures:
        print(f"{name}: incompatible PT_LOAD alignment {', '.join(alignments)}")
    if failures:
        raise SystemExit("Rebuild all native dependencies with 16 KiB ELF alignment before packaging")
    print(f"All {len(libraries)} arm64 libraries have 16 KiB compatible PT_LOAD alignment")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    check(parser.parse_args().apk)
