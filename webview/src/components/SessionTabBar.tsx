import { useState } from 'react';
import { Check, X } from 'lucide-react';

export type OpenSessionTab = {
  id: string;
  title: string;
  running: boolean;
  queuedCount?: number;
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
        <button className="session-tab-main" title={`切换到：${session.title || '新对话'}`} onClick={() => onSelect(session.id)}>
          <span className={session.running ? 'session-running-dot' : 'session-idle-dot'} />
          <span>{session.title || '新对话'}</span>
          {Boolean(session.queuedCount) && <small className="session-tab-queued" title={`${session.queuedCount} 条待发送消息`}>{session.queuedCount}</small>}
        </button>
        <button className="session-tab-close" aria-label={`关闭 ${session.title || '会话'} 标签`} title="关闭标签（任务继续运行）" onClick={() => onClose(session.id)}><X size={13} /></button>
      </div>)}
    </div>
  </nav>;
}
