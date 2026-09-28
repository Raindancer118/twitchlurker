"""Answers Twitch's /oauth2/validate for the e2e token without chat:edit, so no real chat connection is attempted."""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

class H(BaseHTTPRequestHandler):
    def do_GET(self):
        body = json.dumps({"client_id": "x", "login": "tomlurkt", "user_id": "4711", "scopes": ["chat:read", "user_read"], "expires_in": 0}).encode()
        self.send_response(200 if self.path.startswith("/oauth2/validate") else 404)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass

HTTPServer(("127.0.0.1", 18099), H).serve_forever()
