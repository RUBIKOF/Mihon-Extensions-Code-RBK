from pathlib import Path
import gzip

from google.protobuf import json_format
from index_pb2 import Index

ROOT = Path(__file__).resolve().parents[2]

json_file = ROOT / "index.json"
pb_file = ROOT / "index.pb"

index = json_format.Parse(
    json_file.read_text(encoding="utf-8"),
    Index(),
)

data = index.SerializeToString(deterministic=True)

pb_file.write_bytes(
    gzip.compress(data, mtime=0)
)

print(f"Creado correctamente: {pb_file}")