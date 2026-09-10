import { render, screen } from '@testing-library/react';
import { RichMarkdown } from './RichMarkdown';

describe('RichMarkdown', () => {
  beforeEach(() => {
    window.omnicodeSend = vi.fn();
    window.__OMNICODE_PAGE_GENERATION__ = 7;
  });

  it('renders fenced code with bounded syntax highlighting and formulas with KaTeX', async () => {
    const { container } = render(<RichMarkdown text={'```kotlin\nval answer = 42\n```\n\n$E = mc^2$'} />);

    expect(container.querySelector('code.hljs.language-kotlin')).toBeInTheDocument();
    expect(container.querySelector('.katex')).toBeInTheDocument();
  });

  it('refuses oversized Mermaid diagrams before invoking the renderer', () => {
    render(<RichMarkdown text={`\`\`\`mermaid\n${'A-->B;'.repeat(1_500)}\n\`\`\``} />);

    expect(screen.getByText('图表内容超过 8,000 字符安全上限')).toBeInTheDocument();
  });
});
