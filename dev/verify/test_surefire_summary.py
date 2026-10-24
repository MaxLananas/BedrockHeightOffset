"""Unit tests for surefire_summary.py. Run: python3 -m unittest discover -s dev/verify -p 'test_*.py'"""
import contextlib
import io
import os
import tempfile
import unittest

import surefire_summary as s

PASSING = """<?xml version="1.0"?>
<testsuite name="io.example.A" tests="3" failures="0" errors="0" skipped="1"/>
"""
FAILING = """<?xml version="1.0"?>
<testsuite name="io.example.B" tests="2" failures="1" errors="1" skipped="0">
  <testcase classname="io.example.B" name="broken, 100%">
    <failure message="expected 100% but was 2&#10;second line">trace</failure>
  </testcase>
  <testcase classname="io.example.B" name="erroring">
    <error>java.lang.IllegalStateException: boom</error>
  </testcase>
</testsuite>
"""
TRUNCATED = '<?xml version="1.0"?>\n<testsuite name="io.example.C" tests="9" failures="0"'


class SurefireSummaryTest(unittest.TestCase):

    def reports(self, files):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        for name, body in files.items():
            with open(os.path.join(tmp.name, name), "w", encoding="utf-8") as fh:
                fh.write(body)
        return tmp.name

    def run_main(self, argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = s.main(argv)
        return code, out.getvalue(), err.getvalue()

    def test_totals_are_summed_once_from_every_suite(self):
        d = self.reports({"TEST-a.xml": PASSING, "TEST-b.xml": FAILING})
        suites, unreadable = s.read_reports(d)
        self.assertEqual([], unreadable)
        self.assertEqual({"tests": 5, "failures": 1, "errors": 1, "skipped": 1}, s.totals_of(suites))
        text = "\n".join(s.summary(d))
        self.assertIn("| **Total** | **5** | **1** | **1** | **1** |", text)

    def test_truncated_report_is_named_and_excluded_but_does_not_crash(self):
        d = self.reports({"TEST-a.xml": PASSING, "TEST-c.xml": TRUNCATED})
        code, out, _ = self.run_main([d])
        self.assertEqual(0, code)
        self.assertIn("Unreadable report `TEST-c.xml`", out)
        self.assertIn("| **Total** | **3** |", out)  # the truncated suite's 9 tests are not counted

    def test_annotations_warn_about_unreadable_reports_and_error_on_failures(self):
        d = self.reports({"TEST-b.xml": FAILING, "TEST-c.xml": TRUNCATED})
        lines = s.annotations(d)
        self.assertTrue(lines[0].startswith("::notice title=Test results::2 tests, 1 failures, 1 errors"))
        self.assertIn("1 unreadable report(s) not counted", lines[0])
        self.assertTrue(any(l.startswith("::warning title=Unreadable test report::TEST-c.xml") for l in lines))
        errors = [l for l in lines if l.startswith("::error ")]
        self.assertEqual(2, len(errors))
        # the comma and the percent sign in the test name are escaped for workflow commands
        # only the first line of the message is annotated, and its percent sign is escaped
        self.assertEqual("::error title=io.example.B.broken%2C 100%25::expected 100%25 but was 2", errors[0])

    def test_pipe_in_a_suite_name_does_not_break_the_table(self):
        body = PASSING.replace('name="io.example.A"', 'name="io.example|A"')
        text = "\n".join(s.summary(self.reports({"TEST-a.xml": body})))
        self.assertIn("| `io.example\\|A` | 3 | 0 | 0 | 1 |", text)

    def test_empty_directory_is_reported_not_crashed(self):
        self.assertIn("No Surefire reports found", "\n".join(s.summary(self.reports({}))))
        self.assertEqual(["::warning title=Test results::No Surefire reports found; the build failed before tests ran."],
                         s.annotations(self.reports({})))

    def test_unknown_option_is_a_usage_error_not_a_directory(self):
        code, out, err = self.run_main(["--anotate", "x"])
        self.assertEqual(2, code)
        self.assertIn("unknown option: --anotate", err)
        self.assertEqual("", out)

    def test_two_positionals_are_a_usage_error(self):
        code, _, err = self.run_main(["a", "b"])
        self.assertEqual(2, code)
        self.assertIn("usage:", err)

    def test_annotate_flag_works_in_either_position(self):
        d = self.reports({"TEST-b.xml": FAILING})
        _, before, _ = self.run_main(["--annotate", d])
        _, after, _ = self.run_main([d, "--annotate"])
        self.assertEqual(before, after)
        self.assertIn("::error", before)


if __name__ == "__main__":
    unittest.main()
