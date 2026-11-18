#!/usr/bin/env python3
"""download a fastlogin compile-only JAR, publishing only verified bytes."""

import argparse
import base64
import binascii
from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.client
from pathlib import Path
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request


class HTTPSOnlyRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        if urllib.parse.urlsplit(newurl).scheme != 'https':
            raise ValueError('Only HTTPS redirects are allowed')
        return super().redirect_request(req, fp, code, msg, headers, newurl)


 #  !!!!!!! TEMPORARY SHIT  !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
def range_chunks(url):
    """Avoid stalled long CDN responses with verified 8 KiB requests."""
    headers = {'User-Agent': 'pierdol-auth-paper-build/1.0', 'Accept-Encoding': 'identity'}
    opener = urllib.request.build_opener(HTTPSOnlyRedirect())
    request = urllib.request.Request(url, headers=headers, method='HEAD')
    with opener.open(request, timeout=15) as response:
        size = int(response.headers.get('Content-Length', '0'))
        download_url = response.geturl()
    if not 0 < size <= 20 * 1024 * 1024:
        raise ValueError('Missing or invalid CDN content length')

    def read_range(start):
        end = min(start + 8191, size - 1)
        for attempt in range(3):
            try:
                request = urllib.request.Request(
                    download_url, headers={**headers, 'Range': f'bytes={start}-{end}'})
                opener = urllib.request.build_opener(HTTPSOnlyRedirect())
                with opener.open(request, timeout=10) as response:
                    if response.status != 206 or response.headers.get('Content-Range') != f'bytes {start}-{end}/{size}':
                        raise ValueError('CDN returned an unexpected byte range')
                    chunk = response.read(end - start + 2)
                if len(chunk) != end - start + 1:
                    raise ValueError('CDN returned an incomplete byte range')
                return chunk
            except (urllib.error.URLError, OSError, http.client.HTTPException):
                if attempt == 2:
                    raise
                time.sleep(1)

    pool = ThreadPoolExecutor(max_workers=4)
    try:
        yield from pool.map(read_range, range(0, size, 8192))
    finally:
        pool.shutdown(wait=True, cancel_futures=True)

 #  !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!

def fetch(url, sri_sha256, output):
    """Stream to a sibling regular file and atomically publish after verification."""
    if not sri_sha256.startswith('sha256-'):
        raise ValueError('Expected canonical SHA-256 SRI')
    try:
        digest = base64.b64decode(sri_sha256[7:], validate=True)
    except (ValueError, binascii.Error):
        raise ValueError('Expected canonical SHA-256 SRI') from None
    if len(digest) != 32 or 'sha256-' + base64.b64encode(digest).decode('ascii') != sri_sha256:
        raise ValueError('Expected canonical SHA-256 SRI')
    output = Path(output)
    if not output.is_absolute():
        raise ValueError('Output path must be absolute')
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme != 'https' or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError('Expected an HTTPS URL without credentials')

    temporary = None
    try:
        with tempfile.NamedTemporaryFile(
            mode='wb', dir=output.parent, prefix='.' + output.name + '.', suffix='.tmp', delete=False
        ) as target:
            temporary = Path(target.name)
            hasher = hashlib.sha256()

        # !!!!!!!!!!!!!!!!
            # opener = urllib.request.build_opener(HTTPSOnlyRedirect())
            # request = urllib.request.Request(url, headers={'User-Agent': 'pierdol-auth-paper-build/1.0'})
            # with opener.open(request, timeout=60) as response:
            #     while chunk := response.read(1024 * 1024):
            #         target.write(chunk)
            #         hasher.update(chunk)

            for chunk in range_chunks(url):
                target.write(chunk)
                hasher.update(chunk)

        # !!!!!!!!!!!!!!!!

        actual_sri = 'sha256-' + base64.b64encode(hasher.digest()).decode('ascii')
        if actual_sri != sri_sha256:
            raise ValueError('SHA-256 verification failed')
        temporary.replace(output)
        return actual_sri
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--sri-sha256', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    try:
        sri = fetch(args.url, args.sri_sha256, args.output)
    except Exception:
        # transport exceptions can contain URLs, credentials, or response details.
        print('FastLogin download failed: check HTTPS access, SHA-256 SRI, and absolute output path.',
              file=sys.stderr)
        return 1
    print('Verified FastLogin JAR: ' + sri)
    return 0


if __name__ == '__main__':
    sys.exit(main())
