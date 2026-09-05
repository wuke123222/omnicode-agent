import { Component, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ErrorInfo, ReactNode, UIEvent } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  AlertTriangle, Archive, Bot, Check, ChevronDown, ChevronRight, Circle,
  Clock3, Code2, Copy, FileCode2, FileDiff, FilePlus2, FolderOpen,
  Gauge, GitBranch, History, Image, ListChecks, LoaderCircle, MessageSquare,
  Paperclip, Play, Plus, RotateCcw, Search, Send, Settings, ShieldCheck,
  Sparkles, Square, Trash2, Users, Wrench, X
} from 'lucide-react';
import { sendCommand, subscribeBridge } from './bridge';
import type {
  BootstrapPayload, ChatBlock, ChatEventEnvelopeV1, HistoryEntry,
  McpCatalogEntryView, McpServerView, PlanProposal, PromptTemplateView, RootView,
  RunMode, RunStrategy, RuntimeStatusView, SettingsSnapshot, SkillSourceView
} from './types';

type IncomingMessage = {
  type?: string;
  requestId?: string;
  success?: boolean;
  payload?: unknown;
  error?: string;
};

type AttachmentDraft = {
  id: string;
  fileName: string;
  mediaType: string;
  byteSize: number;
  kind: 'image' | 'markdown' | 'text';
  content: string;
  localPath?: string;
};

function ComposerChoice({
  className, title, value, options, disabled, onChange
}: {
  className: string;
  title: string;
  value: string;
  options: Array<{ value: string; label: string }>;
  disabled?: boolean;
  onChange: (value: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const root = useRef<HTMLDivElement>(null);
  const selected = options.find((item) => item.value === value) ?? options[0];
  useEffect(() => {
    if (!open) return;
    const close = (event: MouseEvent) => {
      if (root.current && !root.current.contains(event.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', close);
    return () => document.removeEventListener('mousedown', close);
  }, [open]);
  return <div ref={root} className={`composer-choice ${className}`}>
    <button
      type="button"
      className="composer-choice-trigger"
      title={title}
      aria-label={title}
      aria-haspopup="listbox"
      aria-expanded={open}
      disabled={disabled}
      onClick={() => setOpen((current) => !current)}
      onKeyDown={(event) => {
        if (event.key === 'Escape') setOpen(false);
        if (event.key === 'ArrowDown' && !open) { event.preventDefault(); setOpen(true); }
      }}
    >
      <span>{selected?.label ?? value}</span><ChevronDown size={15} />
    </button>
    {open && <div className="composer-choice-menu" role="listbox" aria-label={title}>
      {options.map((item) => <button
        type="button"
        role="option"
        aria-selected={item.value === value}
        className={item.value === value ? 'selected' : ''}
        key={item.value}
        onClick={() => { onChange(item.value); setOpen(false); }}
      >{item.label}</button>)}
    </div>}
  </div>;
}

type McpTestState = {
  id: string;
  state: 'idle' | 'running' | 'success' | 'error';
  message: string;
  tools?: string[];
};

type McpAuthState = {
  id: string;
  state: 'idle' | 'running' | 'success' | 'error';
  message: string;
};

type McpDraftSelection = {
  id: string;
  warnings: string[];
  revision: number;
};

type McpCatalogState = {
  state: 'idle' | 'loading' | 'ready' | 'offline' | 'error';
  total: number;
  shown: number;
  notice: string;
  fromCache: boolean;
};

type DiagnosticsCheck = {
  id: string;
  category: string;
  title: string;
  status: 'PASS' | 'WARN' | 'FAIL' | 'SKIP';
  summary: string;
  durationMillis: number;
  recoverySuggestion?: string;
};

type DiagnosticsState = {
  state: 'running' | 'success' | 'error';
  overallStatus: string;
  durationMillis?: number;
  passCount?: number;
  warnCount?: number;
  failCount?: number;
  skipCount?: number;
  message?: string;
  checks: DiagnosticsCheck[];
};

type TokenTrackerView = {
  state: 'READY' | 'NOT_RUNNING' | 'UNVERIFIED_SERVICE' | 'ERROR';
  detail: string;
  cliInstalled: boolean;
  dashboardUrl: string;
  installCommand: string;
  documentationUrl: string;
};

type ChangeReview = {
  sessionId?: string;
  workflowId: string;
  files: Array<{
    path: string;
    decision: 'PENDING' | 'KEPT' | 'ROLLED_BACK' | 'MIXED';
    added: number;
    removed: number;
    hunks: Array<{ id: string; beforeStart: number; afterStart: number; before: string; after: string; decision: string }>;
  }>;
};

type UiErrorBoundaryProps = { children: ReactNode };
type UiErrorBoundaryState = { error: string };

export class UiErrorBoundary extends Component<UiErrorBoundaryProps, UiErrorBoundaryState> {
  state: UiErrorBoundaryState = { error: '' };

  static getDerivedStateFromError(error: unknown): UiErrorBoundaryState {
    return { error: error instanceof Error ? error.message.slice(0, 500) : '界面组件发生未知错误' };
  }

  componentDidCatch(error: unknown, info: ErrorInfo) {
    sendCommand('ui.notify', {
      message: `界面渲染失败：${error instanceof Error ? error.message : String(error)}${info.componentStack ? '（已隔离）' : ''}`.slice(0, 500)
    });
  }

  render() {
    if (!this.state.error) return this.props.children;
    return <main className="ui-failure" role="alert">
      <AlertTriangle />
      <h1>界面没有正常完成渲染</h1>
      <p>{this.state.error}</p>
      <div>
        <button className="primary" onClick={() => window.location.reload()}><RotateCcw />重新载入界面</button>
        <button onClick={() => navigator.clipboard?.writeText(this.state.error)}><Copy />复制错误</button>
      </div>
    </main>;
  }
}

const EMPTY_SETTINGS: SettingsSnapshot = {
  provider: {
    id: 'openai', baseUrl: '', model: '', reasoningEffort: 'auto',
    maxOutputTokens: 8192, credentialConfigured: false
  },
  providers: [],
  platform: {
    sandboxMode: 'WORKSPACE_WRITE', historyEnabled: true, historyRetention: 100,
    usageRetentionDays: 365, mcpCount: 0, skillCount: 0, promptCount: 0,
    commitAiEnabled: true, agentContinuousExecution: true, providerMaxAttempts: 3
  },
  theme: {
    id: 'jetbrains-native', petEnabled: false, petId: 'pixel-cat', petX: 8800, petY: 7600,
    palette: {
      background: '#151719', surface: '#202327', elevatedSurface: '#1b1d20', primaryText: '#e7e9ed',
      secondaryText: '#979da7', accent: '#6ea2ff', border: '#34383e', success: '#5dcc83',
      warning: '#d9a14a', error: '#ee6b72'
    }
  },
  themes: [], pets: [], projectContext: { pinnedPaths: [], excludedPaths: [] },
  mcpServers: [], prompts: [], skills: []
};

const SETTINGS_TABS = [
  ['basic', '常规'], ['providers', '供应商'], ['dependencies', '依赖'],
  ['usage', '用量'], ['permissions', '权限'], ['enhancer', '提示增强'],
  ['commit', 'Commit'], ['mcp', 'MCP'], ['agents', 'Agents'],
  ['prompts', '提示词'], ['skills', 'Skills'], ['pet', '桌宠'],
  ['other', '其他'], ['community', '社区']
] as const;

const ENGINES = [
  ['claude', 'Claude Code'], ['codex', 'Codex'], ['grok', 'Grok CLI'], ['kimi', 'Kimi CLI'],
  ['opencode', 'OpenCode'], ['pi', 'Pi CLI'], ['omp', 'OMP CLI'], ['dsh', 'DSH']
] as const;

const SLASH_COMMANDS = [
  { value: '/agent ', label: 'Agent 执行', detail: '允许按当前权限修改项目' },
  { value: '/plan ', label: 'Plan 规划', detail: '只读探索，生成计划后审批' },
  { value: '/claude-plan ', label: 'Claude Plan', detail: 'Claude Code 风格只读规划' },
  { value: '/review ', label: '审阅', detail: '让模型只读审阅代码与变更' }
];

const REASONING_LEVELS = [
  ['auto', '推理 · 自动'], ['low', '推理 · Low'], ['medium', '推理 · Medium'],
  ['high', '推理 · High'], ['max', '推理 · Max']
] as const;

function parseIncoming(raw: unknown): IncomingMessage | null {
  if (!raw) return null;
  if (typeof raw === 'string') {
    try { return JSON.parse(raw) as IncomingMessage; } catch { return null; }
  }
  return typeof raw === 'object' ? raw as IncomingMessage : null;
}

function isValidEventEnvelope(event: Partial<ChatEventEnvelopeV1>): event is ChatEventEnvelopeV1 {
  return event.schemaVersion === 1
    && typeof event.pageGeneration === 'number'
    && Number.isFinite(event.pageGeneration)
    && typeof event.sessionId === 'string' && event.sessionId.length > 0 && event.sessionId.length <= 256
    && typeof event.turnId === 'string' && event.turnId.length > 0 && event.turnId.length <= 256
    && typeof event.blockId === 'string' && event.blockId.length > 0 && event.blockId.length <= 512
    && typeof event.kind === 'string' && event.kind.length > 0
    && typeof event.phase === 'string' && event.phase.length > 0
    && typeof event.sequence === 'number' && Number.isFinite(event.sequence) && event.sequence >= 0
    && !!event.payload && typeof event.payload === 'object';
}

function eventToBlock(event: ChatEventEnvelopeV1): ChatBlock | null {
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

function isInternalActivity(block: ChatBlock): boolean {
  if (block.role !== 'system' || block.status === 'error' || block.status === 'warning') return false;
  return block.kind === 'status'
    || block.kind.startsWith('stage.')
    || block.kind.startsWith('provider.')
    || block.kind.startsWith('context.')
    || block.kind === 'run.mode'
    || block.kind === 'run.strategy';
}

function settleTurnBlocks(blocks: ChatBlock[], event: ChatEventEnvelopeV1): ChatBlock[] {
  const terminalStatus: ChatBlock['status'] = event.phase === 'failed' ? 'error'
    : event.phase === 'warning' ? 'warning' : 'success';
  return blocks.map((block) => {
    if (block.turnId !== event.turnId || block.status !== 'running') return block;
    // Keep the activity timeline complete after a turn finishes. Routine stages are still
    // compact cards, but a failed/cancelled turn must not paint unfinished stages as success.
    const routineStatus: ChatBlock['status'] = event.phase === 'completed' ? 'success' : 'warning';
    return isInternalActivity(block)
      ? { ...block, phase: event.phase, status: routineStatus }
      : { ...block, phase: event.phase, status: terminalStatus };
  });
}

/**
 * `running=false` is an authoritative lifecycle signal from the IDE service.  It is also the
 * recovery path for an old/plugin-reload run whose terminal envelope was lost while the WebView
 * was being recreated.  Never leave those orphaned activity cards spinning forever.
 */
function settleSessionBlocks(blocks: ChatBlock[]): ChatBlock[] {
  return blocks.map((block) => block.status === 'running'
    ? { ...block, phase: 'warning', status: 'warning', text: block.text || '任务已停止；可从最近恢复点继续。' }
    : block);
}

function mergeEvent(blocks: ChatBlock[], event: ChatEventEnvelopeV1): ChatBlock[] {
  const next = eventToBlock(event);
  if (!next) return blocks;
  const index = blocks.findIndex((block) => block.id === next.id);
  if (index < 0) return [...blocks, next];
  const current = blocks[index];
  // A retry/reconnect can deliver the same envelope more than once. Never append an older
  // delta (or let a stale terminal update replace a newer block) just because its block id is
  // stable. Turn-level ordering is checked by the bridge consumer below as well.
  if (next.sequence != null && current.sequence != null && next.sequence <= current.sequence) return blocks;
  const append = event.kind.endsWith('.delta');
  const merged = {
    ...current,
    ...next,
    text: append ? `${current.text}${next.text}` : next.text || current.text
  };
  return [...blocks.slice(0, index), merged, ...blocks.slice(index + 1)];
}

function mergeSnapshotBlocks(current: ChatBlock[], incoming: ChatBlock[]): ChatBlock[] {
  if (!current.length) return incoming;
  if (!incoming.length) return current;
  const currentById = new Map(current.map((block) => [block.id, block]));
  const merged = incoming.map((block) => {
    const live = currentById.get(block.id);
    // Persisted timelines do not carry live sequence numbers. Prefer an observed live block so
    // a reconnect cannot replace streamed text with a stale snapshot.
    if (live && live.sequence != null && block.sequence == null) return live;
    if (live && live.sequence != null && block.sequence != null && live.sequence > block.sequence) return live;
    return block;
  }).filter((block) => {
    // Workflow snapshots use durable event ids while the live mapper uses stable stage/tool
    // ids. Match their human-facing identity as a second line of defence so reconnecting a
    // running session does not duplicate every stage card in the transcript.
    const identity = snapshotIdentity(block);
    if (!identity) return true;
    return !current.some((live) => live.id !== block.id && snapshotIdentity(live) === identity);
  });
  const seen = new Set(merged.map((block) => block.id));
  current.forEach((block) => { if (!seen.has(block.id)) merged.push(block); });
  return merged;
}

function snapshotIdentity(block: ChatBlock): string | null {
  if (block.role !== 'system') return null;
  // Durable workflow snapshots use a fresh event id while live envelopes use stable ids.
  // Normalize every lifecycle spelling (requested/completed/retry as well as started/delta)
  // before comparing, otherwise a provider request appears twice and both cards can spin.
  const kind = block.kind.replace(/\.(started|completed|requested|retry|approval|delta)$/, '');
  const title = (block.title ?? '').trim().replace(/\s+/g, ' ');
  if (!kind || !title) return null;
  return `${kind}:${title}`;
}

function rememberBlockSequences(target: Record<string, number>, blocks: ChatBlock[], sessionId = '') {
  blocks.forEach((block) => {
    if (!block.turnId || block.sequence == null || block.sequence <= 0) return;
    const key = sequenceKey(sessionId, block.turnId);
    target[key] = Math.max(target[key] ?? 0, block.sequence);
  });
}

function sequenceKey(sessionId: string, turnId: string): string {
  // Turn ids are normally UUIDs, but keeping the session namespace prevents one detached
  // conversation from suppressing a coincidentally reused turn id in another conversation.
  return `${sessionId}:${turnId}`;
}

/**
 * A terminal event is a lifecycle boundary, not just the successful `run.completed` envelope.
 * Providers and older hosts can report failed/cancelled/stopped turns with a more specific kind;
 * treating those as terminal prevents a spinner from surviving a failed CLI process.
 */
const TERMINAL_RUN_EVENT_KINDS = new Set([
  'run.completed', 'run.failed', 'run.cancelled', 'run.canceled', 'run.stopped', 'run.aborted',
  'turn.completed', 'turn.failed', 'turn.cancelled', 'turn.canceled', 'turn.stopped',
]);

function isTerminalRunEvent(event: Pick<ChatEventEnvelopeV1, 'kind' | 'phase'>): boolean {
  if (TERMINAL_RUN_EVENT_KINDS.has(event.kind)) return true;
  return event.kind === 'run' && ['completed', 'failed', 'cancelled', 'canceled', 'stopped', 'aborted'].includes(event.phase);
}

function readableKind(kind: string): string {
  if (kind.startsWith('stage.')) return '执行阶段';
  if (kind.startsWith('tool.')) return '工具调用';
  if (kind.startsWith('agent.')) return '子代理';
  if (kind.startsWith('context.')) return '项目上下文';
  if (kind.startsWith('run.')) return '任务状态';
  return '运行状态';
}

function fileLinkMarkdown(text: string): string {
  return text.replace(
    /(?<![\w/])((?:[A-Za-z]:[\\/]|\.\.\/?|\.?\/?)[\w@.+~()\-\\/]+\.[A-Za-z0-9_-]+):(\d+)(?:-(\d+))?/g,
    (_, path: string, line: string, end?: string) => {
      const label = `${path}:${line}${end ? `-${end}` : ''}`;
      return `[${label}](omnicode-file://open?path=${encodeURIComponent(path)}&line=${line}${end ? `&end=${end}` : ''})`;
    }
  );
}

function Markdown({ text }: { text: string }) {
  return (
    <ReactMarkdown
      remarkPlugins={[remarkGfm]}
      components={{
        a: ({ href = '', children }) => {
          if (href.startsWith('omnicode-file://')) {
            return <button className="file-link" onClick={() => {
              const url = new URL(href);
              sendCommand('navigation.openFile', {
                path: url.searchParams.get('path') ?? '',
                line: Number(url.searchParams.get('line') ?? 1),
                end: Number(url.searchParams.get('end') ?? url.searchParams.get('line') ?? 1)
              });
            }}>{children}</button>;
          }
          return <a href={href} onClick={(event) => {
            event.preventDefault();
            sendCommand('navigation.openExternal', { url: href });
          }}>{children}</a>;
        },
        code: ({ children, className }) => <code className={className}>{children}</code>
      }}
    >{fileLinkMarkdown(text)}</ReactMarkdown>
  );
}

function StatusIcon({ block }: { block: ChatBlock }) {
  if (block.status === 'error') return <AlertTriangle size={16} />;
  if (block.status === 'success') return <Check size={16} />;
  if (block.status === 'warning') return <AlertTriangle size={16} />;
  return <LoaderCircle className="spin" size={16} />;
}

function SystemBlock({ block }: { block: ChatBlock }) {
  const [expanded, setExpanded] = useState(block.status === 'error');
  useEffect(() => {
    // Errors can arrive after the card was first rendered as a running status. Keep the
    // actionable detail visible instead of leaving the user with an apparently stuck spinner.
    if (block.status === 'error' || block.status === 'warning') setExpanded(true);
  }, [block.status]);
  const isTool = block.kind.startsWith('tool.');
  const isAgent = block.kind.startsWith('agent.');
  const Icon = isTool ? Wrench : isAgent ? Bot : block.kind.startsWith('context.') ? FolderOpen : ListChecks;
  const backend = isAgent ? String(block.metadata?.backend ?? '') : '';
  const diagnosticEligible = (block.status === 'error' || block.status === 'warning') &&
    /mcp|连接|模型|cli|超时|tls|握手|运行时|api|oauth|初始化/i.test(`${block.title ?? ''} ${block.text}`);
  const mcpIssue = diagnosticEligible && /mcp/i.test(`${block.title ?? ''} ${block.text}`);
  const modelIssue = diagnosticEligible && /模型|api|供应商|key|密钥/i.test(`${block.title ?? ''} ${block.text}`);
  return (
    <section className={`event-card ${block.status ?? ''}`}>
      <button className="event-summary" onClick={() => setExpanded(!expanded)}>
        {expanded ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
        <Icon size={16} />
        <strong>{block.title}</strong>
        {backend && <small className="event-backend">{backend}</small>}
        <span className="event-spacer" />
        <StatusIcon block={block} />
      </button>
      {expanded && block.text && <div className="event-detail"><Markdown text={block.text} /></div>}
      {expanded && diagnosticEligible && <div className="event-actions">
        <button onClick={() => sendCommand('connection.diagnose', {})}><Gauge size={14} />连接诊断</button>
        <button onClick={() => sendCommand('navigation.view', { view: 'settings' })}><Settings size={14} />打开设置</button>
        {modelIssue && <button onClick={() => sendCommand('provider.models', {})}><RotateCcw size={14} />重新加载模型</button>}
        {mcpIssue && <button onClick={() => { sendCommand('navigation.view', { view: 'settings' }); sendCommand('mcp.catalog', { query: '', forceRefresh: false }); }}><Wrench size={14} />打开 MCP 市场</button>}
      </div>}
    </section>
  );
}

function MessageBlock({ block }: { block: ChatBlock }) {
  if (block.role === 'system') return <SystemBlock block={block} />;
  return (
    <article className={`message ${block.role}`}>
      <div className="message-avatar">{block.role === 'user' ? '你' : <Sparkles size={17} />}</div>
      <div className="message-content">
        <div className="message-label">{block.role === 'user' ? '你' : 'OmniCode'}</div>
        <Markdown text={block.text} />
        {block.role === 'assistant' && block.text && (
          <button className="message-action" title="复制" onClick={() => navigator.clipboard?.writeText(block.text)}>
            <Copy size={14} />
          </button>
        )}
      </div>
    </article>
  );
}

function Transcript({ blocks, running }: { blocks: ChatBlock[]; running: boolean }) {
  const parentRef = useRef<HTMLDivElement>(null);
  const stickToBottomRef = useRef(true);
  const [showJumpToBottom, setShowJumpToBottom] = useState(false);
  // Activity is part of the conversation, not a hidden implementation drawer. Keeping the
  // same ordered list for messages and stage/tool cards makes a turn understandable at a
  // glance and matches the CCGUI interaction model. The dock below remains an optional
  // filtered detail view for Tasks / Agents / Edits.
  const visibleBlocks = useMemo(() => blocks, [blocks]);
  const activeProgress = useMemo(() => [...blocks].reverse().find((block) => (
    isInternalActivity(block) && block.status === 'running'
  )), [blocks]);
  const progressLabel = (activeProgress?.text || activeProgress?.title || '正在处理').replace(/…+$/, '');
  const activityRunning = visibleBlocks.some((block) => block.role === 'system' && block.status === 'running');
  const showThinking = running && !activityRunning;
  const rowVirtualizer = useVirtualizer({
    count: visibleBlocks.length,
    getScrollElement: () => parentRef.current,
    estimateSize: (index) => visibleBlocks[index]?.role === 'system' ? 54 : 140,
    overscan: 8
  });

  const latestVisibleText = visibleBlocks[visibleBlocks.length - 1]?.text ?? '';
  useEffect(() => {
    if (!visibleBlocks.length || !stickToBottomRef.current) return;
    requestAnimationFrame(() => {
      const element = parentRef.current;
      if (!element) return;
      if (visibleBlocks.length > 80) rowVirtualizer.scrollToIndex(visibleBlocks.length - 1, { align: 'end' });
      else if (typeof element.scrollTo === 'function') element.scrollTo({ top: element.scrollHeight, behavior: 'auto' });
      else element.scrollTop = element.scrollHeight;
    });
  }, [visibleBlocks.length, latestVisibleText, running, rowVirtualizer]);

  const onScroll = (event: UIEvent<HTMLElement>) => {
    const element = event.currentTarget;
    const atBottom = element.scrollHeight - element.scrollTop - element.clientHeight < 96;
    stickToBottomRef.current = atBottom;
    setShowJumpToBottom(!atBottom && running);
  };

  const jumpToBottom = () => {
    const element = parentRef.current;
    if (!element) return;
    stickToBottomRef.current = true;
    setShowJumpToBottom(false);
    if (visibleBlocks.length > 80) rowVirtualizer.scrollToIndex(visibleBlocks.length - 1, { align: 'end' });
    else element.scrollTo?.({ top: element.scrollHeight, behavior: 'smooth' });
  };

  if (!visibleBlocks.length && !running) {
    return (
      <main className="empty-chat">
        <div className="brand-orb"><Sparkles size={30} /></div>
        <h1>今天想构建什么？</h1>
        <p>选择模型，引用项目文件，或直接描述任务。</p>
        <div className="quick-grid">
          <button onClick={() => sendCommand('composer.prefill', { text: '请快速了解这个项目的结构、技术栈和关键入口。' })}><Search />了解项目</button>
          <button onClick={() => sendCommand('composer.prefill', { text: '/plan ' })}><ListChecks />制定计划</button>
          <button onClick={() => sendCommand('composer.prefill', { text: '/review ' })}><FileDiff />审阅变更</button>
        </div>
      </main>
    );
  }

  // Short conversations are rendered directly. Besides avoiding virtualization
  // setup cost, this guarantees that the optimistic user row is painted in the
  // same frame as the running state. Long histories still use virtual scrolling.
  if (visibleBlocks.length <= 80) {
    return (
        <main ref={parentRef} onScroll={onScroll} className="transcript" aria-live="polite">
        <div className="direct-list">
          {visibleBlocks.map((block) => <MessageBlock key={block.id} block={block} />)}
        </div>
        {showThinking && <div className="thinking" role="status"><LoaderCircle className="spin" size={15} /><span><strong>{progressLabel}</strong><small>正在处理 · 可随时停止</small></span></div>}
        {showJumpToBottom && <button className="jump-to-bottom" onClick={jumpToBottom}>回到底部 <ChevronDown size={14} /></button>}
      </main>
    );
  }

  return (
    <main ref={parentRef} onScroll={onScroll} className="transcript" aria-live="polite">
      <div className="virtual-list" style={{ height: rowVirtualizer.getTotalSize() }}>
        {rowVirtualizer.getVirtualItems().map((row) => (
          <div
            key={visibleBlocks[row.index].id}
            ref={rowVirtualizer.measureElement}
            data-index={row.index}
            className="virtual-row"
            style={{ transform: `translateY(${row.start}px)` }}
          >
            <MessageBlock block={visibleBlocks[row.index]} />
          </div>
        ))}
      </div>
      {showThinking && <div className="thinking" role="status"><LoaderCircle className="spin" size={15} /><span><strong>{progressLabel}</strong><small>正在处理 · 可随时停止</small></span></div>}
      {showJumpToBottom && <button className="jump-to-bottom" onClick={jumpToBottom}>回到底部 <ChevronDown size={14} /></button>}
    </main>
  );
}

function EmbeddedPet({ settings, running }: { settings: SettingsSnapshot; running: boolean }) {
  const pet = settings.pets.find((item) => item.id === settings.theme.petId);
  const [position, setPosition] = useState({
    x: settings.theme.petX ?? 8800,
    y: settings.theme.petY ?? 7600
  });
  const positionRef = useRef(position);
  const dragRef = useRef<{ pointerId: number; host: DOMRect } | null>(null);

  useEffect(() => {
    const next = { x: settings.theme.petX ?? 8800, y: settings.theme.petY ?? 7600 };
    positionRef.current = next;
    setPosition(next);
  }, [settings.theme.petX, settings.theme.petY, settings.theme.petId]);

  if (!settings.theme.petEnabled || !pet) return null;
  const move = (clientX: number, clientY: number, host: DOMRect) => {
    const x = Math.round(((clientX - host.left) / Math.max(1, host.width)) * 10000);
    const y = Math.round(((clientY - host.top) / Math.max(1, host.height)) * 10000);
    const next = { x: Math.max(400, Math.min(9600, x)), y: Math.max(700, Math.min(9300, y)) };
    positionRef.current = next;
    setPosition(next);
  };
  return <button
    className={`embedded-pet ${running ? 'working' : 'idle'}`}
    style={{ left: `${position.x / 100}%`, top: `${position.y / 100}%`, '--pet-accent': pet.accent ?? 'var(--blue)' } as React.CSSProperties}
    aria-label={`移动桌宠 ${pet.name}`}
    title={`${pet.name} · ${running ? '任务进行中' : pet.description}`}
    onPointerDown={(event) => {
      const host = event.currentTarget.parentElement?.getBoundingClientRect();
      if (!host) return;
      dragRef.current = { pointerId: event.pointerId, host };
      event.currentTarget.setPointerCapture(event.pointerId);
      move(event.clientX, event.clientY, host);
    }}
    onPointerMove={(event) => {
      const drag = dragRef.current;
      if (drag?.pointerId === event.pointerId) move(event.clientX, event.clientY, drag.host);
    }}
    onPointerUp={(event) => {
      if (dragRef.current?.pointerId !== event.pointerId) return;
      dragRef.current = null;
      if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
      sendCommand('settings.pet', positionRef.current);
    }}
  >
    <span className="pet-status">{running ? '处理中' : '就绪'}</span>
    <span className="pet-glyph" aria-hidden="true">{pet.glyph}</span>
  </button>;
}

function ChangeReviewPanel({ review }: { review: ChangeReview | null }) {
  const [expanded, setExpanded] = useState<string | null>(null);
  if (!review?.files.length) return <div className="review-empty"><FileDiff /><span><strong>暂无可审阅修改</strong><small>Agent 的文件修改会在任务完成后出现在这里。</small></span></div>;
  return <div className="review-panel">
    <header><span><strong>已编辑 {review.files.length} 个文件</strong><small>逐文件保留或回退；外部修改冲突时会安全停止。</small></span><button className="danger" onClick={() => window.confirm('确认回退本次任务的全部已记录修改？') && sendCommand('review.rollbackTask', { workflowId: review.workflowId, sessionId: review.sessionId })}><RotateCcw />回退全部</button></header>
    <div className="review-files">{review.files.map((file) => <article key={file.path} className={file.decision.toLowerCase()}>
      <div className="review-file-row">
        <button className="review-file-main" onClick={() => setExpanded(expanded === file.path ? null : file.path)}>{expanded === file.path ? <ChevronDown /> : <ChevronRight />}<FileCode2 /><span><strong>{file.path}</strong><small><b>+{file.added}</b> <i>-{file.removed}</i> · {file.decision}</small></span></button>
        <button onClick={() => sendCommand('navigation.openFile', { path: file.path, line: file.hunks[0]?.afterStart ?? 1 })}>打开</button>
        <button onClick={() => sendCommand('review.keepFile', { workflowId: review.workflowId, sessionId: review.sessionId, path: file.path })}>保留</button>
        <button className="danger" onClick={() => window.confirm(`回退 ${file.path}？`) && sendCommand('review.rollbackFile', { workflowId: review.workflowId, sessionId: review.sessionId, path: file.path })}>回退</button>
      </div>
      {expanded === file.path && <div className="review-hunks">{file.hunks.map((hunk) => <div key={hunk.id} className={`review-hunk ${hunk.decision.toLowerCase()}`}>
        <header>
          <small>@@ -{hunk.beforeStart} +{hunk.afterStart} · {hunk.decision}</small>
          <span>
            <button onClick={() => sendCommand('review.keepHunk', { workflowId: review.workflowId, sessionId: review.sessionId, path: file.path, hunkId: hunk.id })}>保留此块</button>
            <button className="danger" onClick={() => window.confirm(`回退 ${file.path} 的这个变更块？`) && sendCommand('review.rollbackHunk', { workflowId: review.workflowId, sessionId: review.sessionId, path: file.path, hunkId: hunk.id })}>回退此块</button>
          </span>
        </header>
        {hunk.before && <pre className="removed">{hunk.before}</pre>}{hunk.after && <pre className="added">{hunk.after}</pre>}
      </div>)}</div>}
    </article>)}</div>
  </div>;
}

function ActivityDock({ blocks, review, strategy }: { blocks: ChatBlock[]; review: ChangeReview | null; strategy: RunStrategy }) {
  const [tab, setTab] = useState<'tasks' | 'agents' | 'edits'>('tasks');
  const [open, setOpen] = useState(false);
  const effectiveStrategy = useMemo(() => {
    const selected = [...blocks].reverse().find((block) => block.kind === 'run.strategy')?.metadata?.strategy;
    return selected === 'TEAM' || selected === 'SINGLE' ? selected : strategy;
  }, [blocks, strategy]);
  const counts = useMemo(() => ({
    tasks: blocks.filter((b) => b.kind.startsWith('stage.') || b.kind.startsWith('run.')).length,
    agents: blocks.filter((b) => b.kind.startsWith('agent.')).length,
    edits: review?.files.length ?? 0
  }), [blocks, review]);
  const filtered = blocks.filter((block) => tab === 'agents'
    ? block.kind.startsWith('agent.')
    : tab === 'edits'
      ? block.kind.startsWith('tool.') && /patch|change|edit/i.test(`${block.title} ${block.text}`)
      : block.kind.startsWith('stage.') || block.kind.startsWith('run.'));
  return (
    <section className={`activity-dock ${open ? 'open' : ''}`}>
      <nav>
        <button className={tab === 'tasks' ? 'active' : ''} onClick={() => { setTab('tasks'); setOpen(true); }}><ListChecks />任务 <span>{counts.tasks}</span></button>
        <button className={tab === 'agents' ? 'active' : ''} onClick={() => { setTab('agents'); setOpen(true); }}><Bot />子代理 <span>{counts.agents}</span></button>
        <button className={tab === 'edits' ? 'active' : ''} onClick={() => { setTab('edits'); setOpen(true); }}><FileDiff />编辑 <span>{counts.edits}</span></button>
        <button className="dock-toggle" onClick={() => setOpen(!open)}>{open ? <ChevronDown /> : <ChevronRight />}</button>
      </nav>
      {open && <div className="activity-content">
        {tab === 'edits'
          ? <ChangeReviewPanel review={review} />
          : filtered.length
            ? filtered.slice(-40).map((block) => <SystemBlock key={block.id} block={block} />)
            : tab !== 'agents' && <p>当前任务还没有相关记录。</p>}
        {tab === 'agents' && !filtered.length && <p className="activity-hint">
          {effectiveStrategy === 'SINGLE'
            ? '当前任务使用 Single，不会启动子代理。需要 Codex 原生子代理时，请在输入框选择 Team；自动协作只会在跨模块或证据密集任务中启用。'
            : 'Team 已启用；子代理将在 Codex 原生协作入口建立后出现在这里。若持续为空，请检查 Codex 是否已安装并登录。'}
        </p>}
      </div>}
    </section>
  );
}

function DiagnosticsCard({ diagnostics, onClose, onRetry }: { diagnostics: DiagnosticsState; onClose: () => void; onRetry: () => void }) {
  const failed = diagnostics.checks.filter((check) => check.status === 'FAIL' || check.status === 'WARN');
  const statusLabel = diagnostics.state === 'running' ? '正在检查' : diagnostics.state === 'error' ? '检查失败' :
    diagnostics.overallStatus === 'FAIL' ? '发现问题' : diagnostics.overallStatus === 'WARN' ? '需要注意' : '连接正常';
  return <section className={`diagnostics-card ${diagnostics.state} ${diagnostics.overallStatus.toLowerCase()}`}>
    <header>
      <span className="diagnostics-icon">{diagnostics.state === 'running' ? <LoaderCircle className="spin" /> : diagnostics.overallStatus === 'FAIL' || diagnostics.state === 'error' ? <AlertTriangle /> : <Check />}</span>
      <div><strong>连接诊断</strong><small>{statusLabel}{diagnostics.durationMillis ? ` · ${diagnostics.durationMillis}ms` : ''}</small></div>
      <button className="icon-button" title="关闭诊断" onClick={onClose}><X size={15} /></button>
    </header>
    {diagnostics.state === 'running' && <p className="diagnostics-progress">正在分别检测供应商、DNS、TLS、模型能力、MCP 和沙箱…</p>}
      {diagnostics.state === 'error' && <p className="diagnostics-message">{diagnostics.message ?? '诊断未完成，请重试。'}</p>}
      {diagnostics.state === 'success' && <>
        <p className="diagnostics-summary">{diagnostics.failCount ?? 0} 失败 · {diagnostics.warnCount ?? 0} 警告 · {diagnostics.passCount ?? 0} 通过 · {diagnostics.skipCount ?? 0} 跳过</p>
      {failed.length > 0 && <div className="diagnostics-checks">{failed.slice(0, 8).map((check) => {
        const copy = localizeDiagnosticCheck(check);
        return <article key={check.id} className={check.status.toLowerCase()}>
          <div><strong>{copy.title}</strong><span>{check.status === 'FAIL' ? '失败' : '警告'}</span></div>
          <p>{copy.summary}</p>
          {copy.recoverySuggestion && <small>建议：{copy.recoverySuggestion}</small>}
        </article>;
      })}</div>}
      {!failed.length && <p className="diagnostics-message">没有发现需要处理的连接问题。</p>}
    </>}
    <footer><button onClick={onRetry} disabled={diagnostics.state === 'running'}><RotateCcw size={14} />重新诊断</button><button onClick={() => sendCommand('navigation.view', { view: 'settings' })}><Settings size={14} />打开设置</button></footer>
  </section>;
}

/**
 * Older providers still return English diagnostic text. Keep the transport schema
 * stable, but normalize the user-facing copy here so a failed check is actionable
 * instead of exposing an implementation detail such as a raw HTTP probe name.
 */
function localizeDiagnosticCheck(check: DiagnosticsCheck): Pick<DiagnosticsCheck, 'title' | 'summary' | 'recoverySuggestion'> {
  const titles: Record<string, string> = {
    'provider.configuration': '供应商配置',
    'provider.credentials': '供应商凭据',
    'provider.base_url': '供应商地址',
    'network.proxy': '代理设置',
    'network.dns': '供应商 DNS',
    'network.tls_http': '供应商 TLS / HTTP',
    'model.tools': '模型工具调用',
    'model.vision': '主模型视觉能力',
    'model.vision_assistant': '视觉辅助模型',
    'sandbox.mode': '沙箱模式',
    'sandbox.enforcement': '沙箱隔离',
  };
  const summary = check.summary;
  const recovery = check.recoverySuggestion;
  const localizedSummary = /hostname did not resolve|stopped resolving/i.test(summary)
    ? '供应商域名无法解析。常见原因是 Base URL 写错、DNS/VPN 不可用或代理未接管该请求。'
    : /endpoint probe failed|connection .*could not be established|TLS negotiation/i.test(summary)
      ? '无法连接供应商地址。请检查网络代理、防火墙、TLS 拦截和 Base URL，然后重试。'
      : /not in the local capability heuristics/i.test(summary)
        ? '当前模型未被本地能力表识别；这不代表模型一定不支持工具调用。'
        : /requires an API key/i.test(summary)
          ? '当前供应商没有检测到凭据。API Key 只会保存到 IDE Password Safe。'
          : summary;
  const localizedRecovery = recovery
    ? /verify the base url/i.test(recovery)
      ? '核对 Base URL、DNS/VPN 和代理设置后重试。'
      : /check proxy, dns, firewall/i.test(recovery)
        ? '打开“设置 → 供应商”，分别测试直连和代理，再重新诊断。'
        : /confirm tool calling support/i.test(recovery)
          ? '确认模型支持工具调用，或换用聊天/编程模型。'
          : /save the provider api key/i.test(recovery)
            ? '在供应商设置中重新保存 API Key，然后重试。'
            : recovery
    : undefined;
  return {
    title: titles[check.id] ?? check.title,
    summary: localizedSummary,
    recoverySuggestion: localizedRecovery,
  };
}

function normalizeDiagnosticsChecks(value: unknown): DiagnosticsCheck[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item, index) => {
    if (!item || typeof item !== 'object') return [];
    const raw = item as Record<string, unknown>;
    const statusValue = String(raw.status ?? 'SKIP').toUpperCase();
    const status: DiagnosticsCheck['status'] = statusValue === 'PASS' || statusValue === 'WARN' || statusValue === 'FAIL' || statusValue === 'SKIP'
      ? statusValue : 'SKIP';
    const duration = Number(raw.durationMillis ?? 0);
    return [{
      id: String(raw.id ?? `check-${index}`).slice(0, 120),
      category: String(raw.category ?? 'UNKNOWN').slice(0, 80),
      title: String(raw.title ?? '未命名检查').slice(0, 240),
      status,
      summary: String(raw.summary ?? '').slice(0, 800),
      durationMillis: Number.isFinite(duration) && duration >= 0 ? Math.min(duration, 86_400_000) : 0,
      recoverySuggestion: raw.recoverySuggestion ? String(raw.recoverySuggestion).slice(0, 800) : undefined
    }];
  }).slice(0, 128);
}

function PlanCard({ plan, running }: { plan: PlanProposal; running: boolean }) {
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [collapsed, setCollapsed] = useState(false);
  const activeSteps = plan.steps.filter((step) => step.state !== 'SKIPPED');
  const canExecute = plan.approvedCount > 0 && !running;
  return <section className="plan-card">
    <header>
      <span className="plan-icon"><ListChecks /></span>
      <div><small>{plan.mode === 'CLAUDE_PLAN' ? 'CLAUDE PLAN' : 'PLAN'} · 只读规划</small><h2>{plan.title}</h2><p>版本 {plan.revision} · {plan.completedCount}/{activeSteps.length} 完成 · 未批准前不会修改文件</p></div>
      <span className={`plan-decision ${plan.decision.toLowerCase()}`}>{plan.decision === 'PENDING' ? '待审批' : plan.decision}</span>
      <button className="plan-collapse" title={collapsed ? '展开计划' : '折叠计划'} onClick={() => setCollapsed(!collapsed)}>{collapsed ? <ChevronRight /> : <ChevronDown />}</button>
    </header>
    {!collapsed && <><div className="plan-steps">
      {plan.steps.map((step, index) => {
        const text = drafts[step.id] ?? step.text;
        const editable = step.state !== 'RUNNING' && step.state !== 'COMPLETED';
        const approved = step.state === 'APPROVED';
        return <article key={step.id} className={`plan-step ${step.state.toLowerCase()}`}>
          <button
            className={`plan-check ${approved || step.state === 'COMPLETED' ? 'checked' : ''}`}
            disabled={!editable || step.state === 'SKIPPED'}
            title={approved ? '取消批准' : '批准此步骤'}
            onClick={() => sendCommand('plan.approve', { stepId: step.id, approved: !approved })}
          >{step.state === 'COMPLETED' ? <Check /> : approved ? <Check /> : index + 1}</button>
          <div className="plan-step-body">
            <div className="plan-step-meta"><strong>步骤 {index + 1}</strong><span>{step.state}</span>{step.attempts > 0 && <span>尝试 {step.attempts}</span>}</div>
            <textarea
              value={text}
              disabled={!editable || step.state === 'SKIPPED'}
              onChange={(event) => setDrafts((current) => ({ ...current, [step.id]: event.target.value }))}
              onBlur={() => {
                if (text.trim() && text.trim() !== step.text) sendCommand('plan.updateStep', { stepId: step.id, text: text.trim() });
              }}
            />
            {step.lastError && <p className="plan-error"><AlertTriangle />{step.lastError}</p>}
          </div>
          <div className="plan-step-actions">
            {step.state === 'SKIPPED'
              ? <button onClick={() => sendCommand('plan.restore', { stepId: step.id })}>恢复</button>
              : step.state === 'FAILED' || step.state === 'PAUSED'
                ? <button onClick={() => sendCommand('plan.retry', { stepId: step.id })}>重试</button>
                : editable && <button onClick={() => sendCommand('plan.skip', { stepId: step.id })}>跳过</button>}
          </div>
        </article>;
      })}
    </div>
    <footer>
      <button onClick={() => sendCommand('plan.approveAll', {})}><Check />全选</button>
      <button onClick={() => sendCommand('plan.continue', {})}><RotateCcw />继续规划</button>
      {running ? <button className="danger" onClick={() => sendCommand('plan.pause', {})}><Square />暂停执行</button> : <>
        <button disabled={!canExecute} onClick={() => sendCommand('plan.review', { action: 'APPROVE_MANUAL' })}><Play />执行下一步</button>
        <button className="primary" disabled={!canExecute} onClick={() => sendCommand('plan.review', { action: 'APPROVE_AUTO' })}><Sparkles />执行已批准步骤</button>
      </>}
    </footer></>}
  </section>;
}

async function filesToDrafts(files: FileList | File[]): Promise<AttachmentDraft[]> {
  const selected = Array.from(files).slice(0, 4);
  return Promise.all(selected.map(async (file) => {
    const kind: AttachmentDraft['kind'] = file.type.startsWith('image/') ? 'image'
      : /\.md$/i.test(file.name) ? 'markdown' : 'text';
    const limit = kind === 'image' ? 5 * 1024 * 1024 : kind === 'markdown' ? 512 * 1024 : 1024 * 1024;
    if (file.size > limit) throw new Error(`${file.name} 超过安全大小限制`);
    const content = kind === 'image'
      ? String(await new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result);
        reader.onerror = () => reject(reader.error);
        reader.readAsDataURL(file);
      }))
      : await file.text();
    return {
      id: `${file.name}-${file.lastModified}-${file.size}`,
      fileName: file.name,
      mediaType: file.type || (kind === 'markdown' ? 'text/markdown' : 'text/plain'),
      byteSize: file.size,
      kind,
      content
    };
  }));
}

function Composer({
  running, mode, setMode, strategy, setStrategy, onSend, onCancel, prefill, prompts, fileSuggestions, settings, models
}: {
  running: boolean;
  mode: RunMode;
  setMode: (mode: RunMode) => void;
  strategy: RunStrategy;
  setStrategy: (strategy: RunStrategy) => void;
  onSend: (text: string, attachments: AttachmentDraft[]) => void;
  onCancel: () => void;
  prefill: { text: string; revision: number };
  prompts: PromptTemplateView[];
  fileSuggestions: string[];
  settings: SettingsSnapshot;
  models: string[];
}) {
  const [text, setText] = useState('');
  const [attachments, setAttachments] = useState<AttachmentDraft[]>([]);
  const fileInput = useRef<HTMLInputElement>(null);
  const slashMatch = text.match(/^\/[^\s]*$/);
  const promptMatch = text.match(/(?:^|\s)!([^\s!]*)$/);
  const fileMatch = text.match(/(?:^|\s)@([^\s@]*)$/);
  const palette = slashMatch ? SLASH_COMMANDS.filter((item) => item.value.startsWith(slashMatch[0].toLowerCase())) : [];
  const promptPalette = promptMatch ? prompts.filter((item) => item.shortcut.toLowerCase().includes(promptMatch[1].toLowerCase())).slice(0, 10) : [];
  const filePalette = fileMatch ? fileSuggestions.slice(0, 12) : [];
  useEffect(() => { if (prefill.text) setText(prefill.text); }, [prefill.revision, prefill.text]);
  useEffect(() => {
    if (fileMatch?.[1]) sendCommand('composer.searchFiles', { query: fileMatch[1] });
  }, [fileMatch?.[1]]);
  const replaceTrigger = (pattern: RegExp, replacement: string) => setText((current) => current.replace(pattern, (match) => `${match.startsWith(' ') ? ' ' : ''}${replacement}`));
  const addProjectReference = (path: string) => {
    const kind: AttachmentDraft['kind'] = /\.(png|jpe?g|gif|webp)$/i.test(path) ? 'image' : /\.(md|markdown)$/i.test(path) ? 'markdown' : 'text';
    setAttachments((current) => current.some((item) => item.localPath === path) || current.length >= 4 ? current : [...current, {
      id: `project-${path}`, fileName: path, mediaType: '', byteSize: 0, kind, content: '', localPath: path
    }]);
    replaceTrigger(/(?:^|\s)@[^\s@]*$/, '');
  };
  const addFiles = async (files: FileList | File[]) => {
    try {
      const added = await filesToDrafts(files);
      setAttachments((current) => [...current, ...added].slice(0, 4));
    }
    catch (error) { sendCommand('ui.notify', { message: error instanceof Error ? error.message : '无法读取附件' }); }
  };
  const submit = () => {
    if (running) { onCancel(); return; }
    if (!text.trim() && !attachments.length) return;
    onSend(text.trim(), attachments);
    setText('');
    setAttachments([]);
  };
  return (
    <section className="composer-shell" onDragOver={(event) => event.preventDefault()} onDrop={(event) => {
      event.preventDefault();
      void addFiles(event.dataTransfer.files);
    }}>
      {attachments.length > 0 && <div className="attachment-row">
        {attachments.map((file) => <span key={file.id}>{file.kind === 'image' ? <Image /> : <FileCode2 />}{file.fileName}<button type="button" onClick={() => setAttachments((all) => all.filter((item) => item.id !== file.id))}><X /></button></span>)}
      </div>}
      <textarea
        value={text}
        onChange={(event) => setText(event.target.value)}
        placeholder="输入任务；/plan 规划，/review 审阅，@ 引用文件，! 选提示词…"
        aria-keyshortcuts="Enter"
        title="Enter 发送，Shift+Enter 换行"
        onKeyDown={(event) => {
          if (event.key === 'Tab' && event.shiftKey) {
            event.preventDefault();
            setMode(mode === 'AGENT' ? 'PLAN' : mode === 'PLAN' ? 'CLAUDE_PLAN' : 'AGENT');
            return;
          }
          // Keep Enter predictable in the composer: it submits, while Shift+Enter
          // remains available for a multiline prompt. Do not submit an IME
          // composition (for example, while committing Chinese input).
          // Chromium/JCEF reports IME composition inconsistently: some versions only expose
          // keyCode=229 and leave nativeEvent.isComposing false. Respect both signals so the
          // first Enter commits Chinese/Japanese input, while the next Enter submits normally.
          const composing = event.nativeEvent.isComposing || event.keyCode === 229;
          if (event.key === 'Enter' && !event.shiftKey && !composing) {
            event.preventDefault();
            submit();
          }
        }}
      />
      {(palette.length > 0 || promptPalette.length > 0 || filePalette.length > 0) && <div className="composer-palette">
        {palette.map((item) => <button key={item.value} onClick={() => setText(item.value)}><Code2 /><span><strong>{item.value.trim()}</strong><small>{item.label} · {item.detail}</small></span></button>)}
        {promptPalette.map((item) => <button key={item.id} onClick={() => replaceTrigger(/(?:^|\s)![^\s!]*$/, item.content)}><Sparkles /><span><strong>!{item.shortcut}</strong><small>{item.name}</small></span></button>)}
        {filePalette.map((path) => <button key={path} onClick={() => addProjectReference(path)}><FileCode2 /><span><strong>{path}</strong><small>作为安全附件引用项目文件</small></span></button>)}
      </div>}
      <footer className="composer-footer">
        <input ref={fileInput} type="file" multiple hidden onChange={(event) => event.target.files && void addFiles(event.target.files)} />
        <button type="button" className="icon-button attachment-button" title="添加文件" onClick={() => fileInput.current?.click()}><Plus /></button>
        <ComposerChoice
          className="mode-selector"
          title="运行模式"
          value={mode}
          options={[{ value: 'AGENT', label: 'Agent' }, { value: 'PLAN', label: 'Plan' }, { value: 'CLAUDE_PLAN', label: 'Claude Plan' }]}
          onChange={(value) => setMode(value as RunMode)}
        />
        <ComposerChoice
          className="strategy-selector"
          title="执行策略"
          value={strategy}
          options={[{ value: 'AUTO', label: '自动协作' }, { value: 'SINGLE', label: 'Single' }, { value: 'TEAM', label: 'Team' }]}
          onChange={(value) => setStrategy(value as RunStrategy)}
        />
        <select
          className="engine-selector"
          title="当前引擎"
          disabled={running}
          value={settings.provider.id}
          onChange={(event) => sendCommand('provider.select', { providerId: event.target.value })}
        >
          {settings.providers.map((provider) => <option key={provider.id} value={provider.id}>{provider.name}</option>)}
        </select>
        <select
          className="model-selector"
          title="当前模型"
          disabled={running}
          value={settings.provider.model}
          onChange={(event) => sendCommand('settings.saveProvider', {
            providerId: settings.provider.id,
            baseUrl: settings.provider.baseUrl,
            model: event.target.value,
            apiKey: '',
            reasoningEffort: settings.provider.reasoningEffort
          })}
        >
          {[settings.provider.model, ...models]
            .filter((item, index, all) => Boolean(item) && all.indexOf(item) === index)
            .map((item) => <option key={item} value={item}>{item}</option>)}
        </select>
        <select
          className="reasoning-selector"
          title="推理强度"
          disabled={running}
          value={settings.provider.reasoningEffort}
          onChange={(event) => sendCommand('settings.saveProvider', {
            providerId: settings.provider.id,
            baseUrl: settings.provider.baseUrl,
            model: settings.provider.model,
            apiKey: '',
            reasoningEffort: event.target.value
          })}
        >
          {REASONING_LEVELS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
        </select>
        <button className="icon-button model-refresh" title="刷新可用模型" disabled={running} onClick={() => sendCommand('provider.models', {})}><RotateCcw /></button>
        <span className="permission-pill"><ShieldCheck />工作区权限</span>
        <span className="composer-spacer" />
        <button type="button" aria-label={running ? '停止任务' : '发送'} className={`send-button ${running ? 'stop' : ''}`} onClick={submit}>{running ? <Square /> : <Send />}</button>
      </footer>
    </section>
  );
}

function ChatView({
  blocks, running, plan, review, diagnostics, onCloseDiagnostics, onRetryDiagnostics, mode, setMode, strategy, setStrategy, onSend, onCancel, prefill, prompts, fileSuggestions, settings, models
}: {
  blocks: ChatBlock[]; running: boolean; plan: PlanProposal | null; review: ChangeReview | null; diagnostics: DiagnosticsState | null;
  onCloseDiagnostics: () => void; onRetryDiagnostics: () => void; mode: RunMode; setMode: (mode: RunMode) => void;
  strategy: RunStrategy; setStrategy: (strategy: RunStrategy) => void;
  onSend: (text: string, attachments: AttachmentDraft[]) => void; onCancel: () => void; prefill: { text: string; revision: number };
  prompts: PromptTemplateView[]; fileSuggestions: string[]; settings: SettingsSnapshot; models: string[];
}) {
  return <div className="chat-view"><Transcript blocks={blocks} running={running} />{diagnostics && <DiagnosticsCard diagnostics={diagnostics} onClose={onCloseDiagnostics} onRetry={onRetryDiagnostics} />}{plan && <PlanCard plan={plan} running={running} />}<ActivityDock blocks={blocks} review={review} strategy={strategy} /><Composer {...{ running, mode, setMode, strategy, setStrategy, onSend, onCancel, prefill, prompts, fileSuggestions, settings, models }} /></div>;
}

function HistoryView({ entries, onLoad, onDelete }: { entries: HistoryEntry[]; onLoad: (id: string) => void; onDelete: (id: string) => void }) {
  const [query, setQuery] = useState('');
  const filtered = entries.filter((entry) => entry.title.toLowerCase().includes(query.toLowerCase()));
  return <main className="page"><div className="page-heading"><div><h1>历史记录</h1><p>继续之前的项目会话。</p></div></div>
    <label className="search-box"><Search /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索会话" /></label>
    <div className="history-list">{filtered.map((entry) => {
      const isRunning = entry.status === 'RUNNING';
      return <article key={entry.id} className={isRunning ? 'running' : ''}>
        <button className="history-main" onClick={() => onLoad(entry.id)}><MessageSquare /><span><strong>{entry.title}</strong><small>{isRunning ? '正在后台运行 · 点击切换到此会话' : `${new Date(entry.updatedAt).toLocaleString()} · ${entry.messageCount} 条消息`}</small></span></button>
        <span className={`history-status ${entry.status.toLowerCase()}`}>{isRunning ? '运行中' : entry.status}</span>
        <button className="icon-button danger" disabled={isRunning} title={isRunning ? '请先停止此会话' : '删除会话'} onClick={() => onDelete(entry.id)}><Trash2 /></button>
      </article>;
    })}</div>
    {!filtered.length && <div className="page-empty"><Archive /><strong>没有匹配的会话</strong></div>}
  </main>;
}

function SettingCard({ title, description, children }: { title: string; description?: string; children?: React.ReactNode }) {
  return <section className="setting-card"><header><h2>{title}</h2>{description && <p>{description}</p>}</header>{children && <div className="setting-body">{children}</div>}</section>;
}

function ProviderSettings({ snapshot, models }: { snapshot: SettingsSnapshot; models: string[] }) {
  const [providerId, setProviderId] = useState(snapshot.provider.id);
  const selected = snapshot.providers.find((provider) => provider.id === providerId);
  const [baseUrl, setBaseUrl] = useState(snapshot.provider.baseUrl);
  const [model, setModel] = useState(snapshot.provider.model);
  const [apiKey, setApiKey] = useState('');
  const [reasoning, setReasoning] = useState(snapshot.provider.reasoningEffort);
  useEffect(() => {
    setProviderId(snapshot.provider.id); setBaseUrl(snapshot.provider.baseUrl);
    setModel(snapshot.provider.model); setReasoning(snapshot.provider.reasoningEffort);
  }, [snapshot]);
  const selectProvider = (id: string) => {
    setProviderId(id);
    const entry = snapshot.providers.find((provider) => provider.id === id);
    if (entry) { setBaseUrl(entry.baseUrl || entry.defaultBaseUrl); setModel(entry.model || entry.defaultModel); }
    setApiKey('');
  };
  const unavailableSelection = selected?.cli && models.length > 0 && model !== 'default' && !models.includes(model);
  return <>
    <SettingCard title="供应商与模型" description="普通 API 与本地 CLI 使用独立配置，不再互相覆盖。">
      <div className="form-grid">
        <label>供应商<select value={providerId} onChange={(event) => selectProvider(event.target.value)}>{snapshot.providers.map((provider) => <option value={provider.id} key={provider.id}>{provider.name}</option>)}</select></label>
        <label>模型<input list="omnicode-models" value={model} onChange={(event) => setModel(event.target.value)} /><datalist id="omnicode-models">{models.map((item) => <option value={item} key={item} />)}</datalist></label>
        {unavailableSelection && <div className="model-warning wide"><AlertTriangle /><span><strong>当前模型已不可用</strong><small>{model} 不在 CLI 返回的可用列表中；请选择列表内模型，否则 CLI 可能无输出地等待。</small></span></div>}
        <label className="wide">Base URL<input value={baseUrl} disabled={selected?.cli} onChange={(event) => setBaseUrl(event.target.value)} /></label>
        {!selected?.cli && <label className="wide">API Key<input type="password" value={apiKey} placeholder={selected?.credentialConfigured ? '已安全保存；留空表示不修改' : '输入 API Key'} onChange={(event) => setApiKey(event.target.value)} /></label>}
        <label>推理强度<select value={reasoning} onChange={(event) => setReasoning(event.target.value)}>{['auto','none','minimal','low','medium','high','xhigh','max'].map((item) => <option key={item}>{item}</option>)}</select></label>
      </div>
      <div className="setting-actions"><button className="primary" disabled={unavailableSelection} onClick={() => sendCommand('settings.saveProvider', { providerId, baseUrl, model, apiKey, reasoningEffort: reasoning })}><Check />保存、验证并加载模型</button><button onClick={() => sendCommand('provider.models', {})}><RotateCcw />刷新当前模型</button></div>
    </SettingCard>
  </>;
}

const EMPTY_MCP: McpServerView = {
  id: '', name: 'MCP Server', enabled: false, transport: 'stdio', command: '', arguments: '',
  environmentKeys: '', workingDirectory: '.', url: '', httpAuthMode: 'none', oauthClientId: '', oauthScopes: '',
  bearerConfigured: false, oauthConfigured: false, oauthUsable: false
};

function McpSettings({ snapshot, catalog, catalogState, testState, authState, draftSelection }: { snapshot: SettingsSnapshot; catalog: McpCatalogEntryView[]; catalogState: McpCatalogState; testState: McpTestState | null; authState: McpAuthState | null; draftSelection: McpDraftSelection | null }) {
  const [selectedId, setSelectedId] = useState(snapshot.mcpServers[0]?.id ?? '');
  const selected = snapshot.mcpServers.find((server) => server.id === selectedId);
  const [draft, setDraft] = useState<McpServerView>(selected ?? EMPTY_MCP);
  const [marketOpen, setMarketOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [installSelections, setInstallSelections] = useState<Record<string, string>>({});
  const editorRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const server = snapshot.mcpServers.find((item) => item.id === selectedId) ?? snapshot.mcpServers[0];
    if (server) { setSelectedId(server.id); setDraft(server); }
  }, [snapshot, selectedId]);
  useEffect(() => {
    if (!marketOpen) return;
    const timer = window.setTimeout(() => sendCommand('mcp.catalog', { query }), 180);
    return () => window.clearTimeout(timer);
  }, [marketOpen, query]);
  useEffect(() => {
    if (!draftSelection) return;
    const server = snapshot.mcpServers.find((item) => item.id === draftSelection.id);
    if (!server) return;
    setSelectedId(server.id);
    setDraft(server);
    setMarketOpen(false);
    window.requestAnimationFrame(() => editorRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest' }));
  }, [draftSelection, snapshot.mcpServers]);
  const update = <K extends keyof McpServerView>(key: K, value: McpServerView[K]) => setDraft((current) => ({ ...current, [key]: value }));
  return <>
    <SettingCard title="MCP 服务" description={`已配置 ${snapshot.mcpServers.length} 个服务器；市场安装只创建禁用草案，审阅后才可启用。`}>
      <div className="setting-actions left"><button onClick={() => { setDraft(EMPTY_MCP); setSelectedId(''); }}><Plus />手动添加</button><button className="primary" onClick={() => setMarketOpen(!marketOpen)}><Sparkles />{marketOpen ? '关闭市场' : 'MCP 市场'}</button></div>
      {marketOpen && <div className="mcp-market">
        <div className="mcp-market-toolbar"><label className="search-box"><Search /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索服务器、发布者或标签" /></label><button title="刷新 MCP Registry" disabled={catalogState.state === 'loading'} onClick={() => sendCommand('mcp.catalog', { query, forceRefresh: true })}><RotateCcw className={catalogState.state === 'loading' ? 'spin' : ''} />刷新</button></div>
        <div className={`mcp-market-state ${catalogState.state}`}><span>{catalogState.state === 'loading' && <LoaderCircle className="spin" />}{catalogState.state === 'ready' && <Check />}{(catalogState.state === 'offline' || catalogState.state === 'error') && <AlertTriangle />}</span><div><strong>{catalogState.total ? `${catalogState.total} 个目录条目 · 显示 ${catalogState.shown}` : '正在读取目录'}</strong><small>{catalogState.notice}{catalogState.fromCache ? ' · 使用本地缓存' : ''}</small></div></div>
        <div className="market-list">{catalog.map((entry) => <article key={entry.id}>
          <header><div><strong>{entry.name}</strong><small>{entry.publisher} · {entry.category} · {entry.source === 'MCP_REGISTRY' ? 'Registry · 未审阅' : '内置精选'}</small></div><span className={`risk ${entry.risk.toLowerCase()}`}>{entry.risk}</span></header>
          <p>{entry.description}</p><small>{entry.riskSummary}</small>
          <footer>{entry.tags.slice(0, 5).map((tag) => <span key={tag}>{tag}</span>)}
            {entry.options.length > 1 && <select aria-label={`${entry.name} 安装方式`} value={installSelections[entry.id] ?? entry.options[0].id} onChange={(event) => setInstallSelections((current) => ({ ...current, [entry.id]: event.target.value }))}>{entry.options.map((option) => <option key={option.id} value={option.id}>{option.name}</option>)}</select>}
            <button disabled={!entry.options.length} onClick={() => sendCommand('mcp.installDraft', { entryId: entry.id, optionId: installSelections[entry.id] ?? entry.options[0]?.id })}>添加并配置</button>
          </footer>
        </article>)}</div>
      </div>}
      <div className="split-editor" ref={editorRef}>
        <nav>{snapshot.mcpServers.map((server) => <button key={server.id} className={selectedId === server.id ? 'active' : ''} onClick={() => { setSelectedId(server.id); setDraft(server); }}><span className={server.enabled ? 'status-on' : ''} />{server.name}<small>{server.transport}</small></button>)}{!snapshot.mcpServers.length && <p>暂无 MCP 配置</p>}</nav>
        <div className="editor-form">
          {draftSelection?.id === draft.id && <div className="mcp-draft-notice" role="status"><Check /><span><strong>市场参数已自动填充</strong><small>{draftSelection.warnings[0] ?? '草案默认禁用；请检查参数，测试连接后再启用。'}</small></span></div>}
          <div className="toggle-row compact"><span><strong>启用此服务器</strong><small>首次连接仍需审批</small></span><button className={`switch ${draft.enabled ? 'on' : ''}`} onClick={() => update('enabled', !draft.enabled)}><span /></button></div>
          <div className="form-grid">
            <label>名称<input value={draft.name} onChange={(event) => update('name', event.target.value)} /></label>
            <label>传输<select value={draft.transport} onChange={(event) => update('transport', event.target.value as 'stdio' | 'http')}><option value="stdio">stdio 本地进程</option><option value="http">Streamable HTTP</option></select></label>
            {draft.transport === 'stdio' ? <>
              <label>启动命令<input value={draft.command} onChange={(event) => update('command', event.target.value)} placeholder="npx / uvx / executable" /></label>
              <label>工作目录<input value={draft.workingDirectory} onChange={(event) => update('workingDirectory', event.target.value)} /></label>
              <label className="wide">参数<input value={draft.arguments} onChange={(event) => update('arguments', event.target.value)} /></label>
              <label className="wide">凭据环境变量名<input value={draft.environmentKeys} onChange={(event) => update('environmentKeys', event.target.value)} placeholder="GITHUB_TOKEN（只保存变量名）" /></label>
            </> : <>
              <label className="wide">HTTP Endpoint<input value={draft.url} onChange={(event) => update('url', event.target.value)} placeholder="https://example.com/mcp" /></label>
              <label>认证<select value={draft.httpAuthMode} onChange={(event) => update('httpAuthMode', event.target.value as McpServerView['httpAuthMode'])}><option value="none">无认证</option><option value="bearer">Bearer</option><option value="oauth">OAuth 2.1 / PKCE</option></select></label>
              {draft.httpAuthMode === 'oauth' && <><label>OAuth Client ID<input value={draft.oauthClientId} onChange={(event) => update('oauthClientId', event.target.value)} /></label><label className="wide">OAuth Scopes<input value={draft.oauthScopes} onChange={(event) => update('oauthScopes', event.target.value)} /></label></>}
            </>}
          </div>
          {draft.transport === 'http' && draft.httpAuthMode === 'bearer' && <div className="credential-row">
            <span><strong>Bearer Token</strong><small>{draft.bearerConfigured ? '已安全保存在 PasswordSafe' : '尚未配置；Token 不会进入网页界面'}</small></span>
            <button disabled={!draft.id} onClick={() => sendCommand('mcp.saveBearer', { id: draft.id })}>{draft.bearerConfigured ? '更新 Token' : '保存 Token…'}</button>
            <button disabled={!draft.id || !draft.bearerConfigured} onClick={() => sendCommand('mcp.clearBearer', { id: draft.id })}>清除</button>
          </div>}
          {draft.transport === 'http' && draft.httpAuthMode === 'oauth' && <div className="oauth-actions">
            <div><strong>OAuth 状态</strong><small>{draft.oauthUsable ? '已登录，当前配置可用' : draft.oauthConfigured ? '已有凭据，但配置已变化，请重新登录' : '尚未登录；请先保存配置'}</small></div>
            <button disabled={!draft.id || authState?.state === 'running'} onClick={() => sendCommand('mcp.oauthDiscover', { id: draft.id })}>自动发现并填充</button>
            <button className="primary" disabled={!draft.id || authState?.state === 'running'} onClick={() => sendCommand('mcp.oauthLogin', { id: draft.id })}>浏览器登录</button>
            <button disabled={!draft.oauthConfigured} onClick={() => sendCommand('mcp.oauthLogout', { id: draft.id })}>退出</button>
          </div>}
          {authState && authState.id === draft.id && <div className={`connection-result ${authState.state}`}>
            {authState.state === 'running' ? <LoaderCircle className="spin" /> : authState.state === 'success' ? <Check /> : authState.state === 'error' ? <AlertTriangle /> : <Circle />}
            <span><strong>{authState.state === 'running' ? 'OAuth 处理中' : authState.state === 'success' ? '认证已更新' : authState.state === 'error' ? '认证失败' : '未执行'}</strong><small>{authState.message}</small></span>
          </div>}
          {testState && (testState.id === draft.id || !draft.id) && <div className={`connection-result ${testState.state}`}>
            {testState.state === 'running' ? <LoaderCircle className="spin" /> : testState.state === 'success' ? <Check /> : <AlertTriangle />}
            <span><strong>{testState.state === 'success' ? '连接可用' : testState.state === 'error' ? '连接失败' : '正在测试'}</strong><small>{testState.message}</small>{Boolean(testState.tools?.length) && <small>工具：{testState.tools?.slice(0, 8).join('、')}</small>}</span>
          </div>}
          <div className="setting-actions"><button className="danger" disabled={!draft.id} onClick={() => draft.id && window.confirm('删除此 MCP 配置？') && sendCommand('mcp.delete', { id: draft.id })}><Trash2 />删除</button><button disabled={testState?.state === 'running'} onClick={() => sendCommand('mcp.test', { ...draft })}><Gauge />测试连接 / 发现工具</button><button className="primary" onClick={() => sendCommand('mcp.save', { ...draft })}><Check />保存配置</button></div>
        </div>
      </div>
    </SettingCard>
  </>;
}

function PromptSettings({ prompts }: { prompts: PromptTemplateView[] }) {
  const [draft, setDraft] = useState<PromptTemplateView>(prompts[0] ?? { id: '', name: '', shortcut: '', content: '' });
  useEffect(() => { if (draft.id) setDraft(prompts.find((item) => item.id === draft.id) ?? prompts[0] ?? draft); }, [prompts]);
  return <SettingCard title="提示词库" description="输入框中使用 !快捷名 插入模板。"><div className="chip-list">{prompts.map((item) => <button className={draft.id === item.id ? 'active' : ''} key={item.id} onClick={() => setDraft(item)}>!{item.shortcut}</button>)}<button onClick={() => setDraft({ id: '', name: '', shortcut: '', content: '' })}><Plus />新增</button></div><div className="form-grid"><label>名称<input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></label><label>快捷名<input value={draft.shortcut} onChange={(e) => setDraft({ ...draft, shortcut: e.target.value.replace(/^!/, '') })} /></label><label className="wide">内容<textarea value={draft.content} onChange={(e) => setDraft({ ...draft, content: e.target.value })} /></label></div><div className="setting-actions"><button disabled={!draft.id} onClick={() => sendCommand('prompt.delete', { id: draft.id })}><Trash2 />删除</button><button onClick={() => sendCommand('prompt.save', { ...draft })}><Check />保存</button></div></SettingCard>;
}

function SkillSettings({ skills }: { skills: SkillSourceView[] }) {
  const [draft, setDraft] = useState<SkillSourceView>(skills[0] ?? { id: '', name: '', path: '', enabled: true });
  useEffect(() => { if (draft.id) setDraft(skills.find((item) => item.id === draft.id) ?? skills[0] ?? draft); }, [skills]);
  return <SettingCard title="Skills" description="配置本机 Skill 目录；项目内容仍按不可信输入处理。"><div className="chip-list">{skills.map((item) => <button className={draft.id === item.id ? 'active' : ''} key={item.id} onClick={() => setDraft(item)}>{item.name}</button>)}<button onClick={() => setDraft({ id: '', name: '', path: '', enabled: true })}><Plus />新增</button></div><div className="form-grid"><label>名称<input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} /></label><label>目录<input value={draft.path} onChange={(e) => setDraft({ ...draft, path: e.target.value })} /></label></div><div className="setting-actions"><button disabled={!draft.id} onClick={() => sendCommand('skill.delete', { id: draft.id })}><Trash2 />删除</button><button onClick={() => sendCommand('skill.save', { ...draft })}><Check />保存</button></div></SettingCard>;
}

function ProjectContextSettingsCard({ snapshot }: { snapshot: SettingsSnapshot }) {
  const [pinnedPaths, setPinnedPaths] = useState(snapshot.projectContext.pinnedPaths.join('\n'));
  const [excludedPaths, setExcludedPaths] = useState(snapshot.projectContext.excludedPaths.join('\n'));
  useEffect(() => {
    setPinnedPaths(snapshot.projectContext.pinnedPaths.join('\n'));
    setExcludedPaths(snapshot.projectContext.excludedPaths.join('\n'));
  }, [snapshot.projectContext.pinnedPaths, snapshot.projectContext.excludedPaths]);
  return <SettingCard title="项目提示增强" description="固定关键文件并排除无关目录；路径会经过项目边界、忽略文件和敏感文件校验。">
    <div className="form-grid">
      <label className="wide">固定到自动上下文（每行一个项目相对路径）<textarea value={pinnedPaths} onChange={(event) => setPinnedPaths(event.target.value)} placeholder="src/main/kotlin/dev/example/App.kt" /></label>
      <label className="wide">排除路径（每行一个项目相对路径）<textarea value={excludedPaths} onChange={(event) => setExcludedPaths(event.target.value)} placeholder="build\nfixtures/large" /></label>
    </div>
    <div className="setting-actions"><button className="primary" onClick={() => sendCommand('settings.projectContext', { pinnedPaths, excludedPaths })}><Check />保存上下文规则</button></div>
  </SettingCard>;
}

function SettingsContent({ tab, snapshot, catalog, catalogState, runtimes, models, mcpTest, mcpAuth, mcpDraftSelection, tokenTracker }: { tab: string; snapshot: SettingsSnapshot; catalog: McpCatalogEntryView[]; catalogState: McpCatalogState; runtimes: RuntimeStatusView[]; models: string[]; mcpTest: McpTestState | null; mcpAuth: McpAuthState | null; mcpDraftSelection: McpDraftSelection | null; tokenTracker: TokenTrackerView | null }) {
  if (tab === 'providers') return <ProviderSettings snapshot={snapshot} models={models} />;
  if (tab === 'dependencies') return <>
    <SettingCard title="本地运行引擎" description="每个引擎独立检测、认证、发现模型和恢复会话。">
      <div className="engine-grid">{ENGINES.map(([id, engine]) => {
        const runtime = runtimes.find((item) => item.id === id);
        const providerId = `cli-${id}`;
        const selected = snapshot.provider.id === providerId;
        return <div key={id}><Code2 /><span><strong>{engine}</strong><small>{runtime ? `${runtime.version}${runtime.path ? ` · ${runtime.path}` : ''}` : '等待检测'}</small>{runtime?.diagnostic && <small>{runtime.diagnostic}</small>}{runtime?.runnable && <small className="runtime-capabilities">{runtime.modelDiscovery ? '模型发现' : '手动模型'} · {runtime.nativeResume ? '原生恢复' : '对话重放'}{runtime.nativeHistory ? ' · 原生历史' : ''}</small>}</span>{runtime?.runnable && <button disabled={selected} onClick={() => sendCommand('provider.select', { providerId })}>{selected ? '当前引擎' : '选择并检查模型'}</button>}<Circle className={runtime?.runnable ? 'runtime-installed' : runtime ? 'runtime-error' : ''} /></div>;
      })}</div>
      <div className="setting-actions"><button onClick={() => sendCommand('runtime.probe', {})}><Gauge />运行诊断</button></div>
    </SettingCard>
  </>;
  if (tab === 'mcp') return <McpSettings snapshot={snapshot} catalog={catalog} catalogState={catalogState} testState={mcpTest} authState={mcpAuth} draftSelection={mcpDraftSelection} />;
  if (tab === 'permissions') return <>
    <SettingCard title="权限与沙箱" description="危险操作继续使用 JetBrains 原生审批。">
      <div className="segmented"><button className={snapshot.platform.sandboxMode === 'WORKSPACE_WRITE' ? 'active' : ''} onClick={() => sendCommand('settings.sandbox', { mode: 'WORKSPACE_WRITE' })}>workspace-write</button><button className={snapshot.platform.sandboxMode === 'DANGER_FULL_ACCESS' ? 'active' : ''} onClick={() => sendCommand('settings.sandbox', { mode: 'DANGER_FULL_ACCESS' })}>danger-full-access</button></div>
    </SettingCard>
  </>;
  if (tab === 'usage') return <SettingCard title="使用统计" description="由本机 TokenTracker 提供用量、费用和趋势。"><div className="feature-row"><Gauge /><span><strong>TokenTracker</strong><small>{tokenTracker?.detail ?? '正在检查本机面板…'}</small><small>{tokenTracker?.cliInstalled ? 'CLI 已发现' : '尚未发现 CLI'}</small></span><span className={`usage-status ${tokenTracker?.state?.toLowerCase() ?? 'unknown'}`}>{tokenTracker?.state === 'READY' ? '已连接' : tokenTracker?.state === 'NOT_RUNNING' ? '未启动' : tokenTracker?.state === 'UNVERIFIED_SERVICE' ? '待验证' : '检查中'}</span></div><div className="setting-actions"><button className="primary" disabled={tokenTracker?.state !== 'READY'} onClick={() => sendCommand('usage.open', {})}>打开本地面板</button><button onClick={() => sendCommand('usage.status', {})}><RotateCcw />重新检查</button><button onClick={() => sendCommand('usage.copyStartCommand', {})}><Copy />复制启动命令</button></div>{tokenTracker?.state !== 'READY' && <p className="muted usage-help">请在系统终端运行 <code>{tokenTracker?.installCommand ?? 'TOKENTRACKER_NO_TELEMETRY=1 npx tokentracker-cli'}</code>，启动后再点击重新检查。首次安装和升级由你在终端确认，OmniCode 不会静默执行 npm/npx。</p>}</SettingCard>;
  if (tab === 'enhancer') return <ProjectContextSettingsCard snapshot={snapshot} />;
  if (tab === 'pet') return <SettingCard title="桌宠与主题" description="个性化能力收纳在设置中，不占用主导航。">
    <div className="form-grid">
      <label>界面主题<select value={snapshot.theme.id} onChange={(event) => sendCommand('settings.pet', { themeId: event.target.value })}>{snapshot.themes.map((theme) => <option key={theme.id} value={theme.id}>{theme.name}</option>)}</select></label>
      <label>桌宠<select value={snapshot.theme.petId} onChange={(event) => sendCommand('settings.pet', { petId: event.target.value })}>{snapshot.pets.map((pet) => <option key={pet.id} value={pet.id}>{pet.glyph} {pet.name}</option>)}</select></label>
    </div>
    <div className="toggle-row"><span><strong>显示桌宠</strong><small>在聊天空闲和运行状态间切换动画。</small></span><button className={`switch ${snapshot.theme.petEnabled ? 'on' : ''}`} onClick={() => sendCommand('settings.pet', { enabled: !snapshot.theme.petEnabled })}><span /></button></div>
  </SettingCard>;
  if (tab === 'agents') return <SettingCard title="Agents" description="自动路由仍由任务复杂度决定；这里只调整可靠性策略。">
    <div className="toggle-row"><span><strong>持续执行</strong><small>一直运行到完成、用户取消或安全边界阻止。</small></span><button className={`switch ${snapshot.platform.agentContinuousExecution ? 'on' : ''}`} onClick={() => sendCommand('settings.agentRuntime', { continuousExecution: !snapshot.platform.agentContinuousExecution, providerMaxAttempts: snapshot.platform.providerMaxAttempts })}><span /></button></div>
    <div className="form-grid compact"><label>供应商瞬时失败重试次数<select value={snapshot.platform.providerMaxAttempts} onChange={(event) => sendCommand('settings.agentRuntime', { continuousExecution: snapshot.platform.agentContinuousExecution, providerMaxAttempts: Number(event.target.value) })}>{[1, 2, 3, 4, 5].map((value) => <option key={value} value={value}>{value}</option>)}</select></label></div>
  </SettingCard>;
  if (tab === 'commit') return <SettingCard title="Commit AI" description="根据已审阅的 Git 差异生成提交信息。"><div className="toggle-row"><span><strong>启用 Commit AI</strong></span><button className={`switch ${snapshot.platform.commitAiEnabled ? 'on' : ''}`} onClick={() => sendCommand('settings.commitAi', { enabled: !snapshot.platform.commitAiEnabled })}><span /></button></div></SettingCard>;
  if (tab === 'skills') return <SkillSettings skills={snapshot.skills} />;
  if (tab === 'prompts') return <PromptSettings prompts={snapshot.prompts} />;
  if (tab === 'community') return <SettingCard title="社区" description="OmniCode 是开放源码项目。"><div className="setting-actions"><button onClick={() => sendCommand('navigation.openExternal', { url: 'https://github.com/wuke123222/omnicode-agent' })}>GitHub</button><button onClick={() => sendCommand('navigation.openExternal', { url: 'https://plugins.jetbrains.com/plugin/33002-omnicode-agent' })}>Marketplace</button></div></SettingCard>;
  if (tab === 'other') return <SettingCard title="其他" description="安全、隐私与故障排查入口。"><div className="feature-row"><ShieldCheck /><span><strong>本地安全边界</strong><small>密钥留在 Password Safe；危险操作仍需原生审批并经过沙箱。</small></span></div><div className="setting-actions"><button onClick={() => sendCommand('navigation.openExternal', { url: 'https://github.com/wuke123222/omnicode-agent/blob/main/SECURITY.md' })}>安全说明</button><button onClick={() => sendCommand('navigation.openExternal', { url: 'https://github.com/wuke123222/omnicode-agent/blob/main/PRIVACY.md' })}>隐私说明</button><button onClick={() => sendCommand('runtime.probe', {})}>重新检测本地引擎</button></div></SettingCard>;
  if (tab === 'basic') return <SettingCard title="常规" description="历史、语言、通知和数据保留。"><div className="form-grid"><label>历史保留数量<input type="number" defaultValue={snapshot.platform.historyRetention} onBlur={(event) => sendCommand('settings.historyRetention', { value: Number(event.target.value) })} /></label><label>用量保留天数<input type="number" defaultValue={snapshot.platform.usageRetentionDays} onBlur={(event) => sendCommand('settings.usageRetention', { value: Number(event.target.value) })} /></label></div></SettingCard>;
  return <SettingCard title={SETTINGS_TABS.find(([id]) => id === tab)?.[1] ?? '设置'} description="该配置已整合到新的 CCGUI 风格设置中心。"><p className="muted">此页面会通过安全桥接读取和保存本地配置，不会把密钥发送到 WebView。</p></SettingCard>;
}

function SettingsView({ snapshot, catalog, catalogState, runtimes, models, mcpTest, mcpAuth, mcpDraftSelection, tokenTracker }: { snapshot: SettingsSnapshot; catalog: McpCatalogEntryView[]; catalogState: McpCatalogState; runtimes: RuntimeStatusView[]; models: string[]; mcpTest: McpTestState | null; mcpAuth: McpAuthState | null; mcpDraftSelection: McpDraftSelection | null; tokenTracker: TokenTrackerView | null }) {
  const [tab, setTab] = useState('basic');
  const [collapsed, setCollapsed] = useState(false);
  useEffect(() => { if (tab === 'dependencies' && !runtimes.length) sendCommand('runtime.probe', {}); }, [tab, runtimes.length]);
  return <main className={`settings-page ${collapsed ? 'collapsed' : ''}`}>
    <aside><button className="collapse-button" onClick={() => setCollapsed(!collapsed)}>{collapsed ? <ChevronRight /> : <ChevronDown />}</button>{SETTINGS_TABS.map(([id, label]) => <button key={id} title={label} className={tab === id ? 'active' : ''} onClick={() => setTab(id)}><span className="settings-dot" />{!collapsed && label}</button>)}</aside>
    <div className="settings-content"><div className="page-heading"><div><h1>{SETTINGS_TABS.find(([id]) => id === tab)?.[1]}</h1><p>OmniCode 设置</p></div></div><SettingsContent tab={tab} snapshot={snapshot} catalog={catalog} catalogState={catalogState} runtimes={runtimes} models={models} mcpTest={mcpTest} mcpAuth={mcpAuth} mcpDraftSelection={mcpDraftSelection} tokenTracker={tokenTracker} /></div>
  </main>;
}

export function App() {
  const [view, setView] = useState<RootView>('chat');
  const [projectName, setProjectName] = useState('OmniCode');
  const [sessionId, setSessionId] = useState('');
  const [blocks, setBlocks] = useState<ChatBlock[]>([]);
  const [historyEntries, setHistoryEntries] = useState<HistoryEntry[]>([]);
  const [settingsSnapshot, setSettingsSnapshot] = useState<SettingsSnapshot>(EMPTY_SETTINGS);
  const [mcpCatalog, setMcpCatalog] = useState<McpCatalogEntryView[]>([]);
  const [mcpCatalogState, setMcpCatalogState] = useState<McpCatalogState>({ state: 'idle', total: 0, shown: 0, notice: '打开市场后加载官方 Registry。', fromCache: false });
  const [mcpTest, setMcpTest] = useState<McpTestState | null>(null);
  const [mcpAuth, setMcpAuth] = useState<McpAuthState | null>(null);
  const [mcpDraftSelection, setMcpDraftSelection] = useState<McpDraftSelection | null>(null);
  const [tokenTracker, setTokenTracker] = useState<TokenTrackerView | null>(null);
  const [diagnostics, setDiagnostics] = useState<DiagnosticsState | null>(null);
  const [runtimes, setRuntimes] = useState<RuntimeStatusView[]>([]);
  const [models, setModels] = useState<string[]>([]);
  const [fileSuggestions, setFileSuggestions] = useState<string[]>([]);
  const [plan, setPlan] = useState<PlanProposal | null>(null);
  const [review, setReview] = useState<ChangeReview | null>(null);
  const [providerStatus, setProviderStatus] = useState('正在载入…');
  const [running, setRunning] = useState(false);
  const [mode, setMode] = useState<RunMode>('AGENT');
  const [strategy, setStrategy] = useState<RunStrategy>('AUTO');
  const [prefill, setPrefill] = useState({ text: '', revision: 0 });
  const [toast, setToast] = useState('');
  const activeProviderRef = useRef('');
  const activeSessionRef = useRef('');
  const sessionBlocksRef = useRef<Record<string, ChatBlock[]>>({});
  const runningSessionsRef = useRef<Set<string>>(new Set());
  const reviewBySessionRef = useRef<Record<string, ChangeReview | null>>({});
  const terminalTurnsRef = useRef<Set<string>>(new Set());
  const lastSequenceByTurnRef = useRef<Record<string, number>>({});

  useEffect(() => {
    activeProviderRef.current = settingsSnapshot.provider.id;
  }, [settingsSnapshot.provider.id]);

  useEffect(() => {
    const root = document.documentElement;
    const variables = ['--bg', '--surface', '--surface-2', '--bg-elevated', '--text', '--muted', '--blue', '--line', '--green', '--warning', '--red'];
    if (settingsSnapshot.theme.id === 'jetbrains-native') {
      variables.forEach((name) => root.style.removeProperty(name));
      return;
    }
    const palette = settingsSnapshot.theme.palette;
    root.style.setProperty('--bg', palette.background);
    root.style.setProperty('--surface', palette.surface);
    root.style.setProperty('--surface-2', palette.elevatedSurface);
    root.style.setProperty('--bg-elevated', palette.elevatedSurface);
    root.style.setProperty('--text', palette.primaryText);
    root.style.setProperty('--muted', palette.secondaryText);
    root.style.setProperty('--blue', palette.accent);
    root.style.setProperty('--line', palette.border);
    root.style.setProperty('--green', palette.success);
    root.style.setProperty('--warning', palette.warning);
    root.style.setProperty('--red', palette.error);
    return () => variables.forEach((name) => root.style.removeProperty(name));
  }, [settingsSnapshot.theme.id, settingsSnapshot.theme.palette]);

  useEffect(() => subscribeBridge((raw) => {
    const message = parseIncoming(raw);
    if (!message?.type) return;
    if (message.type === 'bootstrap') {
      const payload = message.payload as BootstrapPayload;
      activeSessionRef.current = payload.sessionId;
      sessionBlocksRef.current[payload.sessionId] = payload.blocks ?? [];
      rememberBlockSequences(lastSequenceByTurnRef.current, payload.blocks ?? [], payload.sessionId);
      if (payload.running) runningSessionsRef.current.add(payload.sessionId);
      else runningSessionsRef.current.delete(payload.sessionId);
      setProjectName(payload.projectName); setSessionId(payload.sessionId); setRunning(payload.running);
      setProviderStatus(payload.providerStatus); setBlocks(payload.blocks); setHistoryEntries(payload.history);
      setSettingsSnapshot(payload.settings);
      setModels(payload.models ?? []);
      setPlan(payload.plan ?? null);
      if (payload.mode) setMode(payload.mode);
      if (payload.strategy) setStrategy(payload.strategy);
    } else if (message.type === 'event') {
      const event = message.payload as ChatEventEnvelopeV1;
      if (!isValidEventEnvelope(event)) return;
      const currentPageGeneration = window.__OMNICODE_PAGE_GENERATION__ ?? 0;
      if (event.pageGeneration !== currentPageGeneration) return;
      const turnKey = `${event.sessionId}:${event.turnId}`;
      const terminal = isTerminalRunEvent(event);
      if (!terminal && terminalTurnsRef.current.has(turnKey)) return;
      const previousSequence = lastSequenceByTurnRef.current[sequenceKey(event.sessionId, event.turnId)] ?? 0;
      if (event.sequence > 0 && event.sequence <= previousSequence) return;
      if (event.sequence > 0) lastSequenceByTurnRef.current[sequenceKey(event.sessionId, event.turnId)] = event.sequence;
      const current = sessionBlocksRef.current[event.sessionId] ?? [];
      const next = terminal
        ? settleTurnBlocks(mergeEvent(current, event), event)
        : mergeEvent(current, event);
      sessionBlocksRef.current[event.sessionId] = next;
      if (terminal) {
        terminalTurnsRef.current.add(turnKey);
        runningSessionsRef.current.delete(event.sessionId);
      }
      if (event.sessionId === activeSessionRef.current) {
        setBlocks(next);
        if (terminal) setRunning(false);
      }
    } else if (message.type === 'running') {
      const payload = message.payload as { sessionId?: string; running?: boolean };
      const targetSession = payload.sessionId || activeSessionRef.current;
      if (targetSession) {
        if (payload.running) runningSessionsRef.current.add(targetSession);
        else runningSessionsRef.current.delete(targetSession);
        if (payload.running === false) {
          const current = sessionBlocksRef.current[targetSession] ?? [];
          const settled = settleSessionBlocks(current);
          sessionBlocksRef.current[targetSession] = settled;
          if (targetSession === activeSessionRef.current) setBlocks(settled);
        }
      }
      if (!payload.sessionId || payload.sessionId === activeSessionRef.current) setRunning(Boolean(payload.running));
    } else if (message.type === 'send.rejected') {
      const payload = message.payload as { sessionId?: string; clientMessageId?: string; message?: string };
      const targetSession = payload.sessionId || activeSessionRef.current;
      const clientMessageId = String(payload.clientMessageId ?? '');
      const current = sessionBlocksRef.current[targetSession] ?? [];
      const withoutPriorError = current.filter((block) => block.id !== `${clientMessageId}-rejected`);
      const next: ChatBlock[] = [...withoutPriorError, {
        id: `${clientMessageId || 'send'}-rejected`,
        role: 'system',
        kind: 'run.failed',
        title: '消息尚未发送',
        text: String(payload.message ?? '当前任务未能启动。'),
        status: 'error'
      }];
      sessionBlocksRef.current[targetSession] = next;
      runningSessionsRef.current.delete(targetSession);
      if (targetSession === activeSessionRef.current) {
        setRunning(false);
        setBlocks(next);
      }
    } else if (message.type === 'session.reset') {
      const payload = message.payload as { sessionId: string; mode?: RunMode; strategy?: RunStrategy };
      activeSessionRef.current = payload.sessionId;
      const cached = sessionBlocksRef.current[payload.sessionId] ?? [];
      sessionBlocksRef.current[payload.sessionId] = cached;
      setSessionId(payload.sessionId); setBlocks(cached); setView('chat');
      setPlan(null); setReview(reviewBySessionRef.current[payload.sessionId] ?? null);
      setRunning(runningSessionsRef.current.has(payload.sessionId));
      setMode(payload.mode ?? 'AGENT'); setStrategy(payload.strategy ?? 'AUTO');
    } else if (message.type === 'session.loaded') {
      const payload = message.payload as { sessionId: string; blocks: ChatBlock[]; running?: boolean; mode?: RunMode; strategy?: RunStrategy };
      activeSessionRef.current = payload.sessionId;
      // An explicit lifecycle value is authoritative.  The local set can still contain a stale
      // entry when a previous WebView instance observed `running=true` just before the host
      // completed the turn, which otherwise made every history switch resurrect the spinner.
      const wasRunning = payload.running != null
        ? Boolean(payload.running)
        : runningSessionsRef.current.has(payload.sessionId);
      const cached = sessionBlocksRef.current[payload.sessionId];
      // Live caches include deltas and tool cards that persistence may not have flushed yet.
      // Prefer them whenever this WebView has already observed the session.
      const next = wasRunning
        ? mergeSnapshotBlocks(cached ?? [], payload.blocks)
        : settleSessionBlocks(mergeSnapshotBlocks(cached ?? [], payload.blocks));
      rememberBlockSequences(lastSequenceByTurnRef.current, next, payload.sessionId);
      sessionBlocksRef.current[payload.sessionId] = next;
      if (wasRunning) runningSessionsRef.current.add(payload.sessionId);
      else runningSessionsRef.current.delete(payload.sessionId);
      setSessionId(payload.sessionId); setBlocks(next); setView('chat');
      setReview(reviewBySessionRef.current[payload.sessionId] ?? null);
      setRunning(wasRunning);
      if (payload.mode) setMode(payload.mode);
      if (payload.strategy) setStrategy(payload.strategy);
    } else if (message.type === 'session.timeline' || message.type === 'session.liveTimeline') {
      const payload = message.payload as { sessionId: string; blocks: ChatBlock[]; running?: boolean };
      const current = sessionBlocksRef.current[payload.sessionId] ?? [];
      // A timeline read can race with live deltas. While a run is active (the usual
      // session.liveTimeline path), only merge missing history blocks; never replace the live
      // transcript with an older persistence snapshot.
      // Snapshots can arrive after a live delta even when the running flag has already flipped
      // false. Always merge by block/sequence so a late persistence read cannot erase text or
      // tool cards observed by this WebView.
      const next = payload.running === false
        ? settleSessionBlocks(mergeSnapshotBlocks(current, payload.blocks))
        : mergeSnapshotBlocks(current, payload.blocks);
      sessionBlocksRef.current[payload.sessionId] = next;
      rememberBlockSequences(lastSequenceByTurnRef.current, next, payload.sessionId);
      if (payload.running === true) runningSessionsRef.current.add(payload.sessionId);
      if (payload.running === false) runningSessionsRef.current.delete(payload.sessionId);
      if (payload.sessionId === activeSessionRef.current) setBlocks(next);
      if (payload.sessionId === activeSessionRef.current && payload.running != null) setRunning(payload.running);
    } else if (message.type === 'history') {
      setHistoryEntries(message.payload as HistoryEntry[]);
    } else if (message.type === 'settings') {
      const snapshot = message.payload as SettingsSnapshot;
      activeProviderRef.current = snapshot.provider.id;
      setSettingsSnapshot(snapshot);
    } else if (message.type === 'plan') {
      setPlan((message.payload as PlanProposal | null) ?? null);
    } else if (message.type === 'review') {
      const nextReview = (message.payload as (ChangeReview & { sessionId?: string }) | null) ?? null;
      const targetSession = nextReview?.sessionId || activeSessionRef.current;
      if (targetSession) reviewBySessionRef.current[targetSession] = nextReview;
      if (!nextReview || targetSession === activeSessionRef.current) setReview(nextReview);
    } else if (message.type === 'review.clear') {
      const targetSession = String((message.payload as { sessionId?: string })?.sessionId ?? '');
      if (targetSession) reviewBySessionRef.current[targetSession] = null;
      if (!targetSession || targetSession === activeSessionRef.current) setReview(null);
    } else if (message.type === 'mcp.catalog') {
      setMcpCatalog(message.payload as McpCatalogEntryView[]);
    } else if (message.type === 'mcp.catalogState') {
      setMcpCatalogState(message.payload as McpCatalogState);
    } else if (message.type === 'mcp.draft') {
      const payload = message.payload as { id?: string; warnings?: string[] };
      if (payload.id) setMcpDraftSelection((current) => ({ id: payload.id!, warnings: payload.warnings ?? [], revision: (current?.revision ?? 0) + 1 }));
      setToast(`已添加禁用草案。${payload.warnings?.[0] ?? '请审阅后启用。'}`);
      window.setTimeout(() => setToast(''), 5000);
    } else if (message.type === 'mcp.test') {
      setMcpTest(message.payload as McpTestState);
    } else if (message.type === 'mcp.auth') {
      setMcpAuth(message.payload as McpAuthState);
    } else if (message.type === 'diagnostics') {
      const payload = message.payload as Partial<DiagnosticsState>;
      setDiagnostics({
        state: payload.state === 'running' || payload.state === 'error' ? payload.state : 'success',
        overallStatus: String(payload.overallStatus ?? 'SKIP'),
        durationMillis: Number(payload.durationMillis ?? 0),
        passCount: Number(payload.passCount ?? 0),
        warnCount: Number(payload.warnCount ?? 0),
        failCount: Number(payload.failCount ?? 0),
        skipCount: Number(payload.skipCount ?? 0),
        message: payload.message ? String(payload.message).slice(0, 800) : undefined,
        checks: normalizeDiagnosticsChecks(payload.checks)
      });
    } else if (message.type === 'runtime.reset') {
      setRuntimes([]);
    } else if (message.type === 'runtime.status') {
      const status = message.payload as RuntimeStatusView;
      setRuntimes((current) => [...current.filter((item) => item.id !== status.id), status]);
    } else if (message.type === 'models') {
      const payload = message.payload as { providerId?: string; models?: string[]; status?: string; error?: string };
      if (!payload.providerId || payload.providerId === activeProviderRef.current) setModels(payload.models ?? []);
      setToast(payload.error ?? `${payload.models?.length ?? 0} 个模型 · ${payload.status ?? '加载完成'}`);
      window.setTimeout(() => setToast(''), 3500);
    } else if (message.type === 'composer.files') {
      setFileSuggestions(message.payload as string[]);
    } else if (message.type === 'provider.status') {
      setProviderStatus(String((message.payload as { text?: string })?.text ?? ''));
    } else if (message.type === 'usage.status') {
      const payload = message.payload as Partial<TokenTrackerView>;
      setTokenTracker({
        state: payload.state === 'READY' || payload.state === 'NOT_RUNNING' || payload.state === 'UNVERIFIED_SERVICE' || payload.state === 'ERROR' ? payload.state : 'ERROR',
        detail: String(payload.detail ?? '无法读取 TokenTracker 状态。').slice(0, 500),
        cliInstalled: Boolean(payload.cliInstalled),
        dashboardUrl: String(payload.dashboardUrl ?? 'http://127.0.0.1:7680/'),
        installCommand: String(payload.installCommand ?? 'TOKENTRACKER_NO_TELEMETRY=1 npx tokentracker-cli'),
        documentationUrl: String(payload.documentationUrl ?? 'https://github.com/xiufengsun/TokenTracker')
      });
    } else if (message.type === 'composer.prefill') {
      const payload = message.payload as { text?: string; mode?: RunMode };
      setPrefill((current) => ({ text: String(payload?.text ?? ''), revision: current.revision + 1 }));
      if (payload?.mode) setMode(payload.mode);
      setView('chat');
    } else if (message.type === 'notification') {
      setToast(String((message.payload as { message?: string })?.message ?? message.error ?? '操作完成'));
      window.setTimeout(() => setToast(''), 3500);
    } else if (message.type === 'command.error') {
      const payload = message.payload as { command?: string; message?: string };
      setToast(String(payload?.message ?? message.error ?? `操作 ${payload?.command ?? ''} 未执行`).slice(0, 800));
      window.setTimeout(() => setToast(''), 8000);
    } else if (message.type === 'navigation') {
      const target = String((message.payload as { view?: string })?.view ?? 'chat');
      if (target === 'chat' || target === 'history' || target === 'settings') setView(target);
    }
  }), []);

  // The backend sends the first usage.status together with bootstrap, after it has accepted this
  // page instance. Avoid issuing two commands in the same frame and racing the ready handshake.
  useEffect(() => { sendCommand('frontend.ready', {}); }, []);

  const newSession = useCallback(() => sendCommand('session.new', {}), []);
  const cancelActiveRun = useCallback(() => {
    const targetSession = activeSessionRef.current || sessionId;
    if (!targetSession) return;
    // Stop is an interaction guarantee: do not make the user wait for a provider/CLI socket to
    // acknowledge cancellation before the composer becomes usable again. The host still owns
    // process-tree cleanup and sends its authoritative lifecycle event; terminal turn keys below
    // quarantine any late deltas from the cancelled request.
    const current = sessionBlocksRef.current[targetSession] ?? [];
    current.forEach((block) => {
      if (block.status === 'running' && block.turnId) {
        terminalTurnsRef.current.add(`${targetSession}:${block.turnId}`);
      }
    });
    const settled = settleSessionBlocks(current);
    sessionBlocksRef.current[targetSession] = settled;
    runningSessionsRef.current.delete(targetSession);
    if (targetSession === activeSessionRef.current) {
      setBlocks(settled);
      setRunning(false);
      setToast('已请求停止；后台正在回收 CLI 进程，可继续新建会话。');
      window.setTimeout(() => setToast(''), 4000);
    }
    sendCommand('session.cancel', { sessionId: targetSession });
  }, [sessionId]);
  const send = useCallback((text: string, attachments: AttachmentDraft[]) => {
    const randomId = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
    const clientMessageId = `client-${randomId}`.replace(/[^A-Za-z0-9._:-]/g, '-').slice(0, 200);
    const visibleText = text || attachments.map((item) => item.fileName).join('、');
    const targetSession = activeSessionRef.current;
    const current = sessionBlocksRef.current[targetSession] ?? [];
    const next: ChatBlock[] = current.some((block) => block.id === clientMessageId) ? current : [...current, {
      id: clientMessageId,
      role: 'user',
      kind: 'message.user',
      text: visibleText,
      metadata: { attachments: attachments.map((item) => item.fileName), optimistic: true }
    }];
    sessionBlocksRef.current[targetSession] = next;
    runningSessionsRef.current.add(targetSession);
    setBlocks(next);
    setRunning(true);
    sendCommand('session.send', { text, mode, strategy, attachments, clientMessageId });
  }, [mode, strategy]);

  return <div className="app-shell">
    <header className="topbar">
      <button className={`project-title ${view === 'chat' ? 'active' : ''}`} title="返回聊天" onClick={() => setView('chat')}><span className="bolt">ϟ</span><span><strong>{projectName}</strong><small>AI 工作台</small></span></button>
      <div className="provider-status">{providerStatus}</div>
      <div className={`run-state ${running ? 'running' : ''}`}><span />{running ? '运行中' : '就绪'}</div>
      <span className="mode-pill">{mode === 'CLAUDE_PLAN' ? 'Claude Plan' : mode === 'PLAN' ? 'Plan' : 'Agent'}</span>
      <button className="icon-button" title="新建对话" onClick={newSession}><Plus /></button>
      <button className={`icon-button ${view === 'history' ? 'active' : ''}`} title="历史记录" onClick={() => { setView('history'); sendCommand('session.list', {}); }}><History /></button>
      <button className={`icon-button ${view === 'settings' ? 'active' : ''}`} title="设置" onClick={() => { setView('settings'); sendCommand('settings.snapshot', {}); }}><Settings /></button>
    </header>
    <div className="view-host">
      <div className={view === 'chat' ? 'view-layer active' : 'view-layer hidden'}><ChatView blocks={blocks} running={running} plan={plan} review={review} diagnostics={diagnostics} onCloseDiagnostics={() => setDiagnostics(null)} onRetryDiagnostics={() => sendCommand('connection.diagnose', {})} mode={mode} setMode={setMode} strategy={strategy} setStrategy={setStrategy} onSend={send} onCancel={cancelActiveRun} prefill={prefill} prompts={settingsSnapshot.prompts} fileSuggestions={fileSuggestions} settings={settingsSnapshot} models={models} /></div>
      {view === 'history' && <HistoryView entries={historyEntries} onLoad={(id) => sendCommand('session.load', { id })} onDelete={(id) => sendCommand('session.delete', { id })} />}
      {view === 'settings' && <SettingsView snapshot={settingsSnapshot} catalog={mcpCatalog} catalogState={mcpCatalogState} runtimes={runtimes} models={models} mcpTest={mcpTest} mcpAuth={mcpAuth} mcpDraftSelection={mcpDraftSelection} tokenTracker={tokenTracker} />}
      {view === 'chat' && <EmbeddedPet settings={settingsSnapshot} running={running} />}
    </div>
    {toast && <div className="toast">{toast}</div>}
  </div>;
}
