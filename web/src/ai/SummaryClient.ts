import { validateLocal } from '../speech/LocalRecognizer';
import type { LocalConnection } from '../speech/LocalRecognizer';

export const SUMMARY_PROVIDERS = {
  deepseek: { name: 'DeepSeek', model: 'deepseek-flash' },
  openai: { name: 'OpenAI', model: 'gpt-4.1-mini' },
};
export interface SummaryConfig {
  server: LocalConnection;
  provider: keyof typeof SUMMARY_PROVIDERS;
  model: string;
  key: string;
  automatic: boolean;
  consent: boolean;
}
export class SummaryClient {
  readonly config: SummaryConfig;
  constructor(config: SummaryConfig) {
    if (!Object.hasOwn(SUMMARY_PROVIDERS, config.provider) || !config.consent)
      throw new Error('Select a provider and allow transcript sharing first.');
    if (!/^[!-~]{1,4096}$/.test(config.key) || !/^[A-Za-z0-9._:/-]{1,120}$/.test(config.model))
      throw new Error('Check your summary API key and model name.');
    this.config = { ...config, server: validateLocal(config.server) };
  }
  async summarize(text: string, signal: AbortSignal): Promise<string> {
    if (!text.trim() || new TextEncoder().encode(text).length > 48000)
      throw new Error('Summary requires nonempty text up to 48 KB. Nothing was sent.');
    const { server, provider, model, key } = this.config;
    const body = JSON.stringify({ provider, model, key, text, consent: true });
    if (new TextEncoder().encode(body).length > 64000)
      throw new Error('Summary request is too large. Nothing was sent.');
    const controller = new AbortController();
    const abort = () => controller.abort();
    signal.addEventListener('abort', abort, { once: true });
    if (signal.aborted) controller.abort();
    // Server has Android's 120-second overall provider timeout. Allow transport
    // overhead without cutting off a response that would succeed on the phone.
    const timeout = setTimeout(abort, 130000);
    try {
      const response = await fetch(server.url + '/api/summary', {
        method: 'POST',
        body,
        signal: controller.signal,
        headers: { Authorization: 'Bearer ' + server.token, 'Content-Type': 'application/json' },
        credentials: 'omit',
        redirect: 'error',
        cache: 'no-store',
        referrerPolicy: 'no-referrer',
      });
      if (response.status === 404 || response.status === 405)
        throw new Error(
          'This server has no summary API. Stop and restart start-web, then refresh the page. Check the summary server URL.',
        );
      if (response.status === 401)
        throw new Error(
          'Local server token rejected. Copy the current token from the start-web terminal into Connect your API. It is different from the provider API key.',
        );
      if (!response.body) throw new Error('Empty summary response.');
      const reader = response.body.getReader();
      let raw = '',
        count = 0;
      const decoder = new TextDecoder();
      try {
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          count += value.length;
          if (count > 1_100_000) {
            await reader.cancel();
            throw new Error('Summary response too large.');
          }
          raw += decoder.decode(value, { stream: true });
        }
      } finally {
        reader.releaseLock();
      }
      let result;
      try {
        result = JSON.parse(raw + decoder.decode());
      } catch {
        throw new Error(
          'Summary server returned an unreadable response. Restart the updated server.',
        );
      }
      if (!result || typeof result !== 'object' || Array.isArray(result))
        throw new Error(
          'Summary server returned an unreadable response. Restart the updated server.',
        );
      if (!response.ok)
        throw new Error(
          response.status === 401
            ? 'Local server token rejected. Update Connect your API.'
            : typeof result.error === 'string'
              ? result.error.slice(0, 300)
              : `Summary server returned HTTP ${response.status}.`,
        );
      if (typeof result.text !== 'string' || !result.text.trim())
        throw new Error('No summary returned.');
      if (signal.aborted) throw new Error('Summary cancelled.');
      return result.text.trim();
    } catch (error) {
      if (controller.signal.aborted)
        throw new Error(
          signal.aborted
            ? 'Summary cancelled. Provider may already have received/billed the text.'
            : 'Summary timed out. No automatic retry; your transcript is kept.',
        );
      if (error instanceof TypeError)
        throw new Error(
          'Summary server unreachable. Run start-web and check the server URL and token.',
        );
      throw error;
    } finally {
      clearTimeout(timeout);
      signal.removeEventListener('abort', abort);
    }
  }
}
