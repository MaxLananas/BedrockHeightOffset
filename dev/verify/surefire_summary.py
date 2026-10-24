#!/usr/bin/env python3
"""Reports Surefire results for CI.

Usage:
  surefire_summary.py [report-dir]              Markdown summary (for $GITHUB_STEP_SUMMARY).
  surefire_summary.py --annotate [report-dir]   GitHub workflow commands: one notice with the totals, one
                                                warning per unreadable report, and one error annotation per
                                                failing or erroring test.

Both modes exit 0 when the reports cannot be read, so they can run after a failed build; the build step carries
the failure. An unknown option is a usage error (exit 2). The counts come straight from the XML written by
Surefire, never from the console log. A report that cannot be parsed (for example, truncated by a killed
JVM) is named and left out of the totals, so the summary never hides that a suite did not finish.
Annotations are readable through the check-run API even when the job logs are not.
"""
import glob
import os
import sys
import xml.etree.ElementTree as ET

DEFAULT_DIR = "target/surefire-reports"
COUNTERS = ("tests", "failures", "errors", "skipped")
USAGE = "usage: surefire_summary.py [--annotate] [report-dir]"


def read_reports(report_dir):
    """Returns (suites, unreadable): suites is [(path, root)], unreadable is [(path, reason)], both in name order."""
    suites, unreadable = [], []
    for path in sorted(glob.glob(os.path.join(report_dir, "TEST-*.xml"))):
        try:
            suites.append((path, ET.parse(path).getroot()))
        except (ET.ParseError, OSError) as exc:
            unreadable.append((path, str(exc)))
    return suites, unreadable


def counts_of(suite):
    return {k: int(suite.get(k, "0")) for k in COUNTERS}


def totals_of(suites):
    totals = {k: 0 for k in COUNTERS}
    for _, suite in suites:
        for k, v in counts_of(suite).items():
            totals[k] += v
    return totals


def md_cell(value):
    """Escapes a Markdown table cell: a pipe would start a new column, a newline would end the row."""
    return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ")


def summary(report_dir):
    suites, unreadable = read_reports(report_dir)
    lines = ["## Test results"]
    if not suites and not unreadable:
        lines.append("No Surefire reports found (the build may have failed before tests ran).")
        return lines
    lines.append("| Suite | Tests | Failures | Errors | Skipped |")
    lines.append("|---|---:|---:|---:|---:|")
    for path, suite in suites:
        c = counts_of(suite)
        name = md_cell(suite.get("name", os.path.basename(path)))
        lines.append(f"| `{name}` | {c['tests']} | {c['failures']} | {c['errors']} | {c['skipped']} |")
    t = totals_of(suites)
    lines.append(f"| **Total** | **{t['tests']}** | **{t['failures']}** | **{t['errors']}** | **{t['skipped']}** |")
    for path, reason in unreadable:
        lines.append("")
        lines.append(f"> Unreadable report `{md_cell(os.path.basename(path))}` ({md_cell(reason)}). "
                     "It is not in the totals above, so the run is incomplete.")
    if t["skipped"]:
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
    suites, unreadable = read_reports(report_dir)
    if not suites and not unreadable:
        out.append("::warning title=Test results::No Surefire reports found; the build failed before tests ran.")
        return out
    t = totals_of(suites)
    text = f"{t['tests']} tests, {t['failures']} failures, {t['errors']} errors, {t['skipped']} skipped"
    if unreadable:
        text += f"; {len(unreadable)} unreadable report(s) not counted"
    out.append("::notice title=Test results::" + escape(text))
    for path, reason in unreadable:
        out.append("::warning title=Unreadable test report::"
                   + escape(f"{os.path.basename(path)}: {reason}"))
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
    annotate = False
    positional = []
    for arg in argv:
        if arg in ("-h", "--help"):
            print(__doc__)
            return 0
        if arg == "--annotate":
            annotate = True
        elif arg.startswith("-"):
            print(f"unknown option: {arg}\n{USAGE}", file=sys.stderr)
            return 2
        else:
            positional.append(arg)
    if len(positional) > 1:
        print(USAGE, file=sys.stderr)
        return 2
    report_dir = positional[0] if positional else DEFAULT_DIR
    lines = annotations(report_dir) if annotate else summary(report_dir)
    print("\n".join(lines))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
