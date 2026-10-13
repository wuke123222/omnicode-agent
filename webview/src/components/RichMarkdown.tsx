import { useEffect, useId, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import ReactMarkdown, { defaultUrlTransform } from 'react-markdown';
import rehypeKatex from 'rehype-katex';
import remarkGfm from 'remark-gfm';
import remarkMath from 'remark-math';
import hljs from 'highlight.js/lib/core';
import bash from 'highlight.js/lib/languages/bash';
import css from 'highlight.js/lib/languages/css';
import java from 'highlight.js/lib/languages/java';
import javascript from 'highlight.js/lib/languages/javascript';
import json from 'highlight.js/lib/languages/json';
import kotlin from 'highlight.js/lib/languages/kotlin';
import markdown from 'highlight.js/lib/languages/markdown';
import python from 'highlight.js/lib/languages/python';
import sql from 'highlight.js/lib/languages/sql';
import typescript from 'highlight.js/lib/languages/typescript';
import xml from 'highlight.js/lib/languages/xml';
import 'katex/dist/katex.min.css';
import { sendCommand } from '../bridge';

const languages = { bash, css, java, javascript, json, kotlin, markdown, python, sql, typescript, xml };
Object.entries(languages).forEach(([name, grammar]) => hljs.registerLanguage(name, grammar));
hljs.registerAliases(['sh', 'shell', 'zsh'], { languageName: 'bash' });
hljs.registerAliases(['js', 'jsx'], { languageName: 'javascript' });
hljs.registerAliases(['ts', 'tsx'], { languageName: 'typescript' });
hljs.registerAliases(['html', 'vue'], { languageName: 'xml' });

/**
 * Recent remote-JCEF builds can briefly expose an about:blank document while React commits an
 * already-loaded page. Mermaid pulls Cytoscape, which reads documentElement during module
 * initialization. Waiting here keeps an optional diagram from producing a renderer-level error
 * (and, in the worst case, taking the chat bootstrap down with it).
 */
function waitForDocumentRoot(): Promise<void> {
  return new Promise((resolve, reject) => {
    const deadline = Date.now() + 5_000;
    const check = () => {
      if (document.documentElement) {
        resolve();
      } else if (Date.now() >= deadline) {
        reject(new Error('文档尚未就绪，暂无法渲染图表'));
      } else {
        window.setTimeout(check, 16);
      }
    };
    check();
  });
}

function MermaidDiagram({ source }: { source: string }) {
  const reactId = useId().replace(/[^A-Za-z0-9_-]/g, '');
  const [svg, setSvg] = useState('');
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    setSvg(''); setError('');
    if (source.length > 8_000) {
      setError('图表内容超过 8,000 字符安全上限');
      return () => { active = false; };
    }
    void waitForDocumentRoot().then(() => import('mermaid')).then(async ({ default: mermaid }) => {
      mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'dark', suppressErrorRendering: true });
      const rendered = await mermaid.render(`omnicode-${reactId}`, source);
      if (active) setSvg(rendered.svg);
    }).catch((reason) => {
      if (active) setError(reason instanceof Error ? reason.message : '图表渲染失败');
    });
    return () => { active = false; };
  }, [reactId, source]);
  if (error) return <pre className="diagram-error"><code>{source}</code><small>{error}</small></pre>;
  if (!svg) return <div className="diagram-loading">正在渲染图表…</div>;
  return <div className="mermaid-diagram" dangerouslySetInnerHTML={{ __html: svg }} />;
}

function CodeBlock({ className, children }: { className?: string; children: ReactNode }) {
  const source = String(children ?? '').replace(/\n$/, '');
  const language = className?.replace(/^language-/, '').toLowerCase() ?? '';
  const html = useMemo(() => {
    if (source.length > 100_000) return '';
    const normalized = hljs.getLanguage(language) ? language : '';
    return normalized ? hljs.highlight(source, { language: normalized }).value : hljs.highlightAuto(source).value;
  }, [language, source]);
  if (language === 'mermaid') return <MermaidDiagram source={source} />;
  if (!html) return <code className={className}>{source}</code>;
  return <code className={`hljs ${className ?? ''}`} dangerouslySetInnerHTML={{ __html: html }} />;
}

export function RichMarkdown({ text }: { text: string }) {
  return <ReactMarkdown
    remarkPlugins={[remarkGfm, remarkMath]}
    rehypePlugins={[[rehypeKatex, { strict: 'warn', trust: false }]]}
    // ReactMarkdown drops unknown protocols by default. This protocol is local-only and is
    // validated again by the IntelliJ bridge before a project file can be opened.
    urlTransform={(url) => url.startsWith('omnicode-file://open?') ? url : defaultUrlTransform(url)}
    components={{
      a: ({ href = '', children }) => {
        if (href.startsWith('omnicode-file://open?')) {
          return <button className="file-link" onClick={() => {
            const url = new URL(href);
            if (url.protocol !== 'omnicode-file:' || url.hostname !== 'open') return;
            const path = url.searchParams.get('path')?.trim() ?? '';
            if (!path) return;
            const line = Math.max(1, Number(url.searchParams.get('line') ?? 1) || 1);
            const requestedEnd = Number(url.searchParams.get('end') ?? line);
            sendCommand('navigation.openFile', {
              path,
              line,
              end: Number.isFinite(requestedEnd) ? Math.max(line, requestedEnd) : line
            });
          }}>{children}</button>;
        }
        return <a href={href} onClick={(event) => {
          event.preventDefault();
          sendCommand('navigation.openExternal', { url: href });
        }}>{children}</a>;
      },
      code: ({ children, className }) => <CodeBlock className={className}>{children}</CodeBlock>
    }}
  >{text}</ReactMarkdown>;
}
