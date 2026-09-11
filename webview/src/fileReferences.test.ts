import { fileLinkMarkdown } from './fileReferences';

describe('fileLinkMarkdown', () => {
  it.each([
    ['src/views/Overview.vue:148-169', 'src/views/Overview.vue', '148', '169'],
    ['src/页面/概览.vue 148–169', 'src/页面/概览.vue', '148', '169'],
    ['`C:\\work\\demo\\Main.kt:L8-L12`', 'C:\\work\\demo\\Main.kt', '8', '12']
  ])('turns %s into an IDE link', (input, path, line, end) => {
    expect(fileLinkMarkdown(input)).toBe(
      `[${path}:${line}-${end}](omnicode-file://open?path=${encodeURIComponent(path)}&line=${line}&end=${end})`
    );
  });

  it('does not rewrite references in fenced code or nest restored links', () => {
    const fenced = '```text\nsrc/App.vue:12-14\n```';
    const restored = '[src/App.vue:12](omnicode-file://open?path=src%2FApp.vue&line=12)';
    expect(fileLinkMarkdown(fenced)).toBe(fenced);
    expect(fileLinkMarkdown(restored)).toBe(restored);
  });
});
