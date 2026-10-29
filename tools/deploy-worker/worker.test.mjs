import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

const source = (await readFile(new URL('./worker.js', import.meta.url), 'utf8'))
  .replace(/import \{ connect \} from 'cloudflare:sockets';/, '')
  .replace('export default', 'return');

function relay(chunks, { flow = true, autoAck = true, writeDelay = 0, query = '' } = {}) {
  const received = [], uploaded = [], tasks = [];
  let total = 0, controller;
  const server = {
    readyState: 1, listeners: {}, closeCode: null,
    accept() {},
    addEventListener(type, handler) { this.listeners[type] = handler; },
    send(data) {
      const copy = new Uint8Array(data).slice();
      if (received.length === 0) received.push(copy);
      else {
        received.push(copy);
        total += copy.byteLength;
        if (flow && autoAck) queueMicrotask(() => this.listeners.message({ data: `ack:${total}` }));
      }
    },
    close(code) { this.closeCode = code; this.readyState = 3; },
  };
  const tcp = {
    opened: Promise.resolve(),
    readable: new ReadableStream({ start(c) { controller = c; for (const chunk of chunks) c.enqueue(chunk); } }),
    writable: new WritableStream({ async write(data) {
      if (writeDelay) await new Promise(resolve => setTimeout(resolve, writeDelay));
      uploaded.push(new Uint8Array(data).slice());
    } }),
    close: async () => {},
  };
  class Pair { constructor() { return { 0: {}, 1: server }; } }
  class Response { constructor(body, options) { this.body = body; Object.assign(this, options); } }
  const worker = new Function('connect', 'WebSocketPair', 'Response', 'WebSocket', source)(
    () => tcp, Pair, Response, { OPEN: 1, CLOSING: 2 });
  return { server, received, uploaded, controller, tasks, async start() {
    const response = await worker.fetch(new Request(`https://relay.test/tcp-v2?target=149.154.167.50:443${flow ? '&flow=1' : ''}${query}`,
      { headers: { Upgrade: 'websocket' } }), {}, { waitUntil(p) { tasks.push(p); } });
    assert.equal(response.status, 101);
  } };
}

async function completes(promise) {
  let timer;
  try { await Promise.race([promise, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('relay stalled with acknowledged bytes')), 1000);
  })]); } finally { clearTimeout(timer); }
}

test('many short Telegram packets never consume phantom flow-window bytes', async () => {
  const chunks = Array.from({ length: 100 }, (_, i) => new Uint8Array(97 + i).fill(i));
  const r = relay(chunks, { autoAck: false });
  await r.start();
  r.controller.close();
  await completes(Promise.all(r.tasks));
  assert.deepEqual(Buffer.concat(r.received.slice(1)), Buffer.concat(chunks));
});

test('media larger than the window survives uneven TCP chunks and cumulative ACKs', async () => {
  const chunks = Array.from({ length: 90 }, (_, i) => new Uint8Array(65537 + i * 11).fill(i));
  const r = relay(chunks);
  await r.start();
  r.controller.close();
  await completes(Promise.all(r.tasks));
  assert.deepEqual(Buffer.concat(r.received.slice(1)), Buffer.concat(chunks));
});

test('upload remains ordered while downlink ACKs arrive', async () => {
  const r = relay([new Uint8Array(128)], { writeDelay: 2 });
  await r.start();
  const parts = Array.from({ length: 20 }, (_, i) => new Uint8Array(1024).fill(i));
  for (const part of parts) r.server.listeners.message({ data: part.buffer });
  await completes((async () => { while (r.uploaded.length < parts.length) await new Promise(resolve => setTimeout(resolve, 5)); })());
  assert.deepEqual(Buffer.concat(r.uploaded), Buffer.concat(parts));
  r.controller.close();
  await Promise.all(r.tasks);
});

test('destination validation accepts all Telegram networks, IPv6, NAT64 and ports', async () => {
  let connectedTarget = null;
  class Pair { constructor() { return { 0: {}, 1: { readyState: 1, accept() {}, addEventListener() {}, send() {}, close() {} } }; } }
  class TestResponse { constructor(body, options) { this.body = body; Object.assign(this, options); } }

  const makeWorker = () => new Function('connect', 'WebSocketPair', 'Response', 'WebSocket', source)(
    (addr) => {
      connectedTarget = addr;
      return {
        opened: Promise.resolve(),
        readable: new ReadableStream({ start(c) { c.close(); } }),
        writable: new WritableStream(),
        close: async () => {},
      };
    },
    Pair, TestResponse, { OPEN: 1, CLOSING: 2 }
  );

  const worker = makeWorker();

  const validTargets = [
    '149.154.167.51:443',
    '91.108.56.130:443',
    '91.108.0.1:443',       // Subnet 91.108.0.0/16
    '91.108.24.50:443',     // Subnet 91.108.0.0/16
    '91.108.38.10:8080',    // Port 8080
    '91.108.40.20:8888',    // Port 8888
    '91.105.192.100:25',    // Port 25
    '[2001:67c:4e8:f002::a]:443',
    '[2001:b28:f23e:1::1]:443', // 2001:b28:f23e:
    '[2a0a:f281::5]:443',       // 2a0a:f281:
    '[2a0a:f287::1]:8443',      // 2a0a:f287:
    '[64:ff9b::91.108.56.130]:443', // NAT64 dotted
    '[64:ff9b::5b6c:3882]:443',     // NAT64 hex (91.108.56.130)
  ];

  for (const target of validTargets) {
    connectedTarget = null;
    const res = await worker.fetch(
      new Request(`https://relay.test/tcp-v2?target=${encodeURIComponent(target)}`, { headers: { Upgrade: 'websocket' } }),
      {}, { waitUntil() {} }
    );
    assert.equal(res.status, 101, `Target ${target} should be allowed (got ${res.status})`);
    assert.ok(connectedTarget, `connect() must be called for ${target}`);
  }

  // NAT64 unwrap assertion: target was [64:ff9b::5b6c:3882]:443, connectedTarget must be 91.108.56.130
  assert.equal(connectedTarget.hostname, '91.108.56.130');
  assert.equal(connectedTarget.port, 443);

  // Negative tests
  const invalidTargets = [
    '8.8.8.8:443',          // Non-Telegram IP
    '1.1.1.1:443',
    '91.108.56.130:22',     // Disallowed port 22
    '91.108.56.130:445',    // Disallowed port 445
    'google.com:443',       // Disallowed domain
  ];

  for (const target of invalidTargets) {
    const res = await worker.fetch(
      new Request(`https://relay.test/tcp-v2?target=${encodeURIComponent(target)}`, { headers: { Upgrade: 'websocket' } }),
      {}, { waitUntil() {} }
    );
    assert.equal(res.status, 403, `Target ${target} should be rejected with 403`);
  }
});

test('adaptive parameters: window clamping and variable chunk sizes', async () => {
  const chunks = Array.from({ length: 10 }, (_, i) => new Uint8Array(65536).fill(i));
  const r = relay(chunks, { query: '&window=500000&chunk=32768' });
  await r.start();
  r.controller.close();
  await completes(Promise.all(r.tasks));
  assert.deepEqual(Buffer.concat(r.received.slice(1)), Buffer.concat(chunks));
  for (const item of r.received.slice(1)) {
    assert.ok(item.byteLength <= 32768, `chunk size should not exceed negotiated chunk, got ${item.byteLength}`);
  }
});

test('exceeding write chunk queue limit closes connection with 1008', async () => {
  const r = relay([new Uint8Array(64)], { writeDelay: 100 });
  await r.start();
  for (let i = 0; i < 1025; i++) {
    r.server.listeners.message({ data: new Uint8Array(1).buffer });
    if (r.server.closeCode) break;
  }
  assert.equal(r.server.closeCode, 1008);
  r.controller.close();
});

test('apiws monolithic transport accepts connection, defers TCP connect, and streams data', async () => {
  let connectCalled = false;
  let receivedData = [];
  let serverListener = null;

  const tcpMock = {
    opened: Promise.resolve(),
    readable: new ReadableStream({
      start(c) {
        c.enqueue(new Uint8Array([1, 2, 3, 4]));
      }
    }),
    writable: new WritableStream({
      write(chunk) {
        receivedData.push(new Uint8Array(chunk));
      }
    }),
    close: async () => {},
  };

  const serverMock = {
    readyState: 1,
    listeners: {},
    accept() {},
    addEventListener(type, handler) { this.listeners[type] = handler; },
    send(data) {},
    close() {},
  };

  class Pair { constructor() { return { 0: {}, 1: serverMock }; } }
  class TestResponse { constructor(body, options) { this.body = body; Object.assign(this, options); } }

  const worker = new Function('connect', 'WebSocketPair', 'Response', 'WebSocket', source)(
    (addr, opts) => {
      connectCalled = true;
      assert.equal(opts.allowHalfOpen, true);
      return tcpMock;
    },
    Pair, TestResponse, { OPEN: 1, CLOSING: 2 }
  );

  const res = await worker.fetch(
    new Request('https://relay.test/apiws?dst=149.154.167.50:443', { headers: { Upgrade: 'websocket' } }),
    {}, { waitUntil() {} }
  );
  assert.equal(res.status, 101);
  assert.equal(connectCalled, false, 'TCP connect must be lazy/deferred on /apiws until first message');

  // Send first message
  serverMock.listeners.message({ data: new Uint8Array([5, 6, 7, 8]).buffer });
  await new Promise(r => setTimeout(r, 20));

  assert.equal(connectCalled, true, 'TCP connect must be called upon first WS message');
  assert.deepEqual(receivedData, [new Uint8Array([5, 6, 7, 8])]);
});
