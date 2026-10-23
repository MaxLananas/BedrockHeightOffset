#!/usr/bin/env python3
"""Print a Markdown summary of Surefire XML reports (for $GITHUB_STEP_SUMMARY).

Usage: surefire_summary.py [report-dir]

Exits 0 even when no reports exist, so it can run after a failed build; the build step carries the
failure. The counts come straight from the XML written by Surefire, never from the console log.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET


def main() -> int:
    report_dir = sys.argv[1] if len(sys.argv) > 1 else "target/surefire-reports"
    files = sorted(glob.glob(os.path.join(report_dir, "TEST-*.xml")))
    print("## Test results")
    if not files:
        print("No Surefire reports found (the build may have failed before tests ran).")
        return 0
    totals = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    rows = []
    for path in files:
        suite = ET.parse(path).getroot()
        counts = {k: int(suite.get(k, "0")) for k in totals}
        for k in totals:
            totals[k] += counts[k]
        rows.append((suite.get("name", os.path.basename(path)), counts))
    print("| Suite | Tests | Failures | Errors | Skipped |")
    print("|---|---:|---:|---:|---:|")
    for name, c in rows:
        print(f"| `{name}` | {c['tests']} | {c['failures']} | {c['errors']} | {c['skipped']} |")
    print(f"| **Total** | **{totals['tests']}** | **{totals['failures']}** | **{totals['errors']}** | **{totals['skipped']}** |")
    if totals["skipped"]:
        print("\n> Skipped tests are listed in the Surefire reports; each one must have a documented reason.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
