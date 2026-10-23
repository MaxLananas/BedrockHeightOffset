#!/usr/bin/env python3
"""Reports Surefire results for CI.

Usage:
  surefire_summary.py [report-dir]              Markdown summary (for $GITHUB_STEP_SUMMARY).
  surefire_summary.py --annotate [report-dir]   GitHub workflow commands: one notice with the totals,
                                                and one error annotation per failing or erroring test.

Both modes exit 0 even when no reports exist, so they can run after a failed build; the build step carries
the failure. The counts come straight from the XML written by Surefire, never from the console log.
Annotations are readable through the check-run API even when the job logs are not.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_DIR = "target/surefire-reports"
COUNTERS = ("tests", "failures", "errors", "skipped")


def load_suites(report_dir):
    """Yields (suite element) for every TEST-*.xml, in name order."""
    for path in sorted(glob.glob(os.path.join(report_dir, "TEST-*.xml"))):
        yield path, ET.parse(path).getroot()


def totals_of(suites):
    totals = {k: 0 for k in COUNTERS}
    for _, suite in suites:
        for k in COUNTERS:
            totals[k] += int(suite.get(k, "0"))
    return totals


def summary(report_dir):
    lines = ["## Test results"]
    suites = list(load_suites(report_dir))
    if not suites:
        lines.append("No Surefire reports found (the build may have failed before tests ran).")
        return lines
    lines.append("| Suite | Tests | Failures | Errors | Skipped |")
    lines.append("|---|---:|---:|---:|---:|")
    totals = {k: 0 for k in COUNTERS}
    for path, suite in suites:
        counts = {k: int(suite.get(k, "0")) for k in COUNTERS}
        for k in COUNTERS:
            totals[k] += counts[k]
        name = suite.get("name", os.path.basename(path))
        lines.append(f"| `{name}` | {counts['tests']} | {counts['failures']} | {counts['errors']} | "
                     f"{counts['skipped']} |")
    lines.append(f"| **Total** | **{totals['tests']}** | **{totals['failures']}** | **{totals['errors']}** | "
                 f"**{totals['skipped']}** |")
    if totals["skipped"]:
        lines.append("")
        lines.append("> Skipped tests are listed in the Surefire reports; each one must have a documented reason.")
    return lines


def escape(value):
    """Escapes a workflow-command message (%, CR, LF)."""
    return value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(value):
    """Escapes a workflow-command property value (adds ':' and ',')."""
    return escape(value).replace(":", "%3A").replace(",", "%2C")


def annotations(report_dir):
    out = []
    suites = list(load_suites(report_dir))
    if not suites:
        out.append("::warning title=Test results::No Surefire reports found; the build failed before tests ran.")
        return out
    totals = totals_of(suites)
    out.append(
        "::notice title=Test results::"
        + escape(f"{totals['tests']} tests, {totals['failures']} failures, {totals['errors']} errors, "
                 f"{totals['skipped']} skipped")
    )
    for _, suite in suites:
        for case in suite.iter("testcase"):
            for kind in ("failure", "error"):
                node = case.find(kind)
                if node is None:
                    continue
                title = f"{case.get('classname', '?')}.{case.get('name', '?')}"
                message = (node.get("message") or node.text or kind).strip().splitlines()
                first = message[0] if message else kind
                out.append(f"::error title={escape_property(title)}::{escape(first[:500])}")
    return out


def main(argv):
    annotate = "--annotate" in argv
    args = [a for a in argv if a != "--annotate"]
    report_dir = args[0] if args else DEFAULT_DIR
    if annotate:
        print("\n".join(annotations(report_dir)))
    else:
        print("\n".join(summary(report_dir)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
