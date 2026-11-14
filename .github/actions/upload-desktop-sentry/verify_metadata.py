"""Verify all packaged Sentry identities in one process, without extracting JARs."""

from pathlib import Path
import sys
from zipfile import ZipFile


def verify_metadata(metadata: Path, binaries: Path) -> int:
    expected = metadata.read_bytes()
    found = 0
    for jar in binaries.rglob("*.jar"):
        with ZipFile(jar) as archive:
            for entry in archive.infolist():
                if entry.filename == "sentry-debug-meta.properties":
                    if archive.read(entry) != expected:
                        raise ValueError(f"Sentry JVM debug identity mismatch in {jar}")
                    found += 1
    if not found:
        raise ValueError("Packaged desktop artifact has no Sentry JVM debug identity")
    return found


if __name__ == "__main__":
    verify_metadata(Path(sys.argv[1]), Path(sys.argv[2]))
