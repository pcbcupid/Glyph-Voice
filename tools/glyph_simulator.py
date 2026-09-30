#!/usr/bin/env python3
"""Development-only Glyph protocol peer. Sends a WAV over the LAN, never the internet."""
import argparse
import asyncio
import json
import uuid
import wave
import socket

from websockets.asyncio.server import serve


def load_wav(path):
    with wave.open(str(path), "rb") as wav:
        if wav.getnchannels() != 1 or wav.getsampwidth() != 2 or wav.getcomptype() != "NONE":
            raise ValueError("Use mono, uncompressed PCM16 WAV")
        rate = wav.getframerate()
        if rate not in (8000, 16000, 32000, 44100, 48000):
            raise ValueError("Supported rates: 8000, 16000, 32000, 44100, 48000 Hz")
        pcm = wav.readframes(wav.getnframes())
        if len(pcm) != wav.getnframes() * 2:
            raise ValueError("Truncated WAV")
        return rate, pcm


def metadata(rate, recording_id):
    return dict(type="start", version=1, id=recording_id, sampleRate=rate,
                channels=1, encoding="pcm_s16le")


async def stream(connection, rate, pcm, mode):
    if connection.request.path != "/audio":
        await connection.close(1008, "Use /audio")
        return
    print("Phone connected. Recording starts in 2 seconds.", flush=True)
    await asyncio.sleep(2)
    if mode == "malformed":
        await connection.send('{"type":"start",broken')
        await connection.wait_closed()
        return
    recording_id = uuid.uuid4().hex
    await connection.send(json.dumps(metadata(rate, recording_id)))
    chunk_size = rate // 50 * 2  # ~20 ms, always sample aligned
    audio = b"" if mode == "empty" else pcm
    for offset in range(0, len(audio), chunk_size):
        await connection.send(audio[offset:offset + chunk_size])
        if mode == "disconnect" and offset >= len(audio) // 2:
            await connection.close(1011, "Simulated connection loss")
            return
        await asyncio.sleep(chunk_size / (rate * 2))
    await connection.send(json.dumps(dict(type="end", id=recording_id,
                                         bytes=len(audio) + (2 if mode == "bad-count" else 0))))
    print("Recording sent. Read the result on the phone. Reconnect to replay.", flush=True)
    await connection.wait_closed()


async def announce(phone_ip):
    message = json.dumps(dict(service="glyph-voice", version=1, id="020000000001",
                              port=8080, path="/audio")).encode()
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
        udp.setblocking(False)
        while True:
            await asyncio.get_running_loop().sock_sendto(udp, message, (phone_ip, 40123))
            await asyncio.sleep(1)


async def main(args):
    rate, pcm = load_wav(args.wav)
    async with serve(lambda connection: stream(connection, rate, pcm, args.mode),
                     args.host, args.port, max_size=2048, ping_interval=10):
        print(f"Listening on {args.host}:{args.port}/audio; {len(pcm)/(rate*2):.2f}s at {rate} Hz", flush=True)
        print("Tap Find Glyph on the phone hosting this computer's Wi-Fi hotspot.", flush=True)
        if args.port != 8080:
            raise ValueError("Automatic discovery uses port 8080")
        await announce(args.phone_ip)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("wav")
    parser.add_argument("--phone-ip", required=True, help="Hotspot phone gateway IPv4; only this developer simulator needs it")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--mode", choices=("normal", "empty", "malformed", "disconnect", "bad-count"), default="normal")
    try:
        asyncio.run(main(parser.parse_args()))
    except KeyboardInterrupt:
        pass
