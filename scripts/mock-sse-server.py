#!/usr/bin/env python3
import json
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TEXT = (
    "这是本地 mock 服务器的 SSE 流式响应，共分多块返回。"
    "如果 Android 主线程被网络读取阻塞，StrictMode 会记录 NetworkOnMainThread；"
    "当前示例把 session 请求放在 Dispatchers.IO，因此主线程只负责渲染文本。"
)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.0"

    def do_GET(self):
        body = json.dumps({"object": "list", "data": [{"id": "mock", "object": "model"}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        self.rfile.read(length)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        chunks = [TEXT[index:index + 6] for index in range(0, len(TEXT), 6)]
        for chunk in chunks:
            payload = {
                "id": "mock-1",
                "object": "chat.completion.chunk",
                "created": int(time.time()),
                "model": "mock",
                "choices": [{"index": 0, "delta": {"content": chunk}, "finish_reason": None}],
            }
            self.wfile.write(("data: " + json.dumps(payload, ensure_ascii=False) + "\n\n").encode())
            self.wfile.flush()
            time.sleep(0.12)
        final = {
            "id": "mock-1",
            "object": "chat.completion.chunk",
            "created": int(time.time()),
            "model": "mock",
            "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}],
            "usage": {
                "prompt_tokens": 10,
                "completion_tokens": len(chunks),
                "total_tokens": 10 + len(chunks),
            },
        }
        self.wfile.write(("data: " + json.dumps(final, ensure_ascii=False) + "\n\n").encode())
        self.wfile.write(b"data: [DONE]\n\n")
        self.wfile.flush()
        print("[mock-sse] served POST", self.path, flush=True)

    def log_message(self, fmt, *args):
        print("[mock-sse] " + (fmt % args), flush=True)


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", 8765), Handler)
    print("[mock-sse] listening on http://127.0.0.1:8765", flush=True)
    server.serve_forever()
