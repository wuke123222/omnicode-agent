export type RootView = 'chat' | 'history' | 'settings';
export type RunMode = 'AGENT' | 'PLAN' | 'CLAUDE_PLAN';
export type RunStrategy = 'AUTO' | 'SINGLE' | 'TEAM';

export interface BridgeCommand<T = Record<string, unknown>> {
  schemaVersion: 1;
  pageGeneration: number;
  clientInstanceId: string;
  requestId: string;
  command: string;
  payload: T;
}

export interface ChatEventEnvelopeV1 {
  schemaVersion: 1;
  pageGeneration: number;
  sessionId: string;
  turnId: string;
  blockId: string;
  parentId?: string;
  sequence: number;
  kind: string;
  phase: string;
  at: string;
  payload: Record<string, unknown>;
}

export interface ChatBlock {
  id: string;
  turnId?: string;
  sequence?: number;
  role: 'user' | 'assistant' | 'system';
  kind: string;
  phase?: string;
  text: string;
  title?: string;
  status?: 'running' | 'success' | 'error' | 'warning';
  metadata?: Record<string, unknown>;
}

export interface HistoryEntry {
  id: string;
  title: string;
  updatedAt: string;
  status: string;
  messageCount: number;
  /** Navigation-only preference; conversation content remains in the native store. */
  favorite?: boolean;
}

export interface ProviderEntry {
  id: string;
  name: string;
  defaultBaseUrl: string;
  defaultModel: string;
  cli: boolean;
  modelDiscovery?: boolean;
  baseUrl?: string;
  model?: string;
  credentialConfigured?: boolean;
  hidden?: boolean;
}

export interface SettingsSnapshot {
  releaseNotesVersion: string;
  provider: {
    id: string;
    baseUrl: string;
    model: string;
    reasoningEffort: string;
    maxOutputTokens: number;
    credentialConfigured: boolean;
  };
  providers: ProviderEntry[];
  platform: {
    sandboxMode: string;
    historyEnabled: boolean;
    notificationsEnabled: boolean;
    historyRetention: number;
    usageRetentionDays: number;
    mcpCount: number;
    skillCount: number;
    promptCount: number;
    commitAiEnabled: boolean;
    agentContinuousExecution: boolean;
    providerMaxAttempts: number;
  };
  theme: {
    id: string;
    petEnabled: boolean;
    petId: string;
    petX?: number;
    petY?: number;
    palette: {
      background: string; surface: string; elevatedSurface: string; primaryText: string;
      secondaryText: string; accent: string; border: string; success: string; warning: string; error: string;
    };
  };
  appearance: { locale: string; fontFamily: string; fontSize: number; streamingOutput?: boolean };
  support: { wechatQrDataUrl: string; message: string };
  themes: Array<{ id: string; name: string; description: string }>;
  pets: PetView[];
  projectContext: { pinnedPaths: string[]; excludedPaths: string[]; memoryNotes: string };
  mcpServers: McpServerView[];
  databaseConnections: DatabaseConnectionView[];
  prompts: PromptTemplateView[];
  skills: SkillSourceView[];
  sessionTemplates: SessionTemplateView[];
}

export interface PetView {
  id: string;
  name: string;
  description: string;
  glyph: string;
  accent?: string;
  visual: 'creature' | 'owl' | 'duck' | 'robot' | 'idol-vocalist' | 'idol-guitarist' | 'custom-avatar';
  motion: 'bob' | 'hop' | 'glide' | 'doze';
  idleIntervalSeconds: number;
  idleMessages: string[];
}

export interface DatabaseConnectionView {
  id: string;
  name: string;
  enabled: boolean;
  jdbcUrl: string;
  driver: 'postgresql' | 'mariadb';
  username: string;
  connectTimeoutSeconds: number;
  queryTimeoutSeconds: number;
  maxRows: number;
  passwordConfigured: boolean;
  /** Ephemeral evidence for the saved configuration, not an active session connection. */
  recentlyTested?: boolean;
}

export interface McpServerView {
  id: string; name: string; enabled: boolean; transport: 'stdio' | 'http';
  providerScope: 'all' | 'claude' | 'codex';
  command: string; arguments: string; environmentKeys: string; workingDirectory: string;
  url: string; httpAuthMode: 'none' | 'bearer' | 'oauth'; oauthClientId: string; oauthScopes: string;
  bearerConfigured: boolean; oauthConfigured: boolean; oauthUsable: boolean;
  recentlyTested?: boolean;
}

export interface McpCatalogEntryView {
  id: string; name: string; publisher: string; description: string; category: string;
  source: 'BUILT_IN_PRESET' | 'MCP_REGISTRY'; risk: 'LOW' | 'MEDIUM' | 'HIGH'; riskSummary: string; tags: string[];
  options: Array<{ id: string; name: string; kind: string; transport?: 'stdio' | 'http'; command?: string; arguments?: string; workingDirectory?: string; url?: string; authentication?: 'none' | 'bearer' | 'oauth' }>;
}

export interface PromptTemplateView { id: string; name: string; shortcut: string; content: string }
export interface SkillSourceView { id: string; name: string; path: string; enabled: boolean }
export interface SessionTemplateView {
  id: string; name: string; providerId: string; model: string; reasoningEffort: string;
  mode: RunMode; strategy: RunStrategy;
}
export interface RuntimeStatusView {
  id: string; name: string; runnable: boolean; version: string; path: string; diagnostic: string;
  modelDiscovery: boolean; nativeResume: boolean; nativeHistory: boolean;
  sessionContinuity: 'BOUNDED_REPLAY' | 'NATIVE_SESSION_ID' | 'PERSISTENT_HOST';
}

export interface RuntimeVerificationView {
  engineId: string;
  state: 'checking' | 'approval' | 'testing' | 'responded' | 'tool_verified' | 'catalog' | 'unverified' | 'error';
  detail: string;
  modelCount: number;
  catalogVerified?: boolean;
  responseVerified?: boolean;
  toolBridgeVerified?: boolean;
}

export interface NativeHistoryEntryView {
  sessionId: string;
  title: string;
  updatedAt: string;
}

export interface NativeHistoryView {
  engineId: string;
  state: 'loading' | 'ready' | 'error';
  entries: NativeHistoryEntryView[];
  error?: string;
}

export interface NativeHistoryImportView {
  engineId: string;
  sessionId: string;
  state: 'loading' | 'success' | 'error';
  conversationId?: string;
  error?: string;
}

export interface BootstrapPayload {
  locale?: string;
  projectName: string;
  sessionId: string;
  sessionTitle?: string;
  running: boolean;
  cleanupPending?: boolean;
  mode?: RunMode;
  strategy?: RunStrategy;
  providerStatus: string;
  providerConfigured: boolean;
  blocks: ChatBlock[];
  pendingQueue?: PendingPromptSnapshot[];
  historyBefore?: number;
  historyHasMore?: boolean;
  history: HistoryEntry[];
  settings: SettingsSnapshot;
  models?: string[];
  plan?: PlanProposal | null;
}

export interface PendingPromptSnapshot {
  clientMessageId: string;
  text: string;
  mode: RunMode;
  strategy: RunStrategy;
  requiresReattach: boolean;
}

export interface ProjectContextUsageView {
  rulePaths: string[];
  pinnedPaths: string[];
  excludedPathCount: number;
  includedCharacters: number;
  estimatedTokens: number;
  ruleTokens: number;
  memoryTokens: number;
  pinnedTokens: number;
  harnessTokens: number;
  truncated: boolean;
  harnessReadiness?: string;
  harnessScore?: number;
  harnessFeedbackLoopCount: number;
  harnessIssueCount: number;
}

export interface PlanStepView {
  id: string;
  text: string;
  state: 'DRAFT' | 'APPROVED' | 'SKIPPED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'PAUSED';
  attempts: number;
  lastError: string;
}

export interface PlanProposal {
  id: string;
  title: string;
  mode: RunMode;
  revision: number;
  decision: string;
  approvedCount: number;
  completedCount: number;
  steps: PlanStepView[];
}

declare global {
  interface Window {
    omnicodeSend?: (message: string) => void;
    __omnicodeReceive?: (message: unknown) => void;
    __omnicodeBridgeReceipt?: (requestId: string, command: string, response: string) => void;
    __OMNICODE_PAGE_GENERATION__?: number;
  }
}
