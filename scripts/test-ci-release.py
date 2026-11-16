#!/usr/bin/env python3
"""Lightweight checks for release selection; never connects or mutates GitHub."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('prepare_release', Path(__file__).with_name('prepare-ci-release.py'))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
SHA = 'a' * 40


class FakeGitHub:
    def __init__(self, published=False, codes=(12,), tag=None):
        self.published, self.codes, self.tag, self.created = published, codes, tag, []
        self.history_checks = 0

    def release(self, tag):
        return {'draft': False} if self.published else None

    def published_codes(self):
        self.history_checks += 1
        return self.codes

    def tag_commit(self, tag):
        return self.tag

    def create_tag(self, tag, commit):
        self.created.append((tag, commit))
        self.tag = commit


class ReleaseSelectionTests(unittest.TestCase):
    def test_unchanged_version_does_not_check_history_or_create_tag(self):
        api = FakeGitHub(published=True)
        result = module.prepare(api, '0.2.10', 13, SHA)
        self.assertEqual(result['publish'], 'false')
        self.assertEqual(api.history_checks, 0)
        self.assertEqual(api.created, [])

    def test_new_version_creates_exact_tag(self):
        api = FakeGitHub()
        result = module.prepare(api, '0.2.10', 13, SHA)
        self.assertEqual(result['publish'], 'true')
        self.assertEqual(result['version_code'], '13')
        self.assertEqual(api.created, [('v0.2.10', SHA)])

    def test_equal_and_lower_version_codes_stop_before_tag_creation(self):
        for code in [11, 12]:
            api = FakeGitHub()
            with self.assertRaises(ValueError): module.prepare(api, '0.2.10', code, SHA)
            self.assertEqual(api.created, [])

    def test_conflicting_tag_is_never_moved(self):
        api = FakeGitHub(tag='b' * 40)
        with self.assertRaises(ValueError): module.prepare(api, '0.2.10', 13, SHA)
        self.assertEqual(api.tag, 'b' * 40)
        self.assertEqual(api.created, [])

    def test_pushed_tag_must_match_source(self):
        api = FakeGitHub()
        with self.assertRaises(ValueError): module.prepare(api, '0.2.10', 13, SHA, 'v0.2.11')
        self.assertEqual(api.created, [])

    def test_retry_uses_same_source_tag(self):
        api = FakeGitHub(tag=SHA)
        self.assertEqual(module.prepare(api, '0.2.10', 13, SHA)['publish'], 'true')
        self.assertEqual(api.created, [])

    def test_release_source_literals_are_unambiguous(self):
        self.assertEqual(module.source_version('versionName = "0.2.10"\nversionCode = 13\n'), ('0.2.10', 13))
        with self.assertRaises(ValueError): module.source_version('versionName = dynamic\nversionCode = 13\n')
        with self.assertRaises(ValueError): module.source_version('versionName = "0.2.10"\nversionCode = 13\nversionCode = 14\n')


if __name__ == '__main__': unittest.main()
