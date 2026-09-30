"""Tests simulator input validation and the real WebSocket wire exchange."""
import asyncio
import json
from pathlib import Path
import tempfile
import unittest
import wave

from websockets.asyncio.client import connect
from websockets.asyncio.server import serve
from glyph_simulator import load_wav, stream


class SimulatorTest(unittest.TestCase):
    def test_more_than_sixty_seconds(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "long.wav"
            pcm = bytes(16000 * 2 * 61)
            with wave.open(str(path), "wb") as output:
                output.setnchannels(1)
                output.setsampwidth(2)
                output.setframerate(16000)
                output.writeframes(pcm)
            self.assertEqual((16000, pcm), load_wav(path))

    def test_wav_validation(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "sample.wav"
            for channels in (1, 2):
                with wave.open(str(path), "wb") as output:
                    output.setnchannels(channels)
                    output.setsampwidth(2)
                    output.setframerate(16000)
                    output.writeframes(bytes(3200 * channels))
                if channels == 1:
                    self.assertEqual((16000, bytes(3200)), load_wav(path))
                else:
                    with self.assertRaises(ValueError):
                        load_wav(path)


class WireTest(unittest.IsolatedAsyncioTestCase):
    async def test_normal_packet_order_and_byte_count(self):
        async with serve(lambda ws: stream(ws, 16000, bytes(3200), "normal"), "127.0.0.1", 0) as server:
            port = server.sockets[0].getsockname()[1]
            async with connect(f"ws://127.0.0.1:{port}/audio") as ws:
                start = json.loads(await ws.recv())
                self.assertEqual("start", start["type"])
                self.assertEqual(16000, start["sampleRate"])
                data = bytearray()
                async for packet in ws:
                    if isinstance(packet, str):
                        end = json.loads(packet)
                        self.assertEqual(start["id"], end["id"])
                        self.assertEqual(len(data), end["bytes"])
                        break
                    data.extend(packet)
                self.assertEqual(bytes(3200), data)


if __name__ == "__main__":
    unittest.main()
