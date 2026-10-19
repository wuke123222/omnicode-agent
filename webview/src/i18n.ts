export type UiLocale = 'zh-CN' | 'zh-TW' | 'en' | 'ja' | 'ko' | 'de' | 'fr' | 'es' | 'pt-BR' | 'ru';

const zh = {
  'nav.backChat': '返回聊天',
  'nav.newChat': '新建对话',
  'nav.history': '历史记录',
  'nav.settings': '设置',
  'app.workspace': 'AI 工作台',
  'status.running': '运行中',
  'status.cleanupPending': '正在清理',
  'status.ready': '空闲',
  'session.new': '新对话',
  'history.title': '历史记录',
  'history.description': '继续之前的项目会话。',
  'history.search': '搜索会话',
  'history.background': '正在后台运行 · 点击切换到此会话',
  'history.cleanupBackground': '上次任务仍在后台清理 · 点击查看',
  'history.cleanupPending': '正在清理',
  'history.messages': '{count} 条消息',
  'history.favorite': '收藏会话',
  'history.unfavorite': '取消收藏会话',
  'history.export': '导出会话',
  'history.fork': '分叉会话',
  'history.stopFirst': '请先停止此会话',
  'history.cleanupFirst': '后台清理完成后才能删除',
  'history.delete': '删除会话',
  'history.empty': '没有匹配的会话',
  'composer.placeholder': '输入任务；/plan 规划，/review 审阅，@ 引用文件，! 选提示词…',
  'composer.enterHint': 'Enter 发送，Shift+Enter 换行',
  'composer.permission': '命令权限',
  'composer.workspacePermission': '工作区权限',
  'composer.fullAccess': '完全访问',
  'composer.queue': '队列 {count}',
  'composer.queueSend': '排队发送',
  'composer.stop': '停止任务',
  'composer.send': '发送',
  'composer.refreshModels': '刷新可用模型',
} as const;

type TranslationKey = keyof typeof zh;

const en: Record<TranslationKey, string> = {
  'nav.backChat': 'Back to chat',
  'nav.newChat': 'New chat',
  'nav.history': 'History',
  'nav.settings': 'Settings',
  'app.workspace': 'AI workspace',
  'status.running': 'Running',
  'status.cleanupPending': 'Cleaning up',
  'status.ready': 'Idle',
  'session.new': 'New chat',
  'history.title': 'History',
  'history.description': 'Continue a previous project conversation.',
  'history.search': 'Search conversations',
  'history.background': 'Running in background · Click to switch',
  'history.cleanupBackground': 'Previous task is still cleaning up · Click to view',
  'history.cleanupPending': 'Cleaning up',
  'history.messages': '{count} messages',
  'history.favorite': 'Favorite conversation',
  'history.unfavorite': 'Remove favorite',
  'history.export': 'Export conversation',
  'history.fork': 'Fork conversation',
  'history.stopFirst': 'Stop this conversation first',
  'history.cleanupFirst': 'Wait for background cleanup before deleting',
  'history.delete': 'Delete conversation',
  'history.empty': 'No matching conversations',
  'composer.placeholder': 'Describe a task; use /plan, /review, @ files, or ! prompts…',
  'composer.enterHint': 'Enter to send, Shift+Enter for a new line',
  'composer.permission': 'Command permissions',
  'composer.workspacePermission': 'Workspace access',
  'composer.fullAccess': 'Full access',
  'composer.queue': 'Queue {count}',
  'composer.queueSend': 'Queue message',
  'composer.stop': 'Stop task',
  'composer.send': 'Send',
  'composer.refreshModels': 'Refresh available models',
};

export function normalizeLocale(value?: string | null): UiLocale {
  const normalized = value?.trim().toLowerCase() ?? '';
  // The host always sends its resolved locale. Keep a deterministic Chinese
  // fallback for tests and older cached WebViews that do not have this field.
  if (!normalized) return 'zh-CN';
  if (normalized.startsWith('zh-tw') || normalized.startsWith('zh-hk')) return 'zh-TW';
  if (normalized.startsWith('zh')) return 'zh-CN';
  if (normalized.startsWith('ja')) return 'ja';
  if (normalized.startsWith('ko')) return 'ko';
  if (normalized.startsWith('de')) return 'de';
  if (normalized.startsWith('fr')) return 'fr';
  if (normalized.startsWith('es')) return 'es';
  if (normalized.startsWith('pt')) return 'pt-BR';
  if (normalized.startsWith('ru')) return 'ru';
  return 'en';
}

export function createTranslator(locale: UiLocale) {
  const overlays: Partial<Record<UiLocale, Partial<Record<TranslationKey, string>>>> = {
    'zh-TW': { 'nav.backChat': '返回聊天', 'nav.newChat': '新增對話', 'nav.history': '歷史記錄', 'nav.settings': '設定', 'app.workspace': 'AI 工作區', 'status.running': '執行中', 'status.ready': '閒置', 'session.new': '新對話', 'history.title': '歷史記錄', 'history.search': '搜尋對話', 'composer.send': '傳送', 'composer.stop': '停止任務' },
    ja: { 'nav.backChat': 'チャットに戻る', 'nav.newChat': '新しいチャット', 'nav.history': '履歴', 'nav.settings': '設定', 'app.workspace': 'AI ワークスペース', 'status.running': '実行中', 'status.ready': '待機中', 'session.new': '新しいチャット', 'history.title': '履歴', 'history.search': '会話を検索', 'composer.send': '送信', 'composer.stop': 'タスクを停止' },
    ko: { 'nav.backChat': '채팅으로 돌아가기', 'nav.newChat': '새 채팅', 'nav.history': '기록', 'nav.settings': '설정', 'app.workspace': 'AI 작업 공간', 'status.running': '실행 중', 'status.ready': '대기 중', 'session.new': '새 채팅', 'history.title': '기록', 'history.search': '대화 검색', 'composer.send': '보내기', 'composer.stop': '작업 중지' },
    de: { 'nav.backChat': 'Zurück zum Chat', 'nav.newChat': 'Neuer Chat', 'nav.history': 'Verlauf', 'nav.settings': 'Einstellungen', 'app.workspace': 'KI-Arbeitsbereich', 'status.running': 'Läuft', 'status.ready': 'Inaktiv', 'session.new': 'Neuer Chat', 'history.title': 'Verlauf', 'history.search': 'Unterhaltungen suchen', 'composer.send': 'Senden', 'composer.stop': 'Aufgabe stoppen' },
    fr: { 'nav.backChat': 'Retour au chat', 'nav.newChat': 'Nouveau chat', 'nav.history': 'Historique', 'nav.settings': 'Paramètres', 'app.workspace': 'Espace IA', 'status.running': 'En cours', 'status.ready': 'Inactif', 'session.new': 'Nouveau chat', 'history.title': 'Historique', 'history.search': 'Rechercher des conversations', 'composer.send': 'Envoyer', 'composer.stop': 'Arrêter la tâche' },
    es: { 'nav.backChat': 'Volver al chat', 'nav.newChat': 'Nuevo chat', 'nav.history': 'Historial', 'nav.settings': 'Ajustes', 'app.workspace': 'Espacio de IA', 'status.running': 'En ejecución', 'status.ready': 'Inactivo', 'session.new': 'Nuevo chat', 'history.title': 'Historial', 'history.search': 'Buscar conversaciones', 'composer.send': 'Enviar', 'composer.stop': 'Detener tarea' },
    'pt-BR': { 'nav.backChat': 'Voltar ao chat', 'nav.newChat': 'Novo chat', 'nav.history': 'Histórico', 'nav.settings': 'Configurações', 'app.workspace': 'Espaço de IA', 'status.running': 'Em execução', 'status.ready': 'Ocioso', 'session.new': 'Novo chat', 'history.title': 'Histórico', 'history.search': 'Pesquisar conversas', 'composer.send': 'Enviar', 'composer.stop': 'Parar tarefa' },
    ru: { 'nav.backChat': 'Вернуться в чат', 'nav.newChat': 'Новый чат', 'nav.history': 'История', 'nav.settings': 'Настройки', 'app.workspace': 'Рабочая область ИИ', 'status.running': 'Выполняется', 'status.ready': 'Ожидание', 'session.new': 'Новый чат', 'history.title': 'История', 'history.search': 'Поиск диалогов', 'composer.send': 'Отправить', 'composer.stop': 'Остановить задачу' },
  };
  const dictionary: Record<TranslationKey, string> = locale === 'zh-CN'
    ? zh
    : { ...en, ...(overlays[locale] ?? {}) };
  return (key: TranslationKey, values: Record<string, string | number> = {}): string =>
    Object.entries(values).reduce(
      (text, [name, value]) => text.replaceAll(`{${name}}`, String(value)),
      dictionary[key],
    );
}
