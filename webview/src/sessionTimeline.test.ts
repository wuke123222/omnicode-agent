import { describe, expect, it } from 'vitest';
import type { ChatBlock, ChatEventEnvelopeV1 } from './types';
import {
  isTerminalRunEvent, isValidEventEnvelope, mergeEvent, mergeSnapshotBlocks,
  rememberBlockSequences, settleSessionBlocks, settleTerminalTurnBlocks, settleTurnBlocks
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
    expect(settled.map((block) => block.status)).toEqual(['warning', 'warning']);
    expect(settled[1].text).toContain('未报告完成状态');
    expect(settleSessionBlocks(blocks).every((block) => block.status === 'warning')).toBe(true);
    expect(isTerminalRunEvent(failed)).toBe(true);
  });

  it('never promotes an unfinished tool to success just because its turn completed', () => {
    const blocks: ChatBlock[] = [
      { id: 'stage', turnId: 'turn-a', role: 'system', kind: 'stage.execution', text: '', status: 'running' },
      { id: 'tool', turnId: 'turn-a', role: 'system', kind: 'tool.started', text: '', status: 'running' }
    ];
    const terminal = event({ kind: 'run.completed', phase: 'completed', blockId: 'terminal' });
    const settled = settleTurnBlocks(blocks, terminal);
    expect(settled.map((block) => block.status)).toEqual(['success', 'warning']);
    expect(settled[1].text).toContain('未报告完成状态');
  });

  it('restores stale running stages from their own terminal turn without inventing a failure', () => {
    const blocks: ChatBlock[] = [
      { id: 'old-stage', turnId: 'turn-ok', role: 'system', kind: 'stage.context', title: 'Context', text: '', status: 'running' },
      { id: 'old-terminal', turnId: 'turn-ok', role: 'system', kind: 'run.completed', title: '完成', text: '', status: 'success' },
      { id: 'failed-stage', turnId: 'turn-failed', role: 'system', kind: 'stage.execution', title: 'Execution', text: '', status: 'running' },
      { id: 'failed-tool', turnId: 'turn-failed', role: 'system', kind: 'tool.run', title: 'Run command', text: '', status: 'running' },
      { id: 'failed-terminal', turnId: 'turn-failed', role: 'system', kind: 'run.failed', title: '失败', text: '', status: 'error' },
      { id: 'orphan', turnId: 'turn-orphan', role: 'system', kind: 'stage.context', title: 'Context', text: '', status: 'running' },
      { id: 'legacy-stage', turnId: 'turn-legacy', role: 'system', kind: 'stage.context', title: 'Context', text: '', status: 'running' },
      { id: 'legacy-terminal', turnId: 'turn-legacy', role: 'system', kind: 'run', phase: 'completed', title: '完成', text: '', status: 'success' }
    ];
    const restored = settleSessionBlocks(blocks);
    expect(restored.map((block) => block.status)).toEqual(['success', 'success', 'warning', 'warning', 'error', 'warning', 'success', 'success']);
    expect(restored[0].text).toBe('');
    expect(restored[5].text).toContain('任务已停止');
  });

  it('settles older completed turns without stopping the currently active turn', () => {
    const blocks: ChatBlock[] = [
      { id: 'old-stage', turnId: 'old', role: 'system', kind: 'stage.context', title: 'Context', text: '', status: 'running' },
      { id: 'old-terminal', turnId: 'old', role: 'system', kind: 'run.completed', title: '完成', text: '', status: 'success' },
      { id: 'new-stage', turnId: 'current', role: 'system', kind: 'stage.execution', title: 'Execution', text: '', status: 'running' }
    ];
    expect(settleTerminalTurnBlocks(blocks).map((block) => block.status)).toEqual(['success', 'success', 'running']);
  });

  it('names sequence cursors by both session and turn', () => {
    const target: Record<string, number> = {};
    rememberBlockSequences(target, [{ id: 'b', turnId: 'same', sequence: 9, role: 'assistant', kind: 'message.assistant', text: '' }], 'one');
    rememberBlockSequences(target, [{ id: 'b', turnId: 'same', sequence: 3, role: 'assistant', kind: 'message.assistant', text: '' }], 'two');
    expect(target).toEqual({ 'one:same': 9, 'two:same': 3 });
  });
});
