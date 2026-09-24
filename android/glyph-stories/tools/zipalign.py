#!/usr/bin/env python3
"""Minimal zipalign: rewrites an APK so every uncompressed entry's data starts on a 4-byte
boundary (4096 for native libraries), as Android requires. Padding goes in a proper extra
field (id 0xD935, the one Google's zipalign uses)."""
import struct
import sys
import zipfile

def align_for(name):
    return 4096 if name.endswith('.so') else 4

def main(src, dst):
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w') as zout:
        for info in zin.infolist():
            data = zin.read(info.filename)
            out = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            out.compress_type = info.compress_type
            out.external_attr = info.external_attr
            out.extra = b''
            if info.compress_type == zipfile.ZIP_STORED:
                a = align_for(info.filename)
                base = zout.fp.tell() + 30 + len(info.filename.encode('utf-8'))
                if base % a:
                    n = 6
                    while (base + n) % a:
                        n += 1
                    out.extra = struct.pack('<HHH', 0xD935, n - 4, a) + b'\0' * (n - 6)
            zout.writestr(out, data)

    # Check
    with open(dst, 'rb') as f, zipfile.ZipFile(dst) as z:
        for info in z.infolist():
            if info.compress_type != zipfile.ZIP_STORED:
                continue
            f.seek(info.header_offset + 26)
            nlen, xlen = struct.unpack('<HH', f.read(4))
            off = info.header_offset + 30 + nlen + xlen
            if off % align_for(info.filename):
                sys.exit('misaligned: %s at %d' % (info.filename, off))

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
