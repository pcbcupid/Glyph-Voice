import asyncio
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from aiohttp.test_utils import TestClient, TestServer
from aiohttp import web
from .app import create_app, glyph_address
from .inference import Inference
from .models import ModelFolders

TOKEN = "test-token-not-real-1234567890"
ORIGIN = "http://localhost:8765"


class ModelTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.models = ModelFolders(self.root)

    def test_browse_and_validate(self):
        child = self.root / "parakeet"
        child.mkdir()
        self.assertFalse(self.models.browse("parakeet")["compatibleFiles"])
        for name in ("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"):
            (child / name).write_bytes(b"fixture")
        self.assertTrue(self.models.browse("parakeet")["compatibleFiles"])
        self.assertEqual(self.models.browse(".")["folders"], [{"name": "parakeet", "path": "parakeet"}])
        self.assertIsNone(self.models.browse(".")["parent"])

    def test_traversal_and_symlink_escape(self):
        for name in ("..", "../elsewhere", str(self.root), "a/../../", "a\\b", "\x00"):
            with self.assertRaises((ValueError, OSError)):
                self.models.resolve(name)
        (self.root / "escape").symlink_to(self.root.parent, target_is_directory=True)
        with self.assertRaises(ValueError):
            self.models.resolve("escape")
        self.assertEqual(self.models.browse(".")["folders"], [])

    def test_bridge_requires_explicit_private_board_and_fixed_port(self):
        self.assertEqual(glyph_address("192.168.4.2:8080", {"192.168.4.2"}), "ws://192.168.4.2:8080/audio")
        for value in ("192.168.4.3", "192.168.4.2:80", "localhost", "127.0.0.1", "8.8.8.8", "192.168.4.2/path", "192.168.04.2"):
            with self.assertRaises(ValueError):
                glyph_address(value, {"192.168.4.2", "127.0.0.1", "8.8.8.8", "192.168.04.2"})


class FakeInference(Inference):
    async def _command(self, op, value=None):
        return {"ok": True, "text": "Local words" if op in ("audio", "finish") else ""}


class ApiTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.engine = FakeInference()
        self.engine.model, self.engine.model_id = "fixture", "a" * 32
        self.app = create_app(self.temp.name, TOKEN, [ORIGIN], inference=self.engine)
        self.client = TestClient(TestServer(self.app))
        await self.client.start_server()
        self.headers = {"Host": "localhost:8765", "Origin": ORIGIN, "Authorization": "Bearer " + TOKEN}

    async def asyncTearDown(self):
        await self.client.close()
        self.temp.cleanup()

    async def test_auth_host_origin_guards(self):
        for change, expected in (({"Authorization": ""}, 401), ({"Origin": "https://evil.example"}, 403), ({"Host": "evil.example"}, 403)):
            result = await self.client.get("/api/local", headers={**self.headers, **change})
            self.assertEqual(result.status, expected)
        result = await self.client.options("/api/local", headers={"Host": "localhost:8765", "Origin": ORIGIN})
        self.assertEqual(result.headers["Access-Control-Allow-Origin"], ORIGIN)
        self.assertNotIn("Access-Control-Allow-Credentials", result.headers)

    async def test_roundtrip_sequence_validation_and_finish(self):
        response = await self.client.post("/api/streams", json={"sampleRate": 16000, "modelId": "a" * 32}, headers=self.headers)
        stream = (await response.json())["id"]
        again = await self.client.post("/api/streams", json={"sampleRate": 16000, "modelId": "a" * 32}, headers=self.headers)
        self.assertEqual(again.status, 400)
        path = f"/api/streams/{stream}"
        for seq, expected in ((1, 400), (0, 200), (0, 400)):
            result = await self.client.post(path + "/audio", data=b"\0\0" * 3200, headers={**self.headers, "X-Audio-Sequence": str(seq)})
            self.assertEqual(result.status, expected)
        result = await self.client.post(path + "/finish", json={"sequence": 1}, headers=self.headers)
        self.assertEqual((await result.json())["text"], "Local words")
        self.assertIsNone(self.engine.stream_id)

    async def test_wrong_model_invalid_rate_oversize_and_malformed_json(self):
        for body in ({"modelId": "wrong", "sampleRate": 16000}, {"modelId": "a" * 32, "sampleRate": True}, []):
            result = await self.client.post("/api/streams", json=body, headers=self.headers)
            self.assertEqual(result.status, 400)
        result = await self.client.post("/api/model", data="not json", headers=self.headers)
        self.assertEqual(result.status, 400)
        result = await self.client.post("/api/streams/id/audio", data=b"x" * 65537, headers=self.headers)
        self.assertEqual(result.status, 413)

    async def test_invalid_native_model_does_not_crash_http_server(self):
        for name in ("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"):
            (Path(self.temp.name) / name).write_bytes(b"deliberately invalid test model")
        response = await self.client.post("/api/model", json={"folder": "."}, headers=self.headers)
        self.assertEqual(response.status, 503)
        self.assertIsNone(self.engine.model_id)
        self.assertIsNone(self.engine.process)
        self.assertEqual((await self.client.get("/api/local", headers=self.headers)).status, 200)

    async def test_busy_does_not_enqueue_and_cancel_releases(self):
        async with self.engine.lock:
            result = await self.client.post("/api/streams", json={"sampleRate": 16000, "modelId": "a" * 32}, headers=self.headers)
            self.assertEqual(result.status, 409)
        opened = await self.engine.open("a" * 32, 16000)
        result = await self.client.delete("/api/streams/" + opened["id"], headers=self.headers)
        self.assertEqual(result.status, 200)
        self.assertIsNone(self.engine.stream_id)

    async def test_folder_escape_and_no_external_network_calls(self):
        with patch("server.app.ClientSession", side_effect=AssertionError("No network during folder/model status operations")):
            for path, expected in ((".", 200), ("..", 400), ("/etc", 400)):
                result = await self.client.get("/api/folders", params={"path": path}, headers=self.headers)
                self.assertEqual(result.status, expected)
            self.assertEqual((await self.client.get("/api/local", headers=self.headers)).status, 200)

    async def test_websocket_rejects_bad_token_before_board_network(self):
        with patch("server.app.ClientSession", side_effect=AssertionError("Must not contact a board")):
            async with self.client.ws_connect("/api/glyph", headers=self.headers) as ws:
                await ws.send_json({"token": "wrong", "address": "192.168.4.2"})
                await ws.receive()
                self.assertEqual(ws.close_code, 1008)

    async def test_real_bridge_forwards_audio_stop_and_closes_board(self):
        closed = asyncio.Event()
        async def board(request):
            ws = web.WebSocketResponse()
            await ws.prepare(request)
            await ws.send_str('{"type":"start","id":"r1"}')
            await ws.send_bytes(b"\0\0" * 320)
            command = await ws.receive_str()
            self.assertEqual(command, "STOP r1")
            await ws.send_str('{"type":"end","id":"r1","bytes":640}')
            async for unused in ws:
                pass
            closed.set()
            return ws
        peer = web.Application()
        peer.router.add_get("/audio", board)
        async with TestServer(peer) as server:
            with patch("server.app.glyph_address", return_value=str(server.make_url("/audio")).replace("http:", "ws:")):
                async with self.client.ws_connect("/api/glyph", headers=self.headers) as ws:
                    await ws.send_json({"token": TOKEN, "address": "192.168.4.2"})
                    self.assertEqual((await ws.receive_json())["proxy"], "ready")
                    self.assertEqual((await ws.receive_json())["type"], "start")
                    self.assertEqual(len(await ws.receive_bytes()), 640)
                    await ws.send_str("STOP r1")
                    self.assertEqual((await ws.receive_json())["type"], "end")
                await asyncio.wait_for(closed.wait(), 3)

    async def test_bridge_does_not_follow_board_redirect(self):
        reached = []
        async def redirect(request):
            raise web.HTTPFound("/forbidden")
        async def forbidden(request):
            reached.append(True)
            return web.Response()
        peer = web.Application()
        peer.router.add_get("/audio", redirect)
        peer.router.add_get("/forbidden", forbidden)
        async with TestServer(peer) as server:
            with patch("server.app.glyph_address", return_value=str(server.make_url("/audio")).replace("http:", "ws:")):
                async with self.client.ws_connect("/api/glyph", headers=self.headers) as ws:
                    await ws.send_json({"token": TOKEN, "address": "192.168.4.2"})
                    self.assertEqual((await ws.receive_json())["proxy"], "error")
                self.assertEqual(reached, [])


@unittest.skipUnless(os.environ.get("GLYPH_TEST_MODEL") and os.environ.get("GLYPH_TEST_WAV"), "Set local model and WAV fixture paths for real inference")
class RealInferenceTest(unittest.IsolatedAsyncioTestCase):
    @unittest.skipUnless(os.environ.get("GLYPH_TEST_HTTP"), "Set GLYPH_TEST_HTTP=1 for loopback HTTP integration")
    async def test_real_model_through_authenticated_http(self):
        import wave
        root = Path(os.environ["GLYPH_TEST_MODEL"])
        app = create_app(root, TOKEN, [ORIGIN])
        async with TestClient(TestServer(app)) as client:
            headers = {"Host": "localhost:8765", "Origin": ORIGIN, "Authorization": "Bearer " + TOKEN}
            response = await client.post("/api/model", json={"folder": "."}, headers=headers)
            self.assertEqual(response.status, 200)
            model_id = (await response.json())["modelId"]
            with wave.open(os.environ["GLYPH_TEST_WAV"], "rb") as wav:
                response = await client.post("/api/streams", json={"modelId": model_id, "sampleRate": wav.getframerate()}, headers=headers)
                stream_id = (await response.json())["id"]
                seq, partial = 0, ""
                while pcm := wav.readframes(3200):
                    response = await client.post(f"/api/streams/{stream_id}/audio", data=pcm, headers={**headers, "X-Audio-Sequence": str(seq)})
                    self.assertEqual(response.status, 200)
                    partial = (await response.json())["text"]
                    seq += 1
            self.assertTrue(partial)
            response = await client.post(f"/api/streams/{stream_id}/finish", json={"sequence": seq}, headers=headers)
            self.assertTrue((await response.json())["text"])

    async def test_streaming_model_and_silence_without_network(self):
        import wave
        model = Path(os.environ["GLYPH_TEST_MODEL"])
        engine = Inference(4)
        try:
            info = await engine.load(ModelFolders(model).files("."), model.name)
            with wave.open(os.environ["GLYPH_TEST_WAV"], "rb") as wav:
                self.assertEqual((wav.getnchannels(), wav.getsampwidth()), (1, 2))
                opened = await engine.open(info["modelId"], wav.getframerate())
                sequence, partials = 0, []
                while pcm := wav.readframes(3200):
                    result = await engine.audio(opened["id"], sequence, pcm)
                    partials.append(result["text"])
                    sequence += 1
            result = await engine.finish(opened["id"], sequence)
            self.assertTrue(result["text"].strip())
            self.assertTrue(any(partials), "Expected partials before finish")
            print("Real local inference:", result["text"], flush=True)
            opened = await engine.open(info["modelId"], 16000)
            await engine.audio(opened["id"], 0, b"\0\0" * 16000)
            self.assertEqual((await engine.finish(opened["id"], 1))["text"], "")
        finally:
            engine.stop()


if __name__ == "__main__":
    unittest.main()
