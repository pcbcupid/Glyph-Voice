import asyncio
import os
import re
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch, Mock

from aiohttp.test_utils import TestClient, TestServer
from aiohttp import web
from .app import create_app, glyph_address, main as server_main
from .inference import Inference, warm_up
from .models import ModelFolders

TOKEN = "test-token-not-real-1234567890"
ORIGIN = "http://localhost:8765"


class InferencePipeTests(unittest.IsolatedAsyncioTestCase):
    async def wait_until_entered(self, entered):
        async with asyncio.timeout(2):
            while not entered.is_set():
                await asyncio.sleep(.001)

    async def check_blocked_io(self, stage):
        entered, release = threading.Event(), threading.Event()
        caller_threads = []
        def blocked(*unused):
            caller_threads.append(threading.get_ident())
            entered.set()
            release.wait(2)  # Safety bound even if a regression blocks the event loop.
            return {"ok": True, "text": "fixture"}
        engine = Inference()
        engine.pipe = Mock()
        engine.pipe.poll.return_value = True
        engine.pipe.recv.return_value = {"ok": True, "text": "fixture"}
        getattr(engine.pipe, stage).side_effect = blocked
        task = asyncio.create_task(engine._command("audio", b"\0\0" * 3200))
        try:
            await self.wait_until_entered(entered)
            # This coroutine must run while IPC is blocked. The old synchronous
            # send/recv ran on this thread and could stop bridge socket reads.
            self.assertNotEqual(caller_threads, [threading.get_ident()])
            self.assertFalse(task.done())
            release.set()
            self.assertEqual((await task)["text"], "fixture")
            engine.pipe.send.assert_called_once_with(("audio", b"\0\0" * 3200))
        finally:
            release.set()
            await asyncio.gather(task, return_exceptions=True)

    async def test_slow_pipe_send_does_not_block_network_event_loop(self):
        await self.check_blocked_io("send")

    async def test_readable_but_incomplete_reply_does_not_block_network_event_loop(self):
        await self.check_blocked_io("recv")

    async def test_incomplete_reply_still_has_deadline_and_stops_worker(self):
        release = threading.Event()
        engine = Inference()
        engine.pipe = Mock()
        engine.pipe.poll.return_value = True
        engine.pipe.recv.side_effect = lambda: release.wait(2)
        with patch.object(engine, "stop", side_effect=release.set) as stop:
            try:
                with self.assertRaisesRegex(RuntimeError, "timed out"):
                    await engine._reply(timeout=.03)
                stop.assert_called_once_with()
            finally:
                release.set()

    async def test_cancelling_blocked_writer_stops_worker_before_pipe_can_be_reused(self):
        entered, release = threading.Event(), threading.Event()
        def send(*unused):
            entered.set()
            release.wait(2)
        engine = Inference()
        engine.pipe = Mock()
        engine.pipe.send.side_effect = send
        with patch.object(engine, "stop", side_effect=release.set) as stop:
            task = asyncio.create_task(engine._command("audio", b"\0\0"))
            try:
                await self.wait_until_entered(entered)
                task.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await task
                stop.assert_called_once_with()
                engine.pipe.recv.assert_not_called()
            finally:
                release.set()
                await asyncio.gather(task, return_exceptions=True)


class WarmupTests(unittest.TestCase):
    def test_disposable_stream_runs_native_decode_before_readiness(self):
        import numpy as np
        engine = Mock()
        engine.is_ready.side_effect = [True, True, False]
        warm_up(engine, np)
        engine.create_stream.assert_called_once_with()
        stream = engine.create_stream.return_value
        rate, samples = stream.accept_waveform.call_args.args
        self.assertEqual(rate, 16000)
        self.assertEqual(len(samples), 32000)
        self.assertTrue(np.all(samples == 0))
        stream.input_finished.assert_called_once_with()
        self.assertEqual(engine.decode_stream.call_count, 2)
        engine.decode_stream.assert_called_with(stream)


class BrowserLauncherTests(unittest.TestCase):
    def test_browser_opens_only_from_bound_server_callback_without_token_in_url(self):
        with tempfile.TemporaryDirectory() as folder, \
             patch("sys.argv", ["server", "--models-dir", folder, "--dist", folder, "--open-browser"]), \
             patch.dict(os.environ, {"GLYPH_LOCAL_TOKEN": TOKEN}), \
             patch("builtins.print"), patch("server.app.webbrowser.open", return_value=True) as browser, \
             patch("server.app.threading.Thread") as thread, \
             patch("server.app.web.run_app") as run:
            def bound(app, **kwargs):
                browser.assert_not_called()
                thread.assert_not_called()
                kwargs["print"]("Server bound")
                thread.call_args.kwargs["target"]()
            run.side_effect = bound
            server_main()
            thread.return_value.start.assert_called_once_with()
            browser.assert_called_once_with("http://localhost:8765")

    def test_bind_failure_does_not_open_browser(self):
        with tempfile.TemporaryDirectory() as folder, \
             patch("sys.argv", ["server", "--models-dir", folder, "--dist", folder, "--open-browser"]), \
             patch.dict(os.environ, {"GLYPH_LOCAL_TOKEN": TOKEN}), \
             patch("builtins.print"), patch("sys.stderr"), \
             patch("server.app.threading.Thread") as thread, \
             patch("server.app.web.run_app", side_effect=OSError("port busy")):
            with self.assertRaises(SystemExit) as raised:
                server_main()
            self.assertEqual(raised.exception.code, 1)
            thread.assert_not_called()


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
    def assert_opening_words(self, text):
        prefix = os.environ.get("GLYPH_TEST_PREFIX")
        if prefix:
            normalize = lambda value: " ".join(re.findall(r"\w+", value.lower()))
            self.assertTrue(normalize(text).startswith(normalize(prefix)),
                            "Opening words were missing from the real transcript")

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
            text = (await response.json())["text"]
            self.assertTrue(text)
            self.assert_opening_words(text)

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
            self.assert_opening_words(result["text"])
            self.assertTrue(any(partials), "Expected partials before finish")
            print("Real local inference:", result["text"], flush=True)
            opened = await engine.open(info["modelId"], 16000)
            await engine.audio(opened["id"], 0, b"\0\0" * 16000)
            self.assertEqual((await engine.finish(opened["id"], 1))["text"], "")
        finally:
            engine.stop()


if __name__ == "__main__":
    unittest.main()
