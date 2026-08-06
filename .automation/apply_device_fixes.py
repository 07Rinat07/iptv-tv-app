from __future__ import annotations

import base64
import lzma
import subprocess
from pathlib import Path


def main() -> None:
    part_paths = sorted(Path(".automation").glob("device-fixes.part-*"))
    if not part_paths:
        raise RuntimeError("Device-fix patch parts were not found")

    encoded = "".join(path.read_text(encoding="utf-8").strip() for path in part_paths)
    patch_bytes = lzma.decompress(base64.b64decode(encoded))
    patch_path = Path(".automation/device-acceptance-fixes.patch")
    patch_path.write_bytes(patch_bytes)
    try:
        subprocess.run(
            ["git", "apply", "--3way", "--whitespace=fix", str(patch_path)],
            check=True,
        )
    finally:
        patch_path.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
