import type { ChatBlock, ChatEventEnvelopeV1 } from './types';

export function isPrivateReasoningKind(kind: string): boolean {
  return /(^|[._-])(reasoning|thinking|analysis)([._-]|$)/i.test(kind);
}

/** Validate every routing and ordering field before a native bridge event reaches React state. */
export function isValidEventEnvelope(event: Partial<ChatEventEnvelopeV1>): event is ChatEventEnvelopeV1 {
  return event.schemaVersion === 1
    && typeof event.pageGeneration === 'number'
    && Number.isFinite(event.pageGeneration)
    && typeof event.sessionId === 'string' && event.sessionId.length > 0 && event.sessionId.length <= 256
    && typeof event.turnId === 'string' && event.turnId.length > 0 && event.turnId.length <= 256
    && typeof event.blockId === 'string' && event.blockId.length > 0 && event.blockId.length <= 512
    && typeof event.kind === 'string' && event.kind.length > 0 && !isPrivateReasoningKind(event.kind)
    && typeof event.phase === 'string' && event.phase.length > 0
    && typeof event.sequence === 'number' && Number.isFinite(event.sequence) && event.sequence >= 0
    && !!event.payload && typeof event.payload === 'object';
}

function readableKind(kind: string): string {
  if (kind.startsWith('stage.')) return '执行阶段';
  if (kind.startsWith('tool.')) return '工具调用';
  if (kind.startsWith('agent.')) return '子代理';
  if (kind.startsWith('context.')) return '项目上下文';
  if (kind.startsWith('run.')) return '任务状态';
  return '运行状态';
}

function eventToBlock(event: ChatEventEnvelopeV1): ChatBlock | null {
  // A provider must never turn private reasoning into an ordinary chat block.
  // The native mapper omits it too; this is a second boundary for restored/live events.
  if (isPrivateReasoningKind(event.kind)) return null;
  const text = String(event.payload.text ?? event.payload.message ?? event.payload.detail ?? '');
  const title = String(event.payload.title ?? event.payload.name ?? event.payload.stage ?? '');
  if (event.kind === 'usage.updated') return null;
  if (event.kind.startsWith('message.user')) {
    return { id: event.blockId, turnId: event.turnId, sequence: event.sequence, role: 'user', kind: event.kind, text, metadata: event.payload };
  }
  if (event.kind.startsWith('message.assistant')) {
    return { id: event.blockId, turnId: event.turnId, sequence: event.sequence, role: 'assistant', kind: event.kind, text, metadata: event.payload };
  }
  const status: ChatBlock['status'] = event.phase === 'failed' ? 'error'
    : event.phase === 'completed' ? 'success'
      : event.phase === 'warning' ? 'warning' : 'running';
  return {
    id: event.blockId,
    turnId: event.turnId,
    sequence: event.sequence,
    role: 'system',
    kind: event.kind,
    phase: event.phase,
    text,
    title: title || readableKind(event.kind),
    status,
    metadata: event.payload
  };
}

/** Non-streaming changes presentation only; the full live timeline stays available for recovery. */
export function visibleTranscriptBlocks(blocks: ChatBlock[], running: boolean, streamingOutput: boolean): ChatBlock[] {
  const publicBlocks = blocks.filter((block) => !isPrivateReasoningKind(block.kind));
  if (!running || streamingOutput) return publicBlocks;
  const latestTurn = [...publicBlocks].reverse().find((block) => block.turnId);
  if (!latestTurn || isTerminalRunEvent({ kind: latestTurn.kind, phase: latestTurn.phase ?? '' })) return publicBlocks;
  return publicBlocks.filter((block) => block.role !== 'assistant' || block.turnId !== latestTurn.turnId);
}

export function isInternalActivity(block: ChatBlock): boolean {
  if (block.role !== 'system' || block.status === 'error' || block.status === 'warning') return false;
  return block.kind === 'status'
    || block.kind.startsWith('stage.')
    || block.kind.startsWith('provider.')
    || block.kind.startsWith('context.')
    || block.kind === 'run.mode'
    || block.kind === 'run.strategy';
}

export function settleTurnBlocks(blocks: ChatBlock[], event: ChatEventEnvelopeV1): ChatBlock[] {
  return blocks.map((block) => {
    if (block.turnId !== event.turnId || block.status !== 'running') return block;
    const routineStatus: ChatBlock['status'] = event.phase === 'completed' ? 'success' : 'warning';
    return isInternalActivity(block)
      ? { ...block, phase: event.phase, status: routineStatus }
      : { ...block, phase: 'warning', status: 'warning', text: block.text || '此操作未报告完成状态。' };
  });
}

/** Reconcile old running cards for turns that already have a durable terminal result. */
export function settleTerminalTurnBlocks(blocks: ChatBlock[]): ChatBlock[] {
  const terminalByTurn = new Map<string, ChatBlock>();
  blocks.forEach((block) => {
    if (block.turnId && isTerminalRunEvent({ kind: block.kind, phase: block.phase ?? '' })) {
      terminalByTurn.set(block.turnId, block);
    }
  });
  return blocks.map((block) => {
    if (block.status !== 'running') return block;
    const terminal = block.turnId ? terminalByTurn.get(block.turnId) : undefined;
    if (!terminal) return block;
    const completed = (terminal.phase === 'completed' || terminal.kind.endsWith('.completed')) && terminal.status !== 'error' && terminal.status !== 'warning';
    if (!isInternalActivity(block)) return { ...block, phase: 'warning', status: 'warning', text: block.text || '此操作未报告完成状态。' };
    return { ...block, phase: completed ? 'completed' : terminal.phase || 'warning', status: completed ? 'success' : 'warning' };
  });
}

/** Settle orphaned activity cards when the host reports an authoritative stopped session. */
export function settleSessionBlocks(blocks: ChatBlock[]): ChatBlock[] {
  return settleTerminalTurnBlocks(blocks).map((block) => block.status === 'running'
    ? { ...block, phase: 'warning', status: 'warning', text: block.text || '任务已停止；可从最近恢复点继续。' }
    : block);
}

/** Merge one live envelope without accepting duplicated or out-of-order deltas. */
export function mergeEvent(blocks: ChatBlock[], event: ChatEventEnvelopeV1): ChatBlock[] {
  const next = eventToBlock(event);
  if (!next) return blocks;
  const index = blocks.findIndex((block) => block.id === next.id);
  if (index < 0) return [...blocks, next];
  const current = blocks[index];
  if (next.sequence != null && current.sequence != null && next.sequence <= current.sequence) return blocks;
  const append = event.kind.endsWith('.delta');
  const merged = { ...current, ...next, text: append ? `${current.text}${next.text}` : next.text || current.text };
  return [...blocks.slice(0, index), merged, ...blocks.slice(index + 1)];
}

function snapshotIdentity(block: ChatBlock): string | null {
  if (block.role !== 'system') return null;
  const kind = block.kind.replace(/\.(started|completed|requested|retry|approval|delta)$/, '');
  const title = (block.title ?? '').trim().replace(/\s+/g, ' ');
  if (!kind || !title) return null;
  return `${kind}:${title}`;
}

/** Reconcile a durable snapshot without erasing newer streamed blocks already seen by the page. */
export function mergeSnapshotBlocks(current: ChatBlock[], incoming: ChatBlock[]): ChatBlock[] {
  if (!current.length) return incoming;
  if (!incoming.length) return current;
  const currentById = new Map(current.map((block) => [block.id, block]));
  const merged = incoming.map((block) => {
    const live = currentById.get(block.id);
    if (live && live.sequence != null && block.sequence == null) return live;
    if (live && live.sequence != null && block.sequence != null && live.sequence > block.sequence) return live;
    return block;
  }).filter((block) => {
    const identity = snapshotIdentity(block);
    if (!identity) return true;
    return !current.some((live) => live.id !== block.id && snapshotIdentity(live) === identity);
  });
  const seen = new Set(merged.map((block) => block.id));
  current.forEach((block) => { if (!seen.has(block.id)) merged.push(block); });
  return merged;
}

export function sequenceKey(sessionId: string, turnId: string): string {
  return `${sessionId}:${turnId}`;
}

export function rememberBlockSequences(target: Record<string, number>, blocks: ChatBlock[], sessionId = '') {
  blocks.forEach((block) => {
    if (!block.turnId || block.sequence == null || block.sequence <= 0) return;
    const key = sequenceKey(sessionId, block.turnId);
    target[key] = Math.max(target[key] ?? 0, block.sequence);
  });
}

const TERMINAL_RUN_EVENT_KINDS = new Set([
  'run.completed', 'run.failed', 'run.cancelled', 'run.canceled', 'run.stopped', 'run.aborted',
  'turn.completed', 'turn.failed', 'turn.cancelled', 'turn.canceled', 'turn.stopped',
]);

export function isTerminalRunEvent(event: Pick<ChatEventEnvelopeV1, 'kind' | 'phase'>): boolean {
  if (TERMINAL_RUN_EVENT_KINDS.has(event.kind)) return true;
  return event.kind === 'run' && ['completed', 'failed', 'cancelled', 'canceled', 'stopped', 'aborted'].includes(event.phase);
}
