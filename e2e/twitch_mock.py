"""Answers Twitch's /oauth2/validate for the e2e token without chat:edit (so no real chat connection is attempted) and reward code lookups."""
import json
from http.server import BaseHTTPRequestHandler, HTTPServer

class H(BaseHTTPRequestHandler):
    def do_GET(self):
        body = json.dumps({"client_id": "x", "login": "tomlurkt", "user_id": "4711", "scopes": ["chat:read", "user_read"], "expires_in": 0}).encode()
        self.send_response(200 if self.path.startswith("/oauth2/validate") else 404)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        # RewardCodeModal: one code for the Aurora Cape, "server error" for anything else (like Twitch for badges).
        req = json.loads(self.body() or b"{}")
        v = req.get("variables") or {}
        ok = self.path == "/gql" and req.get("operationName") == "RewardCodeModal" and v.get("rewardID") == "r-aurora"
        value = {"value": "AURO-RA12-CAPE", "rewardID": "r-aurora", "rewardCampaignID": v.get("rewardCampaignID"),
                 "expiresAt": "2026-10-22T06:58:59.999Z"} if ok else None
        body = {"data": {"currentUser": {"inventory": {"rewardValue": value}}}}
        if not ok:
            body["errors"] = [{"message": "server error"}]
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(data)

    def body(self):
        if self.headers.get("Transfer-Encoding", "").lower() != "chunked":
            return self.rfile.read(int(self.headers.get("Content-Length", 0)))
        data = b""
        while (size := int(self.rfile.readline().split(b";")[0], 16)):
            data += self.rfile.read(size)
            self.rfile.readline()
        self.rfile.readline()
        return data

    def log_message(self, *args):
        pass

HTTPServer(("127.0.0.1", 18099), H).serve_forever()
