"""Loopback-only preview of one HTML artifact and allowlisted local page images."""
import argparse
import json
import re
from tempfile import TemporaryDirectory
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

IMAGE_TYPES = {'.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg',
               '.webp': 'image/webp', '.gif': 'image/gif'}


def load_assets(root):
    manifest = root / 'asset-manifest.json'
    if not manifest.exists():
        return {}
    entries = json.loads(manifest.read_text('utf-8'))
    assets = {}
    for route, filename in entries.items():
        if not route.startswith('/assets/') or any(part in {'', '.', '..'} for part in route[1:].split('/')):
            raise ValueError('Invalid asset route')
        if not re.fullmatch(r'/[A-Za-z0-9_./-]+', route):
            raise ValueError('Invalid asset route')
        path = Path(filename)
        if not path.is_absolute():
            raise ValueError('Asset path must be absolute')
        path = path.resolve(strict=True)
        if not path.is_relative_to(root) or not path.is_file() or path.suffix.lower() not in IMAGE_TYPES:
            raise ValueError('Asset must be an image inside the prototype directory')
        assets[route] = (path, IMAGE_TYPES[path.suffix.lower()])
    return assets


def resolve_request(route, page, assets):
    if route in {'/', '/index.html'}:
        return page, 'text/html; charset=utf-8'
    return assets.get(route)


def content_security_policy(frame_origin=None):
    if frame_origin not in {None, 'http://127.0.0.1:5192'}:
        raise ValueError('Only the fixed loopback integration prototype may embed this page')
    return ("default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; "
            "img-src 'self' data:; connect-src 'none'; base-uri 'none'; form-action 'none'; "
            "frame-ancestors " + (frame_origin or "'none'"))


def self_check():
    assert content_security_policy().endswith("frame-ancestors 'none'")
    assert content_security_policy('http://127.0.0.1:5192').endswith('frame-ancestors http://127.0.0.1:5192')
    try:
        content_security_policy('https://example.com')
    except ValueError:
        pass
    else:
        raise AssertionError('An unrelated page may not embed the local review')
    page = Path('index.html')
    image = (Path('page.png'), 'image/png')
    assets = {'/assets/page.png': image}
    assert resolve_request('/', page, assets) == (page, 'text/html; charset=utf-8')
    assert resolve_request('/assets/page.png', page, assets) == image
    for denied in ('/assets/', '/assets/../page.png', '/assets/%2e%2e/page.png',
                   '/assets/page.png?other=1', '/index.html?path=private', '//assets/page.png',
                   '/assets\\page.png', '/private.json'):
        assert resolve_request(denied, page, assets) is None
    with TemporaryDirectory(prefix='prototype-routes-') as directory:
        root = Path(directory).resolve() / 'preview'
        root.mkdir()
        outside = root.parent / 'outside.png'
        outside.write_bytes(b'private')
        image = root / 'page.png'
        image.write_bytes(b'prototype')
        manifest = root / 'asset-manifest.json'
        manifest.write_text(json.dumps({'/assets/page.png': str(image)}), 'utf-8')
        assert load_assets(root)['/assets/page.png'] == (image, 'image/png')
        for route, filename in [('/assets/../page.png', image),
                                ('/assets/page.png?copy=1', image),
                                ('/assets/page.png', outside),
                                ('/assets/source.json', manifest)]:
            manifest.write_text(json.dumps({route: str(filename)}), 'utf-8')
            try:
                load_assets(root)
            except (ValueError, FileNotFoundError):
                pass
            else:
                raise AssertionError('Unsafe manifest accepted')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--page', type=Path, default=Path(__file__).with_name('index.html'))
    parser.add_argument('--port', type=int, default=5191)
    parser.add_argument('--frame-origin', choices=['http://127.0.0.1:5192'])
    args = parser.parse_args()
    page = args.page.resolve(strict=True)
    assert page.is_file() and page.suffix == '.html'
    assets = load_assets(page.parent)
    security_policy = content_security_policy(args.frame_origin)
    self_check()


    class Preview(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.headers.get('Host') not in {f'127.0.0.1:{args.port}', f'localhost:{args.port}'}:
                self.send_error(403)
                return
            target = resolve_request(self.path, page, assets)
            if target is None:
                self.send_error(404)
                return
            path, content_type = target
            content = path.read_bytes()
            self.send_response(200)
            self.send_header('Content-Type', content_type)
            self.send_header('Content-Length', str(len(content)))
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('Content-Security-Policy', security_policy)
            self.end_headers()
            self.wfile.write(content)

        def log_message(self, *_):
            pass


    print(f'Prototype page: http://127.0.0.1:{args.port}', flush=True)
    ThreadingHTTPServer(('127.0.0.1', args.port), Preview).serve_forever()


if __name__ == '__main__':
    main()
