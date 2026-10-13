import type { BridgeCommand } from './types';

type Listener = (message: unknown) => void;
const listeners = new Set<Listener>();
const pending = new Map<string, {
  command: string;
  serialized: string;
  startedAt: number;
  timeout: number;
  dispatched: boolean;
}>();
const clientInstanceId = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
const ACK_TIMEOUT_MS = 8_000;
const BRIDGE_STARTUP_TIMEOUT_MS = 60_000;
const BRIDGE_RETRY_MS = 250;

window.__omnicodeReceive = (message: unknown) => {
  const parsed = typeof message === 'string' ? safeParse(message) : message;
  if (parsed && typeof parsed === 'object') {
    const envelope = parsed as { type?: string; requestId?: string };
    if ((envelope.type === 'command.accepted' || envelope.type === 'command.error') && envelope.requestId) {
      const tracked = pending.get(envelope.requestId);
      if (tracked) window.clearTimeout(tracked.timeout);
      pending.delete(envelope.requestId);
    }
  }
  listeners.forEach((listener) => listener(parsed));
};

// JBCefJSQuery's native response confirms that the host received and queued a command. Keep
// this separate from command completion: expensive IDE work may be waiting on the EDT, while
// its actual result/error continues to arrive through the ordered native event queue.
window.__omnicodeBridgeReceipt = (requestId, command, response) => {
  const tracked = pending.get(requestId);
  if (!tracked || tracked.command !== command) return;
  if (response === 'scheduled' || response === 'accepted') {
    window.__omnicodeReceive?.({
      type: 'command.accepted',
      requestId,
      payload: { command, receipt: response }
    });
  } else if (response === 'transport-error') {
    failPending(requestId, 'IDE 桥接通信失败，操作未能送达。请重试；若持续出现，请检查 IDE 日志。');
  }
};

function safeParse(value: string): unknown {
  try { return JSON.parse(value); } catch { return value; }
}

export function subscribeBridge(listener: Listener): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

export function sendCommand<T extends Record<string, unknown>>(command: string, payload = {} as T): string {
  const requestId = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
  const message: BridgeCommand<T> = {
    schemaVersion: 1,
    pageGeneration: window.__OMNICODE_PAGE_GENERATION__ ?? 0,
    clientInstanceId,
    requestId,
    command,
    payload
  };
  const serialized = JSON.stringify(message);
  pending.set(requestId, {
    command,
    serialized,
    startedAt: Date.now(),
    timeout: 0,
    dispatched: false
  });
  dispatchWhenBridgeReady(requestId);
  return requestId;
}

function dispatchWhenBridgeReady(requestId: string) {
  const tracked = pending.get(requestId);
  if (!tracked) return;
  const bridge = window.omnicodeSend;
  if (!bridge) {
    if (Date.now() - tracked.startedAt >= BRIDGE_STARTUP_TIMEOUT_MS) {
      failPending(requestId, 'IDE 桥接启动超时，请重新载入 OmniCode。');
      return;
    }
    tracked.timeout = window.setTimeout(() => dispatchWhenBridgeReady(requestId), BRIDGE_RETRY_MS);
    return;
  }

  try {
    bridge(tracked.serialized);
    tracked.dispatched = true;
  } catch {
    tracked.dispatched = false;
    if (Date.now() - tracked.startedAt >= BRIDGE_STARTUP_TIMEOUT_MS) {
      failPending(requestId, 'IDE 桥接连接失败，请重新载入 OmniCode。');
      return;
    }
    tracked.timeout = window.setTimeout(() => dispatchWhenBridgeReady(requestId), BRIDGE_RETRY_MS);
    return;
  }

  tracked.timeout = window.setTimeout(() => {
    const current = pending.get(requestId);
    if (!current) return;
    // The readiness handshake is idempotent. Remote JCEF can accept the JavaScript call before
    // its query transport is listening, so keep retrying only this safe startup command.
    if (current.command === 'frontend.ready' && Date.now() - current.startedAt < BRIDGE_STARTUP_TIMEOUT_MS) {
      current.dispatched = false;
      dispatchWhenBridgeReady(requestId);
      return;
    }
    failPending(requestId, 'IDE 未确认此操作。请重试；若持续出现，请重新载入 OmniCode。');
  }, tracked.command === 'frontend.ready' ? 1_000 : ACK_TIMEOUT_MS);
}

function failPending(requestId: string, message: string) {
  const tracked = pending.get(requestId);
  if (!tracked) return;
  window.clearTimeout(tracked.timeout);
  pending.delete(requestId);
  listeners.forEach((listener) => listener({
    type: 'command.error', requestId,
    payload: { command: tracked.command, message }
  }));
}
