"""Isolate native model loading/inference: a bad model must not crash the HTTP server."""
import asyncio
import multiprocessing as mp
import time
import uuid


def warm_up(engine, np):
    """Pay native first-decode costs before advertising model readiness.

    Use a disposable stream: no warm-up samples/state may enter a user's recording.
    Finishing the stream also exercises encoders with larger right-context windows.
    """
    stream = engine.create_stream()
    stream.accept_waveform(16000, np.zeros(32000, dtype=np.float32))
    stream.input_finished()
    while engine.is_ready(stream):
        engine.decode_stream(stream)


def worker(pipe, files, threads):
    try:
        # Native crashes must not leave a core dump containing in-memory speech.
        try:
            import resource
            resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
        except (ImportError, OSError, ValueError):
            pass  # Windows has no resource module; OS crash-dump policy is operator-owned.
        import numpy as np
        import sherpa_onnx
        engine = sherpa_onnx.OnlineRecognizer.from_transducer(
            **files, num_threads=threads, sample_rate=16000,
            decoding_method="greedy_search", enable_endpoint_detection=False,
            provider="cpu", debug=False,
        )
        warm_up(engine, np)
        pipe.send({"ok": True})
        stream = None
        rate = 16000
        while True:
            op, value = pipe.recv()
            if op == "open":
                rate = value
                stream = engine.create_stream()
                pipe.send({"ok": True})
            elif op == "cancel":
                stream = None
                pipe.send({"ok": True})
            elif op in ("audio", "finish"):
                if stream is None:
                    raise ValueError("No stream")
                if op == "audio":
                    samples = np.frombuffer(value, dtype="<i2").astype(np.float32) / 32768.0
                else:
                    samples = np.zeros(rate // 50, dtype=np.float32)
                stream.accept_waveform(rate, samples)
                samples.fill(0)
                if op == "finish":
                    stream.input_finished()
                while engine.is_ready(stream):
                    engine.decode_stream(stream)
                text = engine.get_result(stream).strip()
                if len(text.encode("utf-8")) > 1_000_000:
                    raise ValueError("Transcript too large")
                pipe.send({"ok": True, "text": text})
                if op == "finish":
                    stream = None
            else:
                raise ValueError("Invalid worker operation")
    except (EOFError, BrokenPipeError):
        pass
    except Exception:
        # Do not reflect native exceptions/absolute file paths or audio in HTTP responses.
        try:
            pipe.send({"ok": False})
        except (EOFError, BrokenPipeError):
            pass
    finally:
        pipe.close()


class Inference:
    def __init__(self, threads=4):
        self.threads = threads
        self.process = self.pipe = None
        self.model = None
        self.model_id = None
        self.stream_id = None
        self.sequence = 0
        self.touched = time.monotonic()
        self.lock = asyncio.Lock()

    def stop(self):
        if self.process:
            self.process.terminate()
            self.process.join(timeout=1)
            if self.process.is_alive():
                self.process.kill()
                self.process.join(timeout=1)
            self.process.close()
        if self.pipe:
            self.pipe.close()
        self.process = self.pipe = None
        self.model = self.model_id = self.stream_id = None

    async def _reply(self, timeout=30):
        until = time.monotonic() + timeout
        try:
            while time.monotonic() < until:
                if self.pipe.poll():
                    # poll() only guarantees readable bytes, not a whole pickle.
                    # recv() can still block while the worker writes its result;
                    # never make the HTTP / Glyph bridge event loop wait for it.
                    result = await asyncio.wait_for(
                        asyncio.to_thread(self.pipe.recv), max(.001, until - time.monotonic()))
                    if not result.get("ok"):
                        raise RuntimeError("Local inference failed. Check the model folder and reload it.")
                    return result
                if not self.process.is_alive():
                    raise RuntimeError("Local model worker exited. Check model compatibility and available RAM.")
                await asyncio.sleep(.01)
            raise RuntimeError("Local inference timed out. Use a faster host, fewer competing tasks, or a smaller supported model.")
        except TimeoutError as error:
            self.stop()
            raise RuntimeError("Local inference timed out. Reload the model or use a faster host.") from error
        except (EOFError, OSError) as error:
            self.stop()
            raise RuntimeError("Local inference worker disconnected. Reload the model.") from error
        except BaseException:
            self.stop()
            raise

    async def _command(self, op, value=None):
        if not self.pipe:
            raise RuntimeError("Load a local model first.")
        until = time.monotonic() + 30
        try:
            # Pipe capacity is finite. Even with native inference in another
            # process, a blocking send here used to stall every network handler.
            # Keep the same total command deadline, not 30 seconds per phase.
            await asyncio.wait_for(asyncio.to_thread(self.pipe.send, (op, value)), 30)
        except TimeoutError as error:
            self.stop()
            raise RuntimeError("Local inference timed out while accepting audio. Reload the model or use a faster host.") from error
        except (OSError, EOFError) as error:
            self.stop()
            raise RuntimeError("Local worker disconnected. Reload the model.") from error
        except BaseException:
            # A cancelled writer must not remain alive against a reused pipe.
            self.stop()
            raise
        result = await self._reply(max(0, until - time.monotonic()))
        self.touched = time.monotonic()
        return result

    async def load(self, files, name):
        if self.stream_id:
            raise ValueError("Stop the active recording before changing models")
        self.stop()
        context = mp.get_context("spawn")
        parent, child = context.Pipe()
        self.pipe = parent
        self.process = context.Process(target=worker, args=(child, files, self.threads), daemon=True)
        self.process.start()
        child.close()
        await self._reply(120)
        self.model, self.model_id = name, uuid.uuid4().hex
        return self.info()

    def info(self):
        return {"model": self.model, "modelId": self.model_id, "active": self.stream_id is not None,
                "engine": "sherpa-onnx 1.13.8 CPU", "execution": "self-hosted"}

    async def open(self, model_id, rate):
        if model_id != self.model_id or not self.model_id:
            raise ValueError("The selected model changed or was unloaded. Select it again")
        if self.stream_id:
            raise ValueError("This self-hosted worker is already recording in another tab")
        if type(rate) is not int or rate not in (8000, 16000, 32000, 44100, 48000):
            raise ValueError("Unsupported PCM sample rate")
        await self._command("open", rate)
        self.stream_id, self.sequence = uuid.uuid4().hex, 0
        return {"id": self.stream_id}

    def check(self, stream_id, sequence=None):
        if not self.stream_id or stream_id != self.stream_id:
            raise ValueError("Recording expired or belongs to a different session")
        if sequence is not None and (type(sequence) is not int or sequence != self.sequence):
            raise ValueError("Audio sequence mismatch; do not retry audio requests")

    async def audio(self, stream_id, sequence, pcm):
        self.check(stream_id, sequence)
        if not 2 <= len(pcm) <= 65536 or len(pcm) % 2:
            raise ValueError("Expected aligned PCM16, at most 64 KiB per request")
        result = await self._command("audio", pcm)
        self.sequence += 1
        return {"text": result["text"], "sequence": sequence}

    async def finish(self, stream_id, sequence):
        if type(sequence) is not int:
            raise ValueError("Final audio sequence is required")
        self.check(stream_id, sequence)
        result = await self._command("finish")
        self.stream_id = None
        return {"text": result["text"]}

    async def cancel(self, stream_id):
        self.check(stream_id)
        await self._command("cancel")
        self.stream_id = None
        return {"ok": True}
