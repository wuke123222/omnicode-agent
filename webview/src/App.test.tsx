import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { App } from './App';
import packageJson from '../package.json';

const settings = {
  releaseNotesVersion: packageJson.version,
  provider: { id: 'cli-opencode', baseUrl: 'cli://local', model: 'opencode/model-a', reasoningEffort: 'auto', maxOutputTokens: 8192, credentialConfigured: true },
  providers: [
    { id: 'cli-opencode', name: 'OpenCode CLI', defaultBaseUrl: 'cli://local', defaultModel: 'default', cli: true, modelDiscovery: true, baseUrl: 'cli://local', model: 'opencode/model-a', credentialConfigured: true },
    { id: 'openai', name: 'OpenAI', defaultBaseUrl: 'https://api.openai.com/v1', defaultModel: 'gpt-5.6-sol', cli: false, baseUrl: 'https://api.openai.com/v1', model: 'gpt-5.6-sol', credentialConfigured: true }
  ],
  platform: { sandboxMode: 'WORKSPACE_WRITE', historyEnabled: true, notificationsEnabled: true, historyRetention: 100, usageRetentionDays: 365, mcpCount: 0, skillCount: 0, promptCount: 0, commitAiEnabled: true, agentContinuousExecution: true, providerMaxAttempts: 3 },
  theme: {
    id: 'jetbrains-native', petEnabled: false, petId: 'pixel-cat', petX: 8800, petY: 7600,
    palette: { background: '#151719', surface: '#202327', elevatedSurface: '#1b1d20', primaryText: '#e7e9ed', secondaryText: '#979da7', accent: '#6ea2ff', border: '#34383e', success: '#5dcc83', warning: '#d9a14a', error: '#ee6b72' }
  },
  appearance: { locale: 'auto', fontFamily: 'system', fontSize: 13, streamingOutput: true },
  themes: [{ id: 'jetbrains-native', name: 'JetBrains Native', description: 'Follow IDE' }],
  pets: [{
    id: 'pixel-cat', name: 'Pixel Cat', description: 'Pet', glyph: '🐈', accent: '#8FAEFF',
    visual: 'creature', motion: 'bob', idleIntervalSeconds: 45, idleMessages: ['Ready when you are.']
  }],
  projectContext: { pinnedPaths: [], excludedPaths: [], memoryNotes: '' },
  mcpServers: [], databaseConnections: [], prompts: [], skills: [], sessionTemplates: []
};

async function bootstrap(extra: Record<string, unknown> = {}) {
  await act(async () => {
    window.__omnicodeReceive?.({
      type: 'bootstrap',
      payload: {
        projectName: 'demo', sessionId: 's1', running: false, providerStatus: 'OpenCode',
        providerConfigured: true, blocks: [], history: [], settings,
        models: ['opencode/model-a', 'opencode/model-b'],
        ...extra
      }
    });
  });
}

function sentCommands() {
  return vi.mocked(window.omnicodeSend!).mock.calls.map(([value]) => JSON.parse(value));
}

describe('OmniCode CCGUI shell', () => {
  beforeEach(() => {
    window.omnicodeSend = vi.fn();
    window.__OMNICODE_PAGE_GENERATION__ = 7;
    window.localStorage.clear();
  });

  it('shows a per-version release summary and remembers dismissal', async () => {
    const first = render(<App />);
    await bootstrap({ settings: { ...settings, releaseNotesVersion: '' } });
    expect(screen.getByRole('dialog', { name: '这次更新了什么' })).toBeInTheDocument();
    expect(screen.getByText(`OMNICODE 更新 · ${packageJson.version}`)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '开始使用' }));
    expect(sentCommands().some((command) => command.command === 'settings.releaseNotesSeen' && command.payload.version === packageJson.version)).toBe(true);
    first.unmount();

    render(<App />);
    await bootstrap({ settings });
    expect(screen.queryByRole('dialog', { name: '这次更新了什么' })).not.toBeInTheDocument();
  });

  it('offers optional GitHub and WeChat support without gating features', async () => {
    render(<App />);
    await bootstrap({ settings: { ...settings, releaseNotesVersion: '' } });
    fireEvent.click(screen.getByRole('button', { name: '开始使用' }));
    fireEvent.click(screen.getByTitle('支持 OmniCode'));
    expect(screen.getByRole('dialog', { name: '让 OmniCode 继续变好' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /去 GitHub 点个 Star/ })).toBeInTheDocument();
    expect(screen.getByText('尚未配置收款码')).toBeInTheDocument();
  });

  it('exposes only three root views and keeps settings in its own view', () => {
    render(<App />);
    expect(screen.queryByText('任务中心')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTitle('设置'));
    expect(screen.getByText('供应商')).toBeInTheDocument();
    expect(screen.getByText('MCP')).toBeInTheDocument();
    expect(screen.queryByText('实验与科研')).not.toBeInTheDocument();
  });

  it('takes a first-use CLI check directly to dependencies without sending a model request', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByRole('button', { name: /首次使用？检查本地引擎与模型/ }));
    expect(sentCommands().some((item) => item.command === 'navigation.view' && item.payload.tab === 'dependencies')).toBe(true);
    expect(sentCommands().some((item) => item.command === 'runtime.smokeTest')).toBe(false);
    await act(async () => window.__omnicodeReceive?.({ type: 'navigation', payload: { view: 'settings', tab: 'dependencies' } }));
    expect(screen.getByText('本地运行引擎')).toBeInTheDocument();
  });

  it('acknowledges rendered native routes only after the IDE bootstrap', async () => {
    render(<App />);
    expect(sentCommands().some((item) => item.command === 'frontend.view')).toBe(false);
    await bootstrap();
    expect(sentCommands().some((item) => item.command === 'frontend.view' && item.payload.view === 'chat')).toBe(true);
    const historyRequests = sentCommands().filter((item) => item.command === 'session.list').length;
    await act(async () => {
      window.__omnicodeReceive?.({ type: 'navigation', payload: { view: 'history' } });
    });
    expect(sentCommands().some((item) => item.command === 'frontend.view' && item.payload.view === 'history')).toBe(true);
    expect(sentCommands().filter((item) => item.command === 'session.list')).toHaveLength(historyRequests + 1);
    const settingsRequests = sentCommands().filter((item) => item.command === 'settings.snapshot').length;
    await act(async () => {
      window.__omnicodeReceive?.({ type: 'navigation', payload: { view: 'settings' } });
    });
    expect(sentCommands().some((item) => item.command === 'frontend.view' && item.payload.view === 'settings')).toBe(true);
    expect(sentCommands().filter((item) => item.command === 'settings.snapshot')).toHaveLength(settingsRequests + 1);
  });

  it('uses the IDE locale for high-frequency navigation and composer controls', async () => {
    render(<App />);
    await bootstrap({ locale: 'en-US' });
    expect(screen.getByTitle('New chat')).toBeInTheDocument();
    expect(screen.getByTitle('History')).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/Describe a task/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Send' })).toBeInTheDocument();
  });

  it('searches sessions messages files prompts skills and commands from one palette', async () => {
    render(<App />);
    await bootstrap({
      history: [{ id: 'history-1', title: 'Parser migration', updatedAt: '2026-09-10', status: 'COMPLETED', messageCount: 2 }],
      blocks: [{ id: 'answer-1', role: 'assistant', kind: 'message.assistant', text: 'The parser lives in ParserService.kt' }],
      settings: {
        ...settings,
        prompts: [{ id: 'prompt-1', name: 'Review parser', shortcut: 'parser-review', content: 'Review ParserService.kt' }],
        skills: [{ id: 'skill-1', name: 'Parser skill', path: '/skills/parser', enabled: true }],
        sessionTemplates: []
      }
    });
    await act(async () => window.__omnicodeReceive?.({ type: 'composer.files', payload: ['src/ParserService.kt'] }));

    fireEvent.keyDown(window, { key: 'k', metaKey: true });
    const input = screen.getByPlaceholderText(/搜索会话、当前消息/);
    fireEvent.change(input, { target: { value: 'parser' } });

    expect(screen.getByText('Parser migration')).toBeInTheDocument();
    expect(screen.getByText('OmniCode 回复')).toBeInTheDocument();
    expect(screen.getByText('src/ParserService.kt')).toBeInTheDocument();
    expect(screen.getByText('!parser-review · Review parser')).toBeInTheDocument();
    expect(screen.getByText('Parser skill')).toBeInTheDocument();
    fireEvent.click(screen.getByText('src/ParserService.kt'));
    expect(sentCommands().some((value) => value.command === 'navigation.openFile' && value.payload.path === 'src/ParserService.kt')).toBe(true);
  });

  it('opens the bounded IntelliJ project map inside chat without starting a model turn', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByRole('button', { name: /探索项目地图/ }));
    expect(sentCommands().some((value) => value.command === 'project.map')).toBe(true);
    expect(sentCommands().some((value) => value.command === 'session.send')).toBe(false);

    await act(async () => window.__omnicodeReceive?.({
      type: 'project.map',
      payload: {
        scannedFiles: 144, truncated: false,
        roots: [{ name: 'src', files: 88 }], languages: [{ name: 'Kotlin', files: 64 }],
        entryPoints: ['README.md', 'build.gradle.kts']
      }
    }));
    expect(screen.getByRole('dialog', { name: '项目地图' })).toBeInTheDocument();
    expect(screen.getByText('144')).toBeInTheDocument();
    expect(screen.getAllByText('Kotlin').length).toBeGreaterThan(0);
    expect(screen.getByText(/不表示调用或依赖/)).toBeInTheDocument();
    expect(document.querySelectorAll('.project-map-edge')).toHaveLength(0);
    fireEvent.click(screen.getAllByText('README.md').at(-1)!);
    expect(sentCommands().some((value) => value.command === 'navigation.openFile' && value.payload.path === 'README.md')).toBe(true);
  });

  it('renders TokenTracker aggregates natively without a blank iframe or secondary JCEF view', async () => {
    const { container } = render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByTitle('用量'));
    await act(async () => window.__omnicodeReceive?.({
      type: 'usage.status',
      payload: {
        state: 'READY', detail: '本地面板已就绪。', cliInstalled: false,
        dashboardUrl: 'http://127.0.0.1:7680/dashboard', installCommand: 'npx tokentracker-cli',
        usage: {
          from: '2026-08-09', to: '2026-09-07', totalTokens: '5376997219',
          inputTokens: '147709133', outputTokens: '16829835', cachedTokens: '5211594241',
          reasoningTokens: '7150110', conversations: '39818', costUsd: 2270.828912,
          activeDays: 25, averagePerActiveDay: '215079889',
          daily: [{ day: '2026-09-06', tokens: '1000000', costUsd: 1.25 }, { day: '2026-09-07', tokens: '2000000', costUsd: 2.5 }],
          models: [{ source: 'codex', model: 'gpt-5.6-sol', tokens: '3757595283', costUsd: 2165.59 }],
          projects: [{ name: 'omnicode-agent', tokens: '1343606401' }]
        }
      }
    }));

    expect(screen.getByText('服务在线')).toBeInTheDocument();
    expect(screen.getByText(/面板服务正在运行/)).toBeInTheDocument();
    expect(screen.getByLabelText('TokenTracker 内嵌统计')).toBeInTheDocument();
    expect(screen.getByText('gpt-5.6-sol')).toBeInTheDocument();
    expect(screen.getByText('omnicode-agent')).toBeInTheDocument();
    expect(screen.getByText('$2,270.83')).toBeInTheDocument();
    expect(container.querySelector('iframe')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '刷新统计' }));
    expect(sentCommands().some((value) => value.command === 'usage.status')).toBe(true);
    expect(sentCommands().some((value) => value.command === 'usage.openEmbedded')).toBe(false);
  });

  it('lets the user disable native task completion notifications', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByTitle('常规'));
    fireEvent.click(screen.getByRole('button', { name: '任务完成通知' }));

    expect(sentCommands().some((value) =>
      value.command === 'settings.notifications' && value.payload.enabled === false,
    )).toBe(true);
  });

  it('persists locale font and size through the native appearance bridge', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByTitle('常规'));
    fireEvent.change(screen.getByLabelText('界面语言'), { target: { value: 'ja' } });
    expect(sentCommands().some((value) => value.command === 'settings.appearance' && value.payload.locale === 'ja')).toBe(true);

    await act(async () => window.__omnicodeReceive?.({
      type: 'appearance', payload: { locale: 'ja-JP', localePreference: 'ja', fontFamily: 'jetbrains-mono', fontSize: 16 }
    }));
    expect(document.documentElement.style.getPropertyValue('--ui-font-family')).toContain('JetBrains Mono');
    expect(document.documentElement.style.getPropertyValue('--ui-font-size')).toBe('16px');
    expect(screen.getByTitle('設定')).toBeInTheDocument();
  });

  it('defaults to streaming and lets the user disable it without exposing private reasoning', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByTitle('常规'));
    const toggle = screen.getByRole('button', { name: '流式输出' });
    expect(toggle).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText(/模型内部思维链始终不展示/)).toBeInTheDocument();
    fireEvent.click(toggle);
    expect(sentCommands().some((value) => value.command === 'settings.appearance' && value.payload.streamingOutput === false)).toBe(true);
    await act(async () => window.__omnicodeReceive?.({ type: 'appearance', payload: { streamingOutput: false } }));
    expect(toggle).toHaveAttribute('aria-pressed', 'false');
  });

  it('holds only the active assistant reply when streaming is disabled, then reveals it on completion', async () => {
    render(<App />);
    await bootstrap({
      running: true,
      settings: { ...settings, appearance: { ...settings.appearance, streamingOutput: false } },
      blocks: [
        { id: 'older', turnId: 'old-turn', role: 'assistant', kind: 'message.assistant', text: 'Earlier answer' },
        { id: 'older-end', turnId: 'old-turn', role: 'system', kind: 'run.completed', phase: 'completed', status: 'success', text: '' },
        { id: 'current-user', turnId: 'current-turn', role: 'user', kind: 'message.user', text: 'Current request' },
        { id: 'private-reasoning', turnId: 'current-turn', role: 'assistant', kind: 'message.assistant.reasoning', text: 'Private reasoning' },
        { id: 'current-answer', turnId: 'current-turn', role: 'assistant', kind: 'message.assistant.delta', text: 'Buffered answer' }
      ]
    });
    expect(screen.getByText('Earlier answer')).toBeInTheDocument();
    expect(screen.getByText('Current request')).toBeInTheDocument();
    expect(screen.queryByText('Buffered answer')).not.toBeInTheDocument();
    expect(screen.queryByText('Private reasoning')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: '停止任务' })).toBeInTheDocument();

    await act(async () => window.__omnicodeReceive?.({ type: 'running', payload: { sessionId: 's1', running: false } }));
    expect(screen.getByText('Buffered answer')).toBeInTheDocument();
    expect(screen.queryByText('Private reasoning')).not.toBeInTheDocument();
  });

  it('loads older history into the current session without replacing live blocks', async () => {
    render(<App />);
    await bootstrap({
      blocks: [{ id: 'recent', role: 'assistant', kind: 'message.assistant', text: '最近消息' }],
      historyBefore: 4,
      historyHasMore: true
    });
    fireEvent.click(screen.getByRole('button', { name: '加载更早记录' }));
    expect(sentCommands().some((value) => value.command === 'session.loadOlder' && value.payload.sessionId === 's1' && value.payload.before === 4)).toBe(true);

    await act(async () => window.__omnicodeReceive?.({
      type: 'session.page', payload: {
        sessionId: 's1', historyBefore: 0, historyHasMore: false,
        blocks: [{ id: 'older', role: 'user', kind: 'message.user', text: '更早消息' }]
      }
    }));
    expect(screen.getByText('更早消息')).toBeInTheDocument();
    expect(screen.getByText('最近消息')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '加载更早记录' })).toBeNull();
  });

  it('applies a credential-free session template as a new conversation', async () => {
    render(<App />);
    await bootstrap({ settings: {
      ...settings,
      sessionTemplates: [{
        id: 'template-review', name: '只读审阅', providerId: 'cli-opencode',
        model: 'opencode/model-a', reasoningEffort: 'high', mode: 'CLAUDE_PLAN', strategy: 'SINGLE'
      }]
    } });
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByTitle('Agents'));
    fireEvent.click(screen.getByRole('button', { name: '开始' }));

    expect(sentCommands().some((value) => value.command === 'sessionTemplate.apply' &&
      value.payload.id === 'template-review' && value.payload.newSession === true)).toBe(true);
    await act(async () => window.__omnicodeReceive?.({
      type: 'session.templateApplied', payload: { name: '只读审阅', mode: 'CLAUDE_PLAN', strategy: 'SINGLE' }
    }));
    expect(screen.getByTitle('运行模式')).toHaveTextContent('Claude Plan');
  });

  it('configures a remote database without exposing its password to the webview', async () => {
    render(<App />);
    await bootstrap({ settings: {
      ...settings,
      databaseConnections: [{
        id: 'database-1', name: 'Research PostgreSQL', enabled: true,
        jdbcUrl: 'jdbc:postgresql://db.example.com:5432/research?sslmode=require',
        driver: 'postgresql', username: 'researcher', connectTimeoutSeconds: 10,
        queryTimeoutSeconds: 30, maxRows: 200, passwordConfigured: false
      }]
    } });
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByTitle('数据库'));

    expect(screen.getByDisplayValue('jdbc:postgresql://db.example.com:5432/research?sslmode=require')).toBeInTheDocument();
    expect(screen.getByLabelText('数据库密码（Password Safe）')).toHaveValue('');
    expect(screen.getByText(/点击“设置密码”/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '设置密码' }));
    fireEvent.click(screen.getByRole('button', { name: '测试连接' }));
    fireEvent.click(screen.getByRole('button', { name: '保存配置' }));

    const commands = sentCommands();
    expect(commands.some((value) => value.command === 'database.savePassword' &&
      value.payload.id === 'database-1' &&
      value.payload.jdbcUrl === 'jdbc:postgresql://db.example.com:5432/research?sslmode=require',
    )).toBe(true);
    expect(commands.some((value) => value.command === 'database.test' && value.payload.id === 'database-1')).toBe(true);
    const save = commands.find((value) => value.command === 'database.save');
    expect(save?.payload.jdbcUrl).toBe('jdbc:postgresql://db.example.com:5432/research?sslmode=require');
    expect(save?.payload).not.toHaveProperty('password');
  });

  it('restores recent database probe evidence without claiming a live session connection', async () => {
    render(<App />);
    await bootstrap({ settings: {
      ...settings,
      databaseConnections: [{
        id: 'database-1', name: 'Research PostgreSQL', enabled: true,
        jdbcUrl: 'jdbc:postgresql://db.example.com:5432/research',
        driver: 'postgresql', username: 'reader', connectTimeoutSeconds: 10,
        queryTimeoutSeconds: 30, maxRows: 200, passwordConfigured: true, recentlyTested: true
      }]
    } });
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByTitle('数据库'));
    expect(screen.getByText('保存的配置最近测试通过')).toBeInTheDocument();
    expect(screen.getByText(/每次查询仍需审批和实时连接/)).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(/数据库 URL/), { target: { value: 'postgresql://another.example.com' } });
    expect(screen.queryByText('保存的配置最近测试通过')).not.toBeInTheDocument();
  });

  it('can save a new server-only database URL and password in one action', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByTitle('数据库'));

    fireEvent.change(screen.getByLabelText(/数据库 URL/), { target: { value: 'postgresql://db.example.com:5432' } });
    fireEvent.change(screen.getByLabelText(/用户名/), { target: { value: 'researcher' } });
    fireEvent.click(screen.getByRole('button', { name: '设置密码' }));

    const command = sentCommands().find((value) => value.command === 'database.savePassword');
    expect(command?.payload.id).toBe('');
    expect(command?.payload.jdbcUrl).toBe('postgresql://db.example.com:5432');
    expect(command?.payload.username).toBe('researcher');
    expect(command?.payload).not.toHaveProperty('password');
  });

  it('sends a page-scoped ready command', () => {
    render(<App />);
    const ready = sentCommands().find((value) => value.command === 'frontend.ready');
    expect(ready.pageGeneration).toBe(7);
    expect(ready.schemaVersion).toBe(1);
    expect(ready.clientInstanceId).toMatch(/^[A-Za-z0-9._:-]+$/);
    expect(ready.requestId).toMatch(/^[A-Za-z0-9._:-]+$/);
  });

  it('shows a bridge command rejection instead of leaving a dead button', async () => {
    render(<App />);
    await act(async () => window.__omnicodeReceive?.({
      type: 'command.error', requestId: 'request-1',
      payload: { command: 'mcp.installDraft', message: '页面已更新，此操作未执行。请在当前页面重试。' }
    }));
    await waitFor(() => expect(screen.getByText('页面已更新，此操作未执行。请在当前页面重试。')).toBeInTheDocument());
  });

  it('keeps CCGUI-style favorite sessions at the top and persists the toggle natively', async () => {
    render(<App />);
    await bootstrap({ history: [
      { id: 'old', title: '普通会话', updatedAt: '2026-01-01T00:00:00Z', status: 'COMPLETED', messageCount: 2, favorite: false },
      { id: 'favorite', title: '重要会话', updatedAt: '2025-01-01T00:00:00Z', status: 'COMPLETED', messageCount: 4, favorite: true }
    ] });
    fireEvent.click(screen.getByTitle('历史记录'));
    const buttons = screen.getAllByRole('button', { name: /收藏会话|取消收藏会话/ });
    expect(buttons[0]).toHaveAttribute('aria-label', '取消收藏会话');
    fireEvent.click(buttons[0]);
    expect(sentCommands().some((value) => value.command === 'session.favorite' && value.payload.id === 'favorite' && value.payload.favorite === false)).toBe(true);
    fireEvent.click(screen.getAllByRole('button', { name: '导出会话' })[0]);
    expect(sentCommands().some((value) => value.command === 'session.export' && value.payload.id === 'favorite')).toBe(true);
    fireEvent.click(screen.getAllByRole('button', { name: '分叉会话' })[0]);
    expect(sentCommands().some((value) => value.command === 'session.fork' && value.payload.id === 'favorite')).toBe(true);
  });

  it('offers message-level rewind only for persisted history blocks', async () => {
    render(<App />);
    await bootstrap({ blocks: [
      { id: 's1-history-0', role: 'user', kind: 'message.user', text: '第一轮' },
      { id: 'live-message', role: 'assistant', kind: 'message.assistant', text: '实时回复' }
    ] });
    expect(screen.getAllByTitle('从此处分叉')).toHaveLength(1);
    fireEvent.click(screen.getByTitle('从此处分叉'));
    expect(sentCommands().some((value) => value.command === 'session.rewind' && value.payload.messageIndex === 0)).toBe(true);
  });

  it('keeps the chat composer mounted while visiting settings', async () => {
    render(<App />);
    await bootstrap();
    const composer = screen.getByPlaceholderText(/输入任务/) as HTMLTextAreaElement;
    fireEvent.change(composer, { target: { value: '保留这段草稿' } });
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByTitle('返回聊天'));
    expect(screen.getByPlaceholderText(/输入任务/)).toHaveValue('保留这段草稿');
  });

  it('keeps independent composer drafts for each conversation', async () => {
    render(<App />);
    await bootstrap({ sessionTitle: '会话一' });
    fireEvent.change(screen.getByPlaceholderText(/输入任务/), { target: { value: '会话一草稿' } });

    await act(async () => window.__omnicodeReceive?.({ type: 'session.reset', payload: { sessionId: 's2', sessionTitle: '会话二' } }));
    expect(screen.getByPlaceholderText(/输入任务/)).toHaveValue('');
    fireEvent.change(screen.getByPlaceholderText(/输入任务/), { target: { value: '会话二草稿' } });

    await act(async () => window.__omnicodeReceive?.({ type: 'session.loaded', payload: { sessionId: 's1', sessionTitle: '会话一', running: false, blocks: [] } }));
    expect(screen.getByPlaceholderText(/输入任务/)).toHaveValue('会话一草稿');
    await act(async () => window.__omnicodeReceive?.({ type: 'session.loaded', payload: { sessionId: 's2', sessionTitle: '会话二', running: false, blocks: [] } }));
    expect(screen.getByPlaceholderText(/输入任务/)).toHaveValue('会话二草稿');
  });

  it('shows truthful project context usage without sending a model request', async () => {
    render(<App />);
    await bootstrap();
    const composer = screen.getByPlaceholderText(/输入任务/);
    fireEvent.change(composer, { target: { value: '/context' } });
    fireEvent.keyDown(composer, { key: 'Enter', code: 'Enter' });
    expect(sentCommands().some((value) => value.command === 'context.snapshot')).toBe(true);
    expect(sentCommands().some((value) => value.command === 'session.send')).toBe(false);

    await act(async () => window.__omnicodeReceive?.({
      type: 'context.snapshot',
      payload: {
        rulePaths: ['AGENTS.md'], pinnedPaths: ['src/App.kt'], excludedPathCount: 2,
        includedCharacters: 1000, estimatedTokens: 250, ruleTokens: 80, pinnedTokens: 120,
        harnessTokens: 45, truncated: false, harnessReadiness: 'READY', harnessFeedbackLoopCount: 3,
        harnessIssueCount: 0
      }
    }));
    expect(screen.getByRole('dialog', { name: '上下文占用' })).toBeInTheDocument();
    expect(screen.getByText('约 250 tokens')).toBeInTheDocument();
    expect(screen.getByText('≈ 80')).toBeInTheDocument();
    expect(screen.getByText('≈ 120')).toBeInTheDocument();
    expect(screen.getByText('≈ 45')).toBeInTheDocument();
    expect(screen.getByText('≈ 5')).toBeInTheDocument();
  });

  it('renames an open conversation from its session tab', async () => {
    render(<App />);
    await bootstrap({ sessionTitle: '旧标题' });
    fireEvent.doubleClick(screen.getByTitle('切换到：旧标题 · 双击重命名'));
    const titleInput = screen.getByLabelText('会话名称');
    fireEvent.change(titleInput, { target: { value: '新的会话标题' } });
    fireEvent.submit(titleInput.closest('form')!);
    expect(screen.getByTitle('切换到：新的会话标题 · 双击重命名')).toBeInTheDocument();
    expect(sentCommands().some((value) => value.command === 'session.rename' && value.payload.id === 's1' && value.payload.title === '新的会话标题')).toBe(true);
  });

  it('projects the configured pet only into chat without adding another root view', async () => {
    render(<App />);
    await bootstrap({ settings: { ...settings, theme: { ...settings.theme, petEnabled: true } } });
    expect(screen.getByRole('button', { name: '移动桌宠 Pixel Cat' })).toBeInTheDocument();
    expect(document.querySelector('.pet-artwork.visual-creature svg')).toBeInTheDocument();
    fireEvent.click(screen.getByTitle('设置'));
    expect(screen.queryByRole('button', { name: '移动桌宠 Pixel Cat' })).not.toBeInTheDocument();
  });

  it('selects an original pet from the settings gallery', async () => {
    render(<App />);
    await bootstrap({
      settings: {
        ...settings,
        pets: [
          ...settings.pets,
          {
            id: 'tiny-robot', name: 'Tiny Robot', description: 'Build companion', glyph: '🤖', accent: '#64D2C8',
            visual: 'robot', motion: 'doze', idleIntervalSeconds: 40, idleMessages: ['Systems nominal.']
          }
        ]
      }
    });
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByRole('button', { name: '桌宠' }));
    expect(screen.getByRole('list', { name: '桌宠角色库' })).toBeInTheDocument();
    fireEvent.click(screen.getByText('Tiny Robot'));
    expect(sentCommands().some((value) => value.command === 'settings.pet' && value.payload.petId === 'tiny-robot')).toBe(true);
  });

  it('accepts repeated editor context prefills without submitting them', async () => {
    render(<App />);
    await bootstrap();
    const composer = screen.getByPlaceholderText(/输入任务/) as HTMLTextAreaElement;
    await act(async () => window.__omnicodeReceive?.({ type: 'composer.prefill', payload: { text: '请处理当前文件：@src/App.kt' } }));
    expect(composer).toHaveValue('请处理当前文件：@src/App.kt');
    fireEvent.change(composer, { target: { value: '' } });
    await act(async () => window.__omnicodeReceive?.({ type: 'composer.prefill', payload: { text: '请处理当前文件：@src/App.kt' } }));
    expect(composer).toHaveValue('请处理当前文件：@src/App.kt');
    expect(sentCommands().some((value) => value.command === 'session.send')).toBe(false);
  });

  it('offers native cancellation while a run is active', async () => {
    render(<App />);
    await bootstrap({ running: true });
    fireEvent.click(screen.getByRole('button', { name: '停止任务' }));
    expect(sentCommands().some((value) => value.command === 'session.cancel')).toBe(true);
  });

  it('queues a second prompt while a session is running and flushes it after completion', async () => {
    render(<App />);
    await bootstrap({ running: true, blocks: [{ id: 'first', role: 'user', kind: 'message.user', text: '第一条' }] });
    const composer = screen.getByPlaceholderText(/输入任务/) as HTMLTextAreaElement;
    fireEvent.change(composer, { target: { value: '第二条排队消息' } });
    fireEvent.keyDown(composer, { key: 'Enter', code: 'Enter' });

    expect(screen.getByRole('button', { name: '停止任务' })).toBeInTheDocument();
    expect(screen.getByText('第二条排队消息')).toBeInTheDocument();
    expect(screen.getByRole('status', { name: '队列 1' })).toBeInTheDocument();
    expect(sentCommands().filter((value) => value.command === 'session.send')).toHaveLength(0);

    await act(async () => window.__omnicodeReceive?.({ type: 'running', payload: { sessionId: 's1', running: false } }));
    expect(sentCommands().filter((value) => value.command === 'session.send')).toHaveLength(0);

    await act(async () => window.__omnicodeReceive?.({
      type: 'event',
      payload: {
        schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'first-turn',
        blockId: 'first-result', sequence: 2, kind: 'run.completed', phase: 'completed', at: '',
        payload: { title: '第一条完成' }
      }
    }));

    expect(sentCommands().filter((value) => value.command === 'session.send')).toHaveLength(1);
    expect(sentCommands().find((value) => value.command === 'session.send')?.payload.text).toBe('第二条排队消息');
    expect(screen.getByText('运行中')).toBeInTheDocument();
  });

  it('pauses queued messages when a run fails and retains the queue across session switches', async () => {
    render(<App />);
    await bootstrap({ running: true });
    const composer = screen.getByPlaceholderText(/输入任务/) as HTMLTextAreaElement;
    fireEvent.change(composer, { target: { value: '失败后需要检查的消息' } });
    fireEvent.keyDown(composer, { key: 'Enter', code: 'Enter' });
    expect(screen.getByTitle('1 条待发送消息')).toBeInTheDocument();
    fireEvent.click(screen.getByTitle('新建对话'));
    await act(async () => window.__omnicodeReceive?.({ type: 'session.reset', payload: { sessionId: 's2' } }));
    await act(async () => window.__omnicodeReceive?.({ type: 'event', payload: {
      schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'first-turn',
      blockId: 'failed-result', sequence: 1, kind: 'run.failed', phase: 'failed', at: '',
      payload: { title: '任务失败', message: '需要重新登录' }
    } }));
    expect(sentCommands().filter((command) => command.command === 'session.send')).toHaveLength(0);
    expect(screen.getByTitle('1 条待发送消息')).toBeInTheDocument();
    fireEvent.click(screen.getAllByTitle(/^切换到：新对话/)[0]);
    // The newly-created tab can have the same default title; switch by the first tab.
    await act(async () => window.__omnicodeReceive?.({ type: 'session.loaded', payload: { sessionId: 's1', running: false, blocks: [] } }));
    expect(screen.getByRole('button', { name: '继续队列' })).toBeInTheDocument();
    expect(sentCommands().filter((command) => command.command === 'session.send')).toHaveLength(0);
  });

  it('rehydrates a host-held queue after WebView reload without silently sending it', async () => {
    const first = render(<App />);
    await bootstrap({ running: true });
    const composer = screen.getByPlaceholderText(/输入任务/) as HTMLTextAreaElement;
    fireEvent.change(composer, { target: { value: '重载后继续检查' } });
    fireEvent.keyDown(composer, { key: 'Enter', code: 'Enter' });
    const queued = sentCommands().find((command) => command.command === 'queue.upsert');
    expect(queued?.payload.text).toBe('重载后继续检查');
    expect(queued?.payload.requiresReattach).toBe(false);
    first.unmount();

    window.omnicodeSend = vi.fn();
    render(<App />);
    await bootstrap({ running: false, pendingQueue: [{
      clientMessageId: queued.payload.clientMessageId, text: '重载后继续检查',
      mode: 'AGENT', strategy: 'AUTO', requiresReattach: false
    }] });
    expect(screen.getByTitle('1 条待发送消息')).toBeInTheDocument();
    expect(sentCommands().filter((command) => command.command === 'session.send')).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: '继续队列' }));
    expect(sentCommands().filter((command) => command.command === 'session.send')).toHaveLength(1);
    expect(sentCommands().find((command) => command.command === 'session.send')?.payload.clientMessageId).toBe(queued.payload.clientMessageId);
  });

  it('returns a queued prompt to its draft when the host rejects queue storage', async () => {
    render(<App />);
    await bootstrap({ running: true });
    const composer = screen.getByPlaceholderText(/输入任务/) as HTMLTextAreaElement;
    fireEvent.change(composer, { target: { value: '不能丢的内容' } });
    fireEvent.keyDown(composer, { key: 'Enter', code: 'Enter' });
    const queued = sentCommands().find((command) => command.command === 'queue.upsert');
    expect(queued).toBeDefined();
    await act(async () => window.__omnicodeReceive?.({
      type: 'command.error', requestId: queued.requestId,
      payload: { command: 'queue.upsert', message: '队列暂不可用' }
    }));
    expect(composer).toHaveValue('不能丢的内容');
    expect(screen.queryByTitle('1 条待发送消息')).not.toBeInTheDocument();
    expect(sentCommands().filter((command) => command.command === 'session.send')).toHaveLength(0);
  });

  it('never replays an attachment from host memory and returns its text to the composer', async () => {
    render(<App />);
    await bootstrap({ pendingQueue: [{ clientMessageId: 'client-attachment', text: '分析这张图', mode: 'AGENT', strategy: 'AUTO', requiresReattach: true }] });
    fireEvent.click(screen.getByRole('button', { name: /待发送 1 条/ }));
    expect(screen.getByRole('button', { name: '继续队列' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '移到输入框并重新添加附件' }));
    expect(screen.getByPlaceholderText(/输入任务/)).toHaveValue('分析这张图');
    expect(sentCommands().some((command) => command.command === 'queue.remove' && command.payload.clientMessageId === 'client-attachment')).toBe(true);
    expect(sentCommands().filter((command) => command.command === 'session.send')).toHaveLength(0);
  });

  it('waits for the IDE to confirm the target session before flushing a background queue', async () => {
    render(<App />);
    await bootstrap({ running: true });
    const composer = screen.getByPlaceholderText(/输入任务/) as HTMLTextAreaElement;
    fireEvent.change(composer, { target: { value: '在会话一继续' } });
    fireEvent.keyDown(composer, { key: 'Enter', code: 'Enter' });
    fireEvent.click(screen.getByTitle('新建对话'));
    await act(async () => window.__omnicodeReceive?.({ type: 'session.reset', payload: { sessionId: 's2', sessionTitle: '会话二' } }));
    await act(async () => window.__omnicodeReceive?.({ type: 'event', payload: {
      schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'first-turn',
      blockId: 'first-result', sequence: 1, kind: 'run.completed', phase: 'completed', at: '',
      payload: { title: '会话一完成' }
    } }));
    fireEvent.click(screen.getByTitle(/^切换到：新对话/));
    expect(sentCommands().filter((command) => command.command === 'session.send')).toHaveLength(0);
    await act(async () => window.__omnicodeReceive?.({ type: 'session.loaded', payload: { sessionId: 's1', running: false, blocks: [] } }));
    const sends = sentCommands().filter((command) => command.command === 'session.send');
    expect(sends).toHaveLength(1);
    expect(sends[0].payload.text).toBe('在会话一继续');
  });

  it('lets the user edit, reorder and pause queued messages before the next run', async () => {
    render(<App />);
    await bootstrap({ running: true });
    const composer = screen.getByPlaceholderText(/输入任务/) as HTMLTextAreaElement;
    for (const message of ['先检查', '再修复']) {
      fireEvent.change(composer, { target: { value: message } });
      fireEvent.keyDown(composer, { key: 'Enter', code: 'Enter' });
    }
    fireEvent.click(screen.getByRole('button', { name: /待发送 2 条/ }));
    fireEvent.click(screen.getAllByTitle('上移待发送消息')[1]);
    fireEvent.click(screen.getAllByTitle('编辑待发送消息')[0]);
    fireEvent.change(screen.getByRole('textbox', { name: '编辑待发送消息' }), { target: { value: '修复并验证' } });
    fireEvent.click(screen.getByRole('button', { name: '保存' }));
    fireEvent.click(screen.getByRole('button', { name: '暂停队列' }));
    await act(async () => window.__omnicodeReceive?.({ type: 'event', payload: {
      schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'first-turn',
      blockId: 'first-result', sequence: 1, kind: 'run.completed', phase: 'completed', at: '',
      payload: { title: '首轮完成' }
    } }));
    expect(sentCommands().filter((command) => command.command === 'session.send')).toHaveLength(0);
    fireEvent.change(composer, { target: { value: '暂停时追加' } });
    fireEvent.keyDown(composer, { key: 'Enter', code: 'Enter' });
    expect(screen.getByRole('status', { name: '队列 3' })).toBeInTheDocument();
    expect(sentCommands().filter((command) => command.command === 'session.send')).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: '继续队列' }));
    const sends = sentCommands().filter((command) => command.command === 'session.send');
    expect(sends).toHaveLength(1);
    expect(sends[0].payload.text).toBe('修复并验证');
    // The second queued item remains available to remove before it reaches the host.
    fireEvent.click(screen.getAllByTitle('删除待发送消息')[0]);
    expect(screen.getByRole('status', { name: '队列 1' })).toBeInTheDocument();
  });

  it('keeps the draft when the per-session queue is full', async () => {
    render(<App />);
    await bootstrap({ running: true });
    const composer = screen.getByPlaceholderText(/输入任务/) as HTMLTextAreaElement;
    for (let index = 0; index < 20; index += 1) {
      fireEvent.change(composer, { target: { value: `待发送 ${index}` } });
      fireEvent.keyDown(composer, { key: 'Enter', code: 'Enter' });
    }
    expect(screen.getByRole('status', { name: '队列 20' })).toBeInTheDocument();
    fireEvent.change(composer, { target: { value: '不要丢失的草稿' } });
    fireEvent.keyDown(composer, { key: 'Enter', code: 'Enter' });
    expect(composer).toHaveValue('不要丢失的草稿');
    expect(screen.getByRole('status', { name: '队列 20' })).toBeInTheDocument();
    expect(sentCommands().filter((command) => command.command === 'session.send')).toHaveLength(0);
  });

  it('keeps queued prompts in their session and pauses them after stop', async () => {
    render(<App />);
    await bootstrap({ running: true, blocks: [{ id: 'first', role: 'user', kind: 'message.user', text: '第一条' }] });
    const composer = screen.getByPlaceholderText(/输入任务/) as HTMLTextAreaElement;
    fireEvent.change(composer, { target: { value: '只属于会话一' } });
    fireEvent.keyDown(composer, { key: 'Enter', code: 'Enter' });
    expect(screen.getByText('只属于会话一')).toBeInTheDocument();

    fireEvent.click(screen.getByTitle('新建对话'));
    await act(async () => window.__omnicodeReceive?.({ type: 'session.reset', payload: { sessionId: 's2' } }));
    expect(screen.queryByText('只属于会话一')).not.toBeInTheDocument();
    expect(screen.queryByText('队列 1')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTitle('历史记录'));
    await act(async () => window.__omnicodeReceive?.({
      type: 'history', payload: [{ id: 's1', title: '会话一', updatedAt: new Date().toISOString(), status: 'RUNNING', messageCount: 2 }]
    }));
    fireEvent.click(screen.getByText('会话一'));
    await act(async () => window.__omnicodeReceive?.({ type: 'session.loaded', payload: { sessionId: 's1', running: true, blocks: [] } }));
    expect(screen.getByText('只属于会话一')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '停止任务' }));
    expect(screen.getByText('只属于会话一')).toBeInTheDocument();
    expect(screen.getByRole('status', { name: '队列 1' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '继续队列' })).toBeInTheDocument();
    expect(sentCommands().filter((command) => command.command === 'session.send')).toHaveLength(0);
    await act(async () => window.__omnicodeReceive?.({ type: 'event', payload: {
      schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'first-turn',
      blockId: 'cancel-result', sequence: 1, kind: 'run.completed', phase: 'warning', at: '',
      payload: { title: '任务已停止' }
    } }));
    expect(sentCommands().filter((command) => command.command === 'session.send')).toHaveLength(0);
    fireEvent.click(screen.getByRole('button', { name: '继续队列' }));
    expect(sentCommands().filter((command) => command.command === 'session.send')).toHaveLength(0);
    await act(async () => window.__omnicodeReceive?.({ type: 'running', payload: { sessionId: 's1', running: false, cleanupPending: false } }));
    expect(sentCommands().filter((command) => command.command === 'session.send')).toHaveLength(1);
    expect(sentCommands().find((command) => command.command === 'session.send')?.payload.text).toBe('只属于会话一');
  });

  it('summarizes only observed turn results and matching change reviews', async () => {
    render(<App />);
    await bootstrap();
    const emitTurn = async (blockId: string, sequence: number, kind: string, phase: string, payload: Record<string, unknown>) => {
      await act(async () => window.__omnicodeReceive?.({ type: 'event', payload: {
        schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'turn-result',
        blockId, sequence, kind, phase, at: '', payload
      } }));
    };
    await emitTurn('answer', 1, 'message.assistant.delta', 'running', { text: '已修复问题。' });
    await emitTurn('tool-one', 2, 'tool.completed', 'completed', { title: 'Run command', name: 'run_command', text: '命令完成', verification: { label: 'Gradle test/check', exitCode: 0, timedOut: false } });
    await emitTurn('terminal', 3, 'run.completed', 'completed', { title: '任务完成', workflowId: 'workflow-result' });
    await act(async () => window.__omnicodeReceive?.({ type: 'review', payload: {
      sessionId: 's1', workflowId: 'workflow-result', files: [{ path: 'src/Fix.kt', decision: 'PENDING', added: 1, removed: 0, hunks: [] }]
    } }));
    const outcome = screen.getByRole('region', { name: '本轮结果' });
    expect(outcome).toHaveTextContent('已收到可见回复');
    expect(outcome).toHaveTextContent('已记录 1 个文件变更');
    expect(outcome).toHaveTextContent('工具 1 次 · 无失败记录');
    expect(outcome).toHaveTextContent('验证命令 1 项 · 退出码 0 且无超时 1 项');
    expect(outcome).toHaveTextContent('Gradle test/check');
    expect(outcome).toHaveTextContent('不将测试标为通过');
    fireEvent.click(within(outcome).getByRole('button', { name: /src\/Fix.kt/ }));
    expect(sentCommands().some((command) => command.command === 'review.openDiff' && command.payload.workflowId === 'workflow-result' && command.payload.path === 'src/Fix.kt')).toBe(true);
    fireEvent.click(within(outcome).getByRole('button', { name: /审阅变更/ }));
    expect(screen.getByText('已编辑 1 个文件')).toBeInTheDocument();
    await act(async () => window.__omnicodeReceive?.({ type: 'review', payload: {
      sessionId: 's1', workflowId: 'other-workflow', files: [{ path: 'src/Other.kt', decision: 'PENDING', added: 1, removed: 0, hunks: [] }]
    } }));
    expect(outcome).not.toHaveTextContent('已记录 1 个文件变更');
  });

  it('keeps failed turns visibly unfinished without inventing test success', async () => {
    render(<App />);
    await bootstrap();
    await act(async () => window.__omnicodeReceive?.({ type: 'event', payload: {
      schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'failed-turn',
      blockId: 'failed-tool', sequence: 1, kind: 'tool.completed', phase: 'failed', at: '',
      payload: { title: 'Run command', text: '测试命令退出码 1' }
    } }));
    await act(async () => window.__omnicodeReceive?.({ type: 'event', payload: {
      schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'failed-turn',
      blockId: 'failed-terminal', sequence: 2, kind: 'run.failed', phase: 'failed', at: '',
      payload: { title: '任务未完成', message: '验证失败' }
    } }));
    const outcome = screen.getByRole('region', { name: '本轮结果' });
    expect(outcome).toHaveTextContent('任务未完成');
    expect(outcome).toHaveTextContent('未收到可见回复');
    expect(outcome).toHaveTextContent('工具 1 次 · 1 次失败');
    expect(outcome).toHaveTextContent('验证命令未执行');
    expect(outcome).not.toHaveTextContent('测试通过');
    fireEvent.click(within(outcome).getByRole('button', { name: '草拟验证任务' }));
    expect(sentCommands().some((command) => command.command === 'composer.prefill' && String(command.payload.text).includes('实际执行的命令'))).toBe(true);
    expect(sentCommands().some((command) => command.command === 'session.send')).toBe(false);
  });

  it('counts actual database and MCP tool completions instead of configuration alone', async () => {
    render(<App />);
    await bootstrap();
    for (const [blockId, name, phase] of [
      ['db-one', 'query_database', 'completed'],
      ['mcp-one', 'mcp__github__search', 'failed'],
      ['terminal', 'run', 'completed']
    ]) {
      await act(async () => window.__omnicodeReceive?.({ type: 'event', payload: {
        schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'tool-evidence',
        blockId, sequence: blockId === 'db-one' ? 1 : blockId === 'mcp-one' ? 2 : 3,
        kind: blockId === 'terminal' ? 'run.completed' : 'tool.completed', phase, at: '',
        payload: { name, title: name, text: '结果已记录' }
      } }));
    }
    const outcome = screen.getByRole('region', { name: '本轮结果' });
    expect(outcome).toHaveTextContent('数据库实际调用 1 次 · 成功 1 次');
    expect(outcome).toHaveTextContent('MCP 实际调用 1 次 · 成功 0 次');
    expect(outcome).toHaveTextContent('工具 2 次 · 1 次失败');
    expect(outcome).toHaveTextContent('验证命令未执行');
  });

  it('does not call an unclassified command a successful verification', async () => {
    render(<App />);
    await bootstrap();
    for (const [blockId, sequence, kind] of [
      ['command', 1, 'tool.completed'], ['terminal', 2, 'run.completed']
    ] as const) {
      await act(async () => window.__omnicodeReceive?.({ type: 'event', payload: {
        schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'unknown-check',
        blockId, sequence, kind, phase: 'completed', at: '',
        payload: { name: blockId === 'command' ? 'run_command' : '', title: blockId, text: '完成' }
      } }));
    }
    const outcome = screen.getByRole('region', { name: '本轮结果' });
    expect(outcome).toHaveTextContent('执行过命令，但未记录可判定的验证结果');
    expect(outcome).not.toHaveTextContent('验证命令未执行');
  });

  it('shows a submitted message and running state before the host acknowledges it', async () => {
    render(<App />);
    await bootstrap();
    const composer = screen.getByPlaceholderText(/输入任务/);
    fireEvent.change(composer, { target: { value: '立即显示这条消息' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));

    expect(screen.getByText('立即显示这条消息')).toBeInTheDocument();
    expect(screen.getByText('运行中')).toBeInTheDocument();
    const command = sentCommands().find((value) => value.command === 'session.send');
    expect(command.payload.sessionId).toBe('s1');
    expect(command.payload.clientMessageId).toMatch(/^client-[A-Za-z0-9._:-]+$/);
  });

  it('submits the composer with Enter and keeps Shift+Enter for multiline input', async () => {
    const first = render(<App />);
    await bootstrap();
    const composer = screen.getByPlaceholderText(/输入任务/) as HTMLTextAreaElement;
    fireEvent.change(composer, { target: { value: 'Enter 发送' } });
    fireEvent.keyDown(composer, { key: 'Enter', code: 'Enter' });
    expect(sentCommands().some((value) => value.command === 'session.send')).toBe(true);

    first.unmount();
    window.omnicodeSend = vi.fn();
    render(<App />);
    await bootstrap();
    const multilineComposer = screen.getByPlaceholderText(/输入任务/) as HTMLTextAreaElement;
    fireEvent.change(multilineComposer, { target: { value: '第一行' } });
    fireEvent.keyDown(multilineComposer, { key: 'Enter', code: 'Enter', shiftKey: true });
    expect(sentCommands().some((value) => value.command === 'session.send')).toBe(false);
  });

  it('does not submit Enter while JCEF is committing an IME candidate', async () => {
    render(<App />);
    await bootstrap();
    const composer = screen.getByPlaceholderText(/输入任务/);
    fireEvent.change(composer, { target: { value: '中文输入' } });
    fireEvent.compositionStart(composer);
    fireEvent.keyDown(composer, { key: 'Enter', code: 'Enter', keyCode: 229 });

    expect(sentCommands().some((value) => value.command === 'session.send')).toBe(false);
    expect(composer).toHaveValue('中文输入');
  });

  it('hides disabled providers from the compact chat picker without deleting settings', async () => {
    render(<App />);
    await bootstrap({ settings: {
      ...settings,
      providers: settings.providers.map((provider) => provider.id === 'openai' ? { ...provider, hidden: true } : provider)
    } });
    fireEvent.click(screen.getByRole('button', { name: '当前引擎' }));

    expect(screen.queryByRole('option', { name: 'OpenAI' })).not.toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'OpenCode CLI' })).toBeInTheDocument();
  });

  it('opens composer mode choices as an accessible menu above the footer', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByRole('button', { name: '运行模式' }));
    expect(screen.getByRole('listbox', { name: '运行模式' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('option', { name: 'Plan' }));
    expect(screen.getByRole('button', { name: '运行模式' })).toHaveTextContent('Plan');
    expect(screen.queryByRole('listbox', { name: '运行模式' })).toBeNull();
  });

  it('keeps secondary run controls tucked away until requested', async () => {
    render(<App />);
    await bootstrap();
    expect(screen.queryByRole('button', { name: '执行策略' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '推理强度' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '更多运行选项' }));
    expect(screen.getByRole('button', { name: '执行策略' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '推理强度' })).toBeInTheDocument();
  });

  it('runs conversations independently and switches back to a live background session', async () => {
    render(<App />);
    await bootstrap();

    fireEvent.change(screen.getByPlaceholderText(/输入任务/), { target: { value: '会话一任务' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    expect(screen.getByText('会话一任务')).toBeInTheDocument();

    fireEvent.click(screen.getByTitle('新建对话'));
    await act(async () => window.__omnicodeReceive?.({ type: 'session.reset', payload: { sessionId: 's2' } }));
    expect(screen.queryByText('会话一任务')).not.toBeInTheDocument();
    expect(screen.getByText('空闲')).toBeInTheDocument();

    fireEvent.change(screen.getByPlaceholderText(/输入任务/), { target: { value: '会话二任务' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    expect(sentCommands().filter((value) => value.command === 'session.send')).toHaveLength(2);

    await act(async () => {
      window.__omnicodeReceive?.({
        type: 'event',
        payload: {
          schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'turn-s1',
          blockId: 'assistant-s1', sequence: 2, kind: 'message.assistant.delta', phase: 'running', at: '',
          payload: { text: '会话一后台进度' }
        }
      });
      window.__omnicodeReceive?.({
        type: 'event',
        payload: {
          schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'turn-s1',
          blockId: 'result-s1', sequence: 3, kind: 'run.completed', phase: 'completed', at: '',
          payload: { title: '会话一完成' }
        }
      });
    });
    expect(screen.queryByText('会话一后台进度')).not.toBeInTheDocument();
    expect(screen.getByText('运行中')).toBeInTheDocument();

    fireEvent.click(screen.getByTitle('历史记录'));
    await act(async () => window.__omnicodeReceive?.({
      type: 'history',
      payload: [
        { id: 's1', title: '会话一任务', updatedAt: new Date().toISOString(), status: 'COMPLETED', messageCount: 2 },
        { id: 's2', title: '会话二任务', updatedAt: new Date().toISOString(), status: 'RUNNING', messageCount: 1 }
      ]
    }));
    const runningSessionHint = screen.getByText('正在后台运行 · 点击切换到此会话');
    expect(runningSessionHint).toBeInTheDocument();
    fireEvent.click(runningSessionHint.closest('button')!);
    await act(async () => window.__omnicodeReceive?.({ type: 'session.loaded', payload: { sessionId: 's2', running: true, blocks: [] } }));
    expect(screen.getByText('会话二任务')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '停止任务' })).toBeInTheDocument();

    fireEvent.click(screen.getByTitle('历史记录'));
    fireEvent.click(screen.getByText('会话一任务'));
    await act(async () => window.__omnicodeReceive?.({ type: 'session.loaded', payload: { sessionId: 's1', running: false, blocks: [] } }));

    expect(screen.getByText('会话一后台进度')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '本轮结果' })).toHaveTextContent('本轮运行结束');
    expect(screen.getByText('空闲')).toBeInTheDocument();
  });

  it('does not merge late events from a detached conversation', async () => {
    render(<App />);
    await bootstrap();
    await act(async () => window.__omnicodeReceive?.({ type: 'session.reset', payload: { sessionId: 's2' } }));
    await act(async () => window.__omnicodeReceive?.({
      type: 'event',
      payload: { schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'old-turn', blockId: 'old-block', sequence: 1, kind: 'message.assistant', phase: 'completed', at: '', payload: { text: '旧会话迟到内容' } }
    }));
    expect(screen.queryByText('旧会话迟到内容')).not.toBeInTheDocument();
  });

  it('keeps event sequence watermarks isolated between conversations', async () => {
    render(<App />);
    await bootstrap();
    await act(async () => window.__omnicodeReceive?.({
      type: 'event',
      payload: {
        schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'reused-turn',
        blockId: 's1-block', sequence: 1, kind: 'message.assistant', phase: 'completed', at: '',
        payload: { text: '会话一结果' }
      }
    }));
    await act(async () => window.__omnicodeReceive?.({ type: 'session.reset', payload: { sessionId: 's2' } }));
    await act(async () => window.__omnicodeReceive?.({
      type: 'event',
      payload: {
        schemaVersion: 1, pageGeneration: 7, sessionId: 's2', turnId: 'reused-turn',
        blockId: 's2-block', sequence: 1, kind: 'message.assistant', phase: 'completed', at: '',
        payload: { text: '会话二结果' }
      }
    }));
    expect(screen.getByText('会话二结果')).toBeInTheDocument();
  });

  it('turns a rejected background-session send into a visible recoverable error', async () => {
    render(<App />);
    await bootstrap();
    const composer = screen.getByPlaceholderText(/输入任务/);
    fireEvent.change(composer, { target: { value: '新会话消息' } });
    fireEvent.click(screen.getByRole('button', { name: '发送' }));
    const command = sentCommands().find((value) => value.command === 'session.send');
    await act(async () => window.__omnicodeReceive?.({
      type: 'send.rejected', payload: { sessionId: 's1', clientMessageId: command.payload.clientMessageId, message: '另一个会话仍在运行' }
    }));
    expect(screen.getByText('消息尚未发送')).toBeInTheDocument();
    expect(screen.getByText('另一个会话仍在运行')).toBeInTheDocument();
    expect(screen.getByText('空闲')).toBeInTheDocument();
    expect(screen.getByRole('status', { name: '队列 1' })).toBeInTheDocument();
    expect(screen.getByText('待发送')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '继续队列' }));
    expect(sentCommands().filter((value) => value.command === 'session.send')).toHaveLength(2);
    expect(screen.queryByText('消息尚未发送')).not.toBeInTheDocument();
  });

  it('switches a verified CLI model directly from the composer', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('当前模型'));
    fireEvent.click(screen.getByRole('option', { name: 'opencode/model-b' }));
    const command = sentCommands().find((value) => value.command === 'settings.saveProvider');
    expect(command.payload).toMatchObject({ providerId: 'cli-opencode', model: 'opencode/model-b', apiKey: '' });
  });

  it('shows automatic selection separately from real Codex models and preserves discovery errors', async () => {
    render(<App />);
    const codexSettings = {
      ...settings,
      provider: { ...settings.provider, id: 'cli-codex', model: 'auto' },
      providers: [
        ...settings.providers,
        { id: 'cli-codex', name: 'Codex CLI', defaultBaseUrl: 'cli://local', defaultModel: 'auto', cli: true, modelDiscovery: true, baseUrl: 'cli://local', model: 'auto', credentialConfigured: true }
      ]
    };
    await bootstrap({ settings: codexSettings, models: ['gpt-5.6-sol', 'gpt-5.6-terra'] });
    fireEvent.click(screen.getByTitle('当前模型'));
    expect(screen.getByRole('option', { name: /自动（CLI 默认）/ })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'gpt-5.6-sol' })).toBeInTheDocument();

    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByText('供应商'));
    await act(async () => window.__omnicodeReceive?.({
      type: 'models', payload: { providerId: 'cli-codex', models: [], status: 'Unable to load models', error: '请先完成 Codex 登录。' }
    }));
    expect(screen.getByText('模型列表尚不可用')).toBeInTheDocument();
    expect(screen.getAllByText('请先完成 Codex 登录。').length).toBeGreaterThan(0);
  });

  it('marks a removed CLI model unavailable instead of presenting it as verified', async () => {
    render(<App />);
    await bootstrap();
    await act(async () => window.__omnicodeReceive?.({
      type: 'models',
      payload: { providerId: 'cli-opencode', models: ['opencode/model-b'], status: '已从本地 CLI 加载' }
    }));
    const selector = screen.getByLabelText('当前模型');
    expect(selector).toHaveClass('unavailable');
    fireEvent.click(selector);
    expect(screen.getByRole('option', { name: /⚠ opencode\/model-a（已不可用）/ })).toBeDisabled();
  });

  it('switches engine without sending credentials through the webview', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('当前引擎'));
    fireEvent.click(screen.getByRole('option', { name: 'OpenAI' }));
    const command = sentCommands().find((value) => value.command === 'provider.select');
    expect(command.payload).toEqual({ providerId: 'openai' });
    expect(JSON.stringify(command)).not.toContain('apiKey');
  });

  it('activates an installed CLI without overwriting its saved model with default', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByText('依赖'));
    await act(async () => window.__omnicodeReceive?.({
      type: 'runtime.status',
      payload: {
        id: 'kimi', name: 'Kimi CLI', runnable: true, version: '1.2.3', path: '/usr/local/bin/kimi',
        diagnostic: '版本检测通过；登录状态与模型权限将在选择后验证。', modelDiscovery: true,
        nativeResume: false, nativeHistory: false
      }
    }));
    fireEvent.click(screen.getByText('选择使用'));
    const command = sentCommands().find((value) => value.command === 'provider.select' && value.payload.providerId === 'cli-kimi');
    expect(command.payload).toEqual({ providerId: 'cli-kimi' });
    expect(sentCommands().some((value) => value.command === 'settings.saveProvider' && value.payload.model === 'default')).toBe(false);
  });

  it('separates version detection from a read-only model catalog check', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByText('依赖'));
    await act(async () => window.__omnicodeReceive?.({ type: 'runtime.status', payload: {
      id: 'opencode', name: 'OpenCode', runnable: true, version: '1.18.33', path: '/usr/local/bin/opencode',
      diagnostic: '仅版本命令通过；登录、模型和实际会话尚未验证。', modelDiscovery: true,
      nativeResume: true, nativeHistory: true, sessionContinuity: 'NATIVE_SESSION_ID'
    } }));
    expect(screen.getByText('仅版本命令通过；登录、模型和实际会话尚未验证。')).toBeInTheDocument();
    const row = screen.getByText('OpenCode', { selector: '.engine-grid strong' }).closest('.engine-grid > div') as HTMLElement;
    expect(within(row).getByText('程序 已找到')).toBeInTheDocument();
    expect(within(row).getByText('运行依赖 正常')).toBeInTheDocument();
    expect(within(row).getByText('认证 未验证')).toBeInTheDocument();
    expect(within(row).getByText('模型回复 未验证 · 可能计费')).toBeInTheDocument();
    fireEvent.click(within(row).getByRole('button', { name: '检查模型' }));
    expect(sentCommands().some((command) => command.command === 'runtime.verify' && command.payload.engineId === 'opencode')).toBe(true);
    await act(async () => window.__omnicodeReceive?.({ type: 'runtime.verification', payload: {
      engineId: 'opencode', state: 'catalog', modelCount: 3,
      detail: '已读取 3 个模型；这不代表已通过认证或真实对话测试。'
    } }));
    expect(screen.getByText('已读取 3 个模型；这不代表已通过认证或真实对话测试。')).toBeInTheDocument();
    expect(within(row).getByText('模型目录 已读取 3 个')).toBeInTheDocument();
    expect(within(row).getByText('认证 未验证')).toBeInTheDocument();
    expect(sentCommands().some((command) => command.command === 'session.send')).toBe(false);
  });

  it('does not claim runtime dependencies passed when the CLI executable is missing', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByText('依赖'));
    await act(async () => window.__omnicodeReceive?.({ type: 'runtime.status', payload: {
      id: 'kimi', name: 'Kimi CLI', runnable: false, version: '未安装', path: '',
      diagnostic: '未在 IDE PATH 或常见安装目录中找到。', modelDiscovery: true,
      nativeResume: false, nativeHistory: false, sessionContinuity: 'BOUNDED_REPLAY'
    } }));
    const row = screen.getByText('Kimi CLI', { selector: '.engine-grid strong' }).closest('.engine-grid > div') as HTMLElement;
    expect(within(row).getByText('程序 未找到')).toBeInTheDocument();
    expect(within(row).getByText('运行依赖 无法检测')).toBeInTheDocument();
    expect(within(row).getByText('认证 未验证')).toBeInTheDocument();
    expect(within(row).queryByRole('button', { name: '测试实际会话 · 可能计费' })).not.toBeInTheDocument();
  });

  it('requires a separate explicit action for a paid CLI conversation test and can cancel it', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByText('依赖'));
    await act(async () => window.__omnicodeReceive?.({ type: 'runtime.status', payload: {
      id: 'opencode', name: 'OpenCode', runnable: true, version: '1.18.33', path: '/usr/local/bin/opencode',
      diagnostic: '仅版本命令通过。', modelDiscovery: true, nativeResume: true, nativeHistory: true
    } }));
    const row = screen.getByText('OpenCode', { selector: '.engine-grid strong' }).closest('.engine-grid > div') as HTMLElement;
    fireEvent.click(within(row).getByRole('button', { name: '测试实际会话 · 可能计费' }));
    expect(sentCommands().some((command) => command.command === 'runtime.smokeTest' && command.payload.engineId === 'opencode')).toBe(true);
    expect(sentCommands().some((command) => command.command === 'session.send')).toBe(false);

    await act(async () => window.__omnicodeReceive?.({ type: 'runtime.verification', payload: {
      engineId: 'opencode', state: 'testing', modelCount: 0, detail: '正在临时空目录建立会话并等待模型回复。'
    } }));
    fireEvent.click(within(row).getByRole('button', { name: '取消检查' }));
    expect(sentCommands().some((command) => command.command === 'runtime.cancelVerification' && command.payload.engineId === 'opencode')).toBe(true);

    await act(async () => window.__omnicodeReceive?.({ type: 'runtime.verification', payload: {
      engineId: 'opencode', state: 'unverified', modelCount: 0, detail: '已取消检查；未得到可用性结论。'
    } }));
    fireEvent.click(within(row).getByRole('button', { name: '测试实际会话 · 可能计费' }));

    await act(async () => window.__omnicodeReceive?.({ type: 'runtime.verification', payload: {
      engineId: 'opencode', state: 'responded', modelCount: 0,
      detail: '已在临时目录收到模型回复；仅证明当前 CLI/模型能对话。'
    } }));
    expect(within(row).getByText('已在临时目录收到模型回复；仅证明当前 CLI/模型能对话。')).toBeInTheDocument();
    expect(within(row).getByText('认证 实际会话通过')).toBeInTheDocument();
    expect(within(row).getByText('模型回复 已收到')).toBeInTheDocument();
    await act(async () => window.__omnicodeReceive?.({ type: 'runtime.verification', payload: {
      engineId: 'opencode', state: 'checking', modelCount: 0, detail: '正在重新检查。'
    } }));
    expect(within(row).getByText('认证 未验证')).toBeInTheDocument();
    expect(within(row).getByText('模型回复 未验证 · 可能计费')).toBeInTheDocument();
  });

  it('verifies Codex native tool handoff only after a separate paid action', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByText('依赖'));
    await act(async () => window.__omnicodeReceive?.({ type: 'runtime.status', payload: {
      id: 'codex', name: 'Codex CLI', runnable: true, version: '1.0', path: '/usr/local/bin/codex',
      diagnostic: '仅版本命令通过。', modelDiscovery: true, nativeResume: true, nativeHistory: true
    } }));
    const row = screen.getByText('Codex', { selector: '.engine-grid strong' }).closest('.engine-grid > div') as HTMLElement;
    expect(within(row).getByText('宿主工具 未验证 · 可能计费')).toBeInTheDocument();
    expect(sentCommands().some((item) => item.command === 'runtime.smokeTest')).toBe(false);
    fireEvent.click(within(row).getByRole('button', { name: '验证工具桥接 · 可能计费' }));
    expect(sentCommands().some((item) => item.command === 'runtime.smokeTest' &&
      item.payload.engineId === 'codex' && item.payload.verifyToolBridge === true)).toBe(true);
    await act(async () => window.__omnicodeReceive?.({ type: 'runtime.verification', payload: {
      engineId: 'codex', state: 'tool_verified', modelCount: 0, detail: '内置探针已通过。'
    } }));
    expect(within(row).getByText('宿主工具 原生桥接通过')).toBeInTheDocument();
    await act(async () => window.__omnicodeReceive?.({ type: 'runtime.verification', payload: {
      engineId: 'codex', state: 'checking', modelCount: 0, detail: '重新检查。'
    } }));
    expect(within(row).getByText('宿主工具 未验证 · 可能计费')).toBeInTheDocument();
  });

  it('refreshes the active engine model catalog from the composer', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByRole('button', { name: '更多运行选项' }));
    fireEvent.click(screen.getByTitle('刷新可用模型'));
    expect(sentCommands().some((value) => value.command === 'provider.models')).toBe(true);
  });

  it('changes reasoning effort without exposing credentials', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByRole('button', { name: '更多运行选项' }));
    fireEvent.click(screen.getByTitle('推理强度'));
    fireEvent.click(screen.getByRole('option', { name: '推理 · High' }));
    const command = sentCommands().find((value) => value.command === 'settings.saveProvider' && value.payload.reasoningEffort === 'high');
    expect(command.payload).toMatchObject({ providerId: 'cli-opencode', model: 'opencode/model-a', apiKey: '' });
  });

  it('changes the live command permission from the composer', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('命令权限'));
    fireEvent.click(screen.getByRole('option', { name: '完全访问' }));
    expect(sentCommands().some((value) => value.command === 'settings.sandbox' && value.payload.mode === 'DANGER_FULL_ACCESS')).toBe(true);
  });

  it('quotes an assistant message back into the composer', async () => {
    render(<App />);
    await bootstrap({ blocks: [{ id: 'answer', role: 'assistant', kind: 'message.assistant', text: '请继续检查构建结果' }] });
    fireEvent.click(screen.getByTitle('引用到输入框'));
    expect(sentCommands().some((value) => value.command === 'composer.prefill' && value.payload.text === '> 请继续检查构建结果\n\n')).toBe(true);
  });

  it('imports Copilot MCP JSON only through the bounded native parser', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByText('MCP'));
    fireEvent.click(screen.getByText('导入 Copilot / Claude 配置'));
    const json = '{"servers":{"github":{"command":"npx","args":["-y","server"],"env":{"TOKEN":"secret"}}}}';
    fireEvent.change(screen.getByPlaceholderText(/"servers"/), { target: { value: json } });
    fireEvent.click(screen.getByText('导入为禁用草案'));
    const command = sentCommands().find((value) => value.command === 'mcp.importConfig');
    expect(command.payload.json).toBe(json);
  });

  it('renders Plan approval inside chat and sends the selected step', async () => {
    render(<App />);
    await bootstrap({
      plan: {
        id: 'plan-1', title: '修改前先规划', mode: 'CLAUDE_PLAN', revision: 1, decision: 'PENDING',
        approvedCount: 0, completedCount: 0,
        steps: [{ id: 'step-1', text: '只读检查项目', state: 'DRAFT', attempts: 0, lastError: '' }]
      }
    });
    fireEvent.click(screen.getByRole('button', { name: '编辑步骤 1' }));
    const stepEditor = screen.getByRole('textbox', { name: '编辑步骤 1' });
    fireEvent.change(stepEditor, { target: { value: '只读检查项目结构' } });
    fireEvent.blur(stepEditor);
    expect(sentCommands().some((value) => value.command === 'plan.updateStep' && value.payload.stepId === 'step-1' && value.payload.text === '只读检查项目结构')).toBe(true);
    fireEvent.click(screen.getByTitle('批准此步骤'));
    expect(sentCommands().some((value) => value.command === 'plan.approve' && value.payload.stepId === 'step-1')).toBe(true);
    fireEvent.click(screen.getByTitle('折叠计划'));
    expect(screen.queryByTitle('批准此步骤')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTitle('展开计划'));
    expect(screen.getByTitle('批准此步骤')).toBeInTheDocument();
  });

  it('reviews and rolls back an individual diff hunk inside the conversation', async () => {
    render(<App />);
    await bootstrap();
    await act(async () => window.__omnicodeReceive?.({
      type: 'review',
      payload: {
        workflowId: 'workflow-1',
        files: [{
          path: 'src/App.kt', decision: 'PENDING', added: 1, removed: 1,
          hunks: [{ id: 'hunk-1', beforeStart: 8, afterStart: 8, beforeCount: 2, afterCount: 3, before: 'old', after: 'new', decision: 'PENDING' }]
        }]
      }
    }));
    fireEvent.click(screen.getByText('编辑'));
    fireEvent.click(screen.getByText('src/App.kt'));
    fireEvent.click(screen.getByRole('button', { name: 'IDEA 对比' }));
    expect(sentCommands().some((value) => value.command === 'review.openDiff' && value.payload.workflowId === 'workflow-1' && value.payload.path === 'src/App.kt')).toBe(true);
    fireEvent.click(screen.getByText('打开'));
    expect(sentCommands().some((value) => value.command === 'navigation.openFile' && value.payload.path === 'src/App.kt' && value.payload.line === 8 && value.payload.end === 10)).toBe(true);
    fireEvent.click(screen.getByText('保留此块'));
    expect(sentCommands().some((value) => value.command === 'review.keepHunk' && value.payload.hunkId === 'hunk-1')).toBe(true);

    vi.spyOn(window, 'confirm').mockReturnValueOnce(true);
    fireEvent.click(screen.getByText('回退此块'));
    expect(sentCommands().some((value) => value.command === 'review.rollbackHunk' && value.payload.path === 'src/App.kt')).toBe(true);
  });

  it('accepts a file dropped anywhere in chat without sending it until Enter', async () => {
    const { container } = render(<App />);
    await bootstrap();
    const chat = container.querySelector('.chat-view')!;
    const file = new File(['hello'], 'notes.txt', { type: 'text/plain' });
    Object.defineProperty(file, 'text', { value: async () => 'hello' });
    fireEvent.dragEnter(chat, { dataTransfer: { types: ['Files'], files: [file] } });
    expect(screen.getByText('松开以添加附件')).toBeInTheDocument();
    fireEvent.drop(chat, { dataTransfer: { types: ['Files'], files: [file], dropEffect: 'copy' } });
    await waitFor(() => expect(screen.getByText('notes.txt')).toBeInTheDocument());
    expect(screen.queryByText('松开以添加附件')).not.toBeInTheDocument();
    expect(sentCommands().some((value) => value.command === 'session.send')).toBe(false);
  });

  it('adds an IDEA Project-tree drop to the current session draft without sending', async () => {
    render(<App />);
    await bootstrap();
    await act(async () => window.__omnicodeReceive?.({
      type: 'composer.attachProjectFiles',
      payload: { sessionId: 's1', paths: ['src/App.kt', 'src/App.kt', 'docs/README.md'] }
    }));
    expect(screen.getByText('src/App.kt')).toBeInTheDocument();
    expect(screen.getByText('docs/README.md')).toBeInTheDocument();
    expect(screen.getAllByText('src/App.kt')).toHaveLength(1);
    expect(sentCommands().some((value) => value.command === 'session.send')).toBe(false);
  });

  it('passes JCEF file URI drops to the host for project boundary validation', async () => {
    const { container } = render(<App />);
    await bootstrap();
    const chat = container.querySelector('.chat-view')!;
    const dataTransfer = {
      types: ['text/uri-list'], files: [], dropEffect: 'copy',
      getData: (type: string) => type === 'text/uri-list' ? 'file:///workspace/src/App.kt' : ''
    };
    fireEvent.dragOver(chat, { dataTransfer });
    fireEvent.drop(chat, { dataTransfer });
    expect(sentCommands().some((value) => value.command === 'composer.resolveDroppedPaths' && value.payload.paths[0] === '/workspace/src/App.kt')).toBe(true);
    expect(sentCommands().some((value) => value.command === 'session.send')).toBe(false);
  });

  it('persists project context enhancement through the native bridge', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByText('提示增强'));
    fireEvent.change(screen.getByLabelText(/固定到自动上下文/), { target: { value: 'src/App.kt' } });
    fireEvent.change(screen.getByLabelText(/排除路径/), { target: { value: 'build' } });
    fireEvent.click(screen.getByText('保存上下文规则'));
    expect(sentCommands().some((value) => value.command === 'settings.projectContext' && value.payload.pinnedPaths === 'src/App.kt' && value.payload.excludedPaths === 'build')).toBe(true);
  });

  it('keeps MCP OAuth credentials native and exposes explicit login actions', async () => {
    render(<App />);
    await bootstrap({
      settings: {
        ...settings,
        mcpServers: [{
          id: 'github', name: 'GitHub', enabled: false, transport: 'http', command: '', arguments: '',
          environmentKeys: '', workingDirectory: '.', url: 'https://example.com/mcp', httpAuthMode: 'oauth',
          oauthClientId: '', oauthScopes: '', bearerConfigured: false, oauthConfigured: false, oauthUsable: false
        }]
      }
    });
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByText('MCP'));
    fireEvent.click(screen.getByText('浏览器登录'));
    expect(sentCommands().some((value) => value.command === 'mcp.oauthLogin' && value.payload.id === 'github')).toBe(true);
    expect(document.querySelector('input[value*="token"]')).toBeNull();
  });

  it('turns an HTTP authorization challenge into a one-click OAuth recovery', async () => {
    render(<App />);
    await bootstrap({
      settings: {
        ...settings,
        mcpServers: [{
          id: 'protected-docs', name: 'Protected Docs', enabled: false, transport: 'http', command: '', arguments: '',
          environmentKeys: '', workingDirectory: '.', url: 'https://example.com/mcp', httpAuthMode: 'oauth',
          oauthClientId: '', oauthScopes: 'docs.read', bearerConfigured: false, oauthConfigured: false, oauthUsable: false
        }]
      }
    });
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByText('MCP'));
    await act(async () => window.__omnicodeReceive?.({ type: 'mcp.test', payload: {
      id: 'protected-docs', state: 'action_required', action: 'oauthLogin',
      message: '已识别 OAuth 2.1 / PKCE。'
    } }));
    expect(screen.getByText('还差一步授权')).toBeInTheDocument();
    fireEvent.click(screen.getByText('浏览器登录并继续'));
    expect(sentCommands().some((value) => value.command === 'mcp.oauthLogin' && value.payload.id === 'protected-docs')).toBe(true);
  });

  it('selects and reveals an auto-filled MCP marketplace draft', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByText('MCP'));
    fireEvent.click(screen.getByText('MCP 市场'));
    await act(async () => window.__omnicodeReceive?.({
      type: 'mcp.catalog', payload: [{
        id: 'remote-docs', name: 'Remote Docs', publisher: 'example', description: 'Documentation search',
        source: 'MCP_REGISTRY', category: '开发', risk: 'LOW', riskSummary: 'HTTPS remote service', tags: ['docs'],
        options: [
          { id: 'http', name: 'Streamable HTTP', kind: 'STREAMABLE_HTTP' },
          { id: 'stdio', name: 'NPX package', kind: 'NPX_PACKAGE' }
        ]
      }]
    }));
    await act(async () => window.__omnicodeReceive?.({ type: 'mcp.catalogState', payload: { state: 'ready', total: 527, shown: 1, notice: 'Registry 已同步；搜索覆盖全部目录。', fromCache: true } }));
    expect(screen.getByText('527 个目录条目 · 显示 1')).toBeInTheDocument();
    expect(screen.getByText(/使用本地缓存/)).toBeInTheDocument();
    fireEvent.click(screen.getByTitle('刷新 MCP Registry'));
    expect(sentCommands().some((value) => value.command === 'mcp.catalog' && value.payload.forceRefresh === true)).toBe(true);
    fireEvent.change(screen.getByLabelText('Remote Docs 安装方式'), { target: { value: 'http' } });
    fireEvent.click(screen.getByText('添加并配置'));
    expect(sentCommands().some((value) => value.command === 'mcp.installDraft' && value.payload.optionId === 'http')).toBe(true);

    const configured = {
      id: 'remote-docs-http', name: 'Remote Docs', enabled: false, transport: 'http', command: '', arguments: '',
      environmentKeys: '', workingDirectory: '.', url: 'https://mcp.example.com/mcp', httpAuthMode: 'oauth',
      oauthClientId: 'public-client', oauthScopes: 'mcp.read', bearerConfigured: false, oauthConfigured: false, oauthUsable: false
    };
    await act(async () => {
      window.__omnicodeReceive?.({ type: 'settings', payload: { ...settings, mcpServers: [configured] } });
      window.__omnicodeReceive?.({ type: 'mcp.draft', payload: { id: configured.id, warnings: ['首次连接前需要登录。'] } });
    });
    expect(screen.queryByPlaceholderText('搜索服务器、发布者或标签')).not.toBeInTheDocument();
    expect(screen.getByDisplayValue('https://mcp.example.com/mcp')).toBeInTheDocument();
    expect(screen.getByDisplayValue('public-client')).toBeInTheDocument();
    expect(screen.getByText('市场参数已自动填充')).toBeInTheDocument();
    expect(screen.getByText('首次连接前需要登录。')).toBeInTheDocument();
  });

  it('renders activity in the transcript and settles it at the terminal event', async () => {
    const { container } = render(<App />);
    await bootstrap({
      running: true,
      blocks: [{ id: 'user-1', role: 'user', kind: 'message.user', text: '检查项目' }]
    });
    await act(async () => {
      window.__omnicodeReceive?.({
        type: 'event',
        payload: {
          schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'turn-1',
          blockId: 'turn-1-status', sequence: 1, kind: 'status', phase: 'running', at: '',
          payload: { title: '加载配置', message: '正在加载模型配置…' }
        }
      });
      window.__omnicodeReceive?.({
        type: 'event',
        payload: {
          schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'turn-1',
          blockId: 'turn-1-provider', sequence: 2, kind: 'provider.requested', phase: 'running', at: '',
          payload: { title: '模型请求 #1' }
        }
      });
    });

    expect(screen.getByText('模型请求 #1')).toBeInTheDocument();
    expect(screen.queryByText('加载配置')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /模型请求 #1 0\/2/ }));
    expect(screen.getByText('加载配置')).toBeInTheDocument();
    expect(container.querySelectorAll('.thinking')).toHaveLength(0);
    expect(container.querySelectorAll('.event-card.running')).toHaveLength(2);

    await act(async () => window.__omnicodeReceive?.({
      type: 'event',
      payload: {
        schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'turn-1',
        blockId: 'turn-1-result', sequence: 3, kind: 'run.completed', phase: 'completed', at: '',
        payload: { title: '任务完成' }
      }
    }));

    expect(screen.getByRole('region', { name: '本轮结果' })).toHaveTextContent('本轮运行结束');
    expect(container.querySelector('.thinking')).toBeNull();
    expect(container.querySelector('.event-card.running')).toBeNull();
    expect(screen.getByText('空闲')).toBeInTheDocument();

    await act(async () => window.__omnicodeReceive?.({
      type: 'event',
      payload: {
        schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'turn-1',
        blockId: 'late-status', sequence: 2, kind: 'status', phase: 'running', at: '',
        payload: { title: '迟到状态', message: '不应重新出现' }
      }
    }));
    expect(screen.queryByText('迟到状态')).not.toBeInTheDocument();
    expect(container.querySelector('.thinking')).toBeNull();
  });

  it('collapses a long routine lifecycle into one expandable process row', async () => {
    const { container } = render(<App />);
    await bootstrap({ running: false });
    await act(async () => {
      for (const [index, title] of ['Startup', 'Context', 'Mcp'].entries()) {
        window.__omnicodeReceive?.({
          type: 'event',
          payload: {
            schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'compact-turn',
            blockId: `compact-${index}`, sequence: index + 1, kind: 'stage.lifecycle', phase: 'completed', at: '',
            payload: { title, message: `${title} 已完成` }
          }
        });
      }
    });
    expect(container.querySelectorAll('.activity-summary')).toHaveLength(1);
    expect(screen.getByText('3/3 个阶段完成 · 查看详情')).toBeInTheDocument();
    expect(screen.queryByText('Startup 已完成')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /执行过程 3\/3 个阶段完成/ }));
    expect(container.querySelector('.activity-summary-detail')).toHaveTextContent('Startup');
    expect(container.querySelector('.activity-summary-detail')).toHaveTextContent('Context');
  });

  it('does not resurrect a spinner when a stopped session bootstraps with stale running stages', async () => {
    const { container } = render(<App />);
    await bootstrap({ running: false, blocks: [
      { id: 'stage', turnId: 'turn-1', role: 'system', kind: 'stage.lifecycle', title: 'Context', text: '', status: 'running' },
      { id: 'terminal', turnId: 'turn-1', role: 'system', kind: 'run.completed', title: '完成', text: '', status: 'success' }
    ] });
    expect(container.querySelector('.event-card.running')).toBeNull();
    expect(container.querySelector('.activity-summary.running')).toBeNull();
    expect(screen.getByText('1/1 个阶段完成 · 查看详情')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '本轮结果' })).toHaveTextContent('本轮运行结束');
  });

  it('shows per-turn tool readiness without treating saved configuration as a working connection', async () => {
    render(<App />);
    await bootstrap({ blocks: [{
      id: 'tool-surface-1', role: 'system', kind: 'tools.available', title: '本轮工具能力',
      text: '本轮已准备工具；实际连接仍以调用为准。', status: 'success',
      metadata: { entries: [
        { name: 'query_database', state: 'configuration', detail: '密码尚未保存。' },
        { name: 'mcp', state: 'unsupported', detail: '当前引擎没有 MCP 桥接。' }
      ] }
    }] });
    fireEvent.click(screen.getByRole('button', { name: /本轮工具能力/ }));
    expect(screen.getByText('密码尚未保存。')).toBeInTheDocument();
    expect(screen.getByText('当前引擎没有 MCP 桥接。')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '检查数据库配置' }));
    fireEvent.click(screen.getByRole('button', { name: '检查 MCP 配置' }));
    expect(sentCommands().some((command) => command.command === 'navigation.view' && command.payload.tab === 'databases')).toBe(true);
    expect(sentCommands().some((command) => command.command === 'navigation.view' && command.payload.tab === 'mcp')).toBe(true);
  });

  it('keeps live progress compact as stages arrive and exposes failures separately', async () => {
    const { container } = render(<App />);
    await bootstrap({ running: true });
    const event = async (id: string, sequence: number, phase = 'running') => act(async () => {
      window.__omnicodeReceive?.({ type: 'event', payload: {
        schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'compact-live',
        blockId: id, sequence, kind: 'stage.lifecycle', phase, at: '',
        payload: { title: id, message: `${id} 详情` }
      } });
    });
    await event('准备环境', 1);
    expect(container.querySelector('.activity-summary-detail')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /准备环境 0\/1/ }));
    expect(container.querySelector('.activity-summary-detail')).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: /准备环境 0\/1/ }));
    await event('读取上下文', 2);
    await event('请求模型', 3);
    expect(container.querySelector('.activity-summary-detail')).toBeNull();
    expect(container.querySelectorAll('.activity-summary')).toHaveLength(1);
    await event('连接失败', 4, 'failed');
    expect(container.querySelector('.event-card.error')).toHaveTextContent('连接失败 详情');
  });

  it('hides empty detail panels and shows only panels with records', async () => {
    const { container } = render(<App />);
    await bootstrap();
    expect(container.querySelector('.activity-dock')).toBeNull();
    await bootstrap({ blocks: [{ id: 'child-1', role: 'system', kind: 'agent.started', title: '审阅代理', text: '', status: 'running' }] });
    expect(screen.getByRole('button', { name: /子代理 1/ })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /任务 0/ })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /编辑 0/ })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '展开对话详情' }));
    expect(container.querySelector('.activity-content')).toHaveTextContent('审阅代理');
  });

  it('does not mark unfinished activity as successful when a turn fails', async () => {
    const { container } = render(<App />);
    await bootstrap({ running: true });
    await act(async () => window.__omnicodeReceive?.({
      type: 'event',
      payload: {
        schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'failed-turn',
        blockId: 'failed-stage', sequence: 1, kind: 'stage.context', phase: 'running', at: '',
        payload: { title: '准备项目上下文', message: '正在读取项目文件…' }
      }
    }));
    await act(async () => window.__omnicodeReceive?.({
      type: 'event',
      payload: {
        schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'failed-turn',
        blockId: 'failed-result', sequence: 2, kind: 'run.completed', phase: 'failed', at: '',
        payload: { title: '任务未完成', message: '模型连接失败' }
      }
    }));

    expect(container.querySelector('.event-card.warning')).not.toBeNull();
    expect(container.querySelector('.event-card.success strong')?.textContent).not.toBe('准备项目上下文');
    expect(screen.getByText('任务未完成')).toBeInTheDocument();
  });

  it('settles provider-specific failed and cancelled terminal events', async () => {
    const { container } = render(<App />);
    await bootstrap({ running: true });
    await act(async () => window.__omnicodeReceive?.({
      type: 'event',
      payload: {
        schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'provider-failed-turn',
        blockId: 'provider-failed', sequence: 1, kind: 'provider.requested', phase: 'running', at: '',
        payload: { title: '模型请求 #1' }
      }
    }));
    expect(screen.getByRole('button', { name: '停止任务' })).toBeInTheDocument();

    await act(async () => window.__omnicodeReceive?.({
      type: 'event',
      payload: {
        schemaVersion: 1, pageGeneration: 7, sessionId: 's1', turnId: 'provider-failed-turn',
        blockId: 'provider-failed-result', sequence: 2, kind: 'run.failed', phase: 'failed', at: '',
        payload: { title: '任务未完成', message: 'CLI 连接失败' }
      }
    }));

    expect(screen.queryByRole('button', { name: '停止任务' })).toBeNull();
    expect(screen.getByText('空闲')).toBeInTheDocument();
    expect(container.querySelector('.event-card.running')).toBeNull();
  });

  it('stops the visible run immediately while the host reclaims a stuck CLI', async () => {
    render(<App />);
    await bootstrap({ running: true });
    fireEvent.click(screen.getByRole('button', { name: '停止任务' }));
    expect(screen.getByText('正在清理')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '停止任务' })).toBeNull();
    expect(sentCommands().some((value) => value.command === 'session.cancel')).toBe(true);
    fireEvent.change(screen.getByPlaceholderText(/输入任务/), { target: { value: '清理完成后再做' } });
    expect(screen.getByRole('button', { name: '排队发送' })).toBeInTheDocument();
    await act(async () => window.__omnicodeReceive?.({ type: 'running', payload: { sessionId: 's1', running: false, cleanupPending: true } }));
    expect(screen.getByText('正在清理')).toBeInTheDocument();
    await act(async () => window.__omnicodeReceive?.({ type: 'running', payload: { sessionId: 's1', running: false, cleanupPending: false } }));
    expect(screen.getByText('空闲')).toBeInTheDocument();
  });

  it('shows a cleanup quarantine in history and prevents deletion until it ends', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.click(screen.getByTitle('历史记录'));
    await act(async () => window.__omnicodeReceive?.({
      type: 'history', payload: [{ id: 's1', title: '清理中的会话', updatedAt: new Date().toISOString(), status: 'CLEANING_UP', messageCount: 2 }]
    }));
    expect(screen.getByText('上次任务仍在后台清理 · 点击查看')).toBeInTheDocument();
    expect(screen.getByTitle('后台清理完成后才能删除')).toBeDisabled();
  });

  it('renders a recoverable diagnostics card and ignores malformed check payloads', async () => {
    render(<App />);
    await bootstrap();
    await act(async () => window.__omnicodeReceive?.({
      type: 'diagnostics',
      payload: {
        state: 'success', overallStatus: 'FAIL', durationMillis: 42,
        failCount: 1, warnCount: 0, passCount: 0, skipCount: 0,
        checks: [null, { id: 'tls', title: 'TLS', status: 'FAIL', summary: '握手失败', recoverySuggestion: '检查代理' }, { status: 'UNKNOWN' }]
      }
    }));
    expect(screen.getByText('连接诊断')).toBeInTheDocument();
    expect(screen.getByText('TLS')).toBeInTheDocument();
    expect(screen.getByText('握手失败')).toBeInTheDocument();
    expect(screen.getByText('建议：检查代理')).toBeInTheDocument();
    expect(screen.getAllByText('失败').length).toBeGreaterThan(0);
    fireEvent.click(screen.getByText('打开设置'));
    expect(sentCommands().some((value) => value.command === 'navigation.view' && value.payload.view === 'settings')).toBe(true);
    fireEvent.click(screen.getByTitle('关闭诊断'));
    expect(screen.queryByText('连接诊断')).not.toBeInTheDocument();
  });

  it('localizes legacy network diagnostics into actionable Chinese copy', async () => {
    render(<App />);
    await bootstrap();
    await act(async () => window.__omnicodeReceive?.({
      type: 'diagnostics',
      payload: {
        state: 'success', overallStatus: 'FAIL', durationMillis: 12,
        failCount: 1, warnCount: 0, passCount: 0, skipCount: 0,
        checks: [{
          id: 'network.dns', title: 'Provider DNS', status: 'FAIL',
          summary: 'The provider hostname did not resolve.',
          recoverySuggestion: 'Verify the Base URL and local DNS/VPN configuration.'
        }]
      }
    }));
    expect(screen.getByText('供应商 DNS')).toBeInTheDocument();
    expect(screen.getByText(/供应商域名无法解析/)).toBeInTheDocument();
    expect(screen.getByText(/核对 Base URL/)).toBeInTheDocument();
    expect(screen.queryByText('The provider hostname did not resolve.')).not.toBeInTheDocument();
  });
});
