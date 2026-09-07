export type UiLocale = 'zh-CN' | 'en';

const zh = {
  'nav.backChat': '返回聊天',
  'nav.newChat': '新建对话',
  'nav.history': '历史记录',
  'nav.settings': '设置',
  'app.workspace': 'AI 工作台',
  'status.running': '运行中',
  'status.ready': '就绪',
  'session.new': '新对话',
  'history.title': '历史记录',
  'history.description': '继续之前的项目会话。',
  'history.search': '搜索会话',
  'history.background': '正在后台运行 · 点击切换到此会话',
  'history.messages': '{count} 条消息',
  'history.favorite': '收藏会话',
  'history.unfavorite': '取消收藏会话',
  'history.export': '导出会话',
  'history.fork': '分叉会话',
  'history.stopFirst': '请先停止此会话',
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
  'status.ready': 'Ready',
  'session.new': 'New chat',
  'history.title': 'History',
  'history.description': 'Continue a previous project conversation.',
  'history.search': 'Search conversations',
  'history.background': 'Running in background · Click to switch',
  'history.messages': '{count} messages',
  'history.favorite': 'Favorite conversation',
  'history.unfavorite': 'Remove favorite',
  'history.export': 'Export conversation',
  'history.fork': 'Fork conversation',
  'history.stopFirst': 'Stop this conversation first',
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
  return value?.trim().toLowerCase().startsWith('en') ? 'en' : 'zh-CN';
}

export function createTranslator(locale: UiLocale) {
  const dictionary = locale === 'en' ? en : zh;
  return (key: TranslationKey, values: Record<string, string | number> = {}): string =>
    Object.entries(values).reduce(
      (text, [name, value]) => text.replaceAll(`{${name}}`, String(value)),
      dictionary[key],
    );
}
