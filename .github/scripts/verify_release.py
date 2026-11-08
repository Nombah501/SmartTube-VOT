#!/usr/bin/env python3
"""Validate built assets independently of update.json, or every announced download."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.parse
import urllib.request
import zipfile

CERT = '6de965656c0c049c677981e6bfe6dda074ed0ac158c361661ed5c25d744ff7b8'
MAX_DOWNLOAD_BYTES = 256 * 1024 * 1024
ABIS = {'arm64-v8a': {'arm64-v8a'}, 'armeabi-v7a': {'armeabi-v7a'},
        'x86': {'x86'}, 'universal': {'arm64-v8a', 'armeabi-v7a'}}
NAMES = {abi: f'SmartTube_stable_{abi}.apk' for abi in ABIS}
KEYS = {('downloadUrlList' if abi == 'universal' else f'downloadUrlList_{abi}'): abi
        for abi in ABIS}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def source_version(path):
    text = Path(path).read_text()
    names = re.findall(r'^\s*versionName\s+"([0-9]+(?:\.[0-9]+)+)"\s*$', text, re.M)
    codes = re.findall(r'^\s*versionCode\s+(\d+)\s*$', text, re.M)
    require(len(names) == len(codes) == 1, 'ambiguous source version')
    return names[0], int(codes[0])


def tool_output(tool, *args):
    return subprocess.run([str(tool), *map(str, args)], check=True, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout


def verify_apk(path, abi, version, code, tools):
    badging = tool_output(tools / 'aapt', 'dump', 'badging', path)
    packages = re.findall(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    require(packages == [('org.smarttube.stable', str(code), version)],
            f'{path}: wrong package/version: {packages}')
    signing = tool_output(tools / 'apksigner', 'verify', '--verbose', '--print-certs', path)
    certs = re.findall(r'^Signer #\d+ certificate SHA-256 digest: ([0-9a-fA-F]+)\s*$', signing, re.M)
    require([cert.lower() for cert in certs] == [CERT], f'{path}: wrong or missing signer')
    with zipfile.ZipFile(path) as archive:
        native = {name.split('/')[1] for name in archive.namelist()
                  if re.fullmatch(r'lib/[^/]+/[^/]+\.so', name)}
    require(native == ABIS[abi], f'{path}: wrong ABI contents: {sorted(native)}')
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def artifacts(directory, version, code, tools):
    directory = Path(directory)
    require({p.name for p in directory.glob('*.apk')} == set(NAMES.values()),
            'expected exactly four named stable APKs')
    return {name: verify_apk(directory / name, abi, version, code, tools)
            for abi, name in sorted(NAMES.items())}


def validate_url(url, initial=False):
    require(isinstance(url, str), 'URL must be a string')
    parsed = urllib.parse.urlsplit(url)
    host = parsed.hostname or ''
    allowed = host == 'github.com' if initial else (
        host == 'github.com' or host.endswith('.githubusercontent.com'))
    require(parsed.scheme == 'https' and allowed and parsed.port in (None, 443)
            and parsed.username is None and parsed.password is None,
            f'disallowed download URL: {url!r}')


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        validate_url(newurl)
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def download(url, path, manifest_source=False):
    # A public raw manifest is optional; APK starting URLs must be github.com.
    validate_url(url, initial=not manifest_source)
    opener = urllib.request.build_opener(SafeRedirect())
    with opener.open(url, timeout=120) as response:
        validate_url(response.url)
        length = response.headers.get('Content-Length')
        require(length is None or int(length) <= MAX_DOWNLOAD_BYTES, 'download too large')
        total = 0
        with Path(path).open('wb') as stream:
            while True:
                chunk = response.read(min(1024 * 1024, MAX_DOWNLOAD_BYTES - total + 1))
                if not chunk:
                    break
                total += len(chunk)
                require(total <= MAX_DOWNLOAD_BYTES, 'download too large')
                stream.write(chunk)


def manifest(data, directory, tools):
    versions = [(tuple(map(int, name.split('.'))), name, entry)
                for name, entry in data.items() if re.fullmatch(r'\d+(?:\.\d+)+', name)]
    require(bool(versions), 'no announced versions')
    _, version, entry = max(versions)
    code = entry['versionCode']
    require(type(code) is int and code > 0, 'invalid announced versionCode')
    require(all(type(item[2]['versionCode']) is int and item[2]['versionCode'] <= code
                for item in versions), 'highest name and code disagree')
    package = data['package']
    require({key for key in package if key.startswith('downloadUrlList')} == set(KEYS),
            'missing or unsupported download variants')
    records = []
    for key, abi in sorted(KEYS.items()):
        urls = package[key]
        require(isinstance(urls, list) and bool(urls), f'{key}: no URLs')
        for index, url in enumerate(urls):
            path = Path(directory) / f'{abi}-{index}.apk'
            download(url, path)
            digest = verify_apk(path, abi, version, code, tools)
            records.append({'url': url, 'abi': abi, 'sha256': digest,
                            'versionName': version, 'versionCode': code})
    return records


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['artifacts', 'manifest'])
    parser.add_argument('--directory', type=Path)
    parser.add_argument('--gradle', default='smarttubetv/build.gradle')
    parser.add_argument('--tag')
    parser.add_argument('--manifest', default='update.json')
    parser.add_argument('--build-tools', type=Path,
                        default=Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', ''))) / 'build-tools/30.0.3')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.mode == 'artifacts':
        require(args.directory is not None and args.tag is not None, 'artifacts requires --directory and --tag')
        version, code = source_version(args.gradle)
        require(args.tag == f'v{version}', 'tag must match source versionName exactly')
        hashes = artifacts(args.directory, version, code, args.build_tools)
        result = ''.join(f'{digest}  {name}\n' for name, digest in sorted(hashes.items()))
    else:
        with tempfile.TemporaryDirectory() as temporary:
            if args.manifest.startswith('https://'):
                source = Path(temporary) / 'update.json'
                download(args.manifest, source, manifest_source=True)
            else:
                source = Path(args.manifest)
            records = manifest(json.loads(source.read_text()), temporary, args.build_tools)
            result = json.dumps(records, indent=2, sort_keys=True) + '\n'
    args.output.write_text(result)


if __name__ == '__main__':
    main()
