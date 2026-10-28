import { Component, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { DragEvent, ErrorInfo, ReactNode, UIEvent } from 'react';
import { useVirtualizer } from '@tanstack/react-virtual';
import {
  AlertTriangle, Archive, Bot, Check, ChevronDown, ChevronRight, Circle,
  Clock3, Code2, Copy, FileCode2, FileDiff, FilePlus2, FolderOpen,
  Download, Gauge, GitBranch, History, Image, ListChecks, LoaderCircle, MessageSquare,
  Paperclip, Pencil, Play, Plus, RotateCcw, Search, Send, Settings, ShieldCheck, CornerUpLeft,
  Sparkles, Square, Star, SlidersHorizontal, Trash2, Users, Wrench, X, Heart, Upload, ExternalLink
} from 'lucide-react';
import { sendCommand, subscribeBridge } from './bridge';
import { ComposerChoice } from './components/ComposerChoice';
import { ModelPicker } from './components/ModelPicker';
import { PetCompanion, PetPicker } from './components/PetCompanion';
import { RichMarkdown } from './components/RichMarkdown';
import { fileLinkMarkdown } from './fileReferences';
import { SessionTabBar, type OpenSessionTab } from './components/SessionTabBar';
import { createTranslator, normalizeLocale, type UiLocale } from './i18n';
import {
  isInternalActivity, isPrivateReasoningKind, isTerminalRunEvent, isValidEventEnvelope, mergeEvent, mergeSnapshotBlocks,
  presentationTranscriptBlocks, rememberBlockSequences, sequenceKey, settleSessionBlocks, settleTerminalTurnBlocks, settleTurnBlocks,
  visibleTranscriptBlocks
} from './sessionTimeline';
import type {
  BootstrapPayload, ChatBlock, ChatEventEnvelopeV1, DatabaseConnectionView, HistoryEntry, PendingPromptSnapshot,
  McpCatalogEntryView, McpServerView, PlanProposal, PromptTemplateView, RootView,
  NativeHistoryImportView, NativeHistoryView, ProjectContextUsageView, RunMode, RunStrategy,
  RuntimeStatusView, RuntimeVerificationView, SessionTemplateView, SettingsSnapshot, SkillSourceView
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

type ComposerDraft = { text: string; attachments: AttachmentDraft[] };
type ComposerDraftUpdate = ComposerDraft | ((current: ComposerDraft) => ComposerDraft);
const EMPTY_COMPOSER_DRAFT: ComposerDraft = { text: '', attachments: [] };
type HistoryPageState = { before: number; hasMore: boolean; loading: boolean };

/**
 * A prompt that was accepted by the composer while another turn is still running.
 * CCGUI treats this as a first-class per-session queue instead of rejecting the
 * input or turning Enter into an accidental cancel action.
 */
type QueuedPrompt = {
  text: string;
  attachments: AttachmentDraft[];
  mode: RunMode;
  strategy: RunStrategy;
  clientMessageId: string;
  requiresReattach?: boolean;
};

type McpTestState = {
  id: string;
  state: 'idle' | 'running' | 'success' | 'error' | 'action_required';
  message: string;
  tools?: string[];
  action?: 'oauthLogin' | 'oauthDiscover' | 'saveBearer' | 'retry';
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

type DatabaseTestState = {
  id: string;
  state: 'idle' | 'running' | 'success' | 'error';
  message: string;
};

type ProjectMapView = {
  scannedFiles: number;
  truncated: boolean;
  roots: Array<{ name: string; files: number }>;
  languages: Array<{ name: string; files: number }>;
  edges: Array<{ source: string; target: string; files: number }>;
  entryPoints: string[];
  error?: string;
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
  managedByOmniCode?: boolean;
  dashboardUrl: string;
  installCommand: string;
  documentationUrl: string;
  usageError?: string;
  usage?: {
    from: string;
    to: string;
    totalTokens: string;
    inputTokens: string;
    outputTokens: string;
    cachedTokens: string;
    reasoningTokens: string;
    conversations: string;
    costUsd: number;
    activeDays: number;
    averagePerActiveDay: string;
    daily: Array<{ day: string; tokens: string; costUsd: number }>;
    models: Array<{ source: string; model: string; tokens: string; costUsd: number }>;
    projects: Array<{ name: string; tokens: string }>;
  };
};

function usageNumber(value: string | number | undefined): number {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : 0;
}

function compactUsage(value: string | number | undefined): string {
  return new Intl.NumberFormat('zh-CN', { notation: 'compact', maximumFractionDigits: 1 }).format(usageNumber(value));
}

function TokenTrackerDashboard({ status }: { status: TokenTrackerView }) {
  const usage = status.usage;
  if (!usage) return <div className="token-tracker-data-error"><AlertTriangle /><span><strong>统计数据暂不可用</strong><small>{status.usageError ?? '本机服务在线，但没有返回可展示的聚合数据。请重新检查或升级 TokenTracker。'}</small></span></div>;
  const dailyMax = Math.max(1, ...usage.daily.map((point) => usageNumber(point.tokens)));
  const modelMax = Math.max(1, ...usage.models.map((item) => usageNumber(item.tokens)));
  const projectMax = Math.max(1, ...usage.projects.map((item) => usageNumber(item.tokens)));
  return <section className="token-tracker-dashboard" aria-label="TokenTracker 内嵌统计">
    <header className="token-tracker-range"><span>最近 30 天</span><small>{usage.from} — {usage.to} · 数据来自本机 TokenTracker</small></header>
    <div className="usage-metrics">
      <article><small>总 Token</small><strong>{compactUsage(usage.totalTokens)}</strong><span>{usageNumber(usage.totalTokens).toLocaleString('zh-CN')}</span></article>
      <article><small>估算费用</small><strong>${usageNumber(usage.costUsd).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}</strong><span>以 TokenTracker 定价为准</span></article>
      <article><small>会话</small><strong>{compactUsage(usage.conversations)}</strong><span>{usage.activeDays} 个活跃日</span></article>
      <article><small>日均 Token</small><strong>{compactUsage(usage.averagePerActiveDay)}</strong><span>按活跃日计算</span></article>
    </div>
    <div className="usage-chart-card">
      <div className="usage-section-heading"><span><strong>使用趋势</strong><small>每日 Token</small></span><span className="usage-legend"><i />Token</span></div>
      {usage.daily.length > 0 ? <>
        <div className="usage-bars" role="img" aria-label="最近 30 天 Token 使用趋势">{usage.daily.map((point) => <span key={point.day} style={{ height: `${Math.max(3, usageNumber(point.tokens) / dailyMax * 100)}%` }} title={`${point.day} · ${usageNumber(point.tokens).toLocaleString('zh-CN')} tokens · $${usageNumber(point.costUsd).toFixed(2)}`} />)}</div>
        <div className="usage-axis"><span>{usage.daily[0]?.day.slice(5)}</span><span>{usage.daily[usage.daily.length - 1]?.day.slice(5)}</span></div>
      </> : <p className="usage-empty">该时间段暂无使用数据。</p>}
    </div>
    <div className="usage-breakdowns">
      <section><div className="usage-section-heading"><span><strong>模型</strong><small>按 Token 排序</small></span></div>{usage.models.length ? <div className="usage-ranking">{usage.models.map((item) => <article key={`${item.source}/${item.model}`}><div><strong title={item.model}>{item.model}</strong><small>{item.source} · ${usageNumber(item.costUsd).toFixed(2)}</small></div><b>{compactUsage(item.tokens)}</b><span><i style={{ width: `${Math.max(2, usageNumber(item.tokens) / modelMax * 100)}%` }} /></span></article>)}</div> : <p className="usage-empty">暂无模型数据。</p>}</section>
      <section><div className="usage-section-heading"><span><strong>项目</strong><small>仅显示项目名，不暴露仓库地址</small></span></div>{usage.projects.length ? <div className="usage-ranking">{usage.projects.map((item) => <article key={item.name}><div><strong title={item.name}>{item.name}</strong><small>本机项目</small></div><b>{compactUsage(item.tokens)}</b><span><i style={{ width: `${Math.max(2, usageNumber(item.tokens) / projectMax * 100)}%` }} /></span></article>)}</div> : <p className="usage-empty">TokenTracker 尚未记录项目归属。</p>}</section>
    </div>
    <details className="usage-token-details"><summary>Token 构成</summary><div><span>输入 <b>{compactUsage(usage.inputTokens)}</b></span><span>输出 <b>{compactUsage(usage.outputTokens)}</b></span><span>缓存 <b>{compactUsage(usage.cachedTokens)}</b></span><span>推理 <b>{compactUsage(usage.reasoningTokens)}</b></span></div></details>
  </section>;
}

type ChangeReview = {
  sessionId?: string;
  workflowId: string;
  files: Array<{
    path: string;
    decision: 'PENDING' | 'KEPT' | 'ROLLED_BACK' | 'MIXED';
    added: number;
    removed: number;
    hunks: Array<{ id: string; beforeStart: number; afterStart: number; before: string; after: string; beforeCount?: number; afterCount?: number; decision: string }>;
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
  releaseNotesVersion: '',
  provider: {
    id: 'openai', baseUrl: '', model: '', reasoningEffort: 'auto',
    maxOutputTokens: 8192, credentialConfigured: false
  },
  providers: [],
  platform: {
    sandboxMode: 'WORKSPACE_WRITE', historyEnabled: true, notificationsEnabled: true, historyRetention: 100,
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
  appearance: { locale: 'auto', fontFamily: 'system', fontSize: 13, streamingOutput: true },
  support: { wechatQrDataUrl: '', message: '' },
  themes: [], pets: [], projectContext: { pinnedPaths: [], excludedPaths: [], memoryNotes: '' },
  mcpServers: [], databaseConnections: [], prompts: [], skills: [], sessionTemplates: []
};

const SETTINGS_TABS = [
  ['basic', '常规'], ['providers', '供应商'], ['dependencies', '依赖'],
  ['usage', '用量'], ['permissions', '权限'], ['enhancer', '提示增强'],
  ['commit', 'Commit'], ['mcp', 'MCP'], ['databases', '数据库'], ['agents', 'Agents'],
  ['prompts', '提示词'], ['skills', 'Skills'], ['pet', '桌宠'],
  ['other', '其他'], ['community', '社区']
] as const;

declare const __OMNICODE_RELEASE__: { version: string; notes: string[] };

function ReleaseNotesDialog({ onClose }: { onClose: () => void }) {
  return <div className="support-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <section className="release-dialog" role="dialog" aria-modal="true" aria-labelledby="release-title">
      <div className="release-orb"><Sparkles /></div>
      <p className="eyebrow">OMNICODE 更新 · {__OMNICODE_RELEASE__.version}</p>
      <h2 id="release-title">这次更新了什么</h2>
      <ul>{(__OMNICODE_RELEASE__.notes.length ? __OMNICODE_RELEASE__.notes : ['常规稳定性与兼容性改进。']).map((note, index) => <li key={`${index}-${note}`}>{note}</li>)}</ul>
      <div className="release-footer"><button className="text-button" onClick={() => sendCommand('navigation.openExternal', { url: 'https://github.com/wuke123222/omnicode-agent/blob/main/CHANGELOG.md' })}>查看完整更新记录 <ExternalLink size={14} /></button><button className="primary" onClick={onClose}>开始使用</button></div>
    </section>
  </div>;
}

function SupportDialog({ snapshot, onClose }: { snapshot: SettingsSnapshot; onClose: () => void }) {
  const support = snapshot.support ?? { wechatQrDataUrl: '', message: '' };
  return <div className="support-backdrop" role="presentation" onMouseDown={(event) => { if (event.target === event.currentTarget) onClose(); }}>
    <section className="support-dialog" role="dialog" aria-modal="true" aria-labelledby="support-title">
      <button className="support-close icon-button" aria-label="关闭" onClick={onClose}><X /></button>
      <div className="support-mark"><Heart /></div><p className="eyebrow">支持独立开发</p><h2 id="support-title">让 OmniCode 继续变好</h2>
      <p className="support-description">如果它对你的开发有帮助，可以在 GitHub 留下一颗 Star；也可以自愿打赏。所有功能保持免费。</p>
      <button className="github-star-button" onClick={() => sendCommand('navigation.openExternal', { url: 'https://github.com/wuke123222/omnicode-agent' })}><Star size={18} fill="currentColor" />去 GitHub 点个 Star <ExternalLink size={15} /></button>
      <div className="donation-panel"><div><strong>微信打赏</strong><small>{support.message || '自愿支持，不影响任何功能。'}</small></div>{support.wechatQrDataUrl
        ? <img className="donation-qr" src={support.wechatQrDataUrl} alt="微信打赏二维码" />
        : <div className="donation-empty"><Heart /><span>尚未配置收款码</span><small>可在 设置 → 社区 上传你的微信收款码</small></div>}</div>
      <small className="support-footnote">支付由微信直接处理；OmniCode 不接收或保存支付凭据。</small>
    </section>
  </div>;
}

function SupportSettingsCard({ snapshot, onOpenSupport }: { snapshot: SettingsSnapshot; onOpenSupport: () => void }) {
  const support = snapshot.support ?? { wechatQrDataUrl: '', message: '' };
  const [message, setMessage] = useState(support.message);
  const [error, setError] = useState('');
  useEffect(() => setMessage(support.message), [support.message]);
  const selectQr = (file?: File) => {
    if (!file) return;
    if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) { setError('请选择 PNG、JPG 或 WebP 图片。'); return; }
    if (file.size > 1_500_000) { setError('图片不能超过 1.5 MB，请先压缩二维码图片。'); return; }
    const reader = new FileReader();
    reader.onload = () => {
      const dataUrl = String(reader.result ?? '');
      if (!/^data:image\/(png|jpeg|webp);base64,/.test(dataUrl)) { setError('无法读取这张图片。'); return; }
      setError('');
      sendCommand('settings.support', { wechatQrDataUrl: dataUrl, message });
    };
    reader.onerror = () => setError('读取图片失败，请重试。');
    reader.readAsDataURL(file);
  };
  const saveMessage = () => sendCommand('settings.support', { wechatQrDataUrl: support.wechatQrDataUrl, message });
  return <div className="support-settings-card">
    <div className="support-settings-intro"><div className="support-mark"><Heart /></div><div><strong>让喜欢的开发工具持续成长</strong><p>所有功能免费、不会因打赏解锁权益。可选配置你的微信收款码。</p></div></div>
    <div className="donation-config"><div className="donation-preview">{support.wechatQrDataUrl ? <img src={support.wechatQrDataUrl} alt="当前微信收款二维码" /> : <div><Heart /><span>未添加收款码</span></div>}</div><div className="donation-fields"><label>打赏说明<textarea rows={3} maxLength={240} value={message} onChange={(event) => setMessage(event.target.value)} onBlur={saveMessage} placeholder="例如：感谢支持 OmniCode 的持续开发" /></label><div className="setting-actions"><label className="button-like primary"><Upload size={15} />{support.wechatQrDataUrl ? '更换微信收款码' : '上传微信收款码'}<input className="visually-hidden" type="file" accept="image/png,image/jpeg,image/webp" onChange={(event) => selectQr(event.target.files?.[0])} /></label>{support.wechatQrDataUrl && <button className="danger" onClick={() => sendCommand('settings.support', { wechatQrDataUrl: '', message })}><Trash2 />移除</button>}</div>{error && <p className="form-error">{error}</p>}<small>图片以 PNG/JPG/WebP 上传，最大 1.5 MB；仅保存在本机 IDE 设置，不会上传到 OmniCode 服务器或发送给模型。</small></div></div>
    <div className="setting-actions"><button className="github-star-button" onClick={onOpenSupport}><Star size={16} fill="currentColor" />在 GitHub 支持项目 <ExternalLink size={14} /></button><button onClick={() => sendCommand('navigation.openExternal', { url: 'https://plugins.jetbrains.com/plugin/33002-omnicode-agent' })}>Marketplace 页面 <ExternalLink size={14} /></button></div>
  </div>;
}

const ENGINES = [
  ['claude', 'Claude Code'], ['codex', 'Codex'], ['gemini', 'Gemini CLI'], ['grok', 'Grok CLI'], ['kimi', 'Kimi CLI'],
  ['opencode', 'OpenCode'], ['pi', 'Pi CLI'], ['omp', 'OMP CLI'], ['dsh', 'DSH']
] as const;

const SLASH_COMMANDS = [
  { value: '/agent ', label: 'Agent 执行', detail: '允许按当前权限修改项目' },
  { value: '/plan ', label: 'Plan 规划', detail: '只读探索，生成计划后审批' },
  { value: '/claude-plan ', label: 'Claude Plan', detail: 'Claude Code 风格只读规划' },
  { value: '/review ', label: '审阅', detail: '让模型只读审阅代码与变更' },
  { value: '/context', label: '上下文占用', detail: '查看规则、固定文件与 Harness 的 Token 占用' },
  { value: '/map', label: '项目地图', detail: '用 IntelliJ 索引查看模块、语言与关键入口' }
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

/** Localize the deterministic engine boundary report without rewriting ordinary model text. */
function localizedTranscriptText(text: string): string {
  if (!/^Partial result(?:\r?\n|$)/.test(text)) return text;
  return text
    .replace(/^Partial result$/gm, '阶段性结果')
    .replace(/^Achieved$/gm, '已确认完成')
    .replace(/^Evidence$/gm, '已获取证据')
    .replace(/^Remaining$/gm, '尚未完成')
    .replace(/^Risks$/gm, '风险与限制')
    .replace(/^- No task outcome was verified before the run stopped\.$/gm, '- 任务停止前没有可验证的完成结果。')
    .replace(/^- No successful tool evidence or partial model text is available\.$/gm, '- 没有可用的成功工具证据或阶段性模型文本。')
    .replace(/^- Final synthesis and any task steps not proven by the evidence remain incomplete\.$/gm, '- 最终总结以及尚未由证据确认的步骤仍未完成。')
    .replace(/^- No failed tool observation was recorded\.$/gm, '- 未记录到失败的工具观测。')
    .replace(/^- Deterministic boundary summary only; no extra model or tool call was made /gm, '- 这是确定性的运行边界摘要；未额外调用模型或工具 ')
    .replace(/^- Unverified model progress: /gm, '- 尚未核验的模型进度：')
    .replace(/^- Requested but not executed: /gm, '- 已请求但尚未执行：');
}

function downloadConversation(filename: string, content: string) {
  const blob = new Blob([content], { type: 'text/markdown;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename || 'omnicode-session.md';
  anchor.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}

function Markdown({ text }: { text: string }) {
  return <RichMarkdown text={fileLinkMarkdown(localizedTranscriptText(text))} />;
}

function StatusIcon({ block }: { block: ChatBlock }) {
  if (block.kind === 'tools.available') return <Circle size={16} />;
  if (block.status === 'error') return <AlertTriangle size={16} />;
  if (block.status === 'success') return <Check size={16} />;
  if (block.status === 'warning') return <AlertTriangle size={16} />;
  if (block.status === 'running') return <LoaderCircle className="spin" size={16} />;
  return <Circle size={16} />;
}

function SystemBlock({ block }: { block: ChatBlock }) {
  const [expanded, setExpanded] = useState(block.status === 'error');
  useEffect(() => {
    // Errors can arrive after the card was first rendered as a running status. Keep the
    // actionable detail visible instead of leaving the user with an apparently stuck spinner.
    if (block.status === 'error' || block.status === 'warning') setExpanded(true);
  }, [block.status]);
  const isTool = block.kind.startsWith('tool.');
  const isAvailability = block.kind === 'tools.available';
  const availabilityEntries = isAvailability && Array.isArray(block.metadata?.entries)
    ? block.metadata.entries.slice(0, 40).filter((entry): entry is Record<string, unknown> =>
      Boolean(entry) && typeof entry === 'object' && !Array.isArray(entry))
    : [];
  const recoveryBlocked = availabilityEntries.some((entry) => entry.name === 'recovery' && entry.state === 'unsupported');
  const isAgent = block.kind.startsWith('agent.');
  const Icon = isTool || isAvailability ? Wrench : isAgent ? Bot : block.kind.startsWith('context.') ? FolderOpen : ListChecks;
  const backend = isAgent ? String(block.metadata?.backend ?? '') : '';
  const diagnosticEligible = (block.status === 'error' || block.status === 'warning') &&
    /mcp|连接|模型|cli|超时|tls|握手|运行时|api|oauth|初始化/i.test(`${block.title ?? ''} ${block.text}`);
  const mcpIssue = diagnosticEligible && /mcp/i.test(`${block.title ?? ''} ${block.text}`);
  const modelIssue = diagnosticEligible && /模型|api|供应商|key|密钥/i.test(`${block.title ?? ''} ${block.text}`);
  return (
    <section className={`event-card ${recoveryBlocked ? 'warning' : isAvailability ? 'neutral' : block.status ?? ''}`}>
      <button className="event-summary" aria-expanded={expanded} onClick={() => setExpanded(!expanded)}>
        {expanded ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
        <Icon size={16} />
        <strong>{block.title}</strong>
        {isAvailability && <small className="event-backend">{recoveryBlocked ? '上次操作待确认' : availabilityEntries.length ? `${availabilityEntries.length} 项` : '查看详情'}</small>}
        {backend && <small className="event-backend">{backend}</small>}
        <span className="event-spacer" />
        {recoveryBlocked ? <AlertTriangle size={16} /> : <StatusIcon block={block} />}
      </button>
      {recoveryBlocked && <div className="event-detail" role="status">
        <p>上次操作的结果仍待核实。项目读取可继续{availabilityEntries.some((entry) => entry.name === 'inspect_remote_kubernetes' && entry.state === 'approval') ? '，SSH 连接检查和 Kubernetes 日志查询也可经审批继续' : ''}；其他命令和外部操作暂时停用。</p>
        <div className="event-actions">
          <button onClick={() => sendCommand('recovery.review', {})}><AlertTriangle size={14} />查看待确认操作</button>
        </div>
      </div>}
      {expanded && block.text && <div className="event-detail"><Markdown text={block.text} /></div>}
      {expanded && availabilityEntries.length > 0 && <div className="tool-availability-list">
        {availabilityEntries.map((entry, index) => {
          const state = String(entry.state ?? 'unsupported');
          const stateLabel = state === 'available' ? '已暴露' : state === 'approval' ? '需审批' : state === 'configuration' ? '待配置' : '不可用';
          return <div className="tool-availability-row" key={`${String(entry.name ?? '')}-${index}`}>
            <span className="tool-availability-name">{String(entry.name ?? '').slice(0, 128)}</span>
            <span className={`tool-availability-state ${state}`}>{stateLabel}</span>
            <span className="tool-availability-detail">{String(entry.detail ?? '').slice(0, 240)}</span>
          </div>;
        })}
      </div>}
      {expanded && isAvailability && !recoveryBlocked && availabilityEntries.some((entry) => entry.state === 'configuration' || entry.state === 'unsupported') && <div className="event-actions">
        {!recoveryBlocked && availabilityEntries.some((entry) => entry.name === 'query_database' && (entry.state === 'configuration' || entry.state === 'unsupported')) && <button onClick={() => sendCommand('navigation.view', { view: 'settings', tab: 'databases' })}><Settings size={14} />检查数据库配置</button>}
        {!recoveryBlocked && availabilityEntries.some((entry) => entry.name === 'mcp' && (entry.state === 'configuration' || entry.state === 'unsupported')) && <button onClick={() => sendCommand('navigation.view', { view: 'settings', tab: 'mcp' })}><Settings size={14} />检查 MCP 配置</button>}
      </div>}
      {expanded && diagnosticEligible && <div className="event-actions">
        <button onClick={() => sendCommand('connection.diagnose', {})}><Gauge size={14} />连接诊断</button>
        <button onClick={() => sendCommand('navigation.view', { view: 'settings', tab: modelIssue ? 'providers' : mcpIssue ? 'mcp' : 'dependencies' })}><Settings size={14} />打开设置</button>
        {modelIssue && <button onClick={() => sendCommand('provider.models', {})}><RotateCcw size={14} />重新加载模型</button>}
        {mcpIssue && <button onClick={() => { sendCommand('navigation.view', { view: 'settings', tab: 'mcp' }); sendCommand('mcp.catalog', { query: '', forceRefresh: false }); }}><Wrench size={14} />打开 MCP 市场</button>}
      </div>}
    </section>
  );
}

function MessageBlock({ block, onRewind }: { block: ChatBlock; onRewind?: (messageIndex: number) => void }) {
  if (block.role === 'system') return <SystemBlock block={block} />;
  const queued = block.metadata?.queued === true;
  const historyMatch = block.id.match(/-history-(\d+)$/)
  const historyIndex = historyMatch ? Number(historyMatch[1]) : null
  const canRewind = historyIndex !== null && Number.isInteger(historyIndex) && historyIndex >= 0 && Boolean(onRewind);
  return (
    <article className={`message ${block.role}${queued ? ' queued' : ''}`}>
      <div className="message-avatar">{block.role === 'user' ? '你' : <Sparkles size={17} />}</div>
      <div className="message-content">
        <div className="message-label">{queued ? '待发送' : block.role === 'user' ? '你' : 'OmniCode'}</div>
        <Markdown text={block.text} />
        <div className="message-actions">
          {block.text && <button className="message-action" title="复制" onClick={() => navigator.clipboard?.writeText(localizedTranscriptText(block.text))}><Copy size={14} /></button>}
          {block.text && <button className="message-action" title="引用到输入框" onClick={() => {
            const selected = window.getSelection?.()?.toString().trim();
            const quoted = (selected || localizedTranscriptText(block.text)).slice(0, 12_000);
            sendCommand('composer.prefill', { text: quoted.split('\n').map((line) => `> ${line}`).join('\n') + '\n\n' });
          }}><CornerUpLeft size={14} /></button>}
          {canRewind && <button className="message-action" title="从此处分叉" onClick={() => historyIndex !== null && onRewind?.(historyIndex)}><GitBranch size={14} /></button>}
        </div>
      </div>
    </article>
  );
}

function TurnOutcomeCard({ block, blocks, review, onOpenReview }: { block: ChatBlock; blocks: ChatBlock[]; review: ChangeReview | null; onOpenReview?: () => void }) {
  const turnBlocks = block.turnId ? blocks.filter((item) => item.turnId === block.turnId) : [];
  const toolResults = turnBlocks.filter((item) => item.kind === 'tool.completed');
  const failedTools = toolResults.filter((item) => item.status === 'error').length;
  const databaseCalls = toolResults.filter((item) => item.metadata?.name === 'query_database');
  const mcpCalls = toolResults.filter((item) => typeof item.metadata?.name === 'string' && item.metadata.name.startsWith('mcp__'));
  const verificationEvents = toolResults.flatMap((item) => {
    const evidence = item.metadata?.verification;
    if (!evidence || typeof evidence !== 'object') return [];
    const value = evidence as Record<string, unknown>;
    if (typeof value.label !== 'string' || !Number.isInteger(value.exitCode) || typeof value.timedOut !== 'boolean') return [];
    return [{ label: value.label.slice(0, 80), exitCode: value.exitCode as number, timedOut: value.timedOut, failed: item.status === 'error' }];
  }).slice(0, 8);
  const commandCalls = toolResults.filter((item) => item.metadata?.name === 'run_command');
  const assistantResponded = turnBlocks.some((item) => item.role === 'assistant' && item.text.trim().length > 0);
  const workflowId = String(block.metadata?.workflowId ?? '');
  const changedFiles = workflowId && review?.workflowId === workflowId ? review.files : null;
  const pendingFiles = changedFiles?.filter((file) => file.decision === 'PENDING' || file.decision === 'MIXED').length ?? 0;
  const passedVerifications = verificationEvents.filter((item) => item.exitCode === 0 && !item.timedOut && !item.failed).length;
  const hasVerificationProblem = verificationEvents.length > 0 && passedVerifications !== verificationEvents.length;
  const failed = block.status === 'error';
  const stopped = block.status === 'warning';
  return <section className={`turn-outcome ${failed ? 'failed' : stopped ? 'stopped' : 'completed'}`} aria-label="本轮结果">
    <div className="turn-outcome-heading">
      {failed ? <AlertTriangle size={17} /> : stopped ? <Circle size={17} /> : <Check size={17} />}
      <strong>{failed ? (block.title || '任务未完成') : stopped ? (block.title || '任务已停止') : pendingFiles > 0 ? '运行结束 · 待审阅变更' : '本轮运行结束'}</strong>
    </div>
    <div className="delivery-checks" aria-label="交付证据">
      <div className={assistantResponded ? 'observed' : 'missing'}><small>回复</small><strong>{assistantResponded ? '已收到可见回复' : '未收到可见回复'}</strong></div>
      <div className={changedFiles?.length ? 'observed' : 'missing'}><small>文件</small><strong>{changedFiles ? `已记录 ${changedFiles.length} 个文件变更` : '没有关联的变更记录'}</strong></div>
      <div className={verificationEvents.length ? hasVerificationProblem ? 'problem' : 'observed' : 'missing'}><small>验证</small><strong>{verificationEvents.length > 0 ? `验证命令 ${verificationEvents.length} 项 · 退出码 0 且无超时 ${passedVerifications} 项` : commandCalls.length === 0 ? '验证命令未执行' : '执行过命令，但未记录可判定的验证结果'}</strong></div>
    </div>
    {(toolResults.length > 0 || databaseCalls.length > 0 || mcpCalls.length > 0) && <div className="turn-outcome-facts">
      {toolResults.length > 0 && <span>工具 {toolResults.length} 次{failedTools > 0 ? ` · ${failedTools} 次失败` : ' · 无失败记录'}</span>}
      {databaseCalls.length > 0 && <span>数据库实际调用 {databaseCalls.length} 次 · 成功 {databaseCalls.filter((item) => item.status === 'success').length} 次</span>}
      {mcpCalls.length > 0 && <span>MCP 实际调用 {mcpCalls.length} 次 · 成功 {mcpCalls.filter((item) => item.status === 'success').length} 次</span>}
    </div>}
    {changedFiles && changedFiles.length > 0 && <div className="delivery-files"><small>本轮文件 · 点击在 IDEA 中左右对比</small><div>{changedFiles.slice(0, 3).map((file) => <button key={file.path} title={`在 IDEA 中对比 ${file.path}`} onClick={() => sendCommand('review.openDiff', { workflowId, sessionId: review?.sessionId, path: file.path })}><FileDiff size={14} /><span>{file.path}</span><small>+{file.added} −{file.removed}</small></button>)}{changedFiles.length > 3 && <span>另有 {changedFiles.length - 3} 个文件</span>}</div></div>}
    {verificationEvents.length > 0 && <ul className="turn-outcome-verifications">{verificationEvents.map((item, index) => <li key={`${item.label}-${index}`}><code>{item.label}</code><span>{item.timedOut ? '超时' : `退出码 ${item.exitCode}`}</span></li>)}</ul>}
    {block.text && <div className="turn-outcome-detail"><Markdown text={block.text} /></div>}
    {(changedFiles?.length || verificationEvents.length === 0 || hasVerificationProblem) && <div className="delivery-actions">
      {changedFiles && changedFiles.length > 0 && onOpenReview && <button className="primary" onClick={onOpenReview}><FileDiff size={15} />审阅变更{pendingFiles > 0 ? ` · ${pendingFiles} 待决定` : ''}</button>}
      {(verificationEvents.length === 0 || hasVerificationProblem) && <button onClick={() => sendCommand('composer.prefill', { text: '请基于上一轮结果运行相关验证，列出实际执行的命令、退出码、失败原因和未执行的检查；不要把未运行的测试标为通过。' })}><ListChecks size={15} />草拟验证任务</button>}
    </div>}
    <small>仅汇总当前轮已收到的事件；没有结构化测试记录时，不将测试标为通过。验证命令退出码也不等于完整任务验收。</small>
  </section>;
}

type TranscriptRow =
  | { id: string; kind: 'block'; block: ChatBlock }
  | { id: string; kind: 'activity'; blocks: ChatBlock[] }
  | { id: string; kind: 'plan' };

function rowContainsBlock(row: TranscriptRow, blockId: string): boolean {
  return row.kind === 'block' ? row.block.id === blockId
    : row.kind === 'activity' ? row.blocks.some((block) => block.id === blockId) : false;
}

function ActivitySummary({ blocks, focusedBlockId }: { blocks: ChatBlock[]; focusedBlockId?: string }) {
  const [expanded, setExpanded] = useState(false);
  const completed = blocks.filter((block) => block.status === 'success').length;
  const active = [...blocks].reverse().find((block) => block.status === 'running');
  const allCompleted = completed === blocks.length;
  useEffect(() => {
    if (focusedBlockId && blocks.some((block) => block.id === focusedBlockId)) setExpanded(true);
  }, [focusedBlockId, blocks]);
  return <section className={`activity-summary ${active ? 'running' : allCompleted ? 'complete' : 'pending'}`}>
    <button className="activity-summary-header" aria-expanded={expanded} onClick={() => setExpanded((value) => !value)}>
      {expanded ? <ChevronDown size={15} /> : <ChevronRight size={15} />}
      <ListChecks size={16} />
      <span><strong>{active?.title || '执行过程'}</strong><small>{completed}/{blocks.length} 个阶段完成 · {expanded ? '收起详情' : '查看详情'}</small></span>
      <span className="event-spacer" />
      {active ? <LoaderCircle className="spin" size={16} /> : allCompleted ? <Check size={16} /> : <Circle size={16} />}
    </button>
    {expanded && <div className="activity-summary-detail">{blocks.map((block) => <SystemBlock key={block.id} block={block} />)}</div>}
  </section>;
}

function buildTranscriptRows(blocks: ChatBlock[]): TranscriptRow[] {
  const rows: TranscriptRow[] = [];
  let routine: ChatBlock[] = [];
  const flush = () => {
    if (!routine.length) return;
    // Use a stable row from the first event so arriving stages do not remount or
    // reopen a process the user already collapsed. Errors remain outside this group.
    rows.push({ id: `activity-${routine[0].id}`, kind: 'activity', blocks: routine });
    routine = [];
  };
  blocks.forEach((block) => {
    if (isInternalActivity(block)) {
      if (routine.length && routine[0].turnId !== block.turnId) flush();
      routine.push(block);
    }
    else { flush(); rows.push({ id: block.id, kind: 'block', block }); }
  });
  flush();
  return rows;
}

function Transcript({ blocks, running, streamingOutput, plan, review, historyPage, onLoadOlder, onRewind, focusedBlockId, onFocusHandled, onOpenReview, onOpenSupport }: { blocks: ChatBlock[]; running: boolean; streamingOutput: boolean; plan: PlanProposal | null; review: ChangeReview | null; historyPage?: HistoryPageState; onLoadOlder?: () => void; onRewind?: (messageIndex: number) => void; focusedBlockId?: string; onFocusHandled?: () => void; onOpenReview?: () => void; onOpenSupport: () => void }) {
  const parentRef = useRef<HTMLDivElement>(null);
  const stickToBottomRef = useRef(true);
  const [showJumpToBottom, setShowJumpToBottom] = useState(false);
  // Activity is part of the conversation, not a hidden implementation drawer. Keeping the
  // same ordered list for messages and stage/tool cards makes a turn understandable at a
  // glance and matches the CCGUI interaction model. The dock below remains an optional
  // filtered detail view for Tasks / Agents / Edits.
  const visibleBlocks = useMemo(() => visibleTranscriptBlocks(blocks, running, streamingOutput), [blocks, running, streamingOutput]);
  const presentationBlocks = useMemo(() => presentationTranscriptBlocks(visibleBlocks), [visibleBlocks]);
  const transcriptRows = useMemo(() => {
    const rows = buildTranscriptRows(presentationBlocks);
    if (plan) {
      const last = rows[rows.length - 1];
      // A pending plan stays at the end so its approval remains the next action. Once decided,
      // it becomes process context and belongs above the final answer.
      const beforeAnswer = !running && plan.decision !== 'PENDING'
        && last?.kind === 'block' && last.block.role === 'assistant';
      rows.splice(beforeAnswer ? rows.length - 1 : rows.length, 0, { id: `plan-${plan.id}`, kind: 'plan' });
    }
    return rows;
  }, [presentationBlocks, plan, running]);
  const activeProgress = useMemo(() => [...blocks].reverse().find((block) => (
    isInternalActivity(block) && block.status === 'running'
  )), [blocks]);
  const progressLabel = (activeProgress?.text || activeProgress?.title || '正在处理').replace(/…+$/, '');
  const activityRunning = visibleBlocks.some((block) => block.role === 'system' && block.status === 'running');
  const showThinking = running && !activityRunning;
  const rowVirtualizer = useVirtualizer({
    count: transcriptRows.length,
    getScrollElement: () => parentRef.current,
    estimateSize: (index) => transcriptRows[index]?.kind === 'plan' ? 300
      : transcriptRows[index]?.kind === 'activity' || (transcriptRows[index]?.kind === 'block' && transcriptRows[index].block.role === 'system') ? 54 : 140,
    overscan: 8
  });

  const latestVisibleText = presentationBlocks[presentationBlocks.length - 1]?.text ?? '';
  useEffect(() => {
    if ((!visibleBlocks.length && !plan) || !stickToBottomRef.current) return;
    requestAnimationFrame(() => {
      const element = parentRef.current;
      if (!element) return;
      if (typeof element.scrollTo === 'function') {
        if (plan || transcriptRows.length <= 80) element.scrollTo({ top: element.scrollHeight, behavior: 'auto' });
        else rowVirtualizer.scrollToIndex(transcriptRows.length - 1, { align: 'end' });
      } else element.scrollTop = element.scrollHeight;
    });
  }, [transcriptRows.length, latestVisibleText, running, plan?.revision, plan?.decision, rowVirtualizer]);

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
    if (transcriptRows.length > 80) rowVirtualizer.scrollToIndex(transcriptRows.length - 1, { align: 'end' });
    else element.scrollTo?.({ top: element.scrollHeight, behavior: 'smooth' });
  };

  useEffect(() => {
    if (!focusedBlockId) return;
    const index = transcriptRows.findIndex((row) => rowContainsBlock(row, focusedBlockId));
    if (index < 0) { onFocusHandled?.(); return; }
    if (transcriptRows.length > 80) rowVirtualizer.scrollToIndex(index, { align: 'center' });
    else requestAnimationFrame(() => document.querySelector(`[data-transcript-id="${CSS.escape(transcriptRows[index].id)}"]`)?.scrollIntoView({ block: 'center', behavior: 'smooth' }));
    const timer = window.setTimeout(() => onFocusHandled?.(), 700);
    return () => window.clearTimeout(timer);
  }, [focusedBlockId, onFocusHandled, rowVirtualizer, transcriptRows]);

  if (!visibleBlocks.length && !running && !plan) {
    return (
      <main className="empty-chat">
        <section className="welcome-panel" aria-label="开始新对话">
          <div className="welcome-kicker"><span className="welcome-mark"><Sparkles size={15} /></span><span>OMNICODE <i /> 项目工作区</span></div>
          <h1>从一个清晰的小目标开始</h1>
          <p className="welcome-description">描述你想完成的事情。OmniCode 会结合当前项目，边做边向你汇报。</p>
        <div className="quick-grid">
          <button onClick={() => sendCommand('composer.prefill', { text: '请快速了解这个项目的结构、技术栈和关键入口。' })}><span className="welcome-card-icon"><Search /></span><span className="welcome-card-copy"><strong>了解项目</strong><small>结构、技术栈与关键入口</small></span><ChevronRight className="welcome-card-arrow" /></button>
          <button onClick={() => sendCommand('project.map', {})}><span className="welcome-card-icon map"><FolderOpen /></span><span className="welcome-card-copy"><strong>探索项目地图</strong><small>发现模块之间的关联</small></span><ChevronRight className="welcome-card-arrow" /></button>
          <button onClick={() => sendCommand('composer.prefill', { text: '/plan ' })}><span className="welcome-card-icon plan"><ListChecks /></span><span className="welcome-card-copy"><strong>先规划，再执行</strong><small>把复杂任务拆成可审核步骤</small></span><ChevronRight className="welcome-card-arrow" /></button>
          <button onClick={() => sendCommand('composer.prefill', { text: '/review ' })}><span className="welcome-card-icon review"><FileDiff /></span><span className="welcome-card-copy"><strong>审阅当前改动</strong><small>检查变更并指出潜在问题</small></span><ChevronRight className="welcome-card-arrow" /></button>
        </div>
          <button className="welcome-diagnostic" onClick={() => sendCommand('navigation.view', { view: 'settings', tab: 'dependencies' })}><Gauge size={14} />首次使用？检查本地引擎与模型</button>
          <footer className="welcome-footer">
            <span>选择上方任务，或在下方输入框描述需求</span>
            <button className="welcome-support" onClick={onOpenSupport}><Star size={13} /> GitHub Star <ExternalLink size={12} /></button>
          </footer>
        </section>
      </main>
    );
  }

  // Short conversations are rendered directly. Besides avoiding virtualization
  // setup cost, this guarantees that the optimistic user row is painted in the
  // same frame as the running state. Long histories still use virtual scrolling.
  const renderRow = (row: TranscriptRow) => row.kind === 'plan'
    ? plan && <div className="transcript-inline-plan"><PlanCard plan={plan} running={running} /></div>
    : row.kind === 'activity'
      ? <ActivitySummary blocks={row.blocks} focusedBlockId={focusedBlockId} />
      : row.block.kind === 'run.completed' || row.block.kind === 'run.failed'
        ? <TurnOutcomeCard block={row.block} blocks={blocks} review={review} onOpenReview={onOpenReview} />
        : <MessageBlock block={row.block} onRewind={onRewind} />;
  const olderHistoryButton = historyPage?.hasMore && <button className="load-older" disabled={historyPage.loading} onClick={onLoadOlder}>
    {historyPage.loading ? <LoaderCircle className="spin" size={14} /> : <History size={14} />}
    {historyPage.loading ? '正在加载更早记录…' : '加载更早记录'}
  </button>;

  if (transcriptRows.length <= 80) {
    return (
        <main ref={parentRef} onScroll={onScroll} className="transcript" aria-live="polite">
        {olderHistoryButton}
        <div className="direct-list">
          {transcriptRows.map((row) => <div key={row.id} data-transcript-id={row.id} className={focusedBlockId && rowContainsBlock(row, focusedBlockId) ? 'search-focus' : ''}>{renderRow(row)}</div>)}
        </div>
        {showThinking && <div className="thinking" role="status"><LoaderCircle className="spin" size={15} /><span><strong>{progressLabel}</strong><small>正在处理 · 可随时停止</small></span></div>}
        {showJumpToBottom && <button className="jump-to-bottom" onClick={jumpToBottom}>回到底部 <ChevronDown size={14} /></button>}
      </main>
    );
  }

  return (
    <main ref={parentRef} onScroll={onScroll} className="transcript" aria-live="polite">
      {olderHistoryButton}
      <div className="virtual-list" style={{ height: rowVirtualizer.getTotalSize() }}>
        {rowVirtualizer.getVirtualItems().map((row) => {
          const transcriptRow = transcriptRows[row.index];
          const focused = Boolean(focusedBlockId && rowContainsBlock(transcriptRow, focusedBlockId));
          return (
          <div
            key={transcriptRow.id}
            ref={rowVirtualizer.measureElement}
            data-index={row.index}
            data-transcript-id={transcriptRow.id}
            className={`virtual-row ${focused ? 'search-focus' : ''}`}
            style={{ transform: `translateY(${row.start}px)` }}
          >
            {renderRow(transcriptRow)}
          </div>
          );
        })}
      </div>
      {showThinking && <div className="thinking" role="status"><LoaderCircle className="spin" size={15} /><span><strong>{progressLabel}</strong><small>正在处理 · 可随时停止</small></span></div>}
      {showJumpToBottom && <button className="jump-to-bottom" onClick={jumpToBottom}>回到底部 <ChevronDown size={14} /></button>}
    </main>
  );
}

function ChangeReviewPanel({ review }: { review: ChangeReview | null }) {
  const [expanded, setExpanded] = useState<string | null>(null);
  if (!review?.files.length) return <div className="review-empty"><FileDiff /><span><strong>暂无可审阅修改</strong><small>Agent 的文件修改会在任务完成后出现在这里。</small></span></div>;
  return <div className="review-panel">
    <header><span><strong>已编辑 {review.files.length} 个文件</strong><small>逐文件保留或回退；外部修改冲突时会安全停止。</small></span><button className="danger" onClick={() => window.confirm('确认回退本次任务的全部已记录修改？') && sendCommand('review.rollbackTask', { workflowId: review.workflowId, sessionId: review.sessionId })}><RotateCcw />回退全部</button></header>
    <div className="review-files">{review.files.map((file) => <article key={file.path} className={file.decision.toLowerCase()}>
      <div className="review-file-row">
        <button className="review-file-main" onClick={() => setExpanded(expanded === file.path ? null : file.path)}>{expanded === file.path ? <ChevronDown /> : <ChevronRight />}<FileCode2 /><span><strong>{file.path}</strong><small><b>+{file.added}</b> <i>-{file.removed}</i> · {file.decision}</small></span></button>
        <button className="review-open-diff" onClick={() => sendCommand('review.openDiff', { workflowId: review.workflowId, sessionId: review.sessionId, path: file.path })}><FileDiff size={14} />IDEA 对比</button>
        <button onClick={() => {
          const hunk = file.hunks[0];
          const line = hunk?.afterStart ?? 1;
          const end = hunk ? line + Math.max(0, (hunk.afterCount ?? 1) - 1) : line;
          sendCommand('navigation.openFile', { path: file.path, line, end });
        }}>打开</button>
        <button onClick={() => sendCommand('review.keepFile', { workflowId: review.workflowId, sessionId: review.sessionId, path: file.path })}>保留</button>
        <button className="danger" onClick={() => window.confirm(`回退 ${file.path}？`) && sendCommand('review.rollbackFile', { workflowId: review.workflowId, sessionId: review.sessionId, path: file.path })}>回退</button>
      </div>
      {expanded === file.path && <div className="review-hunks">{file.hunks.map((hunk) => <div key={hunk.id} className={`review-hunk ${hunk.decision.toLowerCase()}`}>
        <header>
          <small>@@ -{hunk.beforeStart},{hunk.beforeCount ?? 1} +{hunk.afterStart},{hunk.afterCount ?? 1} · {hunk.decision}</small>
          <span>
            <button onClick={() => sendCommand('review.keepHunk', { workflowId: review.workflowId, sessionId: review.sessionId, path: file.path, hunkId: hunk.id })}>保留此块</button>
            <button className="danger" onClick={() => window.confirm(`回退 ${file.path} 的这个变更块？`) && sendCommand('review.rollbackHunk', { workflowId: review.workflowId, sessionId: review.sessionId, path: file.path, hunkId: hunk.id })}>回退此块</button>
          </span>
        </header>
      </div>)}</div>}
    </article>)}</div>
  </div>;
}

function ActivityDock({ blocks, review, strategy, openReviewToken = 0 }: { blocks: ChatBlock[]; review: ChangeReview | null; strategy: RunStrategy; openReviewToken?: number }) {
  const [tab, setTab] = useState<'tasks' | 'agents' | 'edits'>('tasks');
  const [open, setOpen] = useState(false);
  const handledReviewToken = useRef(0);
  useEffect(() => {
    if (openReviewToken === 0 || openReviewToken === handledReviewToken.current) return;
    handledReviewToken.current = openReviewToken;
    if (review?.files.length) { setTab('edits'); setOpen(true); }
  }, [openReviewToken, review]);
  const effectiveStrategy = useMemo(() => {
    const selected = [...blocks].reverse().find((block) => block.kind === 'run.strategy')?.metadata?.strategy;
    return selected === 'TEAM' || selected === 'SINGLE' ? selected : strategy;
  }, [blocks, strategy]);
  const counts = useMemo(() => ({
    tasks: blocks.filter((b) => b.kind.startsWith('stage.') || b.kind.startsWith('run.')).length,
    agents: blocks.filter((b) => b.kind.startsWith('agent.')).length,
    edits: review?.files.length ?? 0
  }), [blocks, review]);
  const availableTabs = (['tasks', 'agents', 'edits'] as const).filter((name) => counts[name] > 0);
  const selectedTab = availableTabs.includes(tab) ? tab : availableTabs[0];
  const filtered = blocks.filter((block) => selectedTab === 'agents'
    ? block.kind.startsWith('agent.')
    : selectedTab === 'edits'
      ? block.kind.startsWith('tool.') && /patch|change|edit/i.test(`${block.title} ${block.text}`)
      : block.kind.startsWith('stage.') || block.kind.startsWith('run.'));
  if (!availableTabs.length) return null;
  return (
    <section className={`activity-dock ${open ? 'open' : ''}`}>
      <nav style={{ gridTemplateColumns: `repeat(${availableTabs.length}, minmax(0, 1fr)) 36px` }} aria-label="对话详情">
        {counts.tasks > 0 && <button className={selectedTab === 'tasks' ? 'active' : ''} onClick={() => { setTab('tasks'); setOpen(true); }}><ListChecks />任务 <span>{counts.tasks}</span></button>}
        {counts.agents > 0 && <button className={selectedTab === 'agents' ? 'active' : ''} onClick={() => { setTab('agents'); setOpen(true); }}><Bot />子代理 <span>{counts.agents}</span></button>}
        {counts.edits > 0 && <button className={selectedTab === 'edits' ? 'active' : ''} onClick={() => { setTab('edits'); setOpen(true); }}><FileDiff />编辑 <span>{counts.edits}</span></button>}
        <button className="dock-toggle" aria-label={open ? '收起对话详情' : '展开对话详情'} aria-expanded={open} onClick={() => setOpen(!open)}>{open ? <ChevronDown /> : <ChevronRight />}</button>
      </nav>
      {open && <div className="activity-content">
        {selectedTab === 'edits'
          ? <ChangeReviewPanel review={review} />
          : filtered.length
            ? filtered.slice(-40).map((block) => <SystemBlock key={block.id} block={block} />)
            : selectedTab !== 'agents' && <p>当前任务还没有相关记录。</p>}
        {selectedTab === 'agents' && !filtered.length && <p className="activity-hint">
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

function ContextUsageCard({ usage, onClose }: { usage: ProjectContextUsageView; onClose: () => void }) {
  const total = Math.max(1, usage.estimatedTokens);
  const knownTokens = usage.ruleTokens + usage.memoryTokens + usage.pinnedTokens + usage.harnessTokens;
  const otherTokens = Math.max(0, usage.estimatedTokens - knownTokens);
  const segments = [
    { id: 'rules', label: '项目规则', value: usage.ruleTokens, detail: `${usage.rulePaths.length} 个规则文件` },
    { id: 'memory', label: '项目记忆', value: usage.memoryTokens, detail: '用户维护、持久化且已脱敏的项目事实' },
    { id: 'pinned', label: '固定文件', value: usage.pinnedTokens, detail: `${usage.pinnedPaths.length} 个文件` },
    { id: 'harness', label: 'Harness / 项目图', value: usage.harnessTokens, detail: usage.harnessReadiness ? `${usage.harnessReadiness} · ${usage.harnessFeedbackLoopCount} 个反馈环` : '未发现 Harness' },
    { id: 'other', label: '结构开销', value: otherTokens, detail: '段落分隔与上下文元数据' }
  ].filter((segment) => segment.value > 0);
  return <section className="context-usage-card" role="dialog" aria-label="上下文占用">
    <header><div><strong>上下文占用</strong><small>下一轮将使用的只读项目上下文估算</small></div><button className="icon-button" title="关闭上下文占用" onClick={onClose}><X /></button></header>
    <div className="context-meter" aria-label={`约 ${usage.estimatedTokens.toLocaleString()} tokens`}>
      {segments.map((segment) => <span key={segment.id} className={segment.id} style={{ flexGrow: segment.value }} title={`${segment.label} · 约 ${segment.value.toLocaleString()} tokens`} />)}
    </div>
    <div className="context-total"><strong>约 {usage.estimatedTokens.toLocaleString()} tokens</strong><span>{usage.includedCharacters.toLocaleString()} 字符 · 排除 {usage.excludedPathCount} 项{usage.truncated ? ' · 已按安全上限截断' : ''}</span></div>
    <div className="context-breakdown">{segments.map((segment) => <article key={segment.id}><span className={`context-dot ${segment.id}`} /><div><strong>{segment.label}</strong><small>{segment.detail}</small></div><b>≈ {segment.value.toLocaleString()}</b></article>)}</div>
    {(usage.rulePaths.length > 0 || usage.pinnedPaths.length > 0) && <details><summary>查看使用来源</summary><div className="context-paths">{usage.rulePaths.map((path) => <code key={`rule-${path}`}>{path}</code>)}{usage.pinnedPaths.map((path) => <code key={`pin-${path}`}>{path}</code>)}</div></details>}
  </section>;
}

function ProjectMapGraph({ map }: { map: ProjectMapView }) {
  type GraphNode = { id: string; x: number; y: number; width: number; label: string; kind: 'root' | 'language' | 'entry'; files?: number; path?: string };
  const rootLimit = 6;
  const languageLimit = 5;
  const rootCandidates = map.roots.slice(0, rootLimit);
  const languageCandidates = map.languages.slice(0, languageLimit);
  const rootIds = new Set(rootCandidates.map((item) => `root:${item.name}`));
  const languageIds = new Set(languageCandidates.map((item) => `language:${item.name}`));
  const candidates = map.edges
    .filter((edge) => rootIds.has(edge.source) && languageIds.has(edge.target))
    .sort((left, right) => right.files - left.files);
  const selected = new Map<string, { source: string; target: string; files: number }>();
  const rootDegrees = new Map<string, number>();
  const languageDegrees = new Map<string, number>();
  const addEdge = (edge: { source: string; target: string; files: number }) => {
    const key = `${edge.source}>${edge.target}`;
    if (selected.has(key) || (rootDegrees.get(edge.source) ?? 0) >= 2 || (languageDegrees.get(edge.target) ?? 0) >= 3) return;
    selected.set(key, edge);
    rootDegrees.set(edge.source, (rootDegrees.get(edge.source) ?? 0) + 1);
    languageDegrees.set(edge.target, (languageDegrees.get(edge.target) ?? 0) + 1);
  };
  rootCandidates.forEach((root) => candidates.find((edge) => edge.source === `root:${root.name}`) && addEdge(candidates.find((edge) => edge.source === `root:${root.name}`)!));
  candidates.forEach(addEdge);
  const structuralEdges = [...selected.values()].slice(0, 11);
  const graphRootNames = new Set(structuralEdges.map((edge) => edge.source.slice('root:'.length)));
  const graphLanguageNames = new Set(structuralEdges.map((edge) => edge.target.slice('language:'.length)));
  const rootRank = (name: string) => {
    const edge = structuralEdges.find((item) => item.source === `root:${name}`);
    return edge ? languageCandidates.findIndex((language) => `language:${language.name}` === edge.target) : languageCandidates.length;
  };
  const roots = rootCandidates.filter((item) => graphRootNames.has(item.name)).sort((left, right) => rootRank(left.name) - rootRank(right.name) || right.files - left.files);
  const languages = languageCandidates.filter((item) => graphLanguageNames.has(item.name));
  const entryRoot = (path: string) => path.includes('/') ? path.split('/')[0] : '项目根目录';
  const entries = map.entryPoints
    .filter((path) => graphRootNames.has(entryRoot(path)))
    .slice(0, 5);
  const fallbackEntries = entries.length > 0 ? entries : map.entryPoints.slice(0, 4);
  const height = Math.max(350, 108 + Math.max(roots.length, languages.length, fallbackEntries.length) * 68);
  const positions = new Map<string, GraphNode>();
  const layout = (items: Array<{ name?: string; files?: number; path?: string }>, prefix: 'root' | 'language' | 'entry', x: number, width: number) => {
    items.forEach((item, index) => {
      const label = item.name ?? item.path ?? '';
      positions.set(`${prefix}:${label}`, { id: `${prefix}:${label}`, x, y: 64 + index * ((height - 128) / Math.max(1, items.length - 1)), width, label, kind: prefix, files: item.files, path: item.path });
    });
  };
  layout(roots, 'root', 150, 202);
  layout(languages, 'language', 475, 190);
  layout(fallbackEntries.map((path) => ({ path })), 'entry', 810, 230);
  const entryEdges = fallbackEntries.map((path) => ({ source: `root:${entryRoot(path)}`, target: `entry:${path}`, files: 1 }));
  const graphEdges = [...structuralEdges, ...entryEdges].filter((edge) => positions.has(edge.source) && positions.has(edge.target));
  const labelFor = (label: string, length: number) => label.length > length ? `${label.slice(0, length - 1)}…` : label;
  const activate = (node: GraphNode) => {
    if (node.kind === 'entry' && node.path) sendCommand('navigation.openFile', { path: node.path, line: 1, end: 1 });
    else if (node.kind === 'root') sendCommand('composer.prefill', { text: `请分析 @${node.label} 的职责、依赖和关键入口。` });
    else sendCommand('composer.prefill', { text: `请梳理项目中 ${node.label} 相关代码的模块分布与关键入口。` });
  };
  return <div className="project-map-graph" aria-label="项目模块关系图">
    <div className="project-map-legend"><span><i className="root" />目录 / 模块</span><span><i className="language" />技术栈</span><span><i className="entry" />关键入口</span><b>{graphEdges.length} 条已统计关系</b><small>连线只表示文件所在目录、语言和入口位置，不表示调用或依赖</small></div>
    <svg viewBox={`0 0 960 ${height}`} role="img" aria-label="项目目录、语言和入口文件位置图">
      <defs><linearGradient id="map-flow" x1="0" x2="1"><stop stopColor="var(--blue)" stopOpacity=".8" /><stop offset="1" stopColor="var(--purple)" stopOpacity=".46" /></linearGradient><filter id="map-glow"><feGaussianBlur stdDeviation="4" result="blur" /><feMerge><feMergeNode in="blur" /><feMergeNode in="SourceGraphic" /></feMerge></filter></defs>
      <g className="project-map-column-label"><text x="150" y="25">模块</text><text x="475" y="25">技术栈</text><text x="810" y="25">关键入口</text></g>
      {graphEdges.map((edge, index) => {
        const source = positions.get(edge.source)!; const target = positions.get(edge.target)!;
        const direction = target.x > source.x ? 1 : -1;
        const start = source.x + direction * source.width / 2;
        const end = target.x - direction * target.width / 2;
        const bend = Math.abs(end - start) * .46;
        return <path key={`${edge.source}-${edge.target}-${index}`} className="project-map-edge" d={`M ${start} ${source.y} C ${start + direction * bend} ${source.y}, ${end - direction * bend} ${target.y}, ${end} ${target.y}`} style={{ strokeWidth: Math.min(4.5, 1.2 + Math.log2(1 + edge.files)) }} />;
      })}
      {[...positions.values()].map((node) => <g key={node.id} className={`project-map-node ${node.kind}`} role="button" tabIndex={0} aria-label={`${node.kind === 'entry' ? '入口 ' : ''}${node.label}${node.files ? `，${node.files} 个文件` : ''}`} onClick={() => activate(node)} onKeyDown={(event) => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); activate(node); } }}>
        <rect x={node.x - node.width / 2} y={node.y - 23} width={node.width} height="46" rx="14" filter="url(#map-glow)" />
        <circle cx={node.x - node.width / 2 + 18} cy={node.y} r="4" />
        <text className="project-map-label" x={node.x - node.width / 2 + 32} y={node.y + 5} textAnchor="start">{labelFor(node.label, node.kind === 'entry' ? 22 : 19)}</text>
        {node.files != null && <text className="project-map-count" x={node.x + node.width / 2 - 15} y={node.y + 5} textAnchor="end">{node.files}</text>}
      </g>)}
    </svg>
  </div>;
}

function ProjectMapCard({ map, onClose }: { map: ProjectMapView; onClose: () => void }) {
  return <section className="project-map-card" role="dialog" aria-label="项目地图">
    <header><div><strong>项目地图</strong><small>来自 IntelliJ 内容索引；只读取路径元数据，不读取文件正文</small></div><button className="icon-button" title="关闭项目地图" onClick={onClose}><X /></button></header>
    {map.error ? <p className="project-map-error">{map.error}</p> : <>
      <div className="project-map-summary"><strong>{map.scannedFiles.toLocaleString()}</strong><span>个索引文件{map.truncated ? ' · 已按安全上限截断' : ''}</span></div>
      <ProjectMapGraph map={map} />
      <div className="project-map-columns">
        <div><h3>主要目录 / 模块</h3>{map.roots.map((item) => <button key={item.name} onClick={() => sendCommand('composer.prefill', { text: `请分析 @${item.name} 的职责与关键入口。` })}><FolderOpen /><span>{item.name}</span><b>{item.files}</b></button>)}</div>
        <div><h3>技术栈</h3>{map.languages.map((item) => <article key={item.name}><Code2 /><span>{item.name}</span><b>{item.files}</b></article>)}</div>
      </div>
      {map.entryPoints.length > 0 && <div className="project-map-entry"><h3>关键入口</h3>{map.entryPoints.map((path) => <button key={path} onClick={() => sendCommand('navigation.openFile', { path, line: 1, end: 1 })}><FileCode2 />{path}</button>)}</div>}
    </>}
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
  const [editingStepId, setEditingStepId] = useState<string | null>(null);
  const cancelStepEditRef = useRef(false);
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
        const canEdit = editable && step.state !== 'SKIPPED';
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
            {editingStepId === step.id
              ? <textarea
                  aria-label={`编辑步骤 ${index + 1}`}
                  autoFocus
                  rows={1}
                  value={text}
                  onChange={(event) => setDrafts((current) => ({ ...current, [step.id]: event.target.value }))}
                  onKeyDown={(event) => {
                    if (event.key === 'Escape') { event.preventDefault(); cancelStepEditRef.current = true; setDrafts((current) => ({ ...current, [step.id]: step.text })); setEditingStepId(null); }
                    if (event.key === 'Enter' && (event.metaKey || event.ctrlKey)) { event.preventDefault(); event.currentTarget.blur(); }
                  }}
                  onBlur={() => {
                    if (!cancelStepEditRef.current && text.trim() && text.trim() !== step.text) sendCommand('plan.updateStep', { stepId: step.id, text: text.trim() });
                    cancelStepEditRef.current = false;
                    setEditingStepId(null);
                  }}
                />
              : canEdit
                ? <button type="button" className="plan-step-text editable" title="点击编辑步骤" aria-label={`编辑步骤 ${index + 1}`} onClick={() => setEditingStepId(step.id)}><span>{text}</span><Pencil size={12} /></button>
                : <div className="plan-step-text"><span>{text}</span></div>}
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
  const selected = Array.from(files);
  if (selected.length > 4) throw new Error('每条消息最多添加 4 个附件');
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

/** JCEF may expose an IDE-originated drag as a URI or native text, not as HTML5 Files. */
function droppedProjectPaths(data: DataTransfer): string[] {
  const uris = data.getData('text/uri-list').split(/\r?\n/).filter((line) => line && !line.startsWith('#'));
  const plain = data.getData('text/plain').trim();
  const candidates = uris.length ? uris : plain.startsWith('file://') || /^(\/|[A-Za-z]:[\\/])/.test(plain) ? [plain] : [];
  return candidates.slice(0, 32).map((value) => {
    if (!value.startsWith('file://')) return value;
    try {
      const url = new URL(value);
      if (url.protocol !== 'file:' || (url.hostname && url.hostname !== 'localhost')) return '';
      const path = decodeURIComponent(url.pathname);
      return /^\/[A-Za-z]:\//.test(path) ? path.slice(1) : path;
    } catch { return ''; }
  }).filter((value) => value.length > 0 && value.length <= 4096);
}

function Composer({
  running, cleanupPending, queuedPrompts, queuePaused, onQueueRemove, onQueueEdit, onQueueMove, onQueuePause, onQueueRestore, mode, setMode, strategy, setStrategy, onSend, onCancel, prefill, prompts, fileSuggestions, settings, models, draft, onDraftChange, onFilesSelected, locale
}: {
  running: boolean;
  cleanupPending: boolean;
  queuedPrompts: QueuedPrompt[];
  queuePaused: boolean;
  onQueueRemove: (id: string) => void;
  onQueueEdit: (id: string, text: string) => void;
  onQueueMove: (id: string, direction: -1 | 1) => void;
  onQueuePause: (paused: boolean) => void;
  onQueueRestore: (id: string) => void;
  mode: RunMode;
  setMode: (mode: RunMode) => void;
  strategy: RunStrategy;
  setStrategy: (strategy: RunStrategy) => void;
  onSend: (text: string, attachments: AttachmentDraft[]) => boolean;
  onCancel: () => void;
  prefill: { text: string; revision: number };
  prompts: PromptTemplateView[];
  fileSuggestions: string[];
  settings: SettingsSnapshot;
  models: string[];
  draft: ComposerDraft;
  onDraftChange: (update: ComposerDraftUpdate) => void;
  onFilesSelected: (files: FileList | File[]) => void;
  locale: UiLocale;
}) {
  const t = createTranslator(locale);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const { text, attachments } = draft;
  const setText = (value: string | ((current: string) => string)) => onDraftChange((current) => ({
    ...current,
    text: typeof value === 'function' ? value(current.text) : value
  }));
  const setAttachments = (value: AttachmentDraft[] | ((current: AttachmentDraft[]) => AttachmentDraft[])) => onDraftChange((current) => ({
    ...current,
    attachments: typeof value === 'function' ? value(current.attachments) : value
  }));
  const fileInput = useRef<HTMLInputElement>(null);
  const [queueOpen, setQueueOpen] = useState(false);
  const [editingQueueId, setEditingQueueId] = useState<string | null>(null);
  const [queueEditText, setQueueEditText] = useState('');
  const composing = useRef(false);
  const compositionEndedAt = useRef(0);
  const slashMatch = text.match(/^\/[^\s]*$/);
  const promptMatch = text.match(/(?:^|\s)!([^\s!]*)$/);
  const fileMatch = text.match(/(?:^|\s)@([^\s@]*)$/);
  const palette = slashMatch ? SLASH_COMMANDS.filter((item) => item.value.startsWith(slashMatch[0].toLowerCase())) : [];
  const promptPalette = promptMatch ? prompts.filter((item) => item.shortcut.toLowerCase().includes(promptMatch[1].toLowerCase())).slice(0, 10) : [];
  const filePalette = fileMatch ? fileSuggestions.slice(0, 12) : [];
  const activeProvider = settings.providers.find((provider) => provider.id === settings.provider.id);
  const automaticModel = Boolean(
    activeProvider?.cli && ['auto', 'default', 'kimi-k2'].includes(settings.provider.model.toLowerCase())
  );
  const currentModelUnavailable = Boolean(
    activeProvider?.cli && !automaticModel && models.length > 0 && !models.includes(settings.provider.model)
  );
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
  const hasDraft = Boolean(text.trim() || attachments.length);
  const submit = () => {
    // An active run only owns the empty composer action (stop). A non-empty
    // composer is always a send action, and is queued by the session controller.
    if (running && !hasDraft) { onCancel(); return; }
    if (!hasDraft) return;
    if (onSend(text.trim(), attachments)) onDraftChange(EMPTY_COMPOSER_DRAFT);
  };
  return (
    <section className="composer-shell">
      {attachments.length > 0 && <div className="attachment-row">
        {attachments.map((file) => <span key={file.id}>{file.kind === 'image' ? <Image /> : <FileCode2 />}{file.fileName}<button type="button" onClick={() => setAttachments((all) => all.filter((item) => item.id !== file.id))}><X /></button></span>)}
      </div>}
      {queuedPrompts.length > 0 && <div className="queued-prompts">
        <div className="queued-prompts-header">
          <button type="button" aria-expanded={queueOpen} onClick={() => setQueueOpen(!queueOpen)}><Clock3 size={14} />待发送 {queuedPrompts.length} 条 {queueOpen ? <ChevronDown size={14} /> : <ChevronRight size={14} />}</button>
          <button type="button" disabled={queuePaused && Boolean(queuedPrompts[0]?.requiresReattach)} onClick={() => onQueuePause(!queuePaused)}>{queuePaused ? '继续队列' : '暂停队列'}</button>
        </div>
        {queueOpen && <ol className="queued-prompts-list">{queuedPrompts.map((prompt, index) => <li key={prompt.clientMessageId}>
          {editingQueueId === prompt.clientMessageId ? <div className="queued-prompt-editor">
            <textarea aria-label="编辑待发送消息" value={queueEditText} onChange={(event) => setQueueEditText(event.target.value)} />
            <button type="button" disabled={!queueEditText.trim() && !prompt.attachments.length} onClick={() => { onQueueEdit(prompt.clientMessageId, queueEditText.trim()); setEditingQueueId(null); }}>保存</button>
            <button type="button" onClick={() => setEditingQueueId(null)}>取消</button>
          </div> : <>
            <span title={prompt.text || prompt.attachments.map((item) => item.fileName).join('、')}>{prompt.text || prompt.attachments.map((item) => item.fileName).join('、')}</span>
            {prompt.requiresReattach && <button type="button" onClick={() => onQueueRestore(prompt.clientMessageId)}>移到输入框并重新添加附件</button>}
            <button type="button" title="编辑待发送消息" onClick={() => { setEditingQueueId(prompt.clientMessageId); setQueueEditText(prompt.text); }}><Pencil size={14} /></button>
            <button type="button" title="上移待发送消息" disabled={index === 0} onClick={() => onQueueMove(prompt.clientMessageId, -1)}>↑</button>
            <button type="button" title="下移待发送消息" disabled={index === queuedPrompts.length - 1} onClick={() => onQueueMove(prompt.clientMessageId, 1)}>↓</button>
            <button type="button" title="删除待发送消息" onClick={() => onQueueRemove(prompt.clientMessageId)}><X size={14} /></button>
          </>}
        </li>)}</ol>}
      </div>}
      <textarea
        value={text}
        onChange={(event) => setText(event.target.value)}
        onCompositionStart={() => { composing.current = true; }}
        onCompositionEnd={() => {
          composing.current = false;
          // JCEF OSR can dispatch the Enter keydown immediately after compositionend. Keep a
          // tiny commit guard so that key commits the IME candidate instead of submitting.
          compositionEndedAt.current = performance.now();
        }}
        placeholder={t('composer.placeholder')}
        aria-keyshortcuts="Enter"
        title={t('composer.enterHint')}
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
          const isComposing = composing.current || event.nativeEvent.isComposing || event.keyCode === 229 ||
            performance.now() - compositionEndedAt.current < 40;
          if (event.key === 'Enter' && !event.shiftKey && !isComposing) {
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
        <input ref={fileInput} type="file" multiple hidden aria-label="选择附件" onChange={(event) => {
          if (event.target.files?.length) onFilesSelected(event.target.files);
          event.target.value = '';
        }} />
        <button type="button" className="icon-button attachment-button" title="添加文件（也可拖到聊天区）" onClick={() => fileInput.current?.click()}><Plus /></button>
        <ComposerChoice
          className="mode-selector"
          title="运行模式"
          value={mode}
          options={[{ value: 'AGENT', label: 'Agent' }, { value: 'PLAN', label: 'Plan' }, { value: 'CLAUDE_PLAN', label: 'Claude Plan' }]}
          onChange={(value) => setMode(value as RunMode)}
        />
        <ComposerChoice
          className="engine-selector"
          title="当前引擎"
          value={settings.provider.id}
          options={settings.providers.filter((provider) => !provider.hidden || provider.id === settings.provider.id).map((provider) => ({ value: provider.id, label: provider.name }))}
          disabled={running}
          onChange={(value) => sendCommand('provider.select', { providerId: value })}
        />
        <ModelPicker
          providerId={settings.provider.id}
          value={settings.provider.model}
          models={models}
          cli={Boolean(activeProvider?.cli)}
          unavailable={currentModelUnavailable}
          disabled={running}
          onSelect={(model) => sendCommand('settings.saveProvider', {
            providerId: settings.provider.id,
            baseUrl: settings.provider.baseUrl,
            model,
            apiKey: '',
            reasoningEffort: settings.provider.reasoningEffort
          })}
        />
        <button type="button" className={`advanced-toggle ${advancedOpen ? 'active' : ''}`} aria-label="更多运行选项" aria-expanded={advancedOpen} title="更多运行选项" onClick={() => setAdvancedOpen((open) => !open)}><SlidersHorizontal /><span>选项</span></button>
        <ComposerChoice
          className={`permission-selector ${settings.platform.sandboxMode === 'DANGER_FULL_ACCESS' ? 'danger' : ''}`}
          title={t('composer.permission')}
          value={settings.platform.sandboxMode}
          options={[
            { value: 'WORKSPACE_WRITE', label: t('composer.workspacePermission') },
            { value: 'DANGER_FULL_ACCESS', label: t('composer.fullAccess') }
          ]}
          onChange={(value) => sendCommand('settings.sandbox', { mode: value })}
        />
        <span className="composer-spacer" />
        {queuedPrompts.length > 0 && <span className="queue-count" role="status" aria-label={t('composer.queue', { count: queuedPrompts.length })}>{t('composer.queue', { count: queuedPrompts.length })}</span>}
        <button type="button" aria-label={running && !hasDraft ? t('composer.stop') : (running || cleanupPending) && hasDraft ? t('composer.queueSend') : t('composer.send')} className={`send-button ${running && !hasDraft ? 'stop' : (running || cleanupPending) && hasDraft ? 'queue' : ''}`} onClick={submit}>
          {running && !hasDraft ? <Square /> : (running || cleanupPending) && hasDraft ? <Clock3 /> : <Send />}
        </button>
      </footer>
      {advancedOpen && <div className="composer-advanced" role="region" aria-label="更多运行选项">
        <div className="composer-advanced-heading"><strong>运行选项</strong><button type="button" aria-label="关闭运行选项" onClick={() => setAdvancedOpen(false)}><X /></button></div>
        <label><span>执行策略</span><ComposerChoice
          className="strategy-selector"
          title="执行策略"
          value={strategy}
          options={[{ value: 'AUTO', label: '自动协作' }, { value: 'SINGLE', label: 'Single' }, { value: 'TEAM', label: 'Team' }]}
          onChange={(value) => setStrategy(value as RunStrategy)}
        /></label>
        <label><span>推理强度</span><ComposerChoice
          className="reasoning-selector"
          title="推理强度"
          value={settings.provider.reasoningEffort}
          options={REASONING_LEVELS.map(([value, label]) => ({ value, label }))}
          disabled={running}
          onChange={(value) => sendCommand('settings.saveProvider', {
            providerId: settings.provider.id,
            baseUrl: settings.provider.baseUrl,
            model: settings.provider.model,
            apiKey: '',
            reasoningEffort: value
          })}
        /></label>
        <button type="button" className="refresh-models" title={t('composer.refreshModels')} disabled={running} onClick={() => sendCommand('provider.models', {})}><RotateCcw />刷新模型</button>
      </div>}
    </section>
  );
}

function ChatView({
  blocks, running, cleanupPending, queuedPrompts, queuePaused, onQueueRemove, onQueueEdit, onQueueMove, onQueuePause, onQueueRestore, historyPage, onLoadOlder, plan, review, diagnostics, contextUsage, projectMap, onCloseDiagnostics, onCloseContextUsage, onCloseProjectMap, onRetryDiagnostics, mode, setMode, strategy, setStrategy, onSend, onCancel, prefill, prompts, fileSuggestions, settings, models, draft, onDraftChange, onRewind, locale, focusedBlockId, onFocusHandled, onOpenSupport
}: {
  blocks: ChatBlock[]; running: boolean; cleanupPending: boolean; queuedPrompts: QueuedPrompt[]; queuePaused: boolean; onQueueRemove: (id: string) => void; onQueueEdit: (id: string, text: string) => void; onQueueMove: (id: string, direction: -1 | 1) => void; onQueuePause: (paused: boolean) => void; onQueueRestore: (id: string) => void; historyPage?: HistoryPageState; onLoadOlder?: () => void; plan: PlanProposal | null; review: ChangeReview | null; diagnostics: DiagnosticsState | null;
  contextUsage: ProjectContextUsageView | null; projectMap: ProjectMapView | null; onCloseDiagnostics: () => void; onCloseContextUsage: () => void; onCloseProjectMap: () => void; onRetryDiagnostics: () => void; mode: RunMode; setMode: (mode: RunMode) => void;
  strategy: RunStrategy; setStrategy: (strategy: RunStrategy) => void;
  onSend: (text: string, attachments: AttachmentDraft[]) => boolean; onCancel: () => void; prefill: { text: string; revision: number };
  prompts: PromptTemplateView[]; fileSuggestions: string[]; settings: SettingsSnapshot; models: string[]; draft: ComposerDraft; onDraftChange: (update: ComposerDraftUpdate) => void; onRewind: (messageIndex: number) => void;
  locale: UiLocale;
  focusedBlockId?: string; onFocusHandled?: () => void; onOpenSupport: () => void;
}) {
  const [openReviewToken, setOpenReviewToken] = useState(0);
  const [draggingFiles, setDraggingFiles] = useState(false);
  const dragDepth = useRef(0);
  const publicBlocks = useMemo(() => blocks.filter((block) => !isPrivateReasoningKind(block.kind)), [blocks]);
  const acceptsFiles = (event: DragEvent) => Array.from(event.dataTransfer?.types ?? []).some((type) => type === 'Files' || type === 'text/uri-list') || Boolean(event.dataTransfer?.files?.length);
  const onFilesSelected = async (files: FileList | File[]) => {
    try {
      const added = await filesToDrafts(files);
      if (draft.attachments.length + added.length > 4) throw new Error('每条消息最多添加 4 个附件');
      onDraftChange((current) => ({
        ...current,
        attachments: [...current.attachments, ...added.filter((file) => !current.attachments.some((item) => item.id === file.id))].slice(0, 4)
      }));
    } catch (error) {
      sendCommand('ui.notify', { message: error instanceof Error ? error.message : '无法读取附件' });
    }
  };
  return <div className="chat-view" onDragEnter={(event) => {
    if (!acceptsFiles(event)) return;
    event.preventDefault();
    dragDepth.current += 1;
    setDraggingFiles(true);
  }} onDragLeave={() => {
    if (dragDepth.current === 0) return;
    dragDepth.current = Math.max(0, dragDepth.current - 1);
    if (dragDepth.current === 0) setDraggingFiles(false);
  }} onDragOver={(event) => {
    if (!acceptsFiles(event)) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
  }} onDrop={(event) => {
    const files = event.dataTransfer.files;
    const projectPaths = files.length ? [] : droppedProjectPaths(event.dataTransfer);
    dragDepth.current = 0;
    setDraggingFiles(false);
    if (!files.length && !projectPaths.length) return;
    event.preventDefault();
    if (files.length) void onFilesSelected(files);
    else sendCommand('composer.resolveDroppedPaths', { paths: projectPaths });
  }}>
    <Transcript blocks={publicBlocks} running={running} streamingOutput={settings.appearance?.streamingOutput !== false} plan={plan} review={review} historyPage={historyPage} onLoadOlder={onLoadOlder} onRewind={onRewind} focusedBlockId={focusedBlockId} onFocusHandled={onFocusHandled} onOpenReview={() => setOpenReviewToken((value) => value + 1)} onOpenSupport={onOpenSupport} />
    {diagnostics && <DiagnosticsCard diagnostics={diagnostics} onClose={onCloseDiagnostics} onRetry={onRetryDiagnostics} />}{contextUsage && <ContextUsageCard usage={contextUsage} onClose={onCloseContextUsage} />}{projectMap && <ProjectMapCard map={projectMap} onClose={onCloseProjectMap} />}
    <ActivityDock blocks={publicBlocks} review={review} strategy={strategy} openReviewToken={openReviewToken} />
    <Composer {...{ running, cleanupPending, queuedPrompts, queuePaused, onQueueRemove, onQueueEdit, onQueueMove, onQueuePause, onQueueRestore, mode, setMode, strategy, setStrategy, onSend, onCancel, prefill, prompts, fileSuggestions, settings, models, draft, onDraftChange, onFilesSelected, locale }} />
    {draggingFiles && <div className="chat-file-drop" role="status"><Upload size={24} /><strong>松开以添加附件</strong><small>最多 4 个文件；发送前可移除</small></div>}
  </div>;
}

function HistoryView({ entries, onLoad, onDelete, onFavorite, onExport, onFork, locale }: { entries: HistoryEntry[]; onLoad: (id: string) => void; onDelete: (id: string) => void; onFavorite: (id: string, favorite: boolean) => void; onExport: (id: string) => void; onFork: (id: string) => void; locale: UiLocale }) {
  const t = createTranslator(locale);
  const [query, setQuery] = useState('');
  const filtered = entries
    .filter((entry) => entry.title.toLowerCase().includes(query.toLowerCase()))
    .sort((left, right) => Number(right.favorite ?? false) - Number(left.favorite ?? false) || right.updatedAt.localeCompare(left.updatedAt));
  return <main className="page"><div className="page-heading"><div><h1>{t('history.title')}</h1><p>{t('history.description')}</p></div></div>
    <label className="search-box"><Search /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder={t('history.search')} /></label>
    <div className="history-list">{filtered.map((entry) => {
      const isRunning = entry.status === 'RUNNING';
      const cleaningUp = entry.status === 'CLEANING_UP';
      return <article key={entry.id} className={isRunning ? 'running' : cleaningUp ? 'cleaning-up' : ''}>
        <button className="history-main" onClick={() => onLoad(entry.id)}><MessageSquare /><span><strong>{entry.title}</strong><small>{isRunning ? t('history.background') : cleaningUp ? t('history.cleanupBackground') : `${new Date(entry.updatedAt).toLocaleString(locale)} · ${t('history.messages', { count: entry.messageCount })}`}</small></span></button>
        <button className={`icon-button history-favorite ${entry.favorite ? 'active' : ''}`} title={entry.favorite ? t('history.unfavorite') : t('history.favorite')} aria-label={entry.favorite ? t('history.unfavorite') : t('history.favorite')} onClick={() => onFavorite(entry.id, !entry.favorite)}><Star fill={entry.favorite ? 'currentColor' : 'none'} /></button>
        <button className="icon-button" title={t('history.export')} aria-label={t('history.export')} onClick={() => onExport(entry.id)}><Download /></button>
        <button className="icon-button" title={t('history.fork')} aria-label={t('history.fork')} onClick={() => onFork(entry.id)}><GitBranch /></button>
        <span className={`history-status ${entry.status.toLowerCase()}`}>{isRunning ? t('status.running') : cleaningUp ? t('history.cleanupPending') : entry.status}</span>
        <button className="icon-button danger" disabled={isRunning || cleaningUp} title={isRunning ? t('history.stopFirst') : cleaningUp ? t('history.cleanupFirst') : t('history.delete')} onClick={() => onDelete(entry.id)}><Trash2 /></button>
      </article>;
    })}</div>
    {!filtered.length && <div className="page-empty"><Archive /><strong>{t('history.empty')}</strong></div>}
  </main>;
}

function SettingCard({ title, description, children }: { title: string; description?: string; children?: React.ReactNode }) {
  return <section className="setting-card"><header><h2>{title}</h2>{description && <p>{description}</p>}</header>{children && <div className="setting-body">{children}</div>}</section>;
}

function ProviderSettings({ snapshot, models, modelCatalog }: { snapshot: SettingsSnapshot; models: string[]; modelCatalog: { providerId: string; status: string; error?: string } | null }) {
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
    sendCommand('provider.select', { providerId: id });
  };
  const automaticModel = selected?.cli && ['auto', 'default', 'kimi-k2'].includes(model.toLowerCase());
  const unavailableSelection = Boolean(selected?.cli && !automaticModel && models.length > 0 && !models.includes(model));
  return <>
    <SettingCard title="供应商与模型" description="普通 API 与本地 CLI 使用独立配置，不再互相覆盖。">
      <div className="form-grid">
        <label>供应商<select value={providerId} onChange={(event) => selectProvider(event.target.value)}>{snapshot.providers.map((provider) => <option value={provider.id} key={provider.id}>{provider.name}</option>)}</select></label>
        <label>模型{selected?.cli && selected.modelDiscovery
          ? <select value={model} onChange={(event) => setModel(event.target.value)}>
              <option value="auto">自动（使用 CLI 当前默认）</option>
              {unavailableSelection && <option value={model} disabled>⚠ {model}（已不可用）</option>}
              {models.filter((item) => item !== 'auto').map((item) => <option value={item} key={item}>{item}</option>)}
            </select>
          : <><input list="omnicode-models" value={model} onChange={(event) => setModel(event.target.value)} /><datalist id="omnicode-models">{models.map((item) => <option value={item} key={item} />)}</datalist></>}</label>
        {selected?.cli && <small className="wide model-help">“自动”表示不向 CLI 传模型参数；刷新后列出的模型来自该 CLI 自己的登录态。</small>}
        {selected?.modelDiscovery && modelCatalog?.providerId === selected.id && <div className={`model-catalog-state wide ${modelCatalog.error ? 'error' : 'ready'}`}>
          {modelCatalog.error ? <AlertTriangle /> : <Check />}
          <span><strong>{modelCatalog.error ? '模型列表尚不可用' : `${models.length} 个可用模型`}</strong><small>{modelCatalog.error ?? modelCatalog.status}</small></span>
        </div>}
        {unavailableSelection && <div className="model-warning wide"><AlertTriangle /><span><strong>当前模型已不可用</strong><small>{model} 不在 CLI 返回的可用列表中；请选择列表内模型，否则 CLI 可能无输出地等待。</small></span></div>}
        <label className="wide">Base URL<input value={baseUrl} disabled={selected?.cli} onChange={(event) => setBaseUrl(event.target.value)} /></label>
        {!selected?.cli && <label className="wide">API Key<input type="password" value={apiKey} placeholder={selected?.credentialConfigured ? '已安全保存；留空表示不修改' : '输入 API Key'} onChange={(event) => setApiKey(event.target.value)} /></label>}
        <label>推理强度<select value={reasoning} onChange={(event) => setReasoning(event.target.value)}>{['auto','none','minimal','low','medium','high','xhigh','max'].map((item) => <option key={item}>{item}</option>)}</select></label>
        <div className="toggle-row compact wide"><span><strong>显示在聊天选择器</strong><small>隐藏不常用供应商，设置与凭据不会被删除。</small></span><button className={`switch ${selected?.hidden ? '' : 'on'}`} onClick={() => selected && sendCommand('provider.visibility', { providerId: selected.id, visible: Boolean(selected.hidden) })}><span /></button></div>
      </div>
      <div className="setting-actions"><button className="primary" disabled={unavailableSelection} onClick={() => sendCommand('settings.saveProvider', { providerId, baseUrl, model, apiKey, reasoningEffort: reasoning })}><Check />保存、验证并加载模型</button><button onClick={() => sendCommand('provider.models', {})}><RotateCcw />刷新当前模型</button></div>
    </SettingCard>
  </>;
}

const EMPTY_MCP: McpServerView = {
  id: '', name: 'MCP Server', enabled: false, transport: 'stdio', command: '', arguments: '',
  providerScope: 'all',
  environmentKeys: '', workingDirectory: '.', url: '', httpAuthMode: 'none', oauthClientId: '', oauthScopes: '',
  bearerConfigured: false, oauthConfigured: false, oauthUsable: false
};

function McpSettings({ snapshot, catalog, catalogState, testState, authState, draftSelection }: { snapshot: SettingsSnapshot; catalog: McpCatalogEntryView[]; catalogState: McpCatalogState; testState: McpTestState | null; authState: McpAuthState | null; draftSelection: McpDraftSelection | null }) {
  const [scope, setScope] = useState<'all' | 'claude' | 'codex'>('all');
  const [selectedId, setSelectedId] = useState(snapshot.mcpServers[0]?.id ?? '');
  const selected = snapshot.mcpServers.find((server) => server.id === selectedId);
  const [draft, setDraft] = useState<McpServerView>(selected ?? EMPTY_MCP);
  const [marketOpen, setMarketOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [importJson, setImportJson] = useState('');
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
  const visibleServers = snapshot.mcpServers.filter((server) => (server.providerScope ?? 'all') === scope);
  return <>
    <SettingCard title="MCP 服务" description={`已配置 ${snapshot.mcpServers.length} 个服务器；市场安装只创建禁用草案，审阅后才可启用。`}>
      <div className="mcp-scope-tabs" role="tablist" aria-label="MCP 引擎范围">{([['all','通用'],['claude','Claude Code'],['codex','Codex']] as const).map(([id,label]) => <button role="tab" aria-selected={scope === id} className={scope === id ? 'active' : ''} key={id} onClick={() => setScope(id)}>{label}<small>{snapshot.mcpServers.filter((server) => (server.providerScope ?? 'all') === id).length}</small></button>)}</div>
      <div className="setting-actions left"><button onClick={() => { setDraft({ ...EMPTY_MCP, providerScope: scope }); setSelectedId(''); }}><Plus />手动添加</button><button className="primary" onClick={() => setMarketOpen(!marketOpen)}><Sparkles />{marketOpen ? '关闭市场' : 'MCP 市场'}</button><button onClick={() => setImportOpen((value) => !value)}><Download />导入 Copilot / Claude 配置</button></div>
      {importOpen && <div className="mcp-import-panel">
        <label><strong>粘贴 MCP JSON</strong><small>支持 <code>servers</code> 与 <code>mcpServers</code>；凭据值和 HTTP 请求头不会导入。</small><textarea value={importJson} onChange={(event) => setImportJson(event.target.value)} placeholder={'{"servers":{"github":{"type":"http","url":"https://example.com/mcp"}}}'} /></label>
        <div className="setting-actions"><button onClick={() => { setImportJson(''); setImportOpen(false); }}>取消</button><button className="primary" disabled={!importJson.trim()} onClick={() => { sendCommand('mcp.importConfig', { json: importJson }); setImportJson(''); setImportOpen(false); }}><Download />导入为禁用草案</button></div>
      </div>}
      {marketOpen && <div className="mcp-market">
        <div className="mcp-market-toolbar"><label className="search-box"><Search /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索服务器、发布者或标签" /></label><button title="刷新 MCP Registry" disabled={catalogState.state === 'loading'} onClick={() => sendCommand('mcp.catalog', { query, forceRefresh: true })}><RotateCcw className={catalogState.state === 'loading' ? 'spin' : ''} />刷新</button></div>
        <div className={`mcp-market-state ${catalogState.state}`}><span>{catalogState.state === 'loading' && <LoaderCircle className="spin" />}{catalogState.state === 'ready' && <Check />}{(catalogState.state === 'offline' || catalogState.state === 'error') && <AlertTriangle />}</span><div><strong>{catalogState.total ? `${catalogState.total} 个目录条目 · 显示 ${catalogState.shown}` : '正在读取目录'}</strong><small>{catalogState.notice}{catalogState.fromCache ? ' · 使用本地缓存' : ''}</small></div></div>
        <div className="market-list">{catalog.map((entry) => <article key={entry.id}>
          <header><div><strong>{entry.name}</strong><small>{entry.publisher} · {entry.category} · {entry.source === 'MCP_REGISTRY' ? 'Registry · 未审阅' : '内置精选'}</small></div><span className={`risk ${entry.risk.toLowerCase()}`}>{entry.risk}</span></header>
          <p>{entry.description}</p><small>{entry.riskSummary}</small>
          {entry.options.length > 0 && (() => {
            const option = entry.options.find((item) => item.id === (installSelections[entry.id] ?? entry.options[0].id)) ?? entry.options[0];
            const details = option.transport === 'http'
              ? `${option.url || 'HTTPS Endpoint'} · ${option.authentication === 'oauth' ? 'OAuth 登录后可用' : option.authentication === 'bearer' ? '需要 Bearer Token' : '无需认证'}`
              : `${option.command || '命令'} ${option.arguments || ''}`.trim();
            return <small className="mcp-option-preview">将预填：{details}{option.workingDirectory && option.transport !== 'http' ? ` · 工作目录 ${option.workingDirectory}` : ''}</small>;
          })()}
          <footer>{entry.tags.slice(0, 5).map((tag) => <span key={tag}>{tag}</span>)}
            {entry.options.length > 1 && <select aria-label={`${entry.name} 安装方式`} value={installSelections[entry.id] ?? entry.options[0].id} onChange={(event) => setInstallSelections((current) => ({ ...current, [entry.id]: event.target.value }))}>{entry.options.map((option) => <option key={option.id} value={option.id}>{option.name}{option.authentication && option.authentication !== 'none' ? ` · ${option.authentication === 'oauth' ? 'OAuth' : 'Token'}` : ''}</option>)}</select>}
            <button disabled={!entry.options.length} onClick={() => sendCommand('mcp.installDraft', { entryId: entry.id, optionId: installSelections[entry.id] ?? entry.options[0]?.id })}>添加并配置</button>
          </footer>
        </article>)}</div>
      </div>}
      <div className="split-editor" ref={editorRef}>
        <nav>{visibleServers.map((server) => <button key={server.id} className={selectedId === server.id ? 'active' : ''} onClick={() => { setSelectedId(server.id); setDraft(server); }}><span className={server.enabled ? 'status-on' : ''} />{server.name}<small>{server.transport}{server.recentlyTested ? ' · 最近测试通过' : ''}</small></button>)}{!visibleServers.length && <p>此范围暂无 MCP 配置</p>}</nav>
        <div className="editor-form">
          {draftSelection?.id === draft.id && <div className="mcp-draft-notice" role="status"><Check /><span><strong>市场参数已自动填充</strong><small>{draftSelection.warnings[0] ?? '草案默认禁用；请检查参数，测试连接后再启用。'}</small></span></div>}
          <div className="toggle-row compact"><span><strong>启用此服务器</strong><small>首次连接仍需审批</small></span><button className={`switch ${draft.enabled ? 'on' : ''}`} onClick={() => update('enabled', !draft.enabled)}><span /></button></div>
          <div className="form-grid">
            <label>名称<input value={draft.name} onChange={(event) => update('name', event.target.value)} /></label>
            <label>用于<select value={draft.providerScope} onChange={(event) => update('providerScope', event.target.value as McpServerView['providerScope'])}><option value="all">全部引擎</option><option value="claude">仅 Claude Code</option><option value="codex">仅 Codex</option></select></label>
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
            <span><strong>{testState.state === 'success' ? '连接可用' : testState.state === 'error' ? '连接失败' : testState.state === 'action_required' ? '还差一步授权' : '正在测试'}</strong><small>{testState.message}</small>{Boolean(testState.tools?.length) && <small>工具：{testState.tools?.slice(0, 8).join('、')}</small>}</span>
            {testState.state === 'action_required' && testState.action === 'oauthLogin' && <button className="primary" onClick={() => sendCommand('mcp.oauthLogin', { id: draft.id })}>浏览器登录并继续</button>}
            {testState.state === 'action_required' && testState.action === 'oauthDiscover' && <button onClick={() => sendCommand('mcp.oauthDiscover', { id: draft.id })}>重新发现 OAuth</button>}
            {testState.state === 'action_required' && testState.action === 'saveBearer' && <button className="primary" onClick={() => sendCommand('mcp.saveBearer', { id: draft.id })}>保存 Token…</button>}
          </div>}
          {draft === selected && draft.recentlyTested && testState?.id !== draft.id && <div className="connection-result success"><Check /><span><strong>保存的配置最近测试通过</strong><small>只保留 10 分钟；当前任务仍需重新连接并经过审批。</small></span></div>}
          <div className="setting-actions"><button className="danger" disabled={!draft.id} onClick={() => draft.id && window.confirm('删除此 MCP 配置？') && sendCommand('mcp.delete', { id: draft.id })}><Trash2 />删除</button><button disabled={testState?.state === 'running'} onClick={() => sendCommand('mcp.assist', { ...draft })}><Gauge />自动检测并连接</button><button disabled={testState?.state === 'running'} onClick={() => sendCommand('mcp.test', { ...draft })}><RotateCcw />按当前配置测试</button><button className="primary" onClick={() => sendCommand('mcp.save', { ...draft })}><Check />保存配置</button></div>
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

const EMPTY_DATABASE: DatabaseConnectionView = {
  id: '', name: '远程数据库', enabled: false, jdbcUrl: 'postgresql://db.example.com:5432',
  driver: 'postgresql', username: '', connectTimeoutSeconds: 10, queryTimeoutSeconds: 30,
  maxRows: 200, passwordConfigured: false
};

function DatabaseSettings({ connections, testState }: { connections: DatabaseConnectionView[]; testState: DatabaseTestState | null }) {
  const [draft, setDraft] = useState<DatabaseConnectionView>(connections[0] ?? EMPTY_DATABASE);
  useEffect(() => {
    setDraft((current) => {
      if (!current.id) return connections[0] ?? current;
      return connections.find((item) => item.id === current.id) ?? connections[0] ?? EMPTY_DATABASE;
    });
  }, [connections]);
  const matchingTest = testState?.id === draft.id ? testState : null;
  const createNew = () => setDraft({ ...EMPTY_DATABASE });
  const changeDriver = (driver: DatabaseConnectionView['driver']) => setDraft((current) => ({
    ...current,
    driver,
    jdbcUrl: current.jdbcUrl === EMPTY_DATABASE.jdbcUrl || current.jdbcUrl === 'mariadb://db.example.com:3306'
      ? driver === 'postgresql' ? EMPTY_DATABASE.jdbcUrl : 'mariadb://db.example.com:3306'
      : current.jdbcUrl
  }));
  return <>
    <SettingCard title="远程数据库" description="无需安装本地数据库。配置可从当前电脑访问的 PostgreSQL、MySQL 或 MariaDB URL，Agent 即可在审批后执行受限的只读查询。">
      <div className="chip-list">
        {connections.map((item) => <button className={draft.id === item.id ? 'active' : ''} key={item.id} onClick={() => setDraft(item)}>
          {item.name}{item.enabled ? ' · 已启用' : ''}{item.recentlyTested ? ' · 最近测试通过' : ''}
        </button>)}
        <button onClick={createNew}><Plus />新增连接</button>
      </div>
      <div className="form-grid">
        <label>名称<input value={draft.name} maxLength={120} onChange={(event) => setDraft({ ...draft, name: event.target.value })} /></label>
        <label>数据库类型<select value={draft.driver} onChange={(event) => changeDriver(event.target.value as DatabaseConnectionView['driver'])}><option value="postgresql">PostgreSQL</option><option value="mariadb">MySQL / MariaDB</option></select></label>
        <label className="wide">数据库 URL<input value={draft.jdbcUrl} onChange={(event) => setDraft({ ...draft, jdbcUrl: event.target.value })} placeholder={draft.driver === 'postgresql' ? 'postgresql://host:5432?sslmode=require' : 'mysql://host:3306?sslMode=verify-full'} /><small>数据库名称可选；支持服务器 URL、常见 URL 和 JDBC URL，保存后自动规范化。</small></label>
        <label>用户名<input value={draft.username} maxLength={256} autoComplete="off" onChange={(event) => setDraft({ ...draft, username: event.target.value })} /></label>
        <label className="wide database-password-field">
          数据库密码
          <span className="password-safe-control">
            <input
              aria-label="数据库密码（Password Safe）"
              type="password"
              readOnly
              value={draft.passwordConfigured ? 'password-is-configured' : ''}
              placeholder="尚未设置"
              title="密码由 JetBrains 原生密码框录入，此处不会读取或显示真实密码"
            />
            <button type="button" disabled={!draft.jdbcUrl.trim() || !draft.username.trim()} onClick={() => sendCommand('database.savePassword', { ...draft })}>
              <ShieldCheck />{draft.passwordConfigured ? '更新密码' : '设置密码'}
            </button>
            <button type="button" disabled={!draft.id || !draft.passwordConfigured} onClick={() => sendCommand('database.clearPassword', { id: draft.id })}>清除</button>
          </span>
          <small>{draft.passwordConfigured ? '已安全保存在 IDE Password Safe。' : '点击“设置密码”，在 JetBrains 原生安全窗口中输入。'}真实密码不会进入 WebView、日志、项目文件或模型上下文。</small>
        </label>
        <label>连接超时（秒）<input type="number" min={2} max={60} value={draft.connectTimeoutSeconds} onChange={(event) => setDraft({ ...draft, connectTimeoutSeconds: Number(event.target.value) })} /></label>
        <label>查询超时（秒）<input type="number" min={1} max={300} value={draft.queryTimeoutSeconds} onChange={(event) => setDraft({ ...draft, queryTimeoutSeconds: Number(event.target.value) })} /></label>
        <label>最大返回行数<input type="number" min={1} max={1000} value={draft.maxRows} onChange={(event) => setDraft({ ...draft, maxRows: Number(event.target.value) })} /></label>
      </div>
      <div className="toggle-row"><span><strong>允许 Agent 使用</strong><small>每次查询仍需审批；仅允许一条 SELECT / SHOW / DESCRIBE，强制只读事务并限制结果大小。</small></span><button className={`switch ${draft.enabled ? 'on' : ''}`} onClick={() => setDraft({ ...draft, enabled: !draft.enabled })}><span /></button></div>
      {matchingTest && <div className={`connection-result ${matchingTest.state}`}>
        {matchingTest.state === 'running' ? <LoaderCircle className="spin" /> : matchingTest.state === 'success' ? <Check /> : matchingTest.state === 'error' ? <AlertTriangle /> : <Circle />}
        <span><strong>{matchingTest.state === 'success' ? '连接可用' : matchingTest.state === 'error' ? '连接失败' : matchingTest.state === 'running' ? '正在测试' : '数据库连接'}</strong><small>{matchingTest.message}</small></span>
      </div>}
      {!matchingTest && draft.recentlyTested && draft === connections.find((item) => item.id === draft.id) && <div className="connection-result success"><Check /><span><strong>保存的配置最近测试通过</strong><small>只保留 10 分钟；每次查询仍需审批和实时连接。</small></span></div>}
      <div className="setting-actions">
        <button className="danger" disabled={!draft.id} onClick={() => draft.id && window.confirm('删除此数据库连接及其 Password Safe 密码？') && sendCommand('database.delete', { id: draft.id })}><Trash2 />删除</button>
        <button disabled={!draft.id || matchingTest?.state === 'running'} onClick={() => sendCommand('database.test', { id: draft.id })}><Gauge />测试连接</button>
        <button className="primary" onClick={() => sendCommand('database.save', { ...draft })}><Check />保存配置</button>
      </div>
      {!draft.id && <p className="muted">可直接点击密码项中的“设置密码”，插件会同时保存连接配置和 Password Safe 密码，无需先单独保存。</p>}
    </SettingCard>
  </>;
}

function ProjectContextSettingsCard({ snapshot }: { snapshot: SettingsSnapshot }) {
  const [pinnedPaths, setPinnedPaths] = useState(snapshot.projectContext.pinnedPaths.join('\n'));
  const [excludedPaths, setExcludedPaths] = useState(snapshot.projectContext.excludedPaths.join('\n'));
  const [memoryNotes, setMemoryNotes] = useState(snapshot.projectContext.memoryNotes);
  useEffect(() => {
    setPinnedPaths(snapshot.projectContext.pinnedPaths.join('\n'));
    setExcludedPaths(snapshot.projectContext.excludedPaths.join('\n'));
    setMemoryNotes(snapshot.projectContext.memoryNotes);
  }, [snapshot.projectContext.pinnedPaths, snapshot.projectContext.excludedPaths, snapshot.projectContext.memoryNotes]);
  return <SettingCard title="项目提示增强" description="固定关键文件并排除无关目录；路径会经过项目边界、忽略文件和敏感文件校验。">
    <div className="form-grid">
      <label className="wide">项目记忆（架构事实、约定、常用命令）<textarea maxLength={8000} value={memoryNotes} onChange={(event) => setMemoryNotes(event.target.value)} placeholder="例如：前端位于 webview/；提交前运行 ./gradlew test。不要填写密码或 Token。" /><small>最多 8,000 字符；保存前会脱敏，并作为不可信项目事实加入上下文，不能覆盖权限与安全策略。</small></label>
      <label className="wide">固定到自动上下文（每行一个项目相对路径）<textarea value={pinnedPaths} onChange={(event) => setPinnedPaths(event.target.value)} placeholder="src/main/kotlin/dev/example/App.kt" /></label>
      <label className="wide">排除路径（每行一个项目相对路径）<textarea value={excludedPaths} onChange={(event) => setExcludedPaths(event.target.value)} placeholder="build\nfixtures/large" /></label>
    </div>
    <div className="setting-actions"><button className="primary" onClick={() => sendCommand('settings.projectContext', { pinnedPaths, excludedPaths, memoryNotes })}><Check />保存上下文规则</button></div>
  </SettingCard>;
}

function SessionTemplateSettings({ snapshot, currentMode, currentStrategy }: {
  snapshot: SettingsSnapshot; currentMode: RunMode; currentStrategy: RunStrategy;
}) {
  const createDraft = (): SessionTemplateView => ({
    id: '', name: '', providerId: snapshot.provider.id, model: snapshot.provider.model,
    reasoningEffort: snapshot.provider.reasoningEffort, mode: currentMode, strategy: currentStrategy
  });
  const [draft, setDraft] = useState<SessionTemplateView>(snapshot.sessionTemplates[0] ?? createDraft());
  useEffect(() => {
    setDraft((current) => current.id
      ? snapshot.sessionTemplates.find((item) => item.id === current.id) ?? snapshot.sessionTemplates[0] ?? createDraft()
      : current);
  }, [snapshot.sessionTemplates]);
  return <SettingCard title="会话模板" description="一键创建带引擎、模型、推理强度、模式和协作策略的新会话；不会保存 API Key、密码、MCP Token 或提示内容。">
    <div className="session-template-grid">
      {snapshot.sessionTemplates.map((template) => <article key={template.id}>
        <div><strong>{template.name}</strong><small>{template.providerId ? snapshot.providers.find((item) => item.id === template.providerId)?.name ?? template.providerId : '保持当前引擎'} · {template.model || '保持当前模型'}</small><small>{template.mode} · {template.strategy} · 推理 {template.reasoningEffort}</small></div>
        <button className="primary" onClick={() => sendCommand('sessionTemplate.apply', { id: template.id, newSession: true })}><Play />开始</button>
        <button aria-label={`编辑 ${template.name}`} onClick={() => setDraft(template)}><Pencil /></button>
      </article>)}
    </div>
    <div className="form-grid compact">
      <label>模板名称<input maxLength={80} value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} placeholder="例如：只读架构审阅" /></label>
      <label>引擎<select value={draft.providerId} onChange={(event) => {
        const providerId = event.target.value;
        const provider = snapshot.providers.find((item) => item.id === providerId);
        setDraft({ ...draft, providerId, model: provider?.model ?? provider?.defaultModel ?? '' });
      }}><option value="">应用时保持当前引擎</option>{snapshot.providers.filter((item) => !item.hidden || item.id === draft.providerId).map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
      <label>模型<input value={draft.model} onChange={(event) => setDraft({ ...draft, model: event.target.value })} placeholder="留空则保持当前模型" /></label>
      <label>推理强度<select value={draft.reasoningEffort} onChange={(event) => setDraft({ ...draft, reasoningEffort: event.target.value })}>{REASONING_LEVELS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <label>运行模式<select value={draft.mode} onChange={(event) => setDraft({ ...draft, mode: event.target.value as RunMode })}><option value="AGENT">Agent</option><option value="PLAN">Plan</option><option value="CLAUDE_PLAN">Claude Plan</option></select></label>
      <label>协作策略<select value={draft.strategy} onChange={(event) => setDraft({ ...draft, strategy: event.target.value as RunStrategy })}><option value="AUTO">自动协作</option><option value="SINGLE">Single</option><option value="TEAM">Team</option></select></label>
    </div>
    <div className="setting-actions">
      <button disabled={!draft.id} className="danger" onClick={() => draft.id && window.confirm('删除此会话模板？') && sendCommand('sessionTemplate.delete', { id: draft.id })}><Trash2 />删除</button>
      <button onClick={() => setDraft(createDraft())}><Plus />用当前配置新建</button>
      <button className="primary" disabled={!draft.name.trim()} onClick={() => sendCommand('sessionTemplate.save', {
        id: draft.id, name: draft.name, providerId: draft.providerId, model: draft.model,
        reasoningEffort: draft.reasoningEffort, mode: draft.mode, strategy: draft.strategy
      })}><Check />保存模板</button>
    </div>
  </SettingCard>;
}

function SettingsContent({ tab, snapshot, catalog, catalogState, runtimes, runtimeVerifications, nativeHistory, nativeHistoryImport, models, modelCatalog, mcpTest, mcpAuth, mcpDraftSelection, tokenTracker, databaseTest, currentMode, currentStrategy, onOpenSupport }: { tab: string; snapshot: SettingsSnapshot; catalog: McpCatalogEntryView[]; catalogState: McpCatalogState; runtimes: RuntimeStatusView[]; runtimeVerifications: RuntimeVerificationView[]; nativeHistory: NativeHistoryView | null; nativeHistoryImport: NativeHistoryImportView | null; models: string[]; modelCatalog: { providerId: string; status: string; error?: string } | null; mcpTest: McpTestState | null; mcpAuth: McpAuthState | null; mcpDraftSelection: McpDraftSelection | null; tokenTracker: TokenTrackerView | null; databaseTest: DatabaseTestState | null; currentMode: RunMode; currentStrategy: RunStrategy; onOpenSupport: () => void }) {
  if (tab === 'providers') return <ProviderSettings snapshot={snapshot} models={models} modelCatalog={modelCatalog} />;
  if (tab === 'dependencies') return <>
    <SettingCard title="本地运行引擎" description="版本和模型目录检查不发送模型请求。需要验证真实对话时，可单独发起可能计费的测试；IDE 会先征求确认，测试在临时空目录运行。">
      <div className="engine-grid">{ENGINES.map(([id, engine]) => {
        const runtime = runtimes.find((item) => item.id === id);
        const verification = runtimeVerifications.find((item) => item.engineId === id);
        const providerId = `cli-${id}`;
        const selected = snapshot.provider.id === providerId;
        const continuity = runtime?.sessionContinuity === 'PERSISTENT_HOST' ? '持久 Host' : runtime?.sessionContinuity === 'NATIVE_SESSION_ID' ? '原生会话恢复' : '有限对话重放';
        return <div key={id}>
          <Code2 />
          <span>
            <strong>{engine}</strong>
            <small>{runtime ? `${runtime.version}${runtime.path ? ` · ${runtime.path}` : ''}` : '等待检测'}</small>
            {runtime?.diagnostic && <small>{runtime.diagnostic}</small>}
            <span className="runtime-readiness" aria-label={`${engine} 可用性检查`}>
              <span className={runtime?.path ? 'passed' : runtime ? 'failed' : 'pending'}>程序 {runtime ? (runtime.path ? '已找到' : '未找到') : '待检测'}</span>
              <span className={runtime?.runnable ? 'passed' : runtime?.path ? 'failed' : 'pending'}>运行依赖 {runtime?.runnable ? '正常' : runtime?.path ? '异常' : runtime ? '无法检测' : '待检测'}</span>
              <span className={verification?.responseVerified ? 'passed' : 'pending'}>认证 {verification?.responseVerified ? '实际会话通过' : '未验证'}</span>
              <span className={verification?.catalogVerified ? 'passed' : 'pending'}>模型目录 {verification?.catalogVerified ? `已读取 ${verification.modelCount} 个` : '未验证'}</span>
              <span className={verification?.responseVerified ? 'passed' : 'pending'}>模型回复 {verification?.responseVerified ? '已收到' : '未验证 · 可能计费'}</span>
              {id === 'codex' && <span className={verification?.toolBridgeVerified ? 'passed' : 'pending'}>宿主工具 {verification?.toolBridgeVerified ? '原生桥接通过' : '未验证 · 可能计费'}</span>}
            </span>
            {runtime?.runnable && <small className="runtime-capabilities">{runtime.modelDiscovery ? '支持检查模型目录' : '需手动配置模型'} · {continuity}{runtime.nativeHistory ? ' · 可导入原生历史' : ''}</small>}
            {verification && <small className={`runtime-verification ${verification.state}`}>{verification.detail}</small>}
          </span>
          <div className="engine-actions">
            {runtime?.nativeHistory && <button onClick={() => sendCommand('runtime.historyList', { engineId: id })}><History />历史</button>}
            <button disabled={verification?.state === 'checking' || verification?.state === 'approval' || verification?.state === 'testing'} onClick={() => sendCommand('runtime.verify', { engineId: id })}>{verification?.state === 'checking' ? '检查中…' : '检查模型'}</button>
            {runtime?.runnable && (verification?.state === 'checking' || verification?.state === 'approval' || verification?.state === 'testing'
              ? <button onClick={() => sendCommand('runtime.cancelVerification', { engineId: id })}>取消检查</button>
              : <button onClick={() => sendCommand('runtime.smokeTest', { engineId: id })}>测试实际会话 · 可能计费</button>)}
            {id === 'codex' && runtime?.runnable && !['checking', 'approval', 'testing'].includes(verification?.state ?? '') &&
              <button onClick={() => sendCommand('runtime.smokeTest', { engineId: id, verifyToolBridge: true })}>验证工具桥接 · 可能计费</button>}
            {runtime?.runnable && <button disabled={selected} onClick={() => sendCommand('provider.select', { providerId })}>{selected ? '当前引擎' : '选择使用'}</button>}
          </div>
          <Circle className={verification?.state === 'responded' ? 'runtime-responded' : verification?.state === 'catalog' ? 'runtime-catalog' : runtime?.runnable ? 'runtime-installed' : runtime ? 'runtime-error' : ''} />
        </div>;
      })}</div>
      {nativeHistory && <section className={`native-history ${nativeHistory.state}`} aria-label="CLI 原生历史">
        <header><div><strong>{ENGINES.find(([id]) => id === nativeHistory.engineId)?.[1] ?? nativeHistory.engineId} 原生历史</strong><small>仅显示当前项目；导入后可继续原生会话。</small></div>{nativeHistory.state === 'loading' && <LoaderCircle className="spin" />}</header>
        {nativeHistory.error && <p className="native-history-error"><AlertTriangle />{nativeHistory.error}</p>}
        {nativeHistory.state === 'ready' && nativeHistory.entries.length === 0 && <p className="muted">当前项目没有可导入的原生会话。</p>}
        <div className="native-history-list">{nativeHistory.entries.map((entry) => {
          const importing = nativeHistoryImport?.state === 'loading' && nativeHistoryImport.engineId === nativeHistory.engineId && nativeHistoryImport.sessionId === entry.sessionId;
          return <article key={entry.sessionId}><span><strong>{entry.title}</strong><small>{new Date(entry.updatedAt).toLocaleString()}</small></span><button disabled={importing} onClick={() => sendCommand('runtime.historyImport', { engineId: nativeHistory.engineId, sessionId: entry.sessionId })}>{importing ? <LoaderCircle className="spin" /> : <Download />}{importing ? '正在导入' : '导入并继续'}</button></article>;
        })}</div>
      </section>}
      <div className="setting-actions"><button onClick={() => sendCommand('runtime.probe', {})}><Gauge />运行诊断</button></div>
    </SettingCard>
  </>;
  if (tab === 'mcp') return <McpSettings snapshot={snapshot} catalog={catalog} catalogState={catalogState} testState={mcpTest} authState={mcpAuth} draftSelection={mcpDraftSelection} />;
  if (tab === 'databases') return <DatabaseSettings connections={snapshot.databaseConnections} testState={databaseTest} />;
  if (tab === 'permissions') return <>
    <SettingCard title="权限与沙箱" description="危险操作继续使用 JetBrains 原生审批。">
      <div className="segmented"><button className={snapshot.platform.sandboxMode === 'WORKSPACE_WRITE' ? 'active' : ''} onClick={() => sendCommand('settings.sandbox', { mode: 'WORKSPACE_WRITE' })}>workspace-write</button><button className={snapshot.platform.sandboxMode === 'DANGER_FULL_ACCESS' ? 'active' : ''} onClick={() => sendCommand('settings.sandbox', { mode: 'DANGER_FULL_ACCESS' })}>danger-full-access</button></div>
    </SettingCard>
  </>;
  if (tab === 'usage') {
    const ready = tokenTracker?.state === 'READY';
    const stateLabel = ready ? '服务在线' : tokenTracker?.state === 'NOT_RUNNING' ? '未启动' : tokenTracker?.state === 'UNVERIFIED_SERVICE' ? '待验证' : tokenTracker ? '检查失败' : '检查中';
    const cliLabel = tokenTracker?.cliInstalled
      ? '已发现 TokenTracker CLI，可从系统终端独立启动。'
      : ready
        ? '面板服务正在运行；未在 IntelliJ 可见的 PATH 中发现独立 CLI 命令。'
        : '尚未在 IntelliJ 可见的 PATH 中发现 CLI。';
    return <SettingCard title="使用统计" description="直接在 OmniCode 内展示本机 TokenTracker 的聚合用量，不读取提示词或代码。">
      <div className="feature-row"><Gauge /><span><strong>TokenTracker</strong><small>{tokenTracker?.detail ?? '正在检查本机面板…'}</small><small>{cliLabel}</small></span><span className={`usage-status ${tokenTracker?.state?.toLowerCase() ?? 'unknown'}`}>{stateLabel}</span></div>
      <div className="setting-actions">
        <button className="primary" onClick={() => sendCommand('usage.status', {})}><RotateCcw />刷新统计</button>
        <button disabled={!ready} onClick={() => sendCommand('usage.open', {})}>打开完整面板</button>
        {!ready && <button className="primary" onClick={() => sendCommand('usage.start', {})}><Play />安装并启动</button>}
        {tokenTracker?.managedByOmniCode && <button onClick={() => sendCommand('usage.stop', {})}><Square />停止本机服务</button>}
        {!ready && <button onClick={() => sendCommand('usage.copyStartCommand', {})}><Copy />复制启动命令</button>}
      </div>
      {ready
        ? tokenTracker && <TokenTrackerDashboard status={tokenTracker} />
        : <p className="muted usage-help">请在系统终端运行 <code>{tokenTracker?.installCommand ?? 'TOKENTRACKER_NO_TELEMETRY=1 npx tokentracker-cli'}</code>，启动后再点击重新检查。首次安装和升级由你在终端确认，OmniCode 不会静默执行 npm/npx。</p>}
    </SettingCard>;
  }
  if (tab === 'enhancer') return <ProjectContextSettingsCard snapshot={snapshot} />;
  if (tab === 'pet') return <SettingCard title="桌宠与主题" description="原创角色、状态动画和主题外观；角色不会访问项目或网络。">
    <div className="form-grid compact">
      <label>界面主题<select value={snapshot.theme.id} onChange={(event) => sendCommand('settings.pet', { themeId: event.target.value })}>{snapshot.themes.map((theme) => <option key={theme.id} value={theme.id}>{theme.name}</option>)}</select></label>
    </div>
    <div className="toggle-row"><span><strong>在聊天中显示桌宠</strong><small>角色会响应待机、任务运行和完成状态，可拖拽并自动贴边。</small></span><button aria-label="在聊天中显示桌宠" className={`switch ${snapshot.theme.petEnabled ? 'on' : ''}`} onClick={() => sendCommand('settings.pet', { enabled: !snapshot.theme.petEnabled })}><span /></button></div>
    <PetPicker pets={snapshot.pets} selectedId={snapshot.theme.petId} onSelect={(petId) => sendCommand('settings.pet', { petId })} />
  </SettingCard>;
  if (tab === 'agents') return <><SettingCard title="Agents" description="自动路由仍由任务复杂度决定；这里只调整可靠性策略。">
    <div className="toggle-row"><span><strong>持续执行</strong><small>一直运行到完成、用户取消或安全边界阻止。</small></span><button className={`switch ${snapshot.platform.agentContinuousExecution ? 'on' : ''}`} onClick={() => sendCommand('settings.agentRuntime', { continuousExecution: !snapshot.platform.agentContinuousExecution, providerMaxAttempts: snapshot.platform.providerMaxAttempts })}><span /></button></div>
    <div className="form-grid compact"><label>供应商瞬时失败重试次数<select value={snapshot.platform.providerMaxAttempts} onChange={(event) => sendCommand('settings.agentRuntime', { continuousExecution: snapshot.platform.agentContinuousExecution, providerMaxAttempts: Number(event.target.value) })}>{[1, 2, 3, 4, 5].map((value) => <option key={value} value={value}>{value}</option>)}</select></label></div>
  </SettingCard><SessionTemplateSettings snapshot={snapshot} currentMode={currentMode} currentStrategy={currentStrategy} /></>;
  if (tab === 'commit') return <SettingCard title="Commit AI" description="根据已审阅的 Git 差异生成提交信息。"><div className="toggle-row"><span><strong>启用 Commit AI</strong></span><button className={`switch ${snapshot.platform.commitAiEnabled ? 'on' : ''}`} onClick={() => sendCommand('settings.commitAi', { enabled: !snapshot.platform.commitAiEnabled })}><span /></button></div></SettingCard>;
  if (tab === 'skills') return <SkillSettings skills={snapshot.skills} />;
  if (tab === 'prompts') return <PromptSettings prompts={snapshot.prompts} />;
  if (tab === 'community') return <SettingCard title="社区与支持" description="OmniCode 开源且免费。Star 和打赏都是自愿的，不会解锁或限制任何功能。"><SupportSettingsCard snapshot={snapshot} onOpenSupport={onOpenSupport} /></SettingCard>;
  if (tab === 'other') return <SettingCard title="其他" description="安全、隐私与故障排查入口。"><div className="feature-row"><ShieldCheck /><span><strong>本地安全边界</strong><small>密钥留在 Password Safe；危险操作仍需原生审批并经过沙箱。</small></span></div><div className="setting-actions"><button onClick={() => sendCommand('navigation.openExternal', { url: 'https://github.com/wuke123222/omnicode-agent/blob/main/SECURITY.md' })}>安全说明</button><button onClick={() => sendCommand('navigation.openExternal', { url: 'https://github.com/wuke123222/omnicode-agent/blob/main/PRIVACY.md' })}>隐私说明</button><button onClick={() => sendCommand('runtime.probe', {})}>重新检测本地引擎</button></div></SettingCard>;
  if (tab === 'basic') {
    const appearance = snapshot.appearance ?? { locale: 'auto', fontFamily: 'system', fontSize: 13, streamingOutput: true };
    const updateAppearance = (next: Partial<typeof appearance>) => sendCommand('settings.appearance', { ...appearance, ...next });
    return <>
      <SettingCard title="常规" description="历史、任务通知和数据保留。">
        <div className="toggle-row"><span><strong>任务完成通知</strong><small>任务在后台完成、失败或暂停时显示 IDE 原生通知；摘要会脱敏并截断。</small></span><button aria-label="任务完成通知" className={`switch ${snapshot.platform.notificationsEnabled ? 'on' : ''}`} onClick={() => sendCommand('settings.notifications', { enabled: !snapshot.platform.notificationsEnabled })}><span /></button></div>
        <div className="form-grid"><label>历史保留数量<input type="number" defaultValue={snapshot.platform.historyRetention} onBlur={(event) => sendCommand('settings.historyRetention', { value: Number(event.target.value) })} /></label><label>用量保留天数<input type="number" defaultValue={snapshot.platform.usageRetentionDays} onBlur={(event) => sendCommand('settings.usageRetention', { value: Number(event.target.value) })} /></label></div>
      </SettingCard>
      <SettingCard title="回答显示" description="模型内部思维链始终不展示；任务状态和工具结果仍可查看。">
        <div className="toggle-row"><span><strong>流式输出</strong><small>默认开启。关闭后仍接收运行事件，但等本轮结束再一次性显示助手回复。</small></span><button aria-label="流式输出" aria-pressed={appearance.streamingOutput !== false} className={`switch ${appearance.streamingOutput !== false ? 'on' : ''}`} onClick={() => updateAppearance({ streamingOutput: appearance.streamingOutput === false })}><span /></button></div>
      </SettingCard>
      <SettingCard title="外观与语言" description="设置只保存在本机；跟随 IDE 时会使用当前系统语言和 JetBrains 配色。">
        <div className="form-grid compact">
          <label>界面语言<select value={appearance.locale} onChange={(event) => updateAppearance({ locale: event.target.value })}>
            <option value="auto">跟随系统</option><option value="zh-CN">简体中文</option><option value="zh-TW">繁體中文</option><option value="en">English</option><option value="ja">日本語</option><option value="ko">한국어</option><option value="de">Deutsch</option><option value="fr">Français</option><option value="es">Español</option><option value="pt-BR">Português (Brasil)</option><option value="ru">Русский</option>
          </select></label>
          <label>界面字体<select value={appearance.fontFamily} onChange={(event) => updateAppearance({ fontFamily: event.target.value })}>
            <option value="system">系统字体</option><option value="inter">Inter</option><option value="jetbrains-mono">JetBrains Mono</option><option value="noto-sans">Noto Sans</option><option value="serif">衬线字体</option>
          </select></label>
          <label>字号（11–20）<input type="number" min={11} max={20} value={appearance.fontSize} onChange={(event) => updateAppearance({ fontSize: Math.min(20, Math.max(11, Number(event.target.value) || 13)) })} /></label>
        </div>
      </SettingCard>
    </>;
  }
  return <SettingCard title={SETTINGS_TABS.find(([id]) => id === tab)?.[1] ?? '设置'} description="该配置已整合到新的 CCGUI 风格设置中心。"><p className="muted">此页面会通过安全桥接读取和保存本地配置，不会把密钥发送到 WebView。</p></SettingCard>;
}

function SettingsView({ requestedTab, snapshot, catalog, catalogState, runtimes, runtimeVerifications, nativeHistory, nativeHistoryImport, models, modelCatalog, mcpTest, mcpAuth, mcpDraftSelection, tokenTracker, databaseTest, currentMode, currentStrategy, onOpenSupport }: { requestedTab?: { tab: string; sequence: number }; snapshot: SettingsSnapshot; catalog: McpCatalogEntryView[]; catalogState: McpCatalogState; runtimes: RuntimeStatusView[]; runtimeVerifications: RuntimeVerificationView[]; nativeHistory: NativeHistoryView | null; nativeHistoryImport: NativeHistoryImportView | null; models: string[]; modelCatalog: { providerId: string; status: string; error?: string } | null; mcpTest: McpTestState | null; mcpAuth: McpAuthState | null; mcpDraftSelection: McpDraftSelection | null; tokenTracker: TokenTrackerView | null; databaseTest: DatabaseTestState | null; currentMode: RunMode; currentStrategy: RunStrategy; onOpenSupport: () => void }) {
  const [tab, setTab] = useState('basic');
  const [collapsed, setCollapsed] = useState(false);
  useEffect(() => { if (requestedTab && SETTINGS_TABS.some(([id]) => id === requestedTab.tab)) setTab(requestedTab.tab); }, [requestedTab]);
  useEffect(() => { if (tab === 'dependencies' && !runtimes.length) sendCommand('runtime.probe', {}); }, [tab, runtimes.length]);
  return <main className={`settings-page ${collapsed ? 'collapsed' : ''}`}>
    <aside><button className="collapse-button" onClick={() => setCollapsed(!collapsed)}>{collapsed ? <ChevronRight /> : <ChevronDown />}</button>{SETTINGS_TABS.map(([id, label]) => <button key={id} title={label} className={tab === id ? 'active' : ''} onClick={() => setTab(id)}><span className="settings-dot" />{!collapsed && label}</button>)}</aside>
    <div className="settings-content"><div className="page-heading"><div><h1>{SETTINGS_TABS.find(([id]) => id === tab)?.[1]}</h1><p>OmniCode 设置</p></div></div><SettingsContent tab={tab} snapshot={snapshot} catalog={catalog} catalogState={catalogState} runtimes={runtimes} runtimeVerifications={runtimeVerifications} nativeHistory={nativeHistory} nativeHistoryImport={nativeHistoryImport} models={models} modelCatalog={modelCatalog} mcpTest={mcpTest} mcpAuth={mcpAuth} mcpDraftSelection={mcpDraftSelection} tokenTracker={tokenTracker} databaseTest={databaseTest} currentMode={currentMode} currentStrategy={currentStrategy} onOpenSupport={onOpenSupport} /></div>
  </main>;
}

type GlobalSearchItem = {
  id: string;
  kind: 'session' | 'message' | 'file' | 'prompt' | 'skill' | 'command';
  title: string;
  detail: string;
  value: string;
};

function GlobalSearch({
  open, query, onQuery, onClose, history, blocks, files, settings, onOpenSession, onOpenMessage
}: {
  open: boolean;
  query: string;
  onQuery: (value: string) => void;
  onClose: () => void;
  history: HistoryEntry[];
  blocks: ChatBlock[];
  files: string[];
  settings: SettingsSnapshot;
  onOpenSession: (id: string) => void;
  onOpenMessage: (id: string) => void;
}) {
  const inputRef = useRef<HTMLInputElement>(null);
  const normalized = query.trim().toLowerCase();
  useEffect(() => {
    if (!open) return;
    requestAnimationFrame(() => inputRef.current?.focus());
  }, [open]);
  useEffect(() => {
    if (!open || normalized.length < 2) return;
    const timer = window.setTimeout(() => sendCommand('composer.searchFiles', { query: normalized }), 120);
    return () => window.clearTimeout(timer);
  }, [open, normalized]);
  const results = useMemo<GlobalSearchItem[]>(() => {
    if (!normalized) return history.slice(0, 6).map((entry) => ({
      id: `session:${entry.id}`, kind: 'session', title: entry.title, detail: '最近会话', value: entry.id
    }));
    const matches = (value: string) => value.toLowerCase().includes(normalized);
    const items: GlobalSearchItem[] = [];
    history.filter((entry) => matches(entry.title)).slice(0, 6).forEach((entry) => items.push({
      id: `session:${entry.id}`, kind: 'session', title: entry.title, detail: '历史会话', value: entry.id
    }));
    blocks.filter((block) => !isPrivateReasoningKind(block.kind) && block.text && matches(block.text)).slice(-6).forEach((block) => items.push({
      id: `message:${block.id}`, kind: 'message', title: block.role === 'user' ? '你的消息' : 'OmniCode 回复',
      detail: localizedTranscriptText(block.text).replace(/\s+/g, ' ').slice(0, 140), value: block.id
    }));
    files.filter(matches).slice(0, 10).forEach((path) => items.push({
      id: `file:${path}`, kind: 'file', title: path, detail: '项目文件', value: path
    }));
    settings.prompts.filter((item) => matches(`${item.name} ${item.shortcut} ${item.content}`)).slice(0, 5).forEach((item) => items.push({
      id: `prompt:${item.id}`, kind: 'prompt', title: `!${item.shortcut} · ${item.name}`, detail: item.content.slice(0, 140), value: item.content
    }));
    settings.skills.filter((item) => matches(`${item.name} ${item.path}`)).slice(0, 5).forEach((item) => items.push({
      id: `skill:${item.id}`, kind: 'skill', title: item.name, detail: item.path, value: item.path
    }));
    SLASH_COMMANDS.filter((item) => matches(`${item.value} ${item.label} ${item.detail}`)).forEach((item) => items.push({
      id: `command:${item.value}`, kind: 'command', title: item.value.trim(), detail: `${item.label} · ${item.detail}`, value: item.value
    }));
    return items.slice(0, 30);
  }, [blocks, files, history, normalized, settings.prompts, settings.skills]);
  if (!open) return null;
  const activate = (item: GlobalSearchItem) => {
    onClose();
    if (item.kind === 'session') onOpenSession(item.value);
    else if (item.kind === 'message') onOpenMessage(item.value);
    else if (item.kind === 'file') sendCommand('navigation.openFile', { path: item.value, line: 1, end: 1 });
    else if (item.kind === 'prompt' || item.kind === 'command') sendCommand('composer.prefill', { text: item.value });
    else sendCommand('ui.notify', { message: `Skill 目录：${item.value}` });
  };
  const icon = (kind: GlobalSearchItem['kind']) => kind === 'session' ? <History />
    : kind === 'message' ? <MessageSquare />
      : kind === 'file' ? <FileCode2 />
        : kind === 'prompt' ? <Sparkles />
          : kind === 'skill' ? <Wrench /> : <Code2 />;
  return <div className="global-search-backdrop" role="presentation" onMouseDown={(event) => {
    if (event.currentTarget === event.target) onClose();
  }}>
    <section className="global-search-dialog" role="dialog" aria-modal="true" aria-label="全局搜索">
      <label><Search /><input ref={inputRef} value={query} onChange={(event) => onQuery(event.target.value)} onKeyDown={(event) => {
        if (event.key === 'Escape') { event.preventDefault(); onClose(); }
        if (event.key === 'Enter' && results[0]) { event.preventDefault(); activate(results[0]); }
      }} placeholder="搜索会话、当前消息、项目文件、提示词、Skills 和命令" /><kbd>Esc</kbd></label>
      <div className="global-search-results">
        {results.map((item) => <button key={item.id} onClick={() => activate(item)}>{icon(item.kind)}<span><strong>{item.title}</strong><small>{item.detail}</small></span><em>{item.kind}</em></button>)}
        {!results.length && <div className="global-search-empty">没有匹配结果</div>}
      </div>
      <footer><span>⌘/Ctrl K 打开</span><span>Enter 选择 · Esc 关闭</span></footer>
    </section>
  </div>;
}

export function App() {
  const [view, setView] = useState<RootView>('chat');
  const [requestedSettingsTab, setRequestedSettingsTab] = useState<{ tab: string; sequence: number }>();
  const [bridgeBootstrapped, setBridgeBootstrapped] = useState(false);
  const [locale, setLocale] = useState<UiLocale>('zh-CN');
  const [projectName, setProjectName] = useState('OmniCode');
  const [sessionId, setSessionId] = useState('');
  const [blocks, setBlocks] = useState<ChatBlock[]>([]);
  const [draftsBySession, setDraftsBySession] = useState<Record<string, ComposerDraft>>({});
  const [historyEntries, setHistoryEntries] = useState<HistoryEntry[]>([]);
  const [openSessions, setOpenSessions] = useState<OpenSessionTab[]>([]);
  const [historyPages, setHistoryPages] = useState<Record<string, HistoryPageState>>({});
  const [settingsSnapshot, setSettingsSnapshot] = useState<SettingsSnapshot>(EMPTY_SETTINGS);
  const [mcpCatalog, setMcpCatalog] = useState<McpCatalogEntryView[]>([]);
  const [mcpCatalogState, setMcpCatalogState] = useState<McpCatalogState>({ state: 'idle', total: 0, shown: 0, notice: '打开市场后加载官方 Registry。', fromCache: false });
  const [mcpTest, setMcpTest] = useState<McpTestState | null>(null);
  const [mcpAuth, setMcpAuth] = useState<McpAuthState | null>(null);
  const [mcpDraftSelection, setMcpDraftSelection] = useState<McpDraftSelection | null>(null);
  const [tokenTracker, setTokenTracker] = useState<TokenTrackerView | null>(null);
  const [databaseTest, setDatabaseTest] = useState<DatabaseTestState | null>(null);
  const [diagnostics, setDiagnostics] = useState<DiagnosticsState | null>(null);
  const [contextUsage, setContextUsage] = useState<ProjectContextUsageView | null>(null);
  const [projectMap, setProjectMap] = useState<ProjectMapView | null>(null);
  const [runtimes, setRuntimes] = useState<RuntimeStatusView[]>([]);
  const [runtimeVerifications, setRuntimeVerifications] = useState<RuntimeVerificationView[]>([]);
  const [nativeHistory, setNativeHistory] = useState<NativeHistoryView | null>(null);
  const [nativeHistoryImport, setNativeHistoryImport] = useState<NativeHistoryImportView | null>(null);
  const [models, setModels] = useState<string[]>([]);
  const [modelCatalog, setModelCatalog] = useState<{ providerId: string; status: string; error?: string } | null>(null);
  const [fileSuggestions, setFileSuggestions] = useState<string[]>([]);
  const [plan, setPlan] = useState<PlanProposal | null>(null);
  const [review, setReview] = useState<ChangeReview | null>(null);
  const [providerStatus, setProviderStatus] = useState('正在载入…');
  const [running, setRunning] = useState(false);
  const [cleanupPending, setCleanupPending] = useState(false);
  const [mode, setMode] = useState<RunMode>('AGENT');
  const [strategy, setStrategy] = useState<RunStrategy>('AUTO');
  const [queueRevision, setQueueRevision] = useState(0);
  const [prefill, setPrefill] = useState({ text: '', revision: 0 });
  const [toast, setToast] = useState('');
  const [globalSearchOpen, setGlobalSearchOpen] = useState(false);
  const [globalSearchQuery, setGlobalSearchQuery] = useState('');
  const [focusedBlockId, setFocusedBlockId] = useState('');
  const [supportOpen, setSupportOpen] = useState(false);
  const [releaseNotesOpen, setReleaseNotesOpen] = useState(false);
  const activeProviderRef = useRef('');
  const activeSessionRef = useRef('');
  const confirmedSessionRef = useRef('');
  const pendingSessionLoadRef = useRef('');
  const sessionBlocksRef = useRef<Record<string, ChatBlock[]>>({});
  const runningSessionsRef = useRef<Set<string>>(new Set());
  const cleanupPendingSessionsRef = useRef<Set<string>>(new Set());
  const queuedPromptsRef = useRef<Record<string, QueuedPrompt[]>>({});
  const inFlightPromptsRef = useRef<Record<string, QueuedPrompt>>({});
  const pendingQueueAcksRef = useRef<Record<string, { sessionId: string; prompt: QueuedPrompt }>>({});
  const pausedQueuesRef = useRef<Set<string>>(new Set());
  const readyQueuesRef = useRef<Set<string>>(new Set());

  const restoreHostQueue = (targetSession: string, snapshot?: PendingPromptSnapshot[]) => {
    if (!Array.isArray(snapshot) || !snapshot.length || (queuedPromptsRef.current[targetSession]?.length ?? 0) > 0) return;
    const inFlightId = inFlightPromptsRef.current[targetSession]?.clientMessageId;
    const restored: QueuedPrompt[] = snapshot.filter((item) => item.clientMessageId !== inFlightId).map((item) => ({
      clientMessageId: item.clientMessageId, text: item.text, mode: item.mode,
      strategy: item.strategy, attachments: [], requiresReattach: Boolean(item.requiresReattach)
    }));
    if (!restored.length) return;
    queuedPromptsRef.current[targetSession] = restored;
    // A renderer reload never silently dispatches recovered work. Attachments are not copied
    // into host memory; those rows must be returned to the composer and reattached manually.
    pausedQueuesRef.current.add(targetSession);
    readyQueuesRef.current.delete(targetSession);
    const existing = sessionBlocksRef.current[targetSession] ?? [];
    const next = [...existing];
    for (const item of restored) {
      if (!next.some((block) => block.id === item.clientMessageId)) next.push({
        id: item.clientMessageId, role: 'user', kind: 'message.user.queued',
        text: item.text || '附件需重新添加', metadata: { optimistic: true, queued: true }
      });
    }
    sessionBlocksRef.current[targetSession] = next;
    if (targetSession === activeSessionRef.current) setBlocks(next);
    setQueueRevision((revision) => revision + 1);
  };

  const dismissReleaseNotes = () => {
    sendCommand('settings.releaseNotesSeen', { version: __OMNICODE_RELEASE__.version });
    setReleaseNotesOpen(false);
  };
  const reviewBySessionRef = useRef<Record<string, ChangeReview | null>>({});
  const terminalTurnsRef = useRef<Set<string>>(new Set());
  const lastSequenceByTurnRef = useRef<Record<string, number>>({});

  useEffect(() => {
    activeProviderRef.current = settingsSnapshot.provider.id;
  }, [settingsSnapshot.provider.id]);

  useEffect(() => {
    const root = document.documentElement;
    // A remote JCEF renderer can run the first passive effect while replacing its initial
    // about:blank document.  In that tiny window documentElement is null; treating it as a
    // normal no-op keeps the mounted chat alive until the next bootstrap/state update instead
    // of throwing outside the React error boundary and leaving the host waiting forever.
    if (!root) return;
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

  useEffect(() => {
    const root = document.documentElement;
    if (!root) return;
    const fonts: Record<string, string> = {
      system: 'Inter, ui-sans-serif, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif',
      inter: 'Inter, ui-sans-serif, sans-serif',
      'jetbrains-mono': '"JetBrains Mono", ui-monospace, monospace',
      'noto-sans': '"Noto Sans", "Noto Sans CJK SC", ui-sans-serif, sans-serif',
      serif: 'ui-serif, Georgia, "Noto Serif", serif'
    };
    root.style.setProperty('--ui-font-family', fonts[settingsSnapshot.appearance?.fontFamily] ?? fonts.system);
    root.style.setProperty('--ui-font-size', `${Math.min(20, Math.max(11, Number(settingsSnapshot.appearance?.fontSize ?? 13)))}px`);
  }, [settingsSnapshot.appearance]);

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setGlobalSearchOpen(true);
      } else if (event.key === 'Escape' && globalSearchOpen) {
        event.preventDefault();
        setGlobalSearchOpen(false);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [globalSearchOpen]);

  useEffect(() => subscribeBridge((raw) => {
    const message = parseIncoming(raw);
    if (!message?.type) return;
    if (message.type === 'bootstrap') {
      const payload = message.payload as BootstrapPayload;
      setBridgeBootstrapped(true);
      if ((payload.settings?.releaseNotesVersion ?? '') !== __OMNICODE_RELEASE__.version) setReleaseNotesOpen(true);
      const bootstrapLocale = normalizeLocale(payload.locale);
      setLocale(bootstrapLocale);
      activeSessionRef.current = payload.sessionId;
      confirmedSessionRef.current = payload.sessionId;
      pendingSessionLoadRef.current = '';
      sessionBlocksRef.current[payload.sessionId] = payload.running
        ? settleTerminalTurnBlocks(payload.blocks ?? [])
        : settleSessionBlocks(payload.blocks ?? []);
      restoreHostQueue(payload.sessionId, payload.pendingQueue);
      rememberBlockSequences(lastSequenceByTurnRef.current, sessionBlocksRef.current[payload.sessionId], payload.sessionId);
      if (payload.running) runningSessionsRef.current.add(payload.sessionId);
      else runningSessionsRef.current.delete(payload.sessionId);
      if (payload.cleanupPending) cleanupPendingSessionsRef.current.add(payload.sessionId);
      else cleanupPendingSessionsRef.current.delete(payload.sessionId);
      setProjectName(payload.projectName); setSessionId(payload.sessionId); setRunning(payload.running); setCleanupPending(Boolean(payload.cleanupPending));
      setOpenSessions([{ id: payload.sessionId, title: payload.sessionTitle?.trim() || createTranslator(bootstrapLocale)('session.new'), running: payload.running }]);
      setHistoryPages({ [payload.sessionId]: { before: Math.max(0, Number(payload.historyBefore ?? 0)), hasMore: Boolean(payload.historyHasMore), loading: false } });
      setProviderStatus(payload.providerStatus); setBlocks(sessionBlocksRef.current[payload.sessionId]); setHistoryEntries(payload.history);
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
        if (event.phase !== 'completed' || /\.(?:failed|cancelled|canceled|stopped|aborted)$/.test(event.kind)) {
          pausedQueuesRef.current.add(event.sessionId);
          readyQueuesRef.current.delete(event.sessionId);
        } else readyQueuesRef.current.add(event.sessionId);
        delete inFlightPromptsRef.current[event.sessionId];
        setQueueRevision((revision) => revision + 1);
      }
      setOpenSessions((current) => current.map((tab) => tab.id === event.sessionId ? { ...tab, running: terminal ? false : true } : tab));
      if (event.sessionId === activeSessionRef.current) {
        setBlocks(next);
        if (terminal) setRunning(false);
      }
    } else if (message.type === 'running') {
      const payload = message.payload as { sessionId?: string; running?: boolean; cleanupPending?: boolean };
      const targetSession = payload.sessionId || activeSessionRef.current;
      if (targetSession) {
        if (payload.running) runningSessionsRef.current.add(targetSession);
        else runningSessionsRef.current.delete(targetSession);
        if (payload.cleanupPending) cleanupPendingSessionsRef.current.add(targetSession);
        else cleanupPendingSessionsRef.current.delete(targetSession);
        if (payload.running === false) {
          const current = sessionBlocksRef.current[targetSession] ?? [];
          const settled = settleSessionBlocks(current);
          sessionBlocksRef.current[targetSession] = settled;
          if (targetSession === activeSessionRef.current) setBlocks(settled);
        }
        setOpenSessions((current) => current.map((tab) => tab.id === targetSession ? { ...tab, running: Boolean(payload.running) } : tab));
      }
      if (!payload.sessionId || payload.sessionId === activeSessionRef.current) {
        setRunning(Boolean(payload.running));
        setCleanupPending(Boolean(payload.cleanupPending));
      }
    } else if (message.type === 'send.rejected') {
      const payload = message.payload as { sessionId?: string; clientMessageId?: string; message?: string };
      const targetSession = payload.sessionId || activeSessionRef.current;
      const clientMessageId = String(payload.clientMessageId ?? '');
      const current = sessionBlocksRef.current[targetSession] ?? [];
      const withoutPriorError = current.filter((block) => block.id !== `${clientMessageId}-rejected`);
      const inFlight = inFlightPromptsRef.current[targetSession];
      if (inFlight?.clientMessageId === clientMessageId) {
        const pending = queuedPromptsRef.current[targetSession] ?? [];
        if (!pending.some((prompt) => prompt.clientMessageId === clientMessageId)) {
          queuedPromptsRef.current[targetSession] = [inFlight, ...pending];
        }
        delete inFlightPromptsRef.current[targetSession];
      }
      const rejectedError: ChatBlock = {
        id: `${clientMessageId || 'send'}-rejected`,
        role: 'system',
        kind: 'run.failed',
        title: '消息尚未发送',
        text: String(payload.message ?? '当前任务未能启动。'),
        status: 'error'
      };
      const next: ChatBlock[] = [...withoutPriorError, rejectedError].map((block) => block.id === clientMessageId
        ? { ...block, kind: 'message.user.queued', metadata: { ...block.metadata, queued: true, optimistic: true } }
        : block);
      sessionBlocksRef.current[targetSession] = next;
      runningSessionsRef.current.delete(targetSession);
      if (String(payload.message ?? '').includes('后台清理')) cleanupPendingSessionsRef.current.add(targetSession);
      pausedQueuesRef.current.add(targetSession);
      readyQueuesRef.current.delete(targetSession);
      setQueueRevision((revision) => revision + 1);
      if (targetSession === activeSessionRef.current) {
        setRunning(false);
        setCleanupPending(cleanupPendingSessionsRef.current.has(targetSession));
        setBlocks(next);
      }
    } else if (message.type === 'session.reset') {
      const payload = message.payload as { sessionId: string; sessionTitle?: string; mode?: RunMode; strategy?: RunStrategy };
      activeSessionRef.current = payload.sessionId;
      confirmedSessionRef.current = payload.sessionId;
      pendingSessionLoadRef.current = '';
      const cached = sessionBlocksRef.current[payload.sessionId] ?? [];
      const restored = runningSessionsRef.current.has(payload.sessionId) ? settleTerminalTurnBlocks(cached) : settleSessionBlocks(cached);
      sessionBlocksRef.current[payload.sessionId] = restored;
      setSessionId(payload.sessionId); setBlocks(restored); setView('chat');
      setContextUsage(null);
      setOpenSessions((current) => [...current.filter((tab) => tab.id !== payload.sessionId), { id: payload.sessionId, title: payload.sessionTitle?.trim() || '新对话', running: runningSessionsRef.current.has(payload.sessionId) }].slice(-8));
      setHistoryPages((current) => ({ ...current, [payload.sessionId]: current[payload.sessionId] ?? { before: 0, hasMore: false, loading: false } }));
      setPlan(null); setReview(reviewBySessionRef.current[payload.sessionId] ?? null);
      setRunning(runningSessionsRef.current.has(payload.sessionId));
      setCleanupPending(cleanupPendingSessionsRef.current.has(payload.sessionId));
      setMode(payload.mode ?? 'AGENT'); setStrategy(payload.strategy ?? 'AUTO');
    } else if (message.type === 'session.loaded') {
      const payload = message.payload as { sessionId: string; sessionTitle?: string; blocks: ChatBlock[]; pendingQueue?: PendingPromptSnapshot[]; running?: boolean; cleanupPending?: boolean; mode?: RunMode; strategy?: RunStrategy; historyBefore?: number; historyHasMore?: boolean };
      if (pendingSessionLoadRef.current && payload.sessionId !== pendingSessionLoadRef.current) return;
      pendingSessionLoadRef.current = '';
      confirmedSessionRef.current = payload.sessionId;
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
        ? settleTerminalTurnBlocks(mergeSnapshotBlocks(cached ?? [], payload.blocks))
        : settleSessionBlocks(mergeSnapshotBlocks(cached ?? [], payload.blocks));
      rememberBlockSequences(lastSequenceByTurnRef.current, next, payload.sessionId);
      sessionBlocksRef.current[payload.sessionId] = next;
      restoreHostQueue(payload.sessionId, payload.pendingQueue);
      if (wasRunning) runningSessionsRef.current.add(payload.sessionId);
      else runningSessionsRef.current.delete(payload.sessionId);
      if (payload.cleanupPending) cleanupPendingSessionsRef.current.add(payload.sessionId);
      else cleanupPendingSessionsRef.current.delete(payload.sessionId);
      setSessionId(payload.sessionId); setBlocks(sessionBlocksRef.current[payload.sessionId]); setView('chat');
      setContextUsage(null);
      setOpenSessions((current) => [...current.filter((tab) => tab.id !== payload.sessionId), { id: payload.sessionId, title: payload.sessionTitle?.trim() || '新对话', running: wasRunning }].slice(-8));
      setHistoryPages((current) => ({ ...current, [payload.sessionId]: { before: Math.max(0, Number(payload.historyBefore ?? current[payload.sessionId]?.before ?? 0)), hasMore: Boolean(payload.historyHasMore), loading: false } }));
      setReview(reviewBySessionRef.current[payload.sessionId] ?? null);
      setRunning(wasRunning);
      setCleanupPending(Boolean(payload.cleanupPending));
      if (payload.mode) setMode(payload.mode);
      if (payload.strategy) setStrategy(payload.strategy);
      if (!wasRunning && (queuedPromptsRef.current[payload.sessionId]?.length ?? 0) > 0 && !pausedQueuesRef.current.has(payload.sessionId)) {
        readyQueuesRef.current.add(payload.sessionId);
        setQueueRevision((revision) => revision + 1);
      }
    } else if (message.type === 'session.timeline' || message.type === 'session.liveTimeline') {
      const payload = message.payload as { sessionId: string; sessionTitle?: string; blocks: ChatBlock[]; running?: boolean; cleanupPending?: boolean; historyBefore?: number; historyHasMore?: boolean };
      const current = sessionBlocksRef.current[payload.sessionId] ?? [];
      // A timeline read can race with live deltas. While a run is active (the usual
      // session.liveTimeline path), only merge missing history blocks; never replace the live
      // transcript with an older persistence snapshot.
      // Snapshots can arrive after a live delta even when the running flag has already flipped
      // false. Always merge by block/sequence so a late persistence read cannot erase text or
      // tool cards observed by this WebView.
      const next = payload.running === false
        ? settleSessionBlocks(mergeSnapshotBlocks(current, payload.blocks))
        : settleTerminalTurnBlocks(mergeSnapshotBlocks(current, payload.blocks));
      sessionBlocksRef.current[payload.sessionId] = next;
      rememberBlockSequences(lastSequenceByTurnRef.current, next, payload.sessionId);
      if (payload.running === true) runningSessionsRef.current.add(payload.sessionId);
      if (payload.running === false) runningSessionsRef.current.delete(payload.sessionId);
      if (payload.cleanupPending === true) cleanupPendingSessionsRef.current.add(payload.sessionId);
      if (payload.cleanupPending === false) cleanupPendingSessionsRef.current.delete(payload.sessionId);
      if (payload.sessionId === activeSessionRef.current) setBlocks(next);
      if (payload.sessionId === activeSessionRef.current && payload.running != null) setRunning(payload.running);
      if (payload.sessionId === activeSessionRef.current && payload.cleanupPending != null) setCleanupPending(payload.cleanupPending);
      if (payload.historyBefore != null || payload.historyHasMore != null) {
        setHistoryPages((currentPages) => ({ ...currentPages, [payload.sessionId]: {
          before: Math.max(0, Number(payload.historyBefore ?? currentPages[payload.sessionId]?.before ?? 0)),
          hasMore: Boolean(payload.historyHasMore), loading: false
        } }));
      }
    } else if (message.type === 'session.page') {
      const payload = message.payload as { sessionId: string; blocks?: ChatBlock[]; historyBefore?: number; historyHasMore?: boolean };
      const current = sessionBlocksRef.current[payload.sessionId] ?? [];
      const next = mergeSnapshotBlocks(payload.blocks ?? [], current);
      sessionBlocksRef.current[payload.sessionId] = next;
      if (payload.sessionId === activeSessionRef.current) setBlocks(next);
      setHistoryPages((currentPages) => ({ ...currentPages, [payload.sessionId]: {
        before: Math.max(0, Number(payload.historyBefore ?? 0)),
        hasMore: Boolean(payload.historyHasMore), loading: false
      } }));
    } else if (message.type === 'session.renamed') {
      const payload = message.payload as { sessionId?: string; title?: string };
      const title = String(payload.title ?? '').trim();
      if (title && payload.sessionId) {
        setHistoryEntries((current) => current.map((entry) => entry.id === payload.sessionId ? { ...entry, title } : entry));
        setOpenSessions((current) => current.map((tab) => tab.id === payload.sessionId ? { ...tab, title } : tab));
      }
    } else if (message.type === 'history') {
      const entries = message.payload as HistoryEntry[];
      setHistoryEntries(entries);
      entries.forEach((entry) => {
        if (entry.status === 'CLEANING_UP') cleanupPendingSessionsRef.current.add(entry.id);
        else if (entry.status !== 'RUNNING') cleanupPendingSessionsRef.current.delete(entry.id);
      });
      setCleanupPending(cleanupPendingSessionsRef.current.has(activeSessionRef.current));
      setOpenSessions((current) => current.map((tab) => {
        const entry = entries.find((item) => item.id === tab.id);
        return entry ? { ...tab, title: entry.title, running: entry.status === 'RUNNING' || runningSessionsRef.current.has(tab.id) } : tab;
      }));
    } else if (message.type === 'session.exported') {
      const payload = message.payload as { filename?: string; content?: string };
      if (typeof payload.content === 'string') {
        downloadConversation(payload.filename ?? 'omnicode-session.md', payload.content);
        setToast('会话已导出。');
      }
    } else if (message.type === 'settings') {
      const snapshot = message.payload as SettingsSnapshot;
      if (activeProviderRef.current && activeProviderRef.current !== snapshot.provider.id) {
        setModels([]);
        setModelCatalog(null);
      }
      activeProviderRef.current = snapshot.provider.id;
      setSettingsSnapshot((current) => ({
        ...snapshot,
        appearance: { ...(current.appearance ?? EMPTY_SETTINGS.appearance), ...snapshot.appearance,
          streamingOutput: snapshot.appearance?.streamingOutput !== false }
      }));
    } else if (message.type === 'appearance') {
      const payload = message.payload as { locale?: string; localePreference?: string; fontFamily?: string; fontSize?: number; streamingOutput?: boolean };
      if (payload.locale) setLocale(normalizeLocale(payload.locale));
      setSettingsSnapshot((current) => ({ ...current, appearance: {
        locale: payload.localePreference ?? current.appearance?.locale ?? 'auto',
        fontFamily: payload.fontFamily ?? current.appearance?.fontFamily ?? 'system',
        fontSize: Math.min(20, Math.max(11, Number(payload.fontSize ?? current.appearance?.fontSize ?? 13))),
        streamingOutput: payload.streamingOutput ?? current.appearance?.streamingOutput ?? true
      } }));
    } else if (message.type === 'session.templateApplied') {
      const payload = message.payload as { name?: string; mode?: RunMode; strategy?: RunStrategy };
      if (payload.mode === 'AGENT' || payload.mode === 'PLAN' || payload.mode === 'CLAUDE_PLAN') setMode(payload.mode);
      if (payload.strategy === 'AUTO' || payload.strategy === 'SINGLE' || payload.strategy === 'TEAM') setStrategy(payload.strategy);
      setView('chat');
      setToast(`已应用会话模板：${String(payload.name ?? '模板')}`);
      window.setTimeout(() => setToast(''), 3500);
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
    } else if (message.type === 'database.test') {
      setDatabaseTest(message.payload as DatabaseTestState);
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
    } else if (message.type === 'context.snapshot') {
      const payload = message.payload as Partial<ProjectContextUsageView>;
      setContextUsage({
        rulePaths: Array.isArray(payload.rulePaths) ? payload.rulePaths.map(String).slice(0, 64) : [],
        pinnedPaths: Array.isArray(payload.pinnedPaths) ? payload.pinnedPaths.map(String).slice(0, 64) : [],
        excludedPathCount: Math.max(0, Number(payload.excludedPathCount ?? 0)),
        includedCharacters: Math.max(0, Number(payload.includedCharacters ?? 0)),
        estimatedTokens: Math.max(0, Number(payload.estimatedTokens ?? 0)),
        ruleTokens: Math.max(0, Number(payload.ruleTokens ?? 0)),
        memoryTokens: Math.max(0, Number(payload.memoryTokens ?? 0)),
        pinnedTokens: Math.max(0, Number(payload.pinnedTokens ?? 0)),
        harnessTokens: Math.max(0, Number(payload.harnessTokens ?? 0)),
        truncated: Boolean(payload.truncated),
        harnessReadiness: payload.harnessReadiness ? String(payload.harnessReadiness) : undefined,
        harnessScore: payload.harnessScore == null ? undefined : Number(payload.harnessScore),
        harnessFeedbackLoopCount: Math.max(0, Number(payload.harnessFeedbackLoopCount ?? 0)),
        harnessIssueCount: Math.max(0, Number(payload.harnessIssueCount ?? 0))
      });
      setView('chat');
    } else if (message.type === 'project.map') {
      const payload = message.payload as Partial<ProjectMapView>;
      const boundedItems = (value: unknown, limit: number) => Array.isArray(value) ? value.slice(0, limit).map((item) => ({
        name: String((item as { name?: unknown })?.name ?? '').slice(0, 1_024),
        files: Math.max(0, Number((item as { files?: unknown })?.files ?? 0))
      })).filter((item) => item.name) : [];
      setProjectMap({
        scannedFiles: Math.max(0, Number(payload.scannedFiles ?? 0)),
        truncated: Boolean(payload.truncated),
        roots: boundedItems(payload.roots, 16),
        languages: boundedItems(payload.languages, 12),
        edges: Array.isArray(payload.edges) ? payload.edges.slice(0, 96).map((item) => ({
          source: String((item as { source?: unknown })?.source ?? '').slice(0, 160),
          target: String((item as { target?: unknown })?.target ?? '').slice(0, 160),
          files: Math.max(0, Number((item as { files?: unknown })?.files ?? 0))
        })).filter((item) => item.source && item.target) : [],
        entryPoints: Array.isArray(payload.entryPoints) ? payload.entryPoints.map(String).slice(0, 24) : [],
        error: payload.error ? String(payload.error).slice(0, 800) : undefined
      });
      setView('chat');
    } else if (message.type === 'runtime.reset') {
      setRuntimes([]);
      setRuntimeVerifications([]);
    } else if (message.type === 'runtime.status') {
      const status = message.payload as RuntimeStatusView;
      setRuntimes((current) => [...current.filter((item) => item.id !== status.id), status]);
    } else if (message.type === 'runtime.verification') {
      const payload = message.payload as Partial<RuntimeVerificationView>;
      const engineId = String(payload.engineId ?? '').slice(0, 64);
      if (!ENGINES.some(([id]) => id === engineId)) return;
      const state = payload.state === 'checking' || payload.state === 'approval' || payload.state === 'testing' || payload.state === 'responded' || payload.state === 'tool_verified' || payload.state === 'catalog' || payload.state === 'unverified' ? payload.state : 'error';
      const verification: RuntimeVerificationView = {
        engineId,
        state,
        detail: String(payload.detail ?? '').slice(0, 500),
        modelCount: Math.max(0, Math.min(1_000, Number(payload.modelCount ?? 0)))
      };
      setRuntimeVerifications((current) => {
        const previous = current.find((item) => item.engineId === engineId);
        const invalidatePreviousEvidence = state === 'checking' || state === 'error' || state === 'unverified';
        return [...current.filter((item) => item.engineId !== engineId), {
          ...verification,
          modelCount: state === 'catalog' ? verification.modelCount : invalidatePreviousEvidence ? 0 : previous?.modelCount ?? verification.modelCount,
          catalogVerified: state === 'catalog' || (!invalidatePreviousEvidence && Boolean(previous?.catalogVerified)),
          responseVerified: state === 'responded' || state === 'tool_verified' || (!invalidatePreviousEvidence && Boolean(previous?.responseVerified)),
          toolBridgeVerified: state === 'tool_verified' || (!invalidatePreviousEvidence && Boolean(previous?.toolBridgeVerified))
        }];
      });
    } else if (message.type === 'runtime.history') {
      const payload = message.payload as NativeHistoryView;
      setNativeHistory({
        engineId: String(payload.engineId ?? ''),
        state: payload.state === 'loading' || payload.state === 'ready' ? payload.state : 'error',
        entries: Array.isArray(payload.entries) ? payload.entries.slice(0, 50) : [],
        error: payload.error ? String(payload.error).slice(0, 500) : undefined
      });
    } else if (message.type === 'runtime.historyImport') {
      const payload = message.payload as NativeHistoryImportView;
      setNativeHistoryImport(payload);
      if (payload.state === 'error') setToast(payload.error ?? 'CLI 会话导入失败。');
      if (payload.state === 'success') setToast('CLI 会话已导入，可以继续对话。');
      if (payload.state !== 'loading') window.setTimeout(() => setToast(''), 3500);
    } else if (message.type === 'models') {
      const payload = message.payload as { providerId?: string; models?: string[]; status?: string; error?: string };
      if (!payload.providerId || payload.providerId === activeProviderRef.current) {
        setModels(payload.models ?? []);
        setModelCatalog({ providerId: payload.providerId ?? activeProviderRef.current, status: payload.status ?? '加载完成', error: payload.error });
      }
      setToast(payload.error ?? `${payload.models?.length ?? 0} 个模型 · ${payload.status ?? '加载完成'}`);
      window.setTimeout(() => setToast(''), 3500);
    } else if (message.type === 'composer.files') {
      setFileSuggestions(message.payload as string[]);
    } else if (message.type === 'provider.status') {
      setProviderStatus(String((message.payload as { text?: string })?.text ?? ''));
    } else if (message.type === 'usage.status') {
      const payload = message.payload as Partial<TokenTrackerView>;
      const rawUsage = payload.usage;
      setTokenTracker({
        state: payload.state === 'READY' || payload.state === 'NOT_RUNNING' || payload.state === 'UNVERIFIED_SERVICE' || payload.state === 'ERROR' ? payload.state : 'ERROR',
        detail: String(payload.detail ?? '无法读取 TokenTracker 状态。').slice(0, 500),
        cliInstalled: Boolean(payload.cliInstalled),
        managedByOmniCode: Boolean(payload.managedByOmniCode),
        dashboardUrl: String(payload.dashboardUrl ?? 'http://127.0.0.1:7680/dashboard'),
        installCommand: String(payload.installCommand ?? 'TOKENTRACKER_NO_TELEMETRY=1 npx tokentracker-cli'),
        documentationUrl: String(payload.documentationUrl ?? 'https://github.com/xiufengsun/TokenTracker'),
        usageError: payload.usageError ? String(payload.usageError).slice(0, 300) : undefined,
        usage: rawUsage ? {
          from: String(rawUsage.from ?? '').slice(0, 10),
          to: String(rawUsage.to ?? '').slice(0, 10),
          totalTokens: String(rawUsage.totalTokens ?? '0'),
          inputTokens: String(rawUsage.inputTokens ?? '0'),
          outputTokens: String(rawUsage.outputTokens ?? '0'),
          cachedTokens: String(rawUsage.cachedTokens ?? '0'),
          reasoningTokens: String(rawUsage.reasoningTokens ?? '0'),
          conversations: String(rawUsage.conversations ?? '0'),
          costUsd: usageNumber(rawUsage.costUsd),
          activeDays: Math.min(30, Math.floor(usageNumber(rawUsage.activeDays))),
          averagePerActiveDay: String(rawUsage.averagePerActiveDay ?? '0'),
          daily: Array.isArray(rawUsage.daily) ? rawUsage.daily.slice(-30).map((point) => ({ day: String(point.day ?? '').slice(0, 10), tokens: String(point.tokens ?? '0'), costUsd: usageNumber(point.costUsd) })) : [],
          models: Array.isArray(rawUsage.models) ? rawUsage.models.slice(0, 10).map((item) => ({ source: String(item.source ?? '').slice(0, 120), model: String(item.model ?? '').slice(0, 120), tokens: String(item.tokens ?? '0'), costUsd: usageNumber(item.costUsd) })) : [],
          projects: Array.isArray(rawUsage.projects) ? rawUsage.projects.slice(0, 8).map((item) => ({ name: String(item.name ?? '').slice(0, 120), tokens: String(item.tokens ?? '0') })) : []
        } : undefined
      });
    } else if (message.type === 'composer.prefill') {
      const payload = message.payload as { text?: string; mode?: RunMode };
      setPrefill((current) => ({ text: String(payload?.text ?? ''), revision: current.revision + 1 }));
      if (payload?.mode) setMode(payload.mode);
      setView('chat');
    } else if (message.type === 'composer.attachProjectFiles') {
      const payload = message.payload as { sessionId?: string; paths?: string[] };
      const targetSession = String(payload?.sessionId ?? '');
      const paths = Array.isArray(payload?.paths) ? payload.paths.filter((path): path is string => typeof path === 'string') : [];
      if (!targetSession || !paths.length) return;
      setDraftsBySession((current) => {
        const draft = current[targetSession] ?? EMPTY_COMPOSER_DRAFT;
        const attachments = [...draft.attachments];
        for (const path of paths) {
          if (attachments.length >= 4) break;
          if (!path || attachments.some((item) => item.localPath === path)) continue;
          const kind: AttachmentDraft['kind'] = /\.(png|jpe?g|gif|webp)$/i.test(path) ? 'image' : /\.(md|markdown)$/i.test(path) ? 'markdown' : 'text';
          attachments.push({ id: `project-${path}`, fileName: path, mediaType: '', byteSize: 0, kind, content: '', localPath: path });
        }
        return { ...current, [targetSession]: { ...draft, attachments } };
      });
      if (targetSession === activeSessionRef.current) setView('chat');
    } else if (message.type === 'notification') {
      setToast(String((message.payload as { message?: string })?.message ?? message.error ?? '操作完成'));
      window.setTimeout(() => setToast(''), 3500);
    } else if (message.type === 'command.accepted') {
      if (message.requestId) delete pendingQueueAcksRef.current[message.requestId];
    } else if (message.type === 'command.error') {
      const rejectedQueue = message.requestId ? pendingQueueAcksRef.current[message.requestId] : undefined;
      if (rejectedQueue) {
        delete pendingQueueAcksRef.current[message.requestId!];
        const { sessionId: targetSession, prompt } = rejectedQueue;
        if ((queuedPromptsRef.current[targetSession] ?? []).some((item) => item.clientMessageId === prompt.clientMessageId)) {
          queuedPromptsRef.current[targetSession] = (queuedPromptsRef.current[targetSession] ?? []).filter((item) => item.clientMessageId !== prompt.clientMessageId);
          const next = (sessionBlocksRef.current[targetSession] ?? []).filter((block) => block.id !== prompt.clientMessageId);
          sessionBlocksRef.current[targetSession] = next;
          setDraftsBySession((current) => {
            const existing = current[targetSession] ?? EMPTY_COMPOSER_DRAFT;
            return { ...current, [targetSession]: {
              text: [existing.text, prompt.text].filter(Boolean).join('\n'),
              attachments: [...existing.attachments, ...prompt.attachments]
            } };
          });
          if (targetSession === activeSessionRef.current) setBlocks(next);
          setQueueRevision((revision) => revision + 1);
        }
      }
      const payload = message.payload as { command?: string; message?: string };
      setToast(String(payload?.message ?? message.error ?? `操作 ${payload?.command ?? ''} 未执行`).slice(0, 800));
      window.setTimeout(() => setToast(''), 8000);
    } else if (message.type === 'navigation') {
      const navigation = message.payload as { view?: string; tab?: string };
      const target = String(navigation?.view ?? 'chat');
      if (target === 'chat' || target === 'history' || target === 'settings') {
        if (target === 'settings' && navigation.tab && SETTINGS_TABS.some(([id]) => id === navigation.tab)) setRequestedSettingsTab((current) => ({ tab: navigation.tab!, sequence: (current?.sequence ?? 0) + 1 }));
        setView(target);
        // Native title actions bypass the React toolbar; refresh the destination just as the
        // in-WebView buttons do, so a switched page never displays a stale snapshot.
        if (target === 'history') sendCommand('session.list', {});
        if (target === 'settings') sendCommand('settings.snapshot', {});
      }
    }
  }), []);

  // The backend sends the first usage.status together with bootstrap, after it has accepted this
  // page instance. Avoid issuing two commands in the same frame and racing the ready handshake.
  useEffect(() => { sendCommand('frontend.ready', {}); }, []);

  // A route acknowledgement lets the native IDE accessibility tree distinguish a rendered
  // history/settings view from a merely clicked toolbar action. It carries no chat content.
  useEffect(() => {
    if (bridgeBootstrapped) sendCommand('frontend.view', { view });
  }, [bridgeBootstrapped, view]);

  const newSession = useCallback(() => sendCommand('session.new', {}), []);
  const selectSession = useCallback((targetId: string) => {
    if (!targetId || targetId === activeSessionRef.current) { setView('chat'); return; }
    const tab = openSessions.find((item) => item.id === targetId);
    activeSessionRef.current = targetId;
    confirmedSessionRef.current = '';
    pendingSessionLoadRef.current = targetId;
    setSessionId(targetId);
    setBlocks(sessionBlocksRef.current[targetId] ?? []);
    setRunning(runningSessionsRef.current.has(targetId));
    setCleanupPending(cleanupPendingSessionsRef.current.has(targetId));
    setReview(reviewBySessionRef.current[targetId] ?? null);
    setView('chat');
    sendCommand('session.load', { id: targetId });
  }, [openSessions]);
  const closeSessionTab = useCallback((targetId: string) => {
    const targetIndex = openSessions.findIndex((item) => item.id === targetId);
    const remaining = openSessions.filter((item) => item.id !== targetId);
    setOpenSessions(remaining);
    if (targetId !== activeSessionRef.current) return;
    const fallback = remaining[Math.min(Math.max(0, targetIndex), remaining.length - 1)];
    if (fallback) selectSession(fallback.id);
    else newSession();
  }, [newSession, openSessions, selectSession]);
  const loadOlderHistory = useCallback(() => {
    const targetId = activeSessionRef.current;
    if (!targetId) return;
    const page = historyPages[targetId];
    if (!page?.hasMore || page.loading) return;
    setHistoryPages((current) => ({ ...current, [targetId]: { ...page, loading: true } }));
    sendCommand('session.loadOlder', { sessionId: targetId, before: page.before });
  }, [historyPages]);
  const updateDraftForSession = useCallback((targetSession: string, update: ComposerDraftUpdate) => {
    if (!targetSession) return;
    setDraftsBySession((current) => {
      const existing = current[targetSession] ?? EMPTY_COMPOSER_DRAFT;
      const next = typeof update === 'function' ? update(existing) : update;
      return { ...current, [targetSession]: next };
    });
  }, []);
  const cancelActiveRun = useCallback(() => {
    const targetSession = activeSessionRef.current || sessionId;
    if (!targetSession) return;
    // Stop settles the visible turn immediately. The host still owns process-tree cleanup, and
    // the same session remains reserved until that cleanup actually finishes. Late deltas from
    // the cancelled request are quarantined below.
    const current = sessionBlocksRef.current[targetSession] ?? [];
    const queued = queuedPromptsRef.current[targetSession] ?? [];
    // Stopping applies to the active turn only. Preserve follow-up prompts, but pause
    // them so cancellation cannot silently start the next task or discard user input.
    if (queued.length) {
      setQueueRevision((revision) => revision + 1);
    }
    pausedQueuesRef.current.add(targetSession);
    readyQueuesRef.current.delete(targetSession);
    delete inFlightPromptsRef.current[targetSession];
    const activeBlocks = sessionBlocksRef.current[targetSession] ?? [];
    activeBlocks.forEach((block) => {
      if (block.status === 'running' && block.turnId) {
        terminalTurnsRef.current.add(`${targetSession}:${block.turnId}`);
      }
    });
    const settled = settleSessionBlocks(activeBlocks);
    sessionBlocksRef.current[targetSession] = settled;
    runningSessionsRef.current.delete(targetSession);
    cleanupPendingSessionsRef.current.add(targetSession);
    if (targetSession === activeSessionRef.current) {
      setBlocks(settled);
      setRunning(false);
      setCleanupPending(true);
      setToast(queued.length
        ? `已请求停止；${queued.length} 条待发送消息已保留并暂停，请检查后手动继续。`
        : '已请求停止；后台正在回收 CLI 进程，可继续新建会话。');
      window.setTimeout(() => setToast(''), 4000);
    }
    sendCommand('session.cancel', { sessionId: targetSession });
  }, [sessionId]);

  const dispatchPrompt = useCallback((prompt: QueuedPrompt) => {
    const targetSession = activeSessionRef.current;
    if (!targetSession) return;
    const current = (sessionBlocksRef.current[targetSession] ?? []).filter((block) => block.id !== `${prompt.clientMessageId}-rejected`);
    const visibleText = prompt.text || prompt.attachments.map((item) => item.fileName).join('、');
    const optimistic: ChatBlock = {
      id: prompt.clientMessageId,
      role: 'user',
      kind: 'message.user',
      text: visibleText,
      metadata: { attachments: prompt.attachments.map((item) => item.fileName), optimistic: true, queued: false }
    };
    const next = current.some((block) => block.id === prompt.clientMessageId)
      ? current.map((block) => block.id === prompt.clientMessageId
        ? { ...block, kind: 'message.user', text: visibleText, metadata: { ...block.metadata, queued: false, optimistic: true } }
        : block)
      : [...current, optimistic];
    sessionBlocksRef.current[targetSession] = next;
    runningSessionsRef.current.add(targetSession);
    readyQueuesRef.current.delete(targetSession);
    inFlightPromptsRef.current[targetSession] = prompt;
    setBlocks(next);
    setRunning(true);
    sendCommand('session.send', {
      sessionId: targetSession,
      text: prompt.text,
      mode: prompt.mode,
      strategy: prompt.strategy,
      attachments: prompt.attachments,
      clientMessageId: prompt.clientMessageId
    });
  }, []);

  const send = useCallback((text: string, attachments: AttachmentDraft[]) => {
    if (text.trim().toLowerCase() === '/context' && attachments.length === 0) {
      sendCommand('context.snapshot', {});
      return true;
    }
    if (text.trim().toLowerCase() === '/map' && attachments.length === 0) {
      sendCommand('project.map', {});
      return true;
    }
    const randomId = globalThis.crypto?.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(16).slice(2)}`;
    const clientMessageId = `client-${randomId}`.replace(/[^A-Za-z0-9._:-]/g, '-').slice(0, 200);
    const targetSession = activeSessionRef.current;
    if (!targetSession) return false;
    const prompt: QueuedPrompt = { text, attachments, mode, strategy, clientMessageId };
    if (confirmedSessionRef.current !== targetSession || runningSessionsRef.current.has(targetSession) || cleanupPendingSessionsRef.current.has(targetSession) || pausedQueuesRef.current.has(targetSession) || (queuedPromptsRef.current[targetSession]?.length ?? 0) > 0) {
      if ((queuedPromptsRef.current[targetSession]?.length ?? 0) >= 20) {
        setToast('当前会话最多排队 20 条。请先处理或删除待发送消息。');
        window.setTimeout(() => setToast(''), 4000);
        return false;
      }
      const current = sessionBlocksRef.current[targetSession] ?? [];
      const visibleText = text || attachments.map((item) => item.fileName).join('、');
      const next = current.some((block) => block.id === clientMessageId) ? current : [...current, {
        id: clientMessageId,
        role: 'user' as const,
        kind: 'message.user.queued',
        text: visibleText,
        metadata: { attachments: attachments.map((item) => item.fileName), optimistic: true, queued: true }
      }];
      queuedPromptsRef.current[targetSession] = [...(queuedPromptsRef.current[targetSession] ?? []), prompt];
      const requestId = sendCommand('queue.upsert', { sessionId: targetSession, clientMessageId, text, mode, strategy, requiresReattach: attachments.length > 0 });
      pendingQueueAcksRef.current[requestId] = { sessionId: targetSession, prompt };
      sessionBlocksRef.current[targetSession] = next;
      setBlocks(next);
      setQueueRevision((revision) => revision + 1);
      return true;
    }
    dispatchPrompt(prompt);
    return true;
  }, [dispatchPrompt, mode, strategy]);

  // Flush exactly one prompt after the active turn reaches a terminal state. A
  // per-session queue means switching conversations never sends a prompt to the
  // wrong run, and the next prompt gets its own lifecycle/turn on the host.
  useEffect(() => {
    if (running || cleanupPending || !sessionId || confirmedSessionRef.current !== sessionId || runningSessionsRef.current.has(sessionId) || cleanupPendingSessionsRef.current.has(sessionId) || pausedQueuesRef.current.has(sessionId) || !readyQueuesRef.current.has(sessionId)) return;
    const queue = queuedPromptsRef.current[sessionId] ?? [];
    if (!queue.length || queue[0]?.requiresReattach) return;
    const next = queue.shift();
    if (!next) return;
    queuedPromptsRef.current[sessionId] = queue;
    readyQueuesRef.current.delete(sessionId);
    setQueueRevision((revision) => revision + 1);
    dispatchPrompt(next);
  }, [cleanupPending, dispatchPrompt, queueRevision, running, sessionId]);

  const queuedPrompts = queuedPromptsRef.current[sessionId] ?? [];
  const queuePaused = pausedQueuesRef.current.has(sessionId);
  const onQueueRemove = (id: string) => {
    const targetSession = activeSessionRef.current;
    sendCommand('queue.remove', { sessionId: targetSession, clientMessageId: id });
    queuedPromptsRef.current[targetSession] = (queuedPromptsRef.current[targetSession] ?? []).filter((item) => item.clientMessageId !== id);
    const next = (sessionBlocksRef.current[targetSession] ?? []).filter((block) => block.id !== id);
    sessionBlocksRef.current[targetSession] = next;
    setBlocks(next);
    setQueueRevision((revision) => revision + 1);
  };
  const onQueueEdit = (id: string, text: string) => {
    const targetSession = activeSessionRef.current;
    const original = (queuedPromptsRef.current[targetSession] ?? []).find((item) => item.clientMessageId === id);
    if (original) sendCommand('queue.upsert', { sessionId: targetSession, clientMessageId: id, text, mode: original.mode, strategy: original.strategy, requiresReattach: Boolean(original.requiresReattach || original.attachments.length) });
    queuedPromptsRef.current[targetSession] = (queuedPromptsRef.current[targetSession] ?? []).map((item) => item.clientMessageId === id ? { ...item, text } : item);
    const next = (sessionBlocksRef.current[targetSession] ?? []).map((block) => block.id === id ? { ...block, text: text || block.metadata?.attachments?.toString() || '' } : block);
    sessionBlocksRef.current[targetSession] = next;
    setBlocks(next);
    setQueueRevision((revision) => revision + 1);
  };
  const onQueueMove = (id: string, direction: -1 | 1) => {
    const targetSession = activeSessionRef.current;
    const next = [...(queuedPromptsRef.current[targetSession] ?? [])];
    const index = next.findIndex((item) => item.clientMessageId === id);
    const destination = index + direction;
    if (index < 0 || destination < 0 || destination >= next.length) return;
    [next[index], next[destination]] = [next[destination], next[index]];
    sendCommand('queue.move', { sessionId: targetSession, clientMessageId: id, direction });
    queuedPromptsRef.current[targetSession] = next;
    setQueueRevision((revision) => revision + 1);
  };
  const onQueuePause = (paused: boolean) => {
    const targetSession = activeSessionRef.current;
    if (!paused && queuedPromptsRef.current[targetSession]?.[0]?.requiresReattach) return;
    if (paused) pausedQueuesRef.current.add(targetSession);
    else {
      pausedQueuesRef.current.delete(targetSession);
      readyQueuesRef.current.add(targetSession);
    }
    setQueueRevision((revision) => revision + 1);
  };
  const onQueueRestore = (id: string) => {
    const targetSession = activeSessionRef.current;
    const item = (queuedPromptsRef.current[targetSession] ?? []).find((prompt) => prompt.clientMessageId === id);
    if (!item) return;
    setDraftsBySession((current) => {
      const existing = current[targetSession] ?? EMPTY_COMPOSER_DRAFT;
      return { ...current, [targetSession]: { ...existing, text: [existing.text, item.text].filter(Boolean).join('\n') } };
    });
    onQueueRemove(id);
  };
  const activeDraft = draftsBySession[sessionId] ?? EMPTY_COMPOSER_DRAFT;
  const t = createTranslator(locale);

  return <div className="app-shell">
    <header className="topbar">
      <button className="project-title" title={t('nav.backChat')} onClick={() => setView('chat')}><span className="bolt">ϟ</span><span><strong>{projectName}</strong><small>{t('app.workspace')}</small></span></button>
      <div className="provider-status">{providerStatus}</div>
      <div className={`run-state ${running ? 'running' : cleanupPending ? 'cleanup-pending' : ''}`}><span />{running ? t('status.running') : cleanupPending ? t('status.cleanupPending') : t('status.ready')}</div>
      <span className="mode-pill">{mode === 'CLAUDE_PLAN' ? 'Claude Plan' : mode === 'PLAN' ? 'Plan' : 'Agent'}</span>
      <button className="icon-button" title={t('nav.newChat')} onClick={newSession}><Plus /></button>
      <button className="icon-button" title="全局搜索（⌘/Ctrl K）" onClick={() => setGlobalSearchOpen(true)}><Search /></button>
      <button className="icon-button support-trigger" title="支持 OmniCode" onClick={() => setSupportOpen(true)}><Heart /></button>
      <button className={`icon-button ${view === 'history' ? 'active' : ''}`} title={t('nav.history')} onClick={() => { setView('history'); sendCommand('session.list', {}); }}><History /></button>
      <button className={`icon-button ${view === 'settings' ? 'active' : ''}`} title={t('nav.settings')} onClick={() => { setRequestedSettingsTab(undefined); setView('settings'); sendCommand('settings.snapshot', {}); }}><Settings /></button>
    </header>
    {view === 'chat' && <SessionTabBar sessions={openSessions.map((tab) => ({ ...tab, queuedCount: queuedPromptsRef.current[tab.id]?.length ?? 0 }))} activeId={sessionId} onSelect={selectSession} onClose={closeSessionTab} onRename={(id, title) => { setOpenSessions((current) => current.map((tab) => tab.id === id ? { ...tab, title } : tab)); sendCommand('session.rename', { id, title }); }} />}
    <div className="view-host">
      <div className={view === 'chat' ? 'view-layer active' : 'view-layer hidden'}><ChatView blocks={blocks} running={running} cleanupPending={cleanupPending} queuedPrompts={queuedPrompts} queuePaused={queuePaused} onQueueRemove={onQueueRemove} onQueueEdit={onQueueEdit} onQueueMove={onQueueMove} onQueuePause={onQueuePause} onQueueRestore={onQueueRestore} historyPage={historyPages[sessionId]} onLoadOlder={loadOlderHistory} plan={plan} review={review} diagnostics={diagnostics} contextUsage={contextUsage} projectMap={projectMap} onCloseDiagnostics={() => setDiagnostics(null)} onCloseContextUsage={() => setContextUsage(null)} onCloseProjectMap={() => setProjectMap(null)} onRetryDiagnostics={() => sendCommand('connection.diagnose', {})} mode={mode} setMode={setMode} strategy={strategy} setStrategy={setStrategy} onSend={send} onCancel={cancelActiveRun} prefill={prefill} prompts={settingsSnapshot.prompts} fileSuggestions={fileSuggestions} settings={settingsSnapshot} models={models} draft={activeDraft} onDraftChange={(update) => updateDraftForSession(sessionId, update)} onRewind={(messageIndex) => sendCommand('session.rewind', { id: activeSessionRef.current, messageIndex })} locale={locale} focusedBlockId={focusedBlockId} onFocusHandled={() => setFocusedBlockId('')} onOpenSupport={() => setSupportOpen(true)} /></div>
      {view === 'history' && <HistoryView entries={historyEntries} onLoad={selectSession} onDelete={(id) => sendCommand('session.delete', { id })} onFavorite={(id, favorite) => sendCommand('session.favorite', { id, favorite })} onExport={(id) => sendCommand('session.export', { id })} onFork={(id) => sendCommand('session.fork', { id })} locale={locale} />}
      {view === 'settings' && <SettingsView requestedTab={requestedSettingsTab} snapshot={settingsSnapshot} catalog={mcpCatalog} catalogState={mcpCatalogState} runtimes={runtimes} runtimeVerifications={runtimeVerifications} nativeHistory={nativeHistory} nativeHistoryImport={nativeHistoryImport} models={models} modelCatalog={modelCatalog} mcpTest={mcpTest} mcpAuth={mcpAuth} mcpDraftSelection={mcpDraftSelection} tokenTracker={tokenTracker} databaseTest={databaseTest} currentMode={mode} currentStrategy={strategy} onOpenSupport={() => setSupportOpen(true)} />}
      {view === 'chat' && settingsSnapshot.theme.petEnabled && (() => {
        const pet = settingsSnapshot.pets.find((item) => item.id === settingsSnapshot.theme.petId);
        return pet ? <PetCompanion pet={pet} running={running} x={settingsSnapshot.theme.petX ?? 8800} y={settingsSnapshot.theme.petY ?? 7600} /> : null;
      })()}
    </div>
    <GlobalSearch open={globalSearchOpen} query={globalSearchQuery} onQuery={setGlobalSearchQuery} onClose={() => setGlobalSearchOpen(false)} history={historyEntries} blocks={visibleTranscriptBlocks(blocks, running, settingsSnapshot.appearance?.streamingOutput !== false)} files={fileSuggestions} settings={settingsSnapshot} onOpenSession={(id) => sendCommand('session.load', { id })} onOpenMessage={(id) => { setView('chat'); setFocusedBlockId(id); }} />
    {toast && <div className="toast">{toast}</div>}
    {supportOpen && <SupportDialog snapshot={settingsSnapshot} onClose={() => setSupportOpen(false)} />}
    {releaseNotesOpen && <ReleaseNotesDialog onClose={dismissReleaseNotes} />}
  </div>;
}
