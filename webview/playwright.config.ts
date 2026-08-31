import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  retries: 0,
  reporter: [['line']],
  timeout: 30_000,
  snapshotPathTemplate: '{testDir}/{testFilePath}-snapshots/{arg}{ext}',
  expect: {
    timeout: 8_000,
    toHaveScreenshot: { animations: 'disabled', maxDiffPixelRatio: 0.035 }
  },
  use: {
    baseURL: 'http://127.0.0.1:4178',
    colorScheme: 'dark',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai'
  },
  webServer: {
    // Exercise the exact single-file artifact embedded in JCEF. The development server uses
    // external module scripts, which the production CSP intentionally rejects.
    command: 'npm run build && npm exec vite preview -- --host 127.0.0.1 --port 4178',
    url: 'http://127.0.0.1:4178',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000
  }
});
