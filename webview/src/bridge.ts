import type { BridgeCommand } from './types';

type Listener = (message: unknown) => void;
const listeners = new Set<Listener>();
const pending = new Map<string, { command: string; timeout: number }>();
const clientInstanceId = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random()}`;
const ACK_TIMEOUT_MS = 8_000;

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
  const bridge = window.omnicodeSend;
  if (!bridge) {
    window.queueMicrotask(() => listeners.forEach((listener) => listener({
      type: 'command.error', requestId,
      payload: { command, message: 'IDE 桥接尚未就绪，请重新载入 OmniCode。' }
    })));
    return requestId;
  }
  const timeout = window.setTimeout(() => {
    if (!pending.delete(requestId)) return;
    listeners.forEach((listener) => listener({
      type: 'command.error', requestId,
      payload: { command, message: 'IDE 未确认此操作。请重试；若持续出现，请重新载入 OmniCode。' }
    }));
  }, ACK_TIMEOUT_MS);
  pending.set(requestId, { command, timeout });
  bridge(JSON.stringify(message));
  return requestId;
}
