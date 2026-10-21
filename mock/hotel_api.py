#!/usr/bin/env python3
"""Local hotel API for manual testing. Python 3 standard library only."""
import argparse
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Lock
from urllib.parse import urlparse

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--mode', choices=['success', 'retry', 'failure', 'bad-request', 'timeout'], default='success')
parser.add_argument('--port', type=int, default=9090)
args = parser.parse_args()
lock = Lock()
attempts = 0

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        global attempts
        path = urlparse(self.path).path
        if not path.startswith('/hotels/') or not path.endswith('/availability'):
            self.respond(404, {'error': 'Unknown endpoint'})
            return
        with lock:
            attempts += 1
            attempt = attempts
        print(f'Hotel API attempt {attempt}: mode={args.mode}', flush=True)
        if args.mode == 'timeout':
            time.sleep(3)
        if args.mode == 'bad-request':
            self.respond(400, {'error': 'Invalid search'})
        elif args.mode == 'failure' or (args.mode == 'retry' and attempt <= 2):
            self.respond(503, {'error': 'Temporarily unavailable'})
        else:
            self.respond(200, [{'roomId': 'r1', 'roomType': 'King', 'price': 120.00, 'currency': 'USD'}])

    def respond(self, status, body):
        payload = json.dumps(body).encode()
        try:
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass  # Expected when the caller times out.

if __name__ == '__main__':
    server = ThreadingHTTPServer(('127.0.0.1', args.port), Handler)
    print(f'Mock hotel API: http://localhost:{args.port}, mode={args.mode}', flush=True)
    print('Retry mode fails the first two requests after startup, then succeeds. Ctrl+C stops the server.', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
