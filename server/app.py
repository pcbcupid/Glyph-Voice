import argparse
import asyncio
import hmac
import ipaddress
import json
import os
from pathlib import Path
import re
import secrets
import time
from urllib.parse import urlsplit

from aiohttp import web, ClientSession, WSMsgType, TraceConfig
from .inference import Inference
from .models import ModelFolders


def glyph_address(value: str, allowed: set[str]) -> str:
    match = re.fullmatch(r"(\d{1,3}(?:\.\d{1,3}){3})(?::8080)?", value)
    if not match or match[1] not in allowed:
        raise ValueError("Board address is not permitted. Add its IP with --glyph-host on the server")
    ip = ipaddress.IPv4Address(match[1])
    if not any(ip in ipaddress.ip_network(net) for net in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16")):
        raise ValueError("Only local Glyph addresses on port 8080 are supported")
    return f"ws://{ip}:8080/audio"


def create_app(models_root, token, origins, dist=None, glyph_hosts=(), inference=None):
    if not re.fullmatch(r"[A-Za-z0-9_-]{24,256}", token):
        raise ValueError("Use a random server token of at least 24 characters")
    folders = ModelFolders(Path(models_root))
    engine = inference or Inference()
    origins = set(origins)
    if not origins:
        raise ValueError("At least one exact browser origin is required")
    for origin in origins:
        url = urlsplit(origin)
        if url.scheme not in ("http", "https") or not url.netloc or url.path or url.query or url.fragment or url.username:
            raise ValueError("Origins must be exact http(s)://host:port values without paths")
    hosts = {urlsplit(origin).netloc.lower() for origin in origins}
    allowed_glyphs = set(glyph_hosts)
    for address in allowed_glyphs:
        glyph_address(address, allowed_glyphs)
    active_bridges = set()

    def authorized(value):
        return isinstance(value, str) and hmac.compare_digest(value.encode(), token.encode())

    @web.middleware
    async def guard(request, handler):
        origin = request.headers.get("Origin")
        if request.host.lower() not in hosts or (origin is not None and origin not in origins):
            raise web.HTTPForbidden(text="Origin/Host is not allowed")
        if request.method == "OPTIONS":
            response = web.Response(status=204)
        else:
            if request.path.startswith("/api/") and request.path != "/api/glyph":
                if not authorized(request.headers.get("Authorization", "").removeprefix("Bearer ")):
                    response = web.json_response({"error": "Enter the local server access token"}, status=401)
                    if origin:
                        response.headers["Access-Control-Allow-Origin"] = origin
                        response.headers["Vary"] = "Origin"
                    response.headers["Cache-Control"] = "no-store"
                    return response
            try:
                response = await handler(request)
            except (ValueError, FileNotFoundError, NotADirectoryError, PermissionError):
                response = web.json_response({"error": "Invalid request or model folder. Check the selected folder, model and session."}, status=400)
            except RuntimeError as error:
                response = web.json_response({"error": str(error)}, status=503)
            except web.HTTPException as error:
                response = web.json_response({"error": error.reason}, status=error.status)
        if origin:
            response.headers["Access-Control-Allow-Origin"] = origin
            response.headers["Vary"] = "Origin"
            response.headers["Access-Control-Allow-Methods"] = "GET, POST, DELETE, OPTIONS"
            response.headers["Access-Control-Allow-Headers"] = "Authorization, Content-Type, X-Audio-Sequence"
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        return response

    app = web.Application(middlewares=[guard], client_max_size=65536)

    async def locked(operation):
        # No unbounded per-client work queue; the browser serializes its audio.
        if engine.lock.locked():
            raise web.HTTPConflict(text="Local worker is busy; wait for the current operation")
        async with engine.lock:
            return web.json_response(await operation())

    async def info(request):
        return web.json_response({**engine.info(), "glyphBridge": bool(allowed_glyphs)})

    async def browse(request):
        return web.json_response(folders.browse(request.query.get("path", ".")))

    async def load(request):
        data = await request.json()
        if not isinstance(data, dict) or not isinstance(data.get("folder"), str):
            raise ValueError()
        files = folders.files(data["folder"])
        return await locked(lambda: engine.load(files, folders.resolve(data["folder"]).name))

    async def open_stream(request):
        data = await request.json()
        if not isinstance(data, dict):
            raise ValueError()
        return await locked(lambda: engine.open(data.get("modelId"), data.get("sampleRate")))

    async def audio(request):
        pcm = await request.read()
        seq = int(request.headers.get("X-Audio-Sequence", "-1"))
        return await locked(lambda: engine.audio(request.match_info["id"], seq, pcm))

    async def finish(request):
        data = await request.json()
        if not isinstance(data, dict):
            raise ValueError()
        return await locked(lambda: engine.finish(request.match_info["id"], data.get("sequence")))

    async def cancel(request):
        return await locked(lambda: engine.cancel(request.match_info["id"]))

    async def bridge(request):
        # Tokens are in the first message, never URL/query/access logs.
        if request.headers.get("Origin") not in origins:
            raise web.HTTPForbidden()
        ws = web.WebSocketResponse(max_msg_size=16384, heartbeat=20, compress=False)
        await ws.prepare(request)
        owned = False
        try:
            first = await asyncio.wait_for(ws.receive(), 5)
            if first.type != WSMsgType.TEXT or len(first.data) > 2048:
                raise ValueError()
            auth = json.loads(first.data)
            if not isinstance(auth, dict) or not authorized(auth.get("token")):
                await ws.close(code=1008, message=b"Unauthorized")
                return ws
            url = glyph_address(auth.get("address", ""), allowed_glyphs)
            if active_bridges:
                raise ValueError("Another browser already owns the Glyph bridge")
            active_bridges.add(ws)
            owned = True
            async def reject_redirect(session, context, params):
                raise ValueError("Glyph redirects are not allowed")
            trace = TraceConfig()
            trace.on_request_redirect.append(reject_redirect)
            async with ClientSession(trust_env=False, trace_configs=[trace]) as client:
                async with asyncio.timeout(5):
                    board = await client.ws_connect(url, max_msg_size=16384, heartbeat=15, compress=0)
                async with board:
                    await ws.send_json({"proxy": "ready"})

                    async def downstream():
                        async for packet in board:
                            if packet.type == WSMsgType.BINARY:
                                await ws.send_bytes(packet.data)
                            elif packet.type == WSMsgType.TEXT and len(packet.data) <= 2048:
                                await ws.send_str(packet.data)
                            else:
                                break

                    async def upstream():
                        async for packet in ws:
                            if packet.type != WSMsgType.TEXT or not re.fullmatch(r"STOP [A-Za-z0-9_-]{1,64}", packet.data):
                                break
                            await board.send_str(packet.data)

                    tasks = [asyncio.create_task(downstream()), asyncio.create_task(upstream())]
                    try:
                        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
                    finally:
                        for task in tasks:
                            task.cancel()
                        await asyncio.gather(*tasks, return_exceptions=True)
        except Exception:
            if not ws.closed:
                await ws.send_json({"proxy": "error", "message": "Glyph bridge unavailable. Check --glyph-host, board power and the server's LAN connection."})
        finally:
            if owned:
                active_bridges.discard(ws)
            await ws.close()
        return ws

    app.router.add_get("/api/local", info)
    app.router.add_get("/api/folders", browse)
    app.router.add_post("/api/model", load)
    app.router.add_post("/api/streams", open_stream)
    app.router.add_post("/api/streams/{id}/audio", audio)
    app.router.add_post("/api/streams/{id}/finish", finish)
    app.router.add_delete("/api/streams/{id}", cancel)
    app.router.add_get("/api/glyph", bridge)
    async def options(request):
        return web.Response(status=204)
    app.router.add_route("OPTIONS", "/api/{tail:.*}", options)

    if dist:
        static = Path(dist).resolve(strict=True)

        async def index(request):
            return web.FileResponse(static / "index.html")
        app.router.add_get("/", index)
        app.router.add_static("/", static, show_index=False, follow_symlinks=False)

    async def lifecycle(application):
        async def reap():
            while True:
                await asyncio.sleep(5)
                if engine.stream_id and not engine.lock.locked() and time.monotonic() - engine.touched > 60:
                    async with engine.lock:
                        try:
                            await engine.cancel(engine.stream_id)
                        except RuntimeError:
                            pass
        task = asyncio.create_task(reap())
        yield
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        for ws in list(active_bridges):
            await ws.close(code=1001)
        engine.stop()
    app.cleanup_ctx.append(lifecycle)
    return app


def main():
    parser = argparse.ArgumentParser(description="Glyph Voice local-model server (single trusted user)")
    parser.add_argument("--models-dir", type=Path, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--origin", action="append", help="Exact permitted browser origin; repeat for development/LAN")
    parser.add_argument("--glyph-host", action="append", default=[], help="Allowed private Glyph IPv4 for optional same-origin WebSocket bridge")
    parser.add_argument("--threads", type=int, default=min(4, os.cpu_count() or 1))
    parser.add_argument("--dist", type=Path, default=Path(__file__).resolve().parents[1] / "web/dist")
    args = parser.parse_args()
    if not 1 <= args.threads <= 16:
        parser.error("--threads must be between 1 and 16")
    if args.host not in ("127.0.0.1", "localhost", "::1") and not args.origin:
        parser.error("LAN binding requires explicit --origin http(s)://your-host:port; no wildcard origins")
    token = os.environ.get("GLYPH_LOCAL_TOKEN") or secrets.token_urlsafe(32)
    origins = args.origin or [f"http://127.0.0.1:{args.port}", f"http://localhost:{args.port}"]
    app = create_app(args.models_dir, token, origins, args.dist, args.glyph_host, Inference(args.threads))
    print("Inference runs on THIS computer. No automatic model downloads or cloud STT.", flush=True)
    print("Private local server access token (enter in Speech recognition): " + token, flush=True)
    print("Allowed browser origins: " + ", ".join(origins), flush=True)
    web.run_app(app, host=args.host, port=args.port, access_log=None)


if __name__ == "__main__":
    main()
