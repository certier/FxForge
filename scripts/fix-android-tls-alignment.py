#!/usr/bin/env python3
"""Raise AArch64 PT_TLS alignment to the 64-byte minimum required by Android Bionic.

The upstream static PRoot build emits PT_TLS with p_align=8. Android's
AArch64 dynamic loader rejects that executable before it can start. The
segment's file offset and virtual address already have matching 64-byte
skew, so increasing p_align is a valid ELF metadata correction.
"""
import struct
import sys
from pathlib import Path

ELF_HEADER = struct.Struct("<16sHHIQQQIHHHHHH")
PROGRAM_HEADER = struct.Struct("<IIQQQQQQ")
PT_TLS = 7
EM_AARCH64 = 183
TLS_ALIGNMENT = 64


def fix(path: Path) -> None:
    data = bytearray(path.read_bytes())
    if len(data) < ELF_HEADER.size:
        raise SystemExit(f"{path}: truncated ELF header")
    header = ELF_HEADER.unpack_from(data)
    ident, machine = header[0], header[2]
    if ident[:4] != b"\x7fELF" or ident[5] != 1:
        raise SystemExit(f"{path}: invalid or unsupported ELF file")
    if ident[4] == 1:
        print(f"{path}: 32-bit helper; skipped (Android TLS workaround targets AArch64 PRoot)")
        return
    if ident[4] != 2:
        raise SystemExit(f"{path}: unsupported ELF class {ident[4]}")
    if machine != EM_AARCH64:
        raise SystemExit(f"{path}: expected AArch64 ELF (machine={machine})")

    phoff, phentsize, phnum = header[5], header[9], header[10]
    if phentsize < PROGRAM_HEADER.size or phoff + phentsize * phnum > len(data):
        raise SystemExit(f"{path}: invalid program-header table")

    changed = False
    for index in range(phnum):
        offset = phoff + index * phentsize
        ph = PROGRAM_HEADER.unpack_from(data, offset)
        p_type, p_offset, p_vaddr, p_align = ph[0], ph[2], ph[3], ph[7]
        if p_type != PT_TLS:
            continue
        if p_offset % TLS_ALIGNMENT != p_vaddr % TLS_ALIGNMENT:
            raise SystemExit(
                f"{path}: PT_TLS offset/vaddr skew is incompatible with 64-byte alignment"
            )
        if p_align < TLS_ALIGNMENT:
            struct.pack_into("<Q", data, offset + 48, TLS_ALIGNMENT)
            changed = True
        if p_align > 1 and p_align & (p_align - 1):
            raise SystemExit(f"{path}: PT_TLS alignment is not a power of two")

    if not any(
        PROGRAM_HEADER.unpack_from(data, phoff + i * phentsize)[0] == PT_TLS
        for i in range(phnum)
    ):
        print(f"{path}: no PT_TLS segment; unchanged")
    else:
        path.write_bytes(data)
        print(f"{path}: PT_TLS alignment {'raised to 64' if changed else 'already valid'}")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("usage: fix-android-tls-alignment.py ELF [ELF ...]")
    for item in sys.argv[1:]:
        fix(Path(item))
