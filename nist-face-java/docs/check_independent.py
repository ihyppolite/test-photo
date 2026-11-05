"""Small independent structural checker, not a normative conformance validator.
Usage: python3 docs/check_independent.py output.an2 original.png
"""
import hashlib
import re
import sys
from pathlib import Path


def check(path, original):
    data = Path(path).read_bytes()
    pos = 0
    fields_by_type = {}
    for expected_type in (1, 2, 10):
        m = re.match(rb'([0-9]+)\.001:([0-9]+)\x1d', data[pos:])
        assert m and int(m[1]) == expected_type, 'record header/order'
        length = int(m[2])
        end = pos + length
        assert pos < end <= len(data) and data[end-1] == 28, 'LEN/FS'
        body = data[pos:end-1]
        image = None
        if expected_type == 10:
            header, image = body.split(b'\x1d10.999:', 1)
        else:
            header = body
        fields = {}
        for field in header.split(b'\x1d'):
            tag, value = field.split(b':', 1)
            rt, number = map(int, tag.split(b'.'))
            assert rt == expected_type and number not in fields, 'field tag/duplicate'
            fields[number] = value
        fields_by_type[expected_type] = fields
        if image is not None:
            assert image == Path(original).read_bytes(), 'PNG bytes'
            print('PNG SHA-256:', hashlib.sha256(image).hexdigest())
        print(f'Type-{expected_type}: offset={pos} LEN={length}')
        pos = end
    assert pos == len(data), 'trailing bytes'
    assert fields_by_type[1][2] == b'0502'
    assert fields_by_type[1][3] == b'1\x1f2\x1e2\x1f00\x1e10\x1f01'
    assert fields_by_type[2][2] == b'00'
    assert fields_by_type[10][2] == b'01'
    assert fields_by_type[10][992] == b'00'
    assert fields_by_type[10][3] == b'FACE'
    assert fields_by_type[10][11] == b'PNG'
    print('PASS: independent structural and byte-preservation checks')


if __name__ == '__main__':
    if len(sys.argv) != 3:
        raise SystemExit('Usage: python3 docs/check_independent.py file.an2 original.png')
    check(*sys.argv[1:])
