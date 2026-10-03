"""Opt-in real-time transport soak. No microphone/model/API key is used."""
import asyncio
import json
import os
import tempfile
import time
import unittest
from unittest.mock import patch
from aiohttp import web, WSMsgType
from aiohttp.test_utils import TestClient, TestServer
from .app import create_app


@unittest.skipUnless(os.environ.get('GLYPH_LONG_STREAM_SECONDS'), 'Opt-in real-time stream soak')
class LongBridgeTest(unittest.IsolatedAsyncioTestCase):
    async def test_stays_open_until_explicit_stop(self):
        seconds = int(os.environ['GLYPH_LONG_STREAM_SECONDS'])
        self.assertTrue(1 <= seconds <= 3600)
        token = 'test-long-stream-token-12345678'
        origin = 'http://localhost:8765'
        stop = asyncio.Event()

        async def board(request):
            socket = web.WebSocketResponse()
            await socket.prepare(request)
            await socket.send_json({'type': 'start', 'version': 1, 'id': 'long1',
                                    'sampleRate': 16000, 'channels': 1,
                                    'encoding': 'pcm_s16le', 'control': 'stop-v1'})
            sent = 0
            async def produce():
                nonlocal sent
                while not stop.is_set():
                    await socket.send_bytes(bytes(1280))
                    sent += 1280
                    await asyncio.sleep(.04)
            producer = asyncio.create_task(produce())
            try:
                async for message in socket:
                    self.assertEqual(message.data, 'STOP long1')
                    stop.set()
                    await producer
                    await socket.send_json({'type': 'end', 'id': 'long1', 'bytes': sent})
                    break
            finally:
                producer.cancel()
                await asyncio.gather(producer, return_exceptions=True)
            return socket

        peer = web.Application()
        peer.router.add_get('/audio', board)
        with tempfile.TemporaryDirectory() as root:
            async with TestServer(peer) as peer_server:
                with patch('server.app.glyph_address', return_value=str(peer_server.make_url('/audio')).replace('http:', 'ws:')):
                    app = create_app(root, token, [origin], glyph_hosts=['192.168.4.2'])
                    async with TestClient(TestServer(app)) as browser:
                        async with browser.ws_connect('/api/glyph', headers={'Origin': origin, 'Host': 'localhost:8765'}) as socket:
                            await socket.send_json({'token': token, 'address': '192.168.4.2'})
                            self.assertEqual((await socket.receive_json())['proxy'], 'ready')
                            self.assertEqual((await socket.receive_json())['type'], 'start')
                            began = last_report = time.monotonic()
                            received = 0
                            while time.monotonic() - began < seconds:
                                message = await asyncio.wait_for(socket.receive(), 5)
                                self.assertEqual(message.type, WSMsgType.BINARY)
                                received += len(message.data)
                                if time.monotonic() - last_report >= 60:
                                    last_report = time.monotonic()
                                    print(f'[soak] {last_report-began:.0f}s connected; {received} PCM bytes received', flush=True)
                            self.assertFalse(stop.is_set())
                            await socket.send_str('STOP long1')
                            while True:
                                message = await asyncio.wait_for(socket.receive(), 5)
                                if message.type == WSMsgType.BINARY:
                                    received += len(message.data)
                                else:
                                    self.assertEqual(message.type, WSMsgType.TEXT)
                                    end = json.loads(message.data)
                                    self.assertEqual(end, {'type': 'end', 'id': 'long1', 'bytes': received})
                                    break
                            self.assertTrue(stop.is_set())
                            self.assertGreater(received, seconds * 16000)
                            print(f'[soak] Explicit STOP at {seconds}s; exact byte count={received}', flush=True)
