"""Independent hand-defined test vector. Python standard library only.
Not an official NIST/agency conformance fixture. Run from project root.
Uses padded LEN fields, unlike the Java encoder, to test actual reading.
"""
from pathlib import Path
import struct
import zlib


def chunk(kind, data):
    return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))


def record(t, fields, image=None):
    # Eight fixed decimal positions allow a direct length computation (no Java algorithm reuse).
    prefix = f'{t}.001:00000000'.encode('ascii')
    tail = b''.join(b'\x1d' + f'{t}.{f:03}:'.encode('ascii') + v for f, v in fields)
    if image is not None:
        tail += b'\x1d10.999:' + image
    tail += b'\x1c'
    return f'{t}.001:{len(prefix) + len(tail):08}'.encode('ascii') + tail


def main():
    w, h = 32, 40
    rows = b''.join(b'\x00' + bytes(v for x in range(w) for v in (x*7 % 256, y*5 % 256, (x+y) % 256)) for y in range(h))
    png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB',w,h,8,2,0,0,0))
    png += chunk(b'npSt',b'\x1c\x1d\x1e\x1f10.999:')
    png += chunk(b'IDAT',zlib.compress(rows)) + chunk(b'IEND',b'')
    one = [(2,b'0502'),(3,b'1\x1f2\x1e2\x1f00\x1e10\x1f01'),(4,b'TEST'),(5,b'20261105'),
           (7,b'TEST_RECEIVER'),(8,b'TEST_SENDER'),(9,b'TEST-20261105-0001'),
           (11,b'00.00'),(12,b'00.00'),(13,b'DEMO\x1f1')]
    two = [(2,b'00'),(3,b'SYNTHETIC-SUBJECT')]
    ten = [(2,b'01'),(3,b'FACE'),(4,b'TEST_CAMERA_OWNER'),(5,b'20261104'),(6,b'32'),(7,b'40'),
           (8,b'0'),(9,b'1'),(10,b'1'),(11,b'PNG'),(12,b'RGB'),(13,b'0'),(992,b'00')]
    Path('examples/synthetic.png').write_bytes(png)
    Path('examples/reference.an2').write_bytes(record(1,one)+record(2,two)+record(10,ten,png))
    print('Created synthetic PNG and independent reference transaction')


if __name__ == '__main__':
    main()
