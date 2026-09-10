import { useLayoutEffect, useRef, useState } from 'react';
import type { CSSProperties } from 'react';
import { ChevronDown } from 'lucide-react';

export function ComposerChoice({
  className, title, value, options, disabled, onChange
}: {
  className: string;
  title: string;
  value: string;
  options: Array<{ value: string; label: string }>;
  disabled?: boolean;
  onChange: (value: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [menuPosition, setMenuPosition] = useState<CSSProperties>();
  const root = useRef<HTMLDivElement>(null);
  const selected = options.find((item) => item.value === value) ?? options[0];

  useLayoutEffect(() => {
    if (!open) return;
    const placeMenu = () => {
      const anchor = root.current;
      const menu = anchor?.querySelector<HTMLElement>('.composer-choice-menu');
      if (!anchor || !menu) return;
      const bounds = anchor.getBoundingClientRect();
      const margin = 8;
      const measuredWidth = Math.max(
        bounds.width,
        menu.getBoundingClientRect().width,
        ...Array.from(menu.querySelectorAll<HTMLElement>('button')).map((button) => button.scrollWidth + 12)
      );
      const width = Math.max(0, Math.min(measuredWidth, window.innerWidth - margin * 2));
      const left = Math.max(margin, Math.min(bounds.left, window.innerWidth - margin - width));
      setMenuPosition({
        position: 'fixed', left, right: 'auto',
        bottom: Math.max(margin, window.innerHeight - bounds.top + 6),
        width,
        minWidth: Math.min(bounds.width, width),
        maxWidth: Math.max(0, window.innerWidth - margin * 2),
        maxHeight: Math.max(72, Math.min(260, bounds.top - 14))
      });
    };
    const close = (event: MouseEvent) => {
      if (root.current && !root.current.contains(event.target as Node)) setOpen(false);
    };
    placeMenu();
    const frame = requestAnimationFrame(placeMenu);
    document.addEventListener('mousedown', close);
    window.addEventListener('resize', placeMenu);
    window.addEventListener('scroll', placeMenu, true);
    const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(placeMenu);
    if (root.current) observer?.observe(root.current);
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener('mousedown', close);
      window.removeEventListener('resize', placeMenu);
      window.removeEventListener('scroll', placeMenu, true);
      observer?.disconnect();
    };
  }, [open]);

  return <div ref={root} className={`composer-choice ${className}`}>
    <button
      type="button"
      className="composer-choice-trigger"
      title={title}
      aria-label={title}
      aria-haspopup="listbox"
      aria-expanded={open}
      disabled={disabled}
      onClick={() => setOpen((current) => !current)}
      onKeyDown={(event) => {
        if (event.key === 'Escape') setOpen(false);
        if (event.key === 'ArrowDown' && !open) { event.preventDefault(); setOpen(true); }
      }}
    >
      <span>{selected?.label ?? value}</span><ChevronDown size={15} />
    </button>
    {open && <div className="composer-choice-menu" style={menuPosition} role="listbox" aria-label={title}>
      {options.map((item) => <button
        type="button"
        role="option"
        aria-selected={item.value === value}
        className={item.value === value ? 'selected' : ''}
        key={item.value}
        onClick={() => { onChange(item.value); setOpen(false); }}
      >{item.label}</button>)}
    </div>}
  </div>;
}
