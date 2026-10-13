import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import { viteSingleFile } from 'vite-plugin-singlefile';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const changelog = readFileSync(resolve(import.meta.dirname, '../CHANGELOG.md'), 'utf8');
const packageJson = JSON.parse(readFileSync(resolve(import.meta.dirname, 'package.json'), 'utf8')) as { version: string };
const releaseVersion = process.env.OMNICODE_VERSION ?? packageJson.version;
const sectionHeader = new RegExp(`^## \\[${releaseVersion.replaceAll('.', '\\.')}\\][^\\n]*\\n`, 'm');
const sectionMatch = sectionHeader.exec(changelog);
const releaseSection = sectionMatch
  ? changelog.slice(sectionMatch.index + sectionMatch[0].length).split(/^## \[/m, 1)[0]
  : '';
const releaseNotes = releaseSection
  .split(/\r?\n/)
  .filter((line) => /^\s*[-*]\s+/.test(line))
  .map((line) => line.replace(/^\s*[-*]\s+/, '').trim())
  .filter(Boolean)
  .slice(0, 8);

export default defineConfig({
  plugins: [react(), viteSingleFile()],
  define: {
    __OMNICODE_RELEASE__: JSON.stringify({ version: releaseVersion, notes: releaseNotes }),
  },
  base: './',
  build: {
    outDir: '../src/main/resources/webview',
    emptyOutDir: true,
    cssCodeSplit: false,
    sourcemap: false,
    target: 'es2022'
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
    include: ['src/**/*.test.ts', 'src/**/*.test.tsx']
  }
});
