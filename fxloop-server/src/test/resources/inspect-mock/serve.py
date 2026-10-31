#!/usr/bin/env python3
"""Dev-only mock of the viewer's /inspect + /api/* + /sse for exercising inspect.html without an app.
Usage: python3 serve.py [port]   then open http://127.0.0.1:<port>/inspect?t=x"""
import json, os, sys, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

HERE = os.path.dirname(os.path.abspath(__file__))
PAGE = os.path.join(HERE, "..", "..", "..", "main", "resources", "dev", "fxloop", "server", "inspect", "inspect.html")
FIX = {"/api/windows": "windows.json", "/api/tree": "tree.json", "/api/styles": "styles.json",
       "/api/layout": "layout.json", "/api/suggest": "suggest.json"}


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="application/json"):
        b = body if isinstance(body, bytes) else body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def do_GET(self):
        p = urlparse(self.path).path
        if p == "/inspect":
            with open(PAGE, "rb") as f:
                return self.send(200, f.read(), "text/html; charset=utf-8")
        if p in FIX:
            with open(os.path.join(HERE, FIX[p]), "rb") as f:
                return self.send(200, f.read())
        if p == "/sse":
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            try:
                self.wfile.write(b": hi\n\n"); self.wfile.flush()
                time.sleep(3)
                self.wfile.write(b'event: pick\ndata: {"h":14,"path":"Stage[0]/Scene/VBox/HBox/Button#cancel","window":0}\n\n'); self.wfile.flush()
                time.sleep(3)
                self.wfile.write(b'event: reload\ndata: {"files":["app.css"]}\n\n'); self.wfile.flush()
                while True:
                    time.sleep(15); self.wfile.write(b": ka\n\n"); self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError):
                return
        self.send(404, '{"error":{"code":"NOT_FOUND","message":"%s"}}' % p)

    def do_POST(self):
        p = urlparse(self.path).path
        if p == "/api/highlight":
            return self.send(200, '{"shown":true}')
        if p == "/api/pick":
            return self.send(200, '{"armed":true}')
        self.send(404, '{"error":{"code":"NOT_FOUND","message":"%s"}}' % p)


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8765
    ThreadingHTTPServer(("127.0.0.1", port), H).serve_forever()
