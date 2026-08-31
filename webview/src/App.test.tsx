import { act, fireEvent, render, screen } from '@testing-library/react';
import { App } from './App';

const settings = {
  provider: { id: 'cli-opencode', baseUrl: 'cli://local', model: 'opencode/model-a', reasoningEffort: 'auto', maxOutputTokens: 8192, credentialConfigured: true },
  providers: [
    { id: 'cli-opencode', name: 'OpenCode CLI', defaultBaseUrl: 'cli://local', defaultModel: 'default', cli: true, baseUrl: 'cli://local', model: 'opencode/model-a', credentialConfigured: true },
    { id: 'openai', name: 'OpenAI', defaultBaseUrl: 'https://api.openai.com/v1', defaultModel: 'gpt-5.6-sol', cli: false, baseUrl: 'https://api.openai.com/v1', model: 'gpt-5.6-sol', credentialConfigured: true }
  ],
  platform: { sandboxMode: 'WORKSPACE_WRITE', historyEnabled: true, historyRetention: 100, usageRetentionDays: 365, mcpCount: 0, skillCount: 0, promptCount: 0, commitAiEnabled: true, agentContinuousExecution: true, providerMaxAttempts: 3 },
  theme: {
    id: 'jetbrains-native', petEnabled: false, petId: 'pixel-cat', petX: 8800, petY: 7600,
    palette: { background: '#151719', surface: '#202327', elevatedSurface: '#1b1d20', primaryText: '#e7e9ed', secondaryText: '#979da7', accent: '#6ea2ff', border: '#34383e', success: '#5dcc83', warning: '#d9a14a', error: '#ee6b72' }
  },
  themes: [{ id: 'jetbrains-native', name: 'JetBrains Native', description: 'Follow IDE' }],
  pets: [{ id: 'pixel-cat', name: 'Pixel Cat', description: 'Pet', glyph: '🐈' }],
  projectContext: { pinnedPaths: [], excludedPaths: [] },
  mcpServers: [], prompts: [], skills: []
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
  });

  it('exposes only three root views and keeps settings in its own view', () => {
    render(<App />);
    expect(screen.queryByText('任务中心')).not.toBeInTheDocument();
    fireEvent.click(screen.getByTitle('设置'));
    expect(screen.getByText('供应商')).toBeInTheDocument();
    expect(screen.getByText('MCP')).toBeInTheDocument();
    expect(screen.queryByText('实验与科研')).not.toBeInTheDocument();
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

  it('keeps the chat composer mounted while visiting settings', async () => {
    render(<App />);
    await bootstrap();
    const composer = screen.getByPlaceholderText(/输入任务/) as HTMLTextAreaElement;
    fireEvent.change(composer, { target: { value: '保留这段草稿' } });
    fireEvent.click(screen.getByTitle('设置'));
    fireEvent.click(screen.getByTitle('返回聊天'));
    expect(screen.getByPlaceholderText(/输入任务/)).toHaveValue('保留这段草稿');
  });

  it('projects the configured pet only into chat without adding another root view', async () => {
    render(<App />);
    await bootstrap({ settings: { ...settings, theme: { ...settings.theme, petEnabled: true } } });
    expect(screen.getByRole('button', { name: '移动桌宠 Pixel Cat' })).toBeInTheDocument();
    fireEvent.click(screen.getByTitle('设置'));
    expect(screen.queryByRole('button', { name: '移动桌宠 Pixel Cat' })).not.toBeInTheDocument();
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
    fireEvent.change(screen.getByTitle('当前模型'), { target: { value: 'opencode/model-b' } });
    const command = sentCommands().find((value) => value.command === 'settings.saveProvider');
    expect(command.payload).toMatchObject({ providerId: 'cli-opencode', model: 'opencode/model-b', apiKey: '' });
  });

  it('switches engine without sending credentials through the webview', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.change(screen.getByTitle('当前引擎'), { target: { value: 'openai' } });
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
        diagnostic: '版本检测通过；登录状态与模型权限将在选择后验证。', modelDiscovery: false,
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
    fireEvent.click(screen.getByTitle('刷新可用模型'));
    expect(sentCommands().some((value) => value.command === 'provider.models')).toBe(true);
  });

  it('changes reasoning effort without exposing credentials', async () => {
    render(<App />);
    await bootstrap();
    fireEvent.change(screen.getByTitle('推理强度'), { target: { value: 'high' } });
    const command = sentCommands().find((value) => value.command === 'settings.saveProvider' && value.payload.reasoningEffort === 'high');
    expect(command.payload).toMatchObject({ providerId: 'cli-opencode', model: 'opencode/model-a', apiKey: '' });
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
          hunks: [{ id: 'hunk-1', beforeStart: 8, afterStart: 8, before: 'old', after: 'new', decision: 'PENDING' }]
        }]
      }
    }));
    fireEvent.click(screen.getByText('编辑'));
    fireEvent.click(screen.getByText('src/App.kt'));
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
});
