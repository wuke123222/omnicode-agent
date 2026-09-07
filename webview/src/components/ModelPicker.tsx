import { useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties } from 'react';
import { AlertTriangle, ChevronDown, Search, Star } from 'lucide-react';

type Props = {
  providerId: string;
  value: string;
  models: string[];
  cli: boolean;
  unavailable: boolean;
  disabled?: boolean;
  onSelect: (model: string) => void;
};

function storedPins(providerId: string): string[] {
  try {
    const value = JSON.parse(localStorage.getItem(`omnicode.modelPins.${providerId}`) ?? '[]');
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string').slice(0, 40) : [];
  } catch {
    return [];
  }
}

/**
 * A viewport-safe CCGUI-style model picker. Native select popups are owned by JCEF/OS and can
 * open below a bottom-docked composer, so this list is rendered inside the page and opens up.
 */
export function ModelPicker({ providerId, value, models, cli, unavailable, disabled, onSelect }: Props) {
  const root = useRef<HTMLDivElement>(null);
  const input = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [pins, setPins] = useState<string[]>(() => storedPins(providerId));
  const [menuPosition, setMenuPosition] = useState<CSSProperties>();

  useEffect(() => setPins(storedPins(providerId)), [providerId]);
  useLayoutEffect(() => {
    if (!open) return;
    const placeMenu = () => {
      const trigger = root.current?.querySelector<HTMLElement>('.model-picker-trigger');
      if (!trigger) return;
      const bounds = trigger.getBoundingClientRect();
      const margin = 8;
      const width = Math.max(0, Math.min(420, window.innerWidth - margin * 2));
      const left = Math.max(margin, Math.min(bounds.left, window.innerWidth - margin - width));
      setMenuPosition({
        position: 'fixed',
        left,
        right: 'auto',
        bottom: Math.max(margin, window.innerHeight - bounds.top + 6),
        width,
        maxHeight: Math.max(96, Math.min(390, bounds.top - 14))
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
    requestAnimationFrame(() => input.current?.focus());
    return () => {
      cancelAnimationFrame(frame);
      document.removeEventListener('mousedown', close);
      window.removeEventListener('resize', placeMenu);
      window.removeEventListener('scroll', placeMenu, true);
      observer?.disconnect();
    };
  }, [open]);

  const entries = useMemo(() => {
    const automatic = cli ? ['auto'] : [];
    const all = [...pins, value, ...automatic, ...models]
      .map((item) => item.trim())
      .filter((item, index, values) => Boolean(item) && values.indexOf(item) === index);
    const needle = query.trim().toLowerCase();
    return all.filter((item) => !needle || item.toLowerCase().includes(needle))
      .sort((left, right) => Number(pins.includes(right)) - Number(pins.includes(left)) || left.localeCompare(right));
  }, [cli, models, pins, query, value]);

  const togglePin = (model: string) => {
    const next = pins.includes(model) ? pins.filter((item) => item !== model) : [model, ...pins].slice(0, 40);
    setPins(next);
    try { localStorage.setItem(`omnicode.modelPins.${providerId}`, JSON.stringify(next)); } catch { /* preference only */ }
  };

  return <div ref={root} className={`model-picker ${unavailable ? 'unavailable' : ''}`}>
    <button
      type="button"
      className={`model-picker-trigger ${unavailable ? 'unavailable' : ''}`}
      aria-label="当前模型"
      aria-haspopup="listbox"
      aria-expanded={open}
      disabled={disabled}
      title="当前模型"
      onClick={() => { setQuery(''); setOpen((current) => !current); }}
    >
      {unavailable && <AlertTriangle size={14} />}
      <span>{cli && value === 'auto' ? '自动（CLI 默认）' : value || '选择模型'}</span>
      <ChevronDown size={15} />
    </button>
    {open && <div className="model-picker-menu" style={menuPosition} role="listbox" aria-label="模型列表">
      <label className="model-picker-search"><Search size={14} /><input ref={input} value={query} onChange={(event) => setQuery(event.target.value)} placeholder="搜索模型" onKeyDown={(event) => { if (event.key === 'Escape') setOpen(false); }} /></label>
      <div className="model-picker-list">
        {entries.map((model) => {
          const stale = unavailable && model === value;
          return <div className={model === value ? 'selected' : ''} key={model}>
          <button type="button" role="option" aria-selected={model === value} disabled={stale} onClick={() => { onSelect(model); setOpen(false); }}>
            <span>{stale ? `⚠ ${model}（已不可用）` : cli && model === 'auto' ? '自动（CLI 默认）' : model}</span>
            {model === value && <small>当前</small>}
          </button>
          <button type="button" className={`model-pin ${pins.includes(model) ? 'pinned' : ''}`} aria-label={`${pins.includes(model) ? '取消收藏' : '收藏'} ${model}`} title={pins.includes(model) ? '取消收藏' : '收藏模型'} onClick={() => togglePin(model)}><Star size={13} /></button>
        </div>})}
        {!entries.length && <p>没有匹配的模型</p>}
      </div>
    </div>}
  </div>;
}
