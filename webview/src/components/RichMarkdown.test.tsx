import { fireEvent, render, screen } from '@testing-library/react';
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

  it('keeps inline code and punctuation inside the surrounding sentence', () => {
    const { container } = render(<RichMarkdown text={'广告/活动：`campaign`、`advertisement`、`act_*`（例如 `act_code_bind`）。'} />);

    const paragraph = container.querySelector('p');
    expect(paragraph).toHaveTextContent('广告/活动：campaign、advertisement、act_*（例如 act_code_bind）。');
    expect(paragraph?.querySelectorAll('code')).toHaveLength(4);
    expect(paragraph?.querySelector('.hljs')).toBeNull();
    expect(container.querySelector('pre')).toBeNull();
  });

  it('refuses oversized Mermaid diagrams before invoking the renderer', () => {
    render(<RichMarkdown text={`\`\`\`mermaid\n${'A-->B;'.repeat(1_500)}\n\`\`\``} />);

    expect(screen.getByText('图表内容超过 8,000 字符安全上限')).toBeInTheDocument();
  });

  it('keeps the internal project-file protocol and sends the full range to the IDE', () => {
    render(<RichMarkdown text={'[src/页面/Overview.vue:148-169](omnicode-file://open?path=src%2F%E9%A1%B5%E9%9D%A2%2FOverview.vue&line=148&end=169)'} />);

    fireEvent.click(screen.getByRole('button', { name: 'src/页面/Overview.vue:148-169' }));

    const rawCommand = vi.mocked(window.omnicodeSend!).mock.calls[0]?.[0];
    expect(rawCommand).toBeDefined();
    const sent = JSON.parse(rawCommand!);
    expect(sent).toMatchObject({
      command: 'navigation.openFile',
      payload: { path: 'src/页面/Overview.vue', line: 148, end: 169 }
    });
  });
});
