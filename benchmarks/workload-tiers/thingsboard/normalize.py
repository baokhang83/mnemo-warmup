"""Rebuild the vendor's prefixed Spring Boot ZIP without its shell launcher."""
from pathlib import Path
from shutil import copyfileobj
from zipfile import ZipFile

source = Path('/usr/share/thingsboard/bin/thingsboard.jar')
destination = Path('/tmp/thingsboard-plain.jar')
with ZipFile(source) as original, ZipFile(destination, 'w', allowZip64=True) as clean:
    for entry in original.infolist():
        with original.open(entry) as input_file, clean.open(entry, 'w', force_zip64=True) as output_file:
            copyfileobj(input_file, output_file, 1024 * 1024)
