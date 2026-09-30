import { localEndpoint } from '../core/protocol';

export const BLE_SERVICE = 'c8c0f100-7d8c-4b9e-9a26-12f467a3e001';
export const BLE_STATUS = 'c8c0f101-7d8c-4b9e-9a26-12f467a3e001';
export const BLE_COMMAND = 'c8c0f102-7d8c-4b9e-9a26-12f467a3e001';

// Minimal structural types keep this optional browser API out of the portable core.
interface Characteristic {
  readValue(): Promise<DataView>;
  writeValueWithResponse(value: Uint8Array<ArrayBuffer>): Promise<void>;
}
interface GattServer {
  connected: boolean;
  connect(): Promise<GattServer>;
  disconnect(): void;
  getPrimaryService(
    uuid: string,
  ): Promise<{ getCharacteristic(uuid: string): Promise<Characteristic> }>;
}
interface Device extends EventTarget {
  id: string;
  name?: string;
  gatt?: GattServer;
}
interface Bluetooth {
  requestDevice(options: { filters: { services: string[] }[] }): Promise<Device>;
}
export interface ProvisionStatus {
  id: string;
  state: 'ready' | 'queued' | 'joining' | 'connected' | 'failed';
  address: string;
  error: string;
}
export function bluetoothSupport(): string | null {
  if (!window.isSecureContext)
    return 'Bluetooth setup requires HTTPS or localhost. A phone opening a LAN HTTP address cannot use Web Bluetooth.';
  if (!('bluetooth' in navigator))
    return 'This browser does not support Web Bluetooth. iPhone Safari and Firefox need the Wi-Fi setup fallback.';
  return null;
}
export function credentialPackets(ssid: string, password: string): Uint8Array<ArrayBuffer>[] {
  const encoder = new TextEncoder(),
    name = encoder.encode(ssid),
    key = encoder.encode(password);
  try {
    if (!name.length || name.length > 32 || ssid.includes('\0'))
      throw new Error('Wi-Fi name must be 1–32 UTF-8 bytes, without null characters.');
    if (!/^[ -~]{8,63}$/.test(password))
      throw new Error('Use an 8–63 character ASCII password on a 2.4 GHz WPA2 network.');
    const body = new Uint8Array(2 + name.length + key.length);
    body.set([name.length, key.length]);
    body.set(name, 2);
    body.set(key, 2 + name.length);
    const packets = [new Uint8Array([1, body.length])];
    for (let offset = 0; offset < body.length; offset += 18) {
      const part = body.subarray(offset, offset + 18),
        packet = new Uint8Array(part.length + 2);
      packet.set([2, offset]);
      packet.set(part, 2);
      packets.push(packet);
    }
    packets.push(new Uint8Array([3]));
    body.fill(0);
    return packets;
  } finally {
    name.fill(0);
    key.fill(0);
  }
}
export function parseProvisionStatus(value: DataView): ProvisionStatus {
  if (value.byteLength > 224) throw new Error('Setup response too large.');
  let json;
  try {
    json = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(value));
  } catch {
    throw new Error('Invalid Glyph setup response.');
  }
  if (
    !json ||
    json.version !== 1 ||
    json.port !== 8080 ||
    json.path !== '/audio' ||
    typeof json.id !== 'string' ||
    !/^[a-f\d]{12}$/i.test(json.id) ||
    !['ready', 'queued', 'joining', 'connected', 'failed'].includes(json.state)
  )
    throw new Error('Unsupported Glyph setup protocol. Update app and firmware together.');
  let address = '';
  if (json.state === 'connected') {
    if (typeof json.ip !== 'string') throw new Error('Glyph did not return its Wi-Fi address.');
    address = `${json.ip}:8080`;
    localEndpoint(address, 'http:'); // Validate ONLY; HTTPS audio policy still applies later.
  }
  const error =
    json.error === 'network'
      ? 'Glyph could not join Wi-Fi. Check the name, password and 2.4 GHz mode.'
      : json.error === 'storage'
        ? 'Glyph could not save settings. Reset and retry.'
        : 'Setup transfer was rejected. Reconnect and retry.';
  return { id: json.id.toUpperCase(), state: json.state, address, error };
}
function bounded<T>(work: Promise<T>, signal: AbortSignal, ms = 15000): Promise<T> {
  return new Promise((resolve, reject) => {
    const fail = () => {
      clearTimeout(timer);
      reject(new Error('Bluetooth setup cancelled.'));
    };
    const timer = setTimeout(() => {
      signal.removeEventListener('abort', fail);
      reject(new Error('Bluetooth operation timed out. Check pairing and retry.'));
    }, ms);
    work.then(resolve, reject).finally(() => {
      clearTimeout(timer);
      signal.removeEventListener('abort', fail);
    });
    if (signal.aborted) {
      fail();
      return;
    }
    signal.addEventListener('abort', fail, { once: true });
  });
}

export class BleProvisioner {
  private device?: Device;
  private status?: Characteristic;
  private command?: Characteristic;
  private controller = new AbortController();
  private busy = false;
  private disconnected = () => {
    if (!this.controller.signal.aborted) {
      this.controller.abort();
      this.progress('Bluetooth disconnected. Reconnect to check whether Wi-Fi setup completed.');
    }
  };
  constructor(private progress: (message: string) => void) {}
  /** Call directly from a click: browsers deliberately prohibit silent discovery. */
  async choose(): Promise<{ name: string; status: ProvisionStatus }> {
    const unsupported = bluetoothSupport();
    if (unsupported) throw new Error(unsupported);
    this.close();
    this.controller = new AbortController();
    const signal = this.controller.signal;
    this.progress('Choose your Glyph in the browser’s Bluetooth picker.');
    const bluetooth = (navigator as unknown as { bluetooth: Bluetooth }).bluetooth;
    // No timeout on the browser-owned chooser. Closing this component invalidates its eventual result.
    const device = await bluetooth.requestDevice({ filters: [{ services: [BLE_SERVICE] }] });
    if (signal.aborted) throw new Error('Bluetooth setup cancelled.');
    this.device = device;
    device.addEventListener('gattserverdisconnected', this.disconnected);
    if (!device.gatt) throw new Error('Bluetooth GATT unavailable.');
    this.progress(
      'Pair securely using the private six-digit PIN on your Glyph’s USB output / kit label.',
    );
    const connecting = device.gatt.connect();
    void connecting
      .then((server) => {
        if (signal.aborted) server.disconnect();
      })
      .catch(() => {});
    const server = await bounded(connecting, signal, 60000);
    const service = await bounded(server.getPrimaryService(BLE_SERVICE), signal);
    this.status = await bounded(service.getCharacteristic(BLE_STATUS), signal);
    this.command = await bounded(service.getCharacteristic(BLE_COMMAND), signal);
    let status = parseProvisionStatus(await bounded(this.status.readValue(), signal, 60000));
    const until = Date.now() + 40000;
    while (status.state === 'queued' || status.state === 'joining') {
      if (Date.now() >= until) throw new Error('Glyph is still joining Wi-Fi. Reconnect to check.');
      this.progress('Glyph is finishing a Wi-Fi connection…');
      await bounded(new Promise<void>((resolve) => setTimeout(resolve, 800)), signal, 1500);
      if (signal.aborted) throw new Error('Bluetooth setup cancelled.');
      status = parseProvisionStatus(await bounded(this.status.readValue(), signal));
    }
    return { name: device.name || `GLYPH-${status.id.slice(6)}`, status };
  }
  async provision(ssid: string, password: string): Promise<ProvisionStatus> {
    if (!this.device?.gatt?.connected || !this.status || !this.command || this.busy)
      throw new Error('Select and pair with your Glyph first.');
    const packets = credentialPackets(ssid, password),
      signal = this.controller.signal;
    this.busy = true;
    try {
      if (signal.aborted) throw new Error('Bluetooth setup cancelled.');
      this.progress('Sending Wi-Fi settings over the paired Bluetooth connection…');
      // 20-byte writes work even at the default ATT MTU. Always use write-with-response.
      for (const packet of packets) {
        if (signal.aborted) throw new Error('Bluetooth setup cancelled.');
        await bounded(this.command.writeValueWithResponse(packet), signal);
        packet.fill(0);
      }
      const until = Date.now() + 40000;
      while (Date.now() < until) {
        if (signal.aborted) throw new Error('Bluetooth setup cancelled.');
        const status = parseProvisionStatus(await bounded(this.status.readValue(), signal));
        if (status.state === 'connected') return status;
        if (status.state === 'failed') throw new Error(status.error);
        this.progress('Glyph is joining Wi-Fi… Keep this page visible.');
        await bounded(new Promise<void>((resolve) => setTimeout(resolve, 800)), signal, 1500);
      }
      throw new Error(
        'Wi-Fi connection was not confirmed. Reconnect to check; do not assume credentials were saved.',
      );
    } catch (error) {
      this.close();
      throw error;
    } finally {
      packets.forEach((packet) => packet.fill(0));
      this.busy = false;
    }
  }
  close() {
    this.controller.abort();
    if (this.device) {
      this.device.removeEventListener('gattserverdisconnected', this.disconnected);
      this.device.gatt?.disconnect();
    }
    this.device = undefined;
    this.status = this.command = undefined;
  }
}
