#!/usr/bin/env python3
"""Install the official, checksum-pinned GPL runtime for the local preview."""
import argparse
import hashlib
from pathlib import Path
import tempfile
import urllib.request
import zipfile

URL = 'https://cdn.sencha.com/ext/gpl/ext-7.0.0-gpl.zip'
SHA256 = '4afa3328474486173eb7cdd04f67d1ad241c4972b50cd7e0656bc1fbb4df757c'
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--archive', type=Path, help='Use an already downloaded official GPL ZIP')
a = p.parse_args()
archive = a.archive or Path(tempfile.gettempdir()) / 'infrapilot-ext-7.0.0-gpl.zip'
if not archive.is_file():
    print('Downloading official Ext JS 7.0 GPL SDK (about 241 MB)…', flush=True)
    with urllib.request.urlopen(URL, timeout=120) as source, archive.open('wb') as target:
        while chunk := source.read(1024 * 1024):
            target.write(chunk)
with archive.open('rb') as stream:
    digest = hashlib.file_digest(stream, 'sha256').hexdigest()
if digest != SHA256:
    raise SystemExit('SDK checksum mismatch; nothing installed. Remove the ZIP and retry.')
target = Path(__file__).resolve().parents[1] / 'public' / 'vendor' / 'ext'
exact = {'license.txt', 'license-include.txt', 'build/ext-modern-all.js'}
count = 0
with zipfile.ZipFile(archive) as source:
    for entry in source.infolist():
        name = entry.filename.removeprefix('ext-7.0.0/')
        if entry.is_dir() or not (name in exact or name.startswith('build/modern/theme-material/')):
            continue
        path = target / name
        if not path.resolve().is_relative_to(target.resolve()):
            raise SystemExit('Unsafe ZIP path')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(source.read(entry))
        count += 1
print(f'Installed {count} files in {target}. License: {target / "license.txt"}')
