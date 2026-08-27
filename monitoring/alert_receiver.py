"""Small Alertmanager webhook receiver with optional forwarding to an operations gateway."""

from __future__ import annotations

import json
import os
import sys
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY_BYTES = 1_048_576
STATE_LOCK = threading.Lock()
STATE = {"received": 0, "forwarded": 0, "lastStatus": None}


class AlertReceiverHandler(BaseHTTPRequestHandler):
    server_version = "FriendFeedAlertReceiver/1.0"

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self._json(200, {"status": "UP"})
            return
        if self.path == "/status":
            with STATE_LOCK:
                snapshot = dict(STATE)
            self._json(200, snapshot)
            return
        self._json(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/alerts":
            self._json(404, {"error": "not found"})
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            self._json(400, {"error": "invalid content length"})
            return
        if length <= 0 or length > MAX_BODY_BYTES:
            self._json(413, {"error": "invalid alert payload size"})
            return

        body = self.rfile.read(length)
        try:
            payload = json.loads(body)
        except json.JSONDecodeError:
            self._json(400, {"error": "invalid json"})
            return

        alert_count = len(payload.get("alerts", [])) if isinstance(payload, dict) else 0
        status = payload.get("status") if isinstance(payload, dict) else None
        with STATE_LOCK:
            STATE["received"] += 1
            STATE["lastStatus"] = status
        print(json.dumps({
            "event": "alertmanager_webhook_received",
            "status": status,
            "alertCount": alert_count,
            "receiver": payload.get("receiver") if isinstance(payload, dict) else None,
        }, ensure_ascii=False), flush=True)

        forward_url = os.getenv("ALERT_FORWARD_URL", "").strip()
        if forward_url:
            try:
                headers = {"Content-Type": "application/json"}
                token = os.getenv("ALERT_FORWARD_BEARER_TOKEN", "").strip()
                if token:
                    headers["Authorization"] = f"Bearer {token}"
                request = urllib.request.Request(forward_url, data=body, headers=headers, method="POST")
                with urllib.request.urlopen(request, timeout=10) as response:
                    if response.status < 200 or response.status >= 300:
                        raise RuntimeError(f"forward returned HTTP {response.status}")
                with STATE_LOCK:
                    STATE["forwarded"] += 1
            except (OSError, RuntimeError, urllib.error.URLError) as error:
                print(json.dumps({
                    "event": "alertmanager_webhook_forward_failed",
                    "error": str(error),
                }), file=sys.stderr, flush=True)
                self._json(502, {"error": "alert forwarding failed"})
                return

        self._json(202, {"accepted": True})

    def log_message(self, format_string: str, *args: object) -> None:
        if args and str(args[0]).startswith("GET /health "):
            return
        print(json.dumps({"event": "http_access", "message": format_string % args}), flush=True)

    def _json(self, status: int, payload: dict[str, object]) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("0.0.0.0", 8090), AlertReceiverHandler)
    print(json.dumps({"event": "alert_receiver_started", "port": 8090}), flush=True)
    server.serve_forever()
