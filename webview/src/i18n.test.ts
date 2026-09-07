import { describe, expect, it } from 'vitest';
import { createTranslator, normalizeLocale } from './i18n';

describe('UI locale', () => {
  it('uses English only for explicit English locales', () => {
    expect(normalizeLocale('en-US')).toBe('en');
    expect(normalizeLocale('zh-CN')).toBe('zh-CN');
    expect(normalizeLocale(undefined)).toBe('zh-CN');
  });

  it('interpolates bounded interface labels', () => {
    expect(createTranslator('zh-CN')('composer.queue', { count: 2 })).toBe('队列 2');
    expect(createTranslator('en')('history.messages', { count: 3 })).toBe('3 messages');
  });
});
