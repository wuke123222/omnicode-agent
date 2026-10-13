import { act, fireEvent, render, screen } from '@testing-library/react';
import { App } from './App';

const settings = {
  releaseNotesVersion: '3.0.30',
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
  appearance: { locale: 'auto', fontFamily: 'system', fontSize: 13 },
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
    expect(screen.getByText('OMNICODE 更新 · 3.0.30')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '开始使用' }));
    expect(sentCommands().some((command) => command.command === 'settings.releaseNotesSeen' && command.payload.version === '3.0.30')).toBe(true);
    first.unmount();

    render(<App />);
    await bootstrap({ settings });
    expect(screen.queryByRole('dialog', { name: '这次更新了什么' })).not.toBeInTheDocument();
  });

  it('offers optional GitHub and WeChat support without gating features', async () => {
    render(<App />);
    await bootstrap({ settings: { ...settings, releaseNotesVersion: '' } });
    fireEvent.click(screen.getByRole('button', { name: '开始使用' }));
    fireEvent.click(screen.getByTitle('GitHub Star · 自愿支持 OmniCode'));
    expect(screen.getByRole('dialog', { name: '让 OmniCode 继续变好' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /去 GitHub 点个 Star/ })).toBeInTheDocument();
    expect(screen.getByText('尚未配置收款码')).toBeInTheDocument();
  });

  it('shows a focused welcome with four real next actions and a quiet GitHub link', async () => {
    render(<App />);
    await bootstrap();
    expect(screen.getByRole('heading', { name: '从一个清晰的小目标开始' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /了解项目/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /探索项目地图/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /先规划，再执行/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /审阅当前改动/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'GitHub Star · 自愿支持 OmniCode' })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /GitHub Star/ })).toHaveLength(2);
    expect(screen.queryByRole('button', { name: /子代理 0/ })).not.toBeInTheDocument();
  });

  it('exposes only three root views and keeps settings in its own view', () => {
    render(<App />);
    expect(screen.queryByText('任务中心')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTitle('设置'));
    expect(screen.getByText('供应商')).toBeInTheDocument();
    expect(screen.getByText('MCP')).toBeInTheDocument();
    expect(screen.queryByText('实验与科研')).not.toBeInTheDocument();
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
    expect(screen.getByText('页面已更新，此操作未执行。请在当前页面重试。')).toBeInTheDocument();
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

  it('does not leak a queued prompt across sessions and clears it on stop', async () => {
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
    expect(screen.queryByText('只属于会话一')).not.toBeInTheDocument();
    expect(screen.queryByText('队列 1')).not.toBeInTheDocument();
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
    expect(screen.getByText('就绪')).toBeInTheDocument();

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
    expect(screen.getByText('会话一完成')).toBeInTheDocument();
    expect(screen.getByText('就绪')).toBeInTheDocument();
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
    expect(screen.getByText('就绪')).toBeInTheDocument();
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
    fireEvent.click(screen.getByText('选择并检查模型'));
    const command = sentCommands().find((value) => value.command === 'provider.select' && value.payload.providerId === 'cli-kimi');
    expect(command.payload).toEqual({ providerId: 'cli-kimi' });
    expect(sentCommands().some((value) => value.command === 'settings.saveProvider' && value.payload.model === 'default')).toBe(false);
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
    fireEvent.click(screen.getByText('打开'));
    expect(sentCommands().some((value) => value.command === 'navigation.openFile' && value.payload.path === 'src/App.kt' && value.payload.line === 8 && value.payload.end === 10)).toBe(true);
    fireEvent.click(screen.getByText('保留此块'));
    expect(sentCommands().some((value) => value.command === 'review.keepHunk' && value.payload.hunkId === 'hunk-1')).toBe(true);

    vi.spyOn(window, 'confirm').mockReturnValueOnce(true);
    fireEvent.click(screen.getByText('回退此块'));
    expect(sentCommands().some((value) => value.command === 'review.rollbackHunk' && value.payload.path === 'src/App.kt')).toBe(true);
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

    expect(screen.getByText('任务完成')).toBeInTheDocument();
    expect(container.querySelector('.thinking')).toBeNull();
    expect(container.querySelector('.event-card.running')).toBeNull();
    expect(screen.getByText('就绪')).toBeInTheDocument();

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
    expect(screen.getByText('3/3 个阶段完成')).toBeInTheDocument();
    expect(screen.queryByText('Startup 已完成')).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /执行过程 3\/3 个阶段完成/ }));
    expect(container.querySelector('.activity-summary-detail')).toHaveTextContent('Startup');
    expect(container.querySelector('.activity-summary-detail')).toHaveTextContent('Context');
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
    expect(screen.getByText('就绪')).toBeInTheDocument();
    expect(container.querySelector('.event-card.running')).toBeNull();
  });

  it('stops the visible run immediately while the host reclaims a stuck CLI', async () => {
    render(<App />);
    await bootstrap({ running: true });
    fireEvent.click(screen.getByRole('button', { name: '停止任务' }));
    expect(screen.getByText('就绪')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '停止任务' })).toBeNull();
    expect(sentCommands().some((value) => value.command === 'session.cancel')).toBe(true);
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
