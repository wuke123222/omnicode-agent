import { Plus, X } from 'lucide-react';

export type OpenSessionTab = {
  id: string;
  title: string;
  running: boolean;
};

export function SessionTabBar({ sessions, activeId, onSelect, onClose, onNew }: {
  sessions: OpenSessionTab[];
  activeId: string;
  onSelect: (id: string) => void;
  onClose: (id: string) => void;
  onNew: () => void;
}) {
  if (!sessions.length) return null;
  return <nav className="session-tabs" aria-label="打开的会话">
    <div className="session-tab-scroll">
      {sessions.map((session) => <div key={session.id} className={`session-tab ${session.id === activeId ? 'active' : ''}`}>
        <button className="session-tab-main" title={`切换到：${session.title || '新对话'}`} onClick={() => onSelect(session.id)}>
          <span className={session.running ? 'session-running-dot' : 'session-idle-dot'} />
          <span>{session.title || '新对话'}</span>
        </button>
        <button className="session-tab-close" aria-label={`关闭 ${session.title || '会话'} 标签`} title="关闭标签（任务继续运行）" onClick={() => onClose(session.id)}><X size={13} /></button>
      </div>)}
    </div>
    <button className="session-tab-new" aria-label="新建会话" title="新建会话" onClick={onNew}><Plus size={15} /></button>
  </nav>;
}
