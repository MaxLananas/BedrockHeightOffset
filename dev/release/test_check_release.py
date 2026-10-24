"""Unit tests for check_release.py. Run: python3 -m unittest discover -s dev/release -p 'test_*.py'"""
import os
import tempfile
import unittest

import check_release as cr

POM = """<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <version>{version}</version>
</project>
"""
CHANGELOG = """# Changelog

## [Unreleased]

Nothing yet.

## [1.1.0] - 2026-10-23

### Fixed

- Something real.

## Notes

Not part of any release.
"""


class CheckReleaseTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def write(self, name, body):
        path = os.path.join(self.tmp.name, name)
        with open(path, "w", encoding="utf-8") as fh:
            fh.write(body)
        return path

    def check(self, tag, pom_version="1.1.0", changelog=CHANGELOG):
        pom = self.write("pom.xml", POM.format(version=pom_version))
        log = self.write("CHANGELOG.md", changelog)
        return cr.check(tag, pom=pom, changelog=log)

    def test_consistent_release_returns_its_notes(self):
        version, notes = self.check("v1.1.0")
        self.assertEqual("1.1.0", version)
        self.assertIn("Something real.", notes)

    def test_notes_stop_at_the_next_level_two_heading(self):
        _, notes = self.check("v1.1.0")
        self.assertNotIn("Not part of any release", notes)

    def test_snapshot_pom_gets_its_own_message(self):
        with self.assertRaises(cr.ReleaseError) as ctx:
            self.check("v1.1.0", pom_version="1.1.0-SNAPSHOT")
        self.assertIn("still 1.1.0-SNAPSHOT", str(ctx.exception))

    def test_tag_must_match_the_pom_version(self):
        with self.assertRaises(cr.ReleaseError) as ctx:
            self.check("v1.2.0")
        self.assertIn("does not match", str(ctx.exception))

    def test_tag_form_is_strict(self):
        for bad in ("1.1.0", "v1.1", "v1.1.0-rc1", "v1.1.0\n"):
            with self.assertRaises(cr.ReleaseError, msg=bad):
                self.check(bad)

    def test_missing_or_empty_changelog_section_is_rejected(self):
        empty = CHANGELOG.replace("## [1.1.0] - 2026-10-23\n\n### Fixed\n\n- Something real.\n", "")
        with self.assertRaises(cr.ReleaseError) as ctx:
            self.check("v1.1.0", changelog=empty)
        self.assertIn("no non-empty section for [1.1.0]", str(ctx.exception))
        blank = CHANGELOG.replace("### Fixed\n\n- Something real.\n", "")
        with self.assertRaises(cr.ReleaseError):
            self.check("v1.1.0", changelog=blank)

    def test_unreadable_pom_is_a_reported_error_not_a_traceback(self):
        with self.assertRaises(cr.ReleaseError) as ctx:
            cr.check("v1.1.0", pom=os.path.join(self.tmp.name, "missing.xml"),
                     changelog=self.write("CHANGELOG.md", CHANGELOG))
        self.assertIn("cannot read", str(ctx.exception))

    def test_pom_without_version_is_rejected(self):
        pom = self.write("pom.xml", "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"/>")
        with self.assertRaises(cr.ReleaseError):
            cr.check("v1.1.0", pom=pom, changelog=self.write("CHANGELOG.md", CHANGELOG))

    def test_main_exit_codes_and_notes_file(self):
        # main() reads pom.xml and CHANGELOG.md from the working directory, so run it inside the temp dir.
        self.write("pom.xml", POM.format(version="1.1.0"))
        self.write("CHANGELOG.md", CHANGELOG)
        previous = os.getcwd()
        os.chdir(self.tmp.name)
        self.addCleanup(os.chdir, previous)
        notes = os.path.join(self.tmp.name, "notes.md")
        self.assertEqual(0, cr.main(["v1.1.0", "--notes", notes]))
        with open(notes, encoding="utf-8") as fh:
            self.assertIn("Something real.", fh.read())
        failed_notes = os.path.join(self.tmp.name, "failed.md")
        self.assertEqual(1, cr.main(["v9.9.9", "--notes", failed_notes]))
        self.assertFalse(os.path.exists(failed_notes), "no notes are written for a failed check")


if __name__ == "__main__":
    unittest.main()
