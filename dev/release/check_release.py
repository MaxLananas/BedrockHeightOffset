#!/usr/bin/env python3
"""Checks that a release tag is consistent with the project, and extracts its release notes.

Usage: check_release.py vX.Y.Z [--notes FILE]

Fails (exit 1) unless all of these hold:
  * the tag is vX.Y.Z and equals the <version> in pom.xml (no -SNAPSHOT);
  * CHANGELOG.md has a "## [X.Y.Z]" section with content.
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET

POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def pom_version(path: str = "pom.xml") -> str:
    root = ET.parse(path).getroot()
    node = root.find("m:version", POM_NS)
    if node is None or not node.text:
        raise SystemExit("pom.xml has no <version>")
    return node.text.strip()


def changelog_section(version: str, path: str = "CHANGELOG.md") -> str:
    text = open(path, encoding="utf-8").read()
    pattern = re.compile(r"^## \[" + re.escape(version) + r"\][^\n]*\n(.*?)(?=^## \[|\Z)", re.S | re.M)
    match = pattern.search(text)
    if not match or not match.group(1).strip():
        raise SystemExit(f"CHANGELOG.md has no non-empty section for [{version}]")
    return match.group(1).strip() + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    parser.add_argument("--notes", help="write the changelog section here")
    args = parser.parse_args()

    match = re.fullmatch(r"v(\d+\.\d+\.\d+)", args.tag)
    if not match:
        print(f"tag '{args.tag}' is not of the form vX.Y.Z", file=sys.stderr)
        return 1
    tag_version = match.group(1)
    pom = pom_version()
    if pom != tag_version:
        print(f"pom.xml version {pom} does not match tag {args.tag}", file=sys.stderr)
        return 1
    notes = changelog_section(tag_version)
    if args.notes:
        with open(args.notes, "w", encoding="utf-8") as fh:
            fh.write(notes)
    print(f"release {args.tag}: pom version and changelog agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
