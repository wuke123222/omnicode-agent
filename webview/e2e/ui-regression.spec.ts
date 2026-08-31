import { expect, test } from '@playwright/test';

const baseSettings = {
  provider: {
    id: 'cli-opencode', baseUrl: 'cli://local', model: 'opencode/big-pickle',
    reasoningEffort: 'high', maxOutputTokens: 8192, credentialConfigured: true
  },
  providers: [{
    id: 'cli-opencode', name: 'OpenCode', defaultBaseUrl: 'cli://local', defaultModel: 'default',
    cli: true, baseUrl: 'cli://local', model: 'opencode/big-pickle', credentialConfigured: true
  }],
  platform: {
    sandboxMode: 'WORKSPACE_WRITE', historyEnabled: true, historyRetention: 100,
    usageRetentionDays: 365, mcpCount: 2, skillCount: 3, promptCount: 4,
    commitAiEnabled: true, agentContinuousExecution: true, providerMaxAttempts: 3
  },
  themes: [], pets: [], projectContext: { pinnedPaths: [], excludedPaths: [] },
  mcpServers: [], prompts: [], skills: []
};

const blocks = [
  { id: 'u1', role: 'user', kind: 'message.user', text: '请检查登录流程，并给出可以逐步审批的修改计划。' },
  { id: 's1', role: 'system', kind: 'stage.context', title: '项目上下文已就绪', text: '规则 2 · 固定 1 · 约 1,824 tokens', status: 'success' },
  { id: 'a1', role: 'assistant', kind: 'message.assistant', text: '已定位到 `src/auth/LoginService.kt:42-68`。我会先补充边界测试，再修改令牌刷新逻辑。' },
  { id: 't1', role: 'system', kind: 'tool.read_file', title: '读取文件', text: 'src/auth/LoginService.kt:42-68', status: 'success' }
];

const plan = {
  id: 'plan-1', title: '登录可靠性升级', mode: 'CLAUDE_PLAN', revision: 2,
  decision: 'PENDING', approvedCount: 1, completedCount: 0,
  steps: [
    { id: 'step-1', text: '补充刷新令牌过期与并发请求测试', state: 'APPROVED', attempts: 0, lastError: '' },
    { id: 'step-2', text: '修改 LoginService 并运行聚焦测试', state: 'DRAFT', attempts: 0, lastError: '' }
  ]
};

const palettes = {
  dark: {
    background: '#151719', surface: '#202327', elevatedSurface: '#1b1d20', primaryText: '#e7e9ed',
    secondaryText: '#979da7', accent: '#6ea2ff', border: '#34383e', success: '#5dcc83',
    warning: '#d9a14a', error: '#ee6b72'
  },
  light: {
    background: '#f5f6f8', surface: '#ffffff', elevatedSurface: '#edf0f4', primaryText: '#20242a',
    secondaryText: '#66707c', accent: '#2563d9', border: '#d2d7df', success: '#26834a',
    warning: '#9b6415', error: '#c53b46'
  }
};

const scenarios = [
  { name: 'dark-320-100', width: 320, scale: 1, theme: 'dark' as const },
  { name: 'light-480-125', width: 480, scale: 1.25, theme: 'light' as const },
  { name: 'dark-800-150', width: 800, scale: 1.5, theme: 'dark' as const }
];

for (const scenario of scenarios) {
  test(`${scenario.name} keeps chat plan and composer inside the viewport`, async ({ browser }) => {
    const context = await browser.newContext({
      viewport: { width: scenario.width, height: 760 },
      deviceScaleFactor: scenario.scale,
      colorScheme: scenario.theme
    });
    await context.addInitScript(() => {
      window.__OMNICODE_PAGE_GENERATION__ = 1;
      window.omnicodeSend = () => {};
    });
    const page = await context.newPage();
    await page.goto('/');
    await page.evaluate(({ settings, transcript, proposedPlan }) => {
      window.__omnicodeReceive?.({
        type: 'bootstrap',
        payload: {
          projectName: 'OmniCode Fixture', sessionId: 'fixture-session', running: false,
          providerStatus: 'OpenCode · big-pickle', providerConfigured: true,
          blocks: transcript, history: [], settings, models: ['opencode/big-pickle'], plan: proposedPlan
        }
      });
    }, {
      settings: {
        ...baseSettings,
        theme: {
          id: `fixture-${scenario.theme}`, petEnabled: false, petId: 'none', petX: 8000, petY: 7000,
          palette: palettes[scenario.theme]
        }
      },
      transcript: blocks,
      proposedPlan: plan
    });
    await expect(page.locator('.app-shell')).toBeVisible();
    await expect(page.locator('.composer-shell')).toBeVisible();
    await expect(page.locator('.app-shell')).toHaveScreenshot(`${scenario.name}.png`);
    await context.close();
  });
}
