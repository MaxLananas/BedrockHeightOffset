#!/usr/bin/env python3
"""Checks that a release tag is consistent with the project, and extracts its release notes.

Usage: check_release.py vX.Y.Z [--notes FILE]

Exits 1, with the reason on stderr, unless all of these hold:
  * the tag is vX.Y.Z;
  * pom.xml has that <version>, and it is not a -SNAPSHOT;
  * CHANGELOG.md has a "## [X.Y.Z]" section with content. The section ends at the next "## " heading.
"""
import argparse
import re
import sys
import xml.etree.ElementTree as ET

POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
TAG_PATTERN = re.compile(r"v(\d+\.\d+\.\d+)")


class ReleaseError(Exception):
    """A consistency rule failed. The message is the reason, for a human."""


def pom_version(path="pom.xml"):
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as exc:
        raise ReleaseError(f"cannot read {path}: {exc}") from exc
    node = root.find("m:version", POM_NS)
    if node is None or not node.text or not node.text.strip():
        raise ReleaseError(f"{path} has no <version>")
    return node.text.strip()


def changelog_section(version, path="CHANGELOG.md"):
    try:
        with open(path, encoding="utf-8") as fh:
            text = fh.read()
    except OSError as exc:
        raise ReleaseError(f"cannot read {path}: {exc}") from exc
    # The section runs to the next level-2 heading, whatever its text, so an unrelated "## Notes" section
    # cannot end up in the release notes.
    pattern = re.compile(r"^## \[" + re.escape(version) + r"\][^\n]*\n(.*?)(?=^## |\Z)", re.S | re.M)
    match = pattern.search(text)
    if not match or not match.group(1).strip():
        raise ReleaseError(f"CHANGELOG.md has no non-empty section for [{version}]")
    return match.group(1).strip() + "\n"


def check(tag, pom="pom.xml", changelog="CHANGELOG.md"):
    """Returns (version, notes) for a consistent release, raises ReleaseError otherwise."""
    match = TAG_PATTERN.fullmatch(tag)
    if not match:
        raise ReleaseError(f"tag '{tag}' is not of the form vX.Y.Z")
    tag_version = match.group(1)
    current = pom_version(pom)
    if current.endswith("-SNAPSHOT"):
        raise ReleaseError(f"pom.xml is still {current}; set the release version {tag_version} before tagging")
    if current != tag_version:
        raise ReleaseError(f"pom.xml version {current} does not match tag {tag}")
    return tag_version, changelog_section(tag_version, changelog)


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    parser.add_argument("--notes", help="write the changelog section here")
    args = parser.parse_args(argv)
    try:
        version, notes = check(args.tag)
    except ReleaseError as exc:
        print(f"release check failed: {exc}", file=sys.stderr)
        return 1
    if args.notes:
        with open(args.notes, "w", encoding="utf-8") as fh:
            fh.write(notes)
    print(f"release v{version}: pom version and changelog agree")
    return 0


if __name__ == "__main__":
    sys.exit(main())
