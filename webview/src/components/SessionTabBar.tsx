import { useState } from 'react';
import { Check, X } from 'lucide-react';

export type OpenSessionTab = {
  id: string;
  title: string;
  running: boolean;
};

export function SessionTabBar({ sessions, activeId, onSelect, onClose, onRename }: {
  sessions: OpenSessionTab[];
  activeId: string;
  onSelect: (id: string) => void;
  onClose: (id: string) => void;
  onRename: (id: string, title: string) => void;
}) {
  const [editing, setEditing] = useState<{ id: string; title: string } | null>(null);
  if (!sessions.length) return null;
  const finishRename = () => {
    if (!editing) return;
    const title = editing.title.trim();
    if (title) onRename(editing.id, title);
    setEditing(null);
  };
  return <nav className="session-tabs" aria-label="打开的会话">
    <div className="session-tab-scroll">
      {sessions.map((session) => <div key={session.id} className={`session-tab ${session.id === activeId ? 'active' : ''}`}>
        {editing?.id === session.id
          ? <form className="session-tab-rename" onSubmit={(event) => { event.preventDefault(); finishRename(); }}>
            <input aria-label="会话名称" autoFocus maxLength={120} value={editing.title} onChange={(event) => setEditing({ ...editing, title: event.target.value })}
              onKeyDown={(event) => { if (event.key === 'Escape') { event.preventDefault(); setEditing(null); } }} />
            <button type="submit" aria-label="保存会话名称"><Check size={13} /></button>
          </form>
          : <button className="session-tab-main" title={`切换到：${session.title || '新对话'} · 双击重命名`} onClick={() => onSelect(session.id)} onDoubleClick={() => setEditing({ id: session.id, title: session.title || '新对话' })}>
            <span className={session.running ? 'session-running-dot' : 'session-idle-dot'} />
            <span>{session.title || '新对话'}</span>
          </button>}
        <button className="session-tab-close" aria-label={`关闭 ${session.title || '会话'} 标签`} title="关闭标签（任务继续运行）" onClick={() => onClose(session.id)}><X size={13} /></button>
      </div>)}
    </div>
  </nav>;
}
