#!/usr/bin/env python3
"""Select one immutable source revision; skip published versions before any Android setup."""
import base64
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

REPOSITORY = 'jinshenganyuci/codex-cli-app'
VERSION = r'\d+\.\d+\.\d+(?:-[A-Za-z0-9][A-Za-z0-9.-]*)?'


def source_version(text):
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"\s*$', text, re.M)
    codes = re.findall(r'^\s*versionCode\s*=\s*(\d+)\s*$', text, re.M)
    if len(names) != 1 or len(codes) != 1 or not re.fullmatch(VERSION, names[0]):
        raise ValueError('Expected one literal release versionName and versionCode')
    return names[0], int(codes[0])


class GitHub:
    def __init__(self):
        self.token = os.environ['GH_TOKEN']
        if os.environ.get('GH_REPO') != REPOSITORY:
            raise ValueError('Unexpected repository')

    def api(self, path, data=None, missing=False):
        request = urllib.request.Request('https://api.github.com/repos/' + REPOSITORY + path,
            data=None if data is None else json.dumps(data).encode(),
            headers={'Authorization': 'Bearer ' + self.token, 'Accept': 'application/vnd.github+json',
                     'X-GitHub-Api-Version': '2022-11-28', 'Content-Type': 'application/json'})
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if missing and error.code == 404:
                return None
            raise RuntimeError('GitHub API request failed: HTTP ' + str(error.code)) from None

    def release(self, tag):
        return self.api('/releases/tags/' + urllib.parse.quote(tag, safe=''), missing=True)

    def published_codes(self):
        # Only reached for a new version; an unchanged hourly check stops after release(tag).
        codes = []
        page = 1
        while True:
            releases = self.api(f'/releases?per_page=100&page={page}')
            for release in releases:
                if release.get('draft'):
                    continue
                tag = release['tag_name']
                if not re.fullmatch('v' + VERSION, tag):
                    continue
                assets = [a for a in release['assets'] if a['name'] == 'release-metadata.json']
                if assets:
                    expected = 'https://github.com/' + REPOSITORY + '/releases/download/' + tag + '/release-metadata.json'
                    if len(assets) != 1 or assets[0]['browser_download_url'] != expected:
                        raise ValueError('Unexpected published metadata asset')
                    # Public assets need no token; never forward the repository token through redirects.
                    with urllib.request.urlopen(expected, timeout=30) as response:
                        raw = response.read(65537)
                    if len(raw) > 65536:
                        raise ValueError('Published metadata exceeds limit')
                    metadata = json.loads(raw)
                    code = metadata.get('versionCode')
                    if metadata.get('packageName') != 'com.nanci.codexremote' or metadata.get('versionName') != tag[1:] or type(code) is not int or code <= 0:
                        raise ValueError('Invalid published version metadata')
                else:
                    # The original 0.2.7 backup predates release-metadata.json.
                    item = self.api('/contents/app/build.gradle.kts?ref=' + urllib.parse.quote(tag, safe=''))
                    version, code = source_version(base64.b64decode(item['content']).decode())
                    if 'v' + version != tag:
                        raise ValueError('Legacy release tag/version mismatch')
                codes.append(code)
            if len(releases) < 100:
                return codes
            page += 1

    def tag_commit(self, tag):
        ref = self.api('/git/ref/tags/' + urllib.parse.quote(tag, safe=''), missing=True)
        if ref is None:
            return None
        obj = ref['object']
        for _ in range(8):
            if obj['type'] == 'commit':
                return obj['sha']
            if obj['type'] != 'tag':
                break
            obj = self.api('/git/tags/' + obj['sha'])['object']
        raise ValueError('Tag does not resolve to a commit')

    def create_tag(self, tag, commit):
        self.api('/git/refs', {'ref': 'refs/tags/' + tag, 'sha': commit})


def prepare(github, version, code, commit, pushed_tag=None):
    tag = 'v' + version
    if code <= 0 or not re.fullmatch(VERSION, version) or not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Invalid source version or revision')
    if pushed_tag is not None and pushed_tag != tag:
        raise ValueError('Pushed tag does not match source version')
    existing = github.release(tag)
    if existing is not None and not existing.get('draft'):
        return {'publish': 'false', 'tag': tag, 'source_sha': commit, 'version_code': str(code), 'reason': 'Version is already published'}
    previous = github.published_codes()
    if previous and code <= max(previous):
        raise ValueError('versionCode must exceed every published release')
    tagged = github.tag_commit(tag)
    if tagged is None:
        github.create_tag(tag, commit)
        tagged = github.tag_commit(tag)
    if tagged != commit:
        raise ValueError('Existing version tag points to different source; bump the version instead')
    return {'publish': 'true', 'tag': tag, 'source_sha': commit, 'version_code': str(code), 'reason': 'New release version'}


def main():
    event = os.environ['GITHUB_EVENT_NAME']
    if event not in {'push', 'schedule', 'workflow_dispatch'}:
        raise ValueError('Unexpected workflow trigger')
    if event != 'push' and os.environ['GITHUB_REF'] != 'refs/heads/main':
        raise ValueError('Scheduled and manual checks must use main')
    version, code = source_version(Path('app/build.gradle.kts').read_text())
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    pushed = os.environ['GITHUB_REF_NAME'] if event == 'push' else None
    selection = prepare(GitHub(), version, code, commit, pushed)
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        for key, value in selection.items():
            output.write(key + '=' + value + '\n')
    print(selection['reason'] + ': ' + selection['tag'] + ' at ' + selection['source_sha'])
    summary = os.environ.get('GITHUB_STEP_SUMMARY')
    if summary:
        with open(summary, 'a') as output:
            output.write(selection['reason'] + ': **' + selection['tag'] + '** (`' + commit + '`).\n')


if __name__ == '__main__':
    main()
