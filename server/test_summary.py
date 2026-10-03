import asyncio
import json
import tempfile
import unittest
import contextlib
import io
import ssl
from unittest.mock import AsyncMock, Mock, patch

from aiohttp.test_utils import TestClient, TestServer
from aiohttp import (ClientConnectorCertificateError, ClientConnectorDNSError,
                     ConnectionTimeoutError, ClientError, web)
from .app import create_app
from .summary import payload, result, summarize, SummaryError

DATA = {"provider": "deepseek", "key": "test-provider-key", "model": "deepseek-flash",
        "text": "We agreed to meet tomorrow.", "consent": True}
ANSWER = {"choices": [{"finish_reason": "stop", "message": {"content": "They will meet tomorrow."}}]}
TOKEN = "test-server-token-1234567890"
HEADERS = {"Host": "localhost:8765", "Origin": "http://localhost:8765", "Authorization": "Bearer " + TOKEN}


class ContractTests(unittest.TestCase):
    def test_fixed_destinations_text_only_and_natural_english_prompt(self):
        url, key, provider, body = payload(DATA)
        self.assertEqual(url, "https://api.deepseek.com/chat/completions")
        self.assertEqual(key, DATA["key"])
        self.assertEqual(provider, "deepseek")
        self.assertEqual(body["thinking"], {"type": "disabled"})
        self.assertEqual(body["messages"][1]["content"], DATA["text"])
        self.assertIn("English-only", body["messages"][0]["content"])
        self.assertIn("No headings", body["messages"][0]["content"])
        url, _, _, body = payload({**DATA, "provider": "openai", "model": "gpt-4.1-mini"})
        self.assertEqual(url, "https://api.openai.com/v1/responses")
        self.assertFalse(body["store"])
        self.assertEqual(body["input"], DATA["text"])
        self.assertNotIn("audio", body)

    def test_invalid_input_rejected_before_network(self):
        for change in ({"provider": "http://elsewhere"}, {"key": "x\r\nx"}, {"consent": False},
                       {"model": ""}, {"text": ""}, {"text": "x" * 48001}, {"text": "中" * 16001}):
            with self.subTest(change=list(change)):
                with self.assertRaises(SummaryError):
                    payload({**DATA, **change})
        for data in (None, [], "bad"):
            with self.assertRaises(SummaryError):
                payload(data)

    def test_parse_both_providers(self):
        self.assertEqual(result("deepseek", ANSWER), "They will meet tomorrow.")
        self.assertEqual(result("openai", {"status": "completed", "output": [
            {"type": "message", "content": [{"type": "output_text", "text": "A short summary."}]}]}), "A short summary.")

    def test_reject_malformed_truncated_refused_and_non_latin(self):
        for data in ({}, [], {"choices": []}, {"choices": [{"finish_reason": "length"}]},
                     {"choices": [{"finish_reason": "stop", "message": {"content": "中文"}}]}):
            with self.assertRaises(SummaryError):
                result("deepseek", data)
        for data in ({"status": "incomplete"}, {"status": "completed", "output": [
            {"type": "message", "content": [{"type": "refusal"}]}]}):
            with self.assertRaises(SummaryError):
                result("openai", data)


class ProviderTests(unittest.IsolatedAsyncioTestCase):
    async def call(self, status=200, raw=None):
        async def chunks(size):
            yield raw if raw is not None else json.dumps(ANSWER).encode()
        response = Mock(status=status)
        response.content.iter_chunked = chunks
        post = AsyncMock()
        post.__aenter__.return_value = response
        client = Mock()
        client.post.return_value = post
        context = AsyncMock()
        context.__aenter__.return_value = client
        with patch("server.summary.ClientSession", return_value=context) as session:
            answer = await summarize(DATA)
        timeout = session.call_args.kwargs["timeout"]
        self.assertEqual((timeout.connect, timeout.sock_read, timeout.total), (15, 90, 120))
        self.assertFalse(client.post.call_args.kwargs["allow_redirects"])
        return answer

    async def test_success(self):
        self.assertEqual(await self.call(), "They will meet tomorrow.")

    async def test_errors_are_sanitized_and_not_retried(self):
        for status in (301, 401, 403, 402, 429, 500):
            with self.assertRaises(SummaryError) as error:
                await self.call(status, b"secret-provider-response")
            self.assertNotIn("secret", str(error.exception))
        for raw in (b"invalid-json", b"x" * 1_000_001):
            with self.assertRaises(SummaryError):
                await self.call(raw=raw)

    async def test_network_failure_diagnostics_do_not_log_keys_or_transcripts(self):
        errors = [(ClientConnectorCertificateError(None, ssl.CertificateError("secret-error-detail")), "certificate"),
                  (ClientConnectorDNSError(None, OSError("secret-error-detail")), "DNS"),
                  (ConnectionTimeoutError("secret-error-detail"), "15 seconds"),
                  (TimeoutError("secret-error-detail"), "120 seconds"),
                  (ClientError("secret-error-detail"), "interrupted")]
        for error, expected in errors:
            context = AsyncMock()
            context.__aenter__.side_effect = error
            output = io.StringIO()
            with patch("server.summary.ClientSession", return_value=context), contextlib.redirect_stdout(output):
                with self.assertRaises(SummaryError) as raised:
                    await summarize(DATA)
            self.assertIn(expected, str(raised.exception))
            self.assertIn("[request ", str(raised.exception))
            self.assertIn("outcome=failed", output.getvalue())
            for secret in (DATA["key"], DATA["text"], "secret-error-detail"):
                self.assertNotIn(secret, str(raised.exception) + output.getvalue())


class RouteTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.client = TestClient(TestServer(create_app(self.temp.name, TOKEN, ["http://localhost:8765"])))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        self.temp.cleanup()

    async def test_auth_required_and_error_preserves_privacy(self):
        with patch("server.app.summarize", new_callable=AsyncMock) as call:
            response = await self.client.post("/api/summary", json=DATA, headers={**HEADERS, "Authorization": "bad"})
            self.assertEqual(response.status, 401)
            call.assert_not_called()
            call.return_value = "A summary."
            response = await self.client.post("/api/summary", json=DATA, headers=HEADERS)
            self.assertEqual(await response.json(), {"text": "A summary."})
            self.assertEqual(response.headers["Cache-Control"], "no-store")
            call.side_effect = SummaryError("API key rejected.")
            response = await self.client.post("/api/summary", json=DATA, headers=HEADERS)
            self.assertEqual(response.status, 400)
            self.assertEqual(await response.json(), {"error": "API key rejected."})

    async def test_busy_and_disconnect_release_slot_without_blocking_stt(self):
        started, cancelled = asyncio.Event(), asyncio.Event()
        async def pending(data):
            started.set()
            try:
                await asyncio.Event().wait()
            finally:
                cancelled.set()
        with patch("server.app.summarize", side_effect=pending):
            request = asyncio.create_task(self.client.post("/api/summary", json=DATA, headers=HEADERS))
            await asyncio.wait_for(started.wait(), 2)
            response = await self.client.post("/api/summary", json=DATA, headers=HEADERS)
            self.assertEqual(response.status, 409)
            response = await self.client.get("/api/local", headers=HEADERS)
            self.assertEqual(response.status, 200)
            request.cancel()
            await asyncio.gather(request, return_exceptions=True)
            await asyncio.wait_for(cancelled.wait(), 2)
        with patch("server.app.summarize", new_callable=AsyncMock, return_value="Next summary."):
            response = await self.client.post("/api/summary", json=DATA, headers=HEADERS)
            self.assertEqual(response.status, 200)

    async def test_real_http_relay_to_delayed_provider_without_paid_requests(self):
        async def provider(request):
            self.assertEqual(request.headers["Authorization"], "Bearer " + DATA["key"])
            body = await request.json()
            self.assertEqual(body["messages"][1]["content"], DATA["text"])
            # DeepSeek may send whitespace keep-alives before its JSON body.
            response = web.StreamResponse(headers={"Content-Type": "application/json"})
            await response.prepare(request)
            await response.write(b"\n ")
            await asyncio.sleep(0.05)
            await response.write(json.dumps(ANSWER).encode())
            await response.write_eof()
            return response
        peer = web.Application()
        peer.router.add_post("/chat/completions", provider)
        async with TestServer(peer) as provider_server:
            with patch.dict("server.summary.PROVIDERS", {"deepseek": str(provider_server.make_url("/chat/completions"))}):
                response = await self.client.post("/api/summary", json=DATA, headers=HEADERS)
                self.assertEqual(response.status, 200)
                self.assertEqual(await response.json(), {"text": "They will meet tomorrow."})
