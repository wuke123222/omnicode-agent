import { describe, expect, it } from 'vitest';
import type { ChatBlock, ChatEventEnvelopeV1 } from './types';
import {
  isTerminalRunEvent, isValidEventEnvelope, mergeEvent, mergeSnapshotBlocks,
  rememberBlockSequences, settleSessionBlocks, settleTurnBlocks
} from './sessionTimeline';

function event(overrides: Partial<ChatEventEnvelopeV1> = {}): ChatEventEnvelopeV1 {
  return {
    schemaVersion: 1,
    pageGeneration: 4,
    sessionId: 'session-a',
    turnId: 'turn-a',
    blockId: 'answer-a',
    sequence: 1,
    kind: 'message.assistant.delta',
    phase: 'running',
    at: '2026-09-07T00:00:00Z',
    payload: { text: 'A' },
    ...overrides
  };
}

describe('session timeline reducer', () => {
  it('rejects incomplete routing envelopes', () => {
    expect(isValidEventEnvelope(event())).toBe(true);
    expect(isValidEventEnvelope({ ...event(), sessionId: '' })).toBe(false);
    expect(isValidEventEnvelope({ ...event(), sequence: -1 })).toBe(false);
  });

  it('deduplicates and orders streamed deltas by sequence', () => {
    const first = mergeEvent([], event());
    const duplicate = mergeEvent(first, event());
    const second = mergeEvent(duplicate, event({ sequence: 2, payload: { text: 'B' } }));
    expect(duplicate).toBe(first);
    expect(second).toHaveLength(1);
    expect(second[0].text).toBe('AB');
    expect(second[0].sequence).toBe(2);
  });

  it('keeps newer live text when a stale snapshot arrives', () => {
    const live: ChatBlock = { id: 'answer-a', turnId: 'turn-a', sequence: 7, role: 'assistant', kind: 'message.assistant.delta', text: 'complete' };
    const stale: ChatBlock = { ...live, sequence: 3, text: 'partial' };
    expect(mergeSnapshotBlocks([live], [stale])).toEqual([live]);
  });

  it('settles terminal turns and stopped sessions without success masking', () => {
    const blocks: ChatBlock[] = [
      { id: 'stage', turnId: 'turn-a', role: 'system', kind: 'stage.execution', text: '', status: 'running' },
      { id: 'tool', turnId: 'turn-a', role: 'system', kind: 'tool.run', text: '', status: 'running' }
    ];
    const failed = event({ kind: 'run.failed', phase: 'failed', blockId: 'terminal' });
    const settled = settleTurnBlocks(blocks, failed);
    expect(settled.map((block) => block.status)).toEqual(['warning', 'error']);
    expect(settleSessionBlocks(blocks).every((block) => block.status === 'warning')).toBe(true);
    expect(isTerminalRunEvent(failed)).toBe(true);
  });

  it('names sequence cursors by both session and turn', () => {
    const target: Record<string, number> = {};
    rememberBlockSequences(target, [{ id: 'b', turnId: 'same', sequence: 9, role: 'assistant', kind: 'message.assistant', text: '' }], 'one');
    rememberBlockSequences(target, [{ id: 'b', turnId: 'same', sequence: 3, role: 'assistant', kind: 'message.assistant', text: '' }], 'two');
    expect(target).toEqual({ 'one:same': 9, 'two:same': 3 });
  });
});
