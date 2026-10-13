import { defineConfig } from '@playwright/test';

const previewPort = Number(process.env.PREVIEW_PORT || 4178);
const reusePreview = process.env.REUSE_PREVIEW === 'true' || (!process.env.CI && !process.env.PREVIEW_PORT);

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  retries: 0,
  reporter: [['line']],
  timeout: 30_000,
  snapshotPathTemplate: '{testDir}/{testFilePath}-snapshots/{arg}{ext}',
  expect: {
    timeout: 8_000,
    // Headless Linux and macOS rasterize CJK text and fractional-scale borders slightly
    // differently. Keep visual snapshots useful while explicit viewport bounds below guard
    // the layout invariants that must never regress.
    toHaveScreenshot: { animations: 'disabled', maxDiffPixelRatio: 0.05 }
  },
  use: {
    baseURL: `http://127.0.0.1:${previewPort}`,
    colorScheme: 'dark',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai'
  },
  webServer: {
    // Exercise the exact single-file artifact embedded in JCEF. The development server uses
    // external module scripts, which the production CSP intentionally rejects.
    command: `npm run build && npm exec vite preview -- --host 127.0.0.1 --port ${previewPort}`,
    url: `http://127.0.0.1:${previewPort}`,
    reuseExistingServer: reusePreview,
    timeout: 60_000
  }
});
