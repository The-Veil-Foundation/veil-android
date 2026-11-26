import argparse
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser(description="Recompress an APK before zipalign and signing")
parser.add_argument("input", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
if args.input.resolve() == args.output.resolve():
    parser.error("Input and output must differ")
with zipfile.ZipFile(args.input) as source, zipfile.ZipFile(args.output, "x", compresslevel=9) as target:
    for entry in source.infolist():
        name = entry.filename.upper()
        if name.startswith("META-INF/") and (name.endswith((".SF", ".RSA", ".DSA", ".EC")) or name == "META-INF/MANIFEST.MF"):
            continue
        target.writestr(entry, source.read(entry), compress_type=entry.compress_type, compresslevel=9)
print(f"Unsigned APK: {args.output}. Run zipalign and apksigner before installing.")
