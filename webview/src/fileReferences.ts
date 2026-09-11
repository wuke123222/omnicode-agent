const FILE_REFERENCE = /(?<![\w/])((?:(?:[A-Za-z]:)?[\\/])?(?:(?:\.\.?|[\p{L}\p{N}_@.+~()\-]+)[\\/])*[\p{L}\p{N}_@.+~()\-]+\.[A-Za-z0-9_-]+)(?::\s*|\s+)[Ll]?(\d+)(?:\s*(?:[-–—~]|至)\s*[Ll]?(\d+))?/gu;

function linkify(segment: string): string {
  return segment.replace(FILE_REFERENCE, (_match, path: string, line: string, end?: string) => {
    const label = `${path}:${line}${end ? `-${end}` : ''}`;
    const range = end ? `&end=${end}` : '';
    return `[${label}](omnicode-file://open?path=${encodeURIComponent(path)}&line=${line}${range})`;
  });
}

/**
 * Converts model-produced project references into the internal, allow-listed IDE link protocol.
 *
 * References may be written as `src/App.vue:148-169`, `src/App.vue 148–169`, or
 * `src/App.vue:L148-L169`. Inline-code references are deliberately converted too, while fenced
 * code remains literal so commands and code samples are never rewritten.
 */
export function fileLinkMarkdown(text: string): string {
  // Do not turn already-rendered links into nested Markdown links during history recovery.
  if (text.includes('omnicode-file://')) return text;

  return text.split(/(```[\s\S]*?```)/g).map((segment) => {
    if (segment.startsWith('```')) return segment;
    return segment.split(/(`[^`\n]+`)/g).map((inlineCode) => {
      if (!inlineCode.startsWith('`') || !inlineCode.endsWith('`')) return linkify(inlineCode);
      const content = inlineCode.slice(1, -1);
      const linked = linkify(content);
      return linked === content ? inlineCode : linked;
    }).join('');
  }).join('');
}
