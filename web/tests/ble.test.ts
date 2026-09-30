import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  BLE_SERVICE,
  BLE_STATUS,
  BleProvisioner,
  bluetoothSupport,
  credentialPackets,
  parseProvisionStatus,
} from '../src/network/BleProvisioner';

const status = {
  version: 1,
  id: 'aabbccddeeff',
  state: 'connected',
  ip: '192.168.4.2',
  port: 8080,
  path: '/audio',
};
const view = (value: unknown) =>
  new DataView(new TextEncoder().encode(JSON.stringify(value)).buffer);
afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});
describe('BLE provisioning protocol', () => {
  it('matches the Java/firmware golden frame', () => {
    expect(credentialPackets('Lab', 'testpass').map((p) => [...p])).toEqual([
      [1, 13],
      [2, 0, 3, 8, 76, 97, 98, 116, 101, 115, 116, 112, 97, 115, 115],
      [3],
    ]);
  });
  it('bounds packets at the default MTU and counts UTF-8 bytes', () => {
    const packets = credentialPackets('x'.repeat(32), 'y'.repeat(63));
    expect(packets).toHaveLength(8);
    expect(packets[0][1]).toBe(97);
    let offset = 0;
    for (const p of packets.slice(1, -1)) {
      expect(p.length).toBeLessThanOrEqual(20);
      expect(p[1]).toBe(offset);
      offset += p.length - 2;
    }
    expect(offset).toBe(97);
    expect(credentialPackets('é'.repeat(16), 'testpass')[1][2]).toBe(32);
    expect(credentialPackets(' Lab ', 'testpass')[1][2]).toBe(5);
  });
  it('rejects unsupported credentials', () => {
    for (const ssid of ['', 'x'.repeat(33), 'é'.repeat(17), 'x\0y'])
      expect(() => credentialPackets(ssid, 'testpass')).toThrow();
    for (const key of ['short', 'x'.repeat(64), 'test\npass', 'passwörd'])
      expect(() => credentialPackets('Lab', key)).toThrow();
  });
  it('validates the advertised endpoint without bypassing HTTPS audio policy', () => {
    expect(parseProvisionStatus(view(status))).toMatchObject({
      id: 'AABBCCDDEEFF',
      address: '192.168.4.2:8080',
    });
    for (const bad of [
      {},
      { ...status, ip: '8.8.8.8' },
      { ...status, version: 2 },
      { ...status, port: 80 },
      { ...status, path: '/other' },
      { ...status, id: 'bad' },
      { ...status, state: 'other' },
      'x'.repeat(225),
    ])
      expect(() => parseProvisionStatus(view(bad))).toThrow();
    expect(() => parseProvisionStatus(new DataView(new Uint8Array([255]).buffer))).toThrow();
  });
  it('gives honest unsupported-browser and insecure-origin fallbacks', () => {
    vi.stubGlobal('window', { isSecureContext: false });
    vi.stubGlobal('navigator', {});
    expect(bluetoothSupport()).toContain('HTTPS');
    vi.stubGlobal('window', { isSecureContext: true });
    expect(bluetoothSupport()).toContain('Safari');
  });
});

function mockBle(read = vi.fn(async () => view({ ...status, state: 'ready', ip: '' }))) {
  const writes: number[][] = [];
  const refs: Uint8Array[] = [];
  const command = {
    writeValueWithResponse: vi.fn(async (bytes: Uint8Array) => {
      writes.push([...bytes]);
      refs.push(bytes);
      if (bytes[0] === 3) read.mockResolvedValue(view(status));
    }),
  };
  const server = {
    connected: true,
    connect: vi.fn(),
    disconnect: vi.fn(),
    getPrimaryService: vi.fn(async () => ({
      getCharacteristic: vi.fn(async (uuid: string) =>
        uuid === BLE_STATUS ? { readValue: read } : command,
      ),
    })),
  };
  server.connect.mockResolvedValue(server);
  const device = Object.assign(new EventTarget(), {
    id: 'test-board',
    name: 'GLYPH-DDEEFF',
    gatt: server,
  });
  const requestDevice = vi.fn(async () => device);
  vi.stubGlobal('window', { isSecureContext: true });
  vi.stubGlobal('navigator', { bluetooth: { requestDevice } });
  return { server, device, requestDevice, writes, refs, command };
}
it('selects only compatible service and transfers sequentially with response, wiping buffers', async () => {
  const mock = mockBle(),
    client = new BleProvisioner(() => {});
  expect((await client.choose()).name).toBe('GLYPH-DDEEFF');
  expect(mock.requestDevice).toHaveBeenCalledWith({ filters: [{ services: [BLE_SERVICE] }] });
  expect((await client.provision('Lab', 'testpass')).address).toBe('192.168.4.2:8080');
  expect(mock.writes).toEqual(credentialPackets('Lab', 'testpass').map((p) => [...p]));
  expect(mock.refs.every((p) => p.every((byte) => byte === 0))).toBe(true);
  client.close();
  expect(mock.server.disconnect).toHaveBeenCalled();
});
it('ignores a chooser result after the screen closes', async () => {
  const mock = mockBle();
  let select!: (device: typeof mock.device) => void;
  mock.requestDevice.mockImplementation(
    () =>
      new Promise((resolve) => {
        select = resolve;
      }),
  );
  const client = new BleProvisioner(() => {}),
    pending = client.choose();
  client.close();
  select(mock.device);
  await expect(pending).rejects.toThrow('cancelled');
  expect(mock.server.connect).not.toHaveBeenCalled();
});
it('rejects disconnect during setup instead of reporting saved credentials', async () => {
  const mock = mockBle(),
    client = new BleProvisioner(() => {});
  await client.choose();
  mock.device.dispatchEvent(new Event('gattserverdisconnected'));
  await expect(client.provision('Lab', 'testpass')).rejects.toThrow('cancelled');
  expect(mock.server.disconnect).toHaveBeenCalled();
});
it('times out a stalled GATT connection and closes a late connection', async () => {
  vi.useFakeTimers();
  const mock = mockBle();
  let finish!: (value: typeof mock.server) => void;
  mock.server.connect.mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const client = new BleProvisioner(() => {}),
    pending = client.choose();
  const assertion = expect(pending).rejects.toThrow('timed out');
  await vi.advanceTimersByTimeAsync(60001);
  await assertion;
  client.close();
  finish(mock.server);
  await Promise.resolve();
  expect(mock.server.disconnect).toHaveBeenCalled();
});
