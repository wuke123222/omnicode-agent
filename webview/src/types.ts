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
}

export interface ProviderEntry {
  id: string;
  name: string;
  defaultBaseUrl: string;
  defaultModel: string;
  cli: boolean;
  baseUrl?: string;
  model?: string;
  credentialConfigured?: boolean;
}

export interface SettingsSnapshot {
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
  themes: Array<{ id: string; name: string; description: string }>;
  pets: Array<{ id: string; name: string; description: string; glyph: string; accent?: string }>;
  projectContext: { pinnedPaths: string[]; excludedPaths: string[] };
  mcpServers: McpServerView[];
  prompts: PromptTemplateView[];
  skills: SkillSourceView[];
}

export interface McpServerView {
  id: string; name: string; enabled: boolean; transport: 'stdio' | 'http';
  command: string; arguments: string; environmentKeys: string; workingDirectory: string;
  url: string; httpAuthMode: 'none' | 'bearer' | 'oauth'; oauthClientId: string; oauthScopes: string;
  bearerConfigured: boolean; oauthConfigured: boolean; oauthUsable: boolean;
}

export interface McpCatalogEntryView {
  id: string; name: string; publisher: string; description: string; category: string;
  source: 'BUILT_IN_PRESET' | 'MCP_REGISTRY'; risk: 'LOW' | 'MEDIUM' | 'HIGH'; riskSummary: string; tags: string[];
  options: Array<{ id: string; name: string; kind: string }>;
}

export interface PromptTemplateView { id: string; name: string; shortcut: string; content: string }
export interface SkillSourceView { id: string; name: string; path: string; enabled: boolean }
export interface RuntimeStatusView {
  id: string; name: string; runnable: boolean; version: string; path: string; diagnostic: string;
  modelDiscovery: boolean; nativeResume: boolean; nativeHistory: boolean;
}

export interface BootstrapPayload {
  projectName: string;
  sessionId: string;
  running: boolean;
  providerStatus: string;
  providerConfigured: boolean;
  blocks: ChatBlock[];
  history: HistoryEntry[];
  settings: SettingsSnapshot;
  models?: string[];
  plan?: PlanProposal | null;
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
    __OMNICODE_PAGE_GENERATION__?: number;
  }
}
