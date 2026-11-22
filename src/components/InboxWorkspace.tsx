import { useState } from 'react';
import {
  CheckCircle2,
  Clock,
  CloudUpload,
  Inbox,
  MessageSquarePlus,
  Search,
  Smartphone,
  Wifi,
  WifiOff,
  HardDrive,
} from 'lucide-react';
import { INCOMING_SMS_PRESETS } from '../data/sampleSms';
import type { SmsMessage, SmsSyncStatus, SyncActivityItem } from '../types/sms';

interface InboxWorkspaceProps {
  messages: SmsMessage[];
  selectedId: string | null;
  onSelectMessage: (id: string) => void;
  autoBackupAllTime: boolean;
  onToggleAutoBackup: (next: boolean) => void;
  isPhoneOnline: boolean;
  onTogglePhoneOnline: (next: boolean) => void;
  isSyncing: boolean;
  onBackupSingle: (id: string) => Promise<void>;
  onBackupAllNow: () => Promise<void>;
  onSimulateIncoming: (sender: string, body: string, category: SmsMessage['category']) => Promise<void>;
  activities: SyncActivityItem[];
  onSwitchToCodeTab: (tab: 'php' | 'android') => void;
}

function StatusLabel({ status }: { status: SmsSyncStatus }) {
  if (status === 'synced') {
    return (
      <span className="inline-flex items-center gap-1.5 text-xs font-medium text-emerald-700 whitespace-nowrap shrink-0">
        <CheckCircle2 className="w-3.5 h-3.5" aria-hidden="true" />
        <span>Synced</span>
      </span>
    );
  }
  if (status === 'queued') {
    return (
      <span className="inline-flex items-center gap-1.5 text-xs font-medium text-amber-700 whitespace-nowrap shrink-0">
        <Clock className="w-3.5 h-3.5" aria-hidden="true" />
        <span>Queued (Offline)</span>
      </span>
    );
  }
  return (
    <span className="inline-flex items-center gap-1.5 text-xs font-medium text-slate-600 whitespace-nowrap shrink-0">
      <HardDrive className="w-3.5 h-3.5" aria-hidden="true" />
      <span>Local Only</span>
    </span>
  );
}

export function InboxWorkspace({
  messages,
  selectedId,
  onSelectMessage,
  autoBackupAllTime,
  onToggleAutoBackup,
  isPhoneOnline,
  onTogglePhoneOnline,
  isSyncing,
  onBackupSingle,
  onBackupAllNow,
  onSimulateIncoming,
  activities,
  onSwitchToCodeTab,
}: InboxWorkspaceProps) {
  const [filter, setFilter] = useState<'all' | SmsSyncStatus>('all');
  const [searchQuery, setSearchQuery] = useState('');
  const [showComposer, setShowComposer] = useState(false);
  const [customSender, setCustomSender] = useState('+91 98290 11223');
  const [customBody, setCustomBody] = useState(
    'Payment of Rs. 950.00 received from Amit via UPI Ref 74829103.'
  );
  const [customCategory, setCustomCategory] = useState<SmsMessage['category']>('Banking');

  const filteredMessages = messages.filter((msg) => {
    if (filter !== 'all' && msg.status !== filter) return false;
    if (!searchQuery.trim()) return true;
    const q = searchQuery.toLowerCase();
    return msg.sender.toLowerCase().includes(q) || msg.body.toLowerCase().includes(q);
  });

  const selectedMessage =
    messages.find((m) => m.id === selectedId) ?? filteredMessages[0] ?? null;

  const syncedCount = messages.filter((m) => m.status === 'synced').length;
  const queuedCount = messages.filter((m) => m.status === 'queued').length;
  const localCount = messages.filter((m) => m.status === 'local').length;

  const handleFirePreset = async () => {
    const preset =
      INCOMING_SMS_PRESETS[Math.floor(Math.random() * INCOMING_SMS_PRESETS.length)];
    await onSimulateIncoming(preset.sender, preset.body, preset.category);
  };

  const handleSubmitCustom = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!customSender.trim() || !customBody.trim()) return;
    await onSimulateIncoming(customSender.trim(), customBody.trim(), customCategory);
    setShowComposer(false);
  };

  return (
    <div className="space-y-5">
      {/* Workspace Toolbar: Controls for Auto-Backup Always On, Network Mode, and Incoming SMS */}
      <div className="bg-white border border-slate-200 rounded-xl p-4 sm:p-5">
        <div className="flex flex-col xl:flex-row xl:items-center justify-between gap-4">
          <div className="space-y-1">
            <h1 className="text-lg font-semibold text-slate-900 tracking-tight">
              Phone SMS Inbox & Offline Sync Queue
            </h1>
            <div className="flex flex-wrap items-center gap-2 text-xs text-slate-500 tabular-nums">
              <span>{messages.length} total SMS</span>
              <span aria-hidden="true">·</span>
              <span className="text-emerald-700 font-medium">{syncedCount} synced</span>
              <span aria-hidden="true">·</span>
              <span className="text-amber-700 font-medium">{queuedCount} queued offline</span>
              <span aria-hidden="true">·</span>
              <span>{localCount} local only</span>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2.5">
            {/* Auto-Backup All Time Switch */}
            <button
              type="button"
              role="switch"
              aria-checked={autoBackupAllTime}
              onClick={() => onToggleAutoBackup(!autoBackupAllTime)}
              className={`inline-flex items-center gap-2 px-3.5 py-2 rounded-lg text-xs font-medium border transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                autoBackupAllTime
                  ? 'bg-blue-50 border-blue-200 text-blue-900'
                  : 'bg-white border-slate-200 text-slate-700 hover:bg-slate-50'
              }`}
            >
              <span
                className={`w-7 h-4 rounded-full p-0.5 transition-colors flex items-center ${
                  autoBackupAllTime ? 'bg-blue-600 justify-end' : 'bg-slate-300 justify-start'
                }`}
              >
                <span className="w-3 h-3 rounded-full bg-white block" />
              </span>
              <span>Auto-Backup All Time: {autoBackupAllTime ? 'ON' : 'OFF'}</span>
            </button>

            {/* Phone Network Mode Simulator (Online / Offline) */}
            <button
              type="button"
              onClick={() => onTogglePhoneOnline(!isPhoneOnline)}
              className={`inline-flex items-center gap-1.5 px-3.5 py-2 rounded-lg text-xs font-medium border transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                isPhoneOnline
                  ? 'bg-emerald-50 border-emerald-200 text-emerald-800 hover:bg-emerald-100/70'
                  : 'bg-amber-50 border-amber-200 text-amber-800 hover:bg-amber-100/70'
              }`}
            >
              {isPhoneOnline ? (
                <>
                  <Wifi className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>Phone: Online</span>
                </>
              ) : (
                <>
                  <WifiOff className="w-3.5 h-3.5" aria-hidden="true" />
                  <span>Phone: Offline</span>
                </>
              )}
            </button>

            <button
              type="button"
              onClick={handleFirePreset}
              className="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-lg text-xs font-medium bg-slate-900 text-white hover:bg-slate-800 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
            >
              <MessageSquarePlus className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Receive Test SMS</span>
            </button>

            <button
              type="button"
              onClick={() => setShowComposer((prev) => !prev)}
              className="inline-flex items-center gap-1.5 px-3.5 py-2 rounded-lg text-xs font-medium bg-white border border-slate-200 text-slate-700 hover:bg-slate-50 transition-colors whitespace-nowrap shrink-0 cursor-pointer"
            >
              <span>{showComposer ? 'Close Custom' : 'Custom SMS'}</span>
            </button>
          </div>
        </div>

        {showComposer && (
          <form
            onSubmit={handleSubmitCustom}
            className="mt-4 pt-4 border-t border-slate-200 grid grid-cols-1 md:grid-cols-12 gap-3 items-end"
          >
            <div className="md:col-span-3">
              <label className="block text-xs font-medium text-slate-700 mb-1">
                Sender Number / ID
              </label>
              <input
                type="text"
                value={customSender}
                onChange={(e) => setCustomSender(e.target.value)}
                required
                className="w-full px-3 py-1.5 text-sm bg-slate-50 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:bg-white font-mono"
              />
            </div>
            <div className="md:col-span-2">
              <label className="block text-xs font-medium text-slate-700 mb-1">
                Category
              </label>
              <select
                value={customCategory}
                onChange={(e) => setCustomCategory(e.target.value as SmsMessage['category'])}
                className="w-full px-3 py-1.5 text-sm bg-slate-50 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:bg-white"
              >
                <option value="Banking">Banking</option>
                <option value="OTP">OTP</option>
                <option value="Personal">Personal</option>
                <option value="Delivery">Delivery</option>
              </select>
            </div>
            <div className="md:col-span-5">
              <label className="block text-xs font-medium text-slate-700 mb-1">
                SMS Text
              </label>
              <input
                type="text"
                value={customBody}
                onChange={(e) => setCustomBody(e.target.value)}
                required
                className="w-full px-3 py-1.5 text-sm bg-slate-50 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:bg-white"
              />
            </div>
            <div className="md:col-span-2">
              <button
                type="submit"
                className="w-full px-4 py-2 text-xs font-medium bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors whitespace-nowrap cursor-pointer"
              >
                Trigger SMS
              </button>
            </div>
          </form>
        )}

        {/* Clear explanation of browser sandbox vs Native Android APK */}
        <div className="mt-4 pt-3.5 border-t border-slate-100 flex flex-col sm:flex-row sm:items-center justify-between gap-2 text-xs text-slate-600">
          <div className="flex items-center gap-2">
            <Smartphone className="w-4 h-4 text-slate-400 shrink-0" aria-hidden="true" />
            <span>
              Web browsers cannot read device SMS directly for security. Use this console to test online/offline sync behavior, then deploy the{' '}
              <button
                type="button"
                onClick={() => onSwitchToCodeTab('php')}
                className="font-medium text-blue-600 hover:underline cursor-pointer"
              >
                PHP MySQL API
              </button>{' '}
              and{' '}
              <button
                type="button"
                onClick={() => onSwitchToCodeTab('android')}
                className="font-medium text-blue-600 hover:underline cursor-pointer"
              >
                Android Kotlin App
              </button>
              .
            </span>
          </div>
        </div>
      </div>

      {/* Main Split: Left SMS Inbox List + Right SMS Detail & Sync Log */}
      <div className="grid grid-cols-1 lg:grid-cols-12 gap-5 items-start">
        {/* SMS List */}
        <div className="lg:col-span-7 bg-white border border-slate-200 rounded-xl overflow-hidden">
          <div className="p-3.5 border-b border-slate-200 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
            <div className="flex items-center gap-1 p-1 bg-slate-100 rounded-lg overflow-x-auto">
              {(
                [
                  { id: 'all', label: 'All' },
                  { id: 'synced', label: 'Synced' },
                  { id: 'queued', label: 'Queued' },
                  { id: 'local', label: 'Local' },
                ] as const
              ).map((tab) => (
                <button
                  key={tab.id}
                  type="button"
                  onClick={() => setFilter(tab.id)}
                  className={`px-3 py-1 text-xs font-medium rounded-md transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                    filter === tab.id
                      ? 'bg-white text-slate-900 shadow-xs'
                      : 'text-slate-600 hover:text-slate-900'
                  }`}
                >
                  {tab.label}
                </button>
              ))}
            </div>

            <div className="relative w-full sm:w-56">
              <Search className="w-3.5 h-3.5 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" aria-hidden="true" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Filter SMS..."
                className="w-full pl-8 pr-3 py-1.5 text-xs bg-slate-50 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:bg-white"
              />
            </div>
          </div>

          {filteredMessages.length === 0 ? (
            <div className="p-10 text-center space-y-2">
              <Inbox className="w-8 h-8 text-slate-300 mx-auto" aria-hidden="true" />
              <div className="text-sm font-medium text-slate-800">No SMS match this filter</div>
              <p className="text-xs text-slate-500">
                Click Receive Test SMS above to simulate an incoming message.
              </p>
            </div>
          ) : (
            <div className="divide-y divide-slate-100 max-h-[560px] overflow-y-auto">
              {filteredMessages.map((msg) => {
                const isSelected = selectedMessage?.id === msg.id;
                return (
                  <button
                    key={msg.id}
                    type="button"
                    onClick={() => onSelectMessage(msg.id)}
                    className={`w-full text-left px-4 py-3.5 transition-colors cursor-pointer block ${
                      isSelected ? 'bg-blue-50/60' : 'hover:bg-slate-50'
                    }`}
                  >
                    <div className="flex items-center justify-between gap-3">
                      <div className="flex items-center gap-2 min-w-0">
                        <span className="text-sm font-semibold text-slate-900 truncate">
                          {msg.sender}
                        </span>
                        <span className="text-xs text-slate-400 shrink-0" aria-hidden="true">
                          ·
                        </span>
                        <span className="text-xs text-slate-500 shrink-0">{msg.category}</span>
                      </div>
                      <StatusLabel status={msg.status} />
                    </div>
                    <p className="mt-1 text-xs text-slate-600 line-clamp-2 leading-relaxed">
                      {msg.body}
                    </p>
                    <div className="mt-1.5 flex items-center gap-2 text-[11px] text-slate-400 font-mono tabular-nums">
                      <span>{new Date(msg.receivedAt).toLocaleString()}</span>
                      <span aria-hidden="true">·</span>
                      <span>Hash {msg.smsHash.slice(0, 10)}</span>
                    </div>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* Right Column: Selected SMS Inspector + Live Sync Activity */}
        <div className="lg:col-span-5 space-y-5">
          {selectedMessage ? (
            <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-4">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <div className="text-xs text-slate-500">
                    {selectedMessage.category} ·{' '}
                    <span className="font-mono tabular-nums">
                      {new Date(selectedMessage.receivedAt).toLocaleString()}
                    </span>
                  </div>
                  <h3 className="text-base font-semibold text-slate-900 mt-0.5 truncate">
                    {selectedMessage.sender}
                  </h3>
                </div>
                <StatusLabel status={selectedMessage.status} />
              </div>

              <div className="bg-slate-50 border border-slate-200 rounded-lg p-3.5 text-sm text-slate-800 leading-relaxed">
                {selectedMessage.body}
              </div>

              <div className="space-y-1.5 text-xs text-slate-600 border-t border-slate-100 pt-3">
                <div className="flex items-center justify-between gap-2">
                  <span>Deduplication Hash</span>
                  <span className="font-mono text-slate-800">
                    {selectedMessage.smsHash.slice(0, 18)}…
                  </span>
                </div>
                <div className="flex items-center justify-between gap-2">
                  <span>Server Sync Timestamp</span>
                  <span className="font-mono text-slate-800 tabular-nums">
                    {selectedMessage.syncedAt
                      ? new Date(selectedMessage.syncedAt).toLocaleString()
                      : 'Not backed up yet'}
                  </span>
                </div>
              </div>

              <div className="pt-1 flex items-center gap-2.5">
                {selectedMessage.status !== 'synced' ? (
                  <button
                    type="button"
                    onClick={() => onBackupSingle(selectedMessage.id)}
                    disabled={isSyncing}
                    className="flex-1 inline-flex items-center justify-center gap-1.5 px-4 py-2 rounded-lg text-xs font-medium bg-blue-600 text-white hover:bg-blue-700 transition-colors whitespace-nowrap cursor-pointer disabled:opacity-60"
                  >
                    <CloudUpload className="w-3.5 h-3.5" aria-hidden="true" />
                    <span>
                      {isPhoneOnline
                        ? 'Backup This SMS to Server'
                        : 'Queue SMS (Offline)'}
                    </span>
                  </button>
                ) : (
                  <div className="flex-1 text-center py-2 px-3 rounded-lg bg-emerald-50 border border-emerald-200 text-xs font-medium text-emerald-800">
                    Safely backed up on server
                  </div>
                )}

                <button
                  type="button"
                  onClick={onBackupAllNow}
                  disabled={isSyncing}
                  className="inline-flex items-center justify-center gap-1.5 px-3.5 py-2 rounded-lg text-xs font-medium bg-white border border-slate-200 text-slate-700 hover:bg-slate-50 transition-colors whitespace-nowrap shrink-0 cursor-pointer disabled:opacity-60"
                >
                  <span>Backup All</span>
                </button>
              </div>
            </div>
          ) : null}

          <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-3">
            <div className="flex items-center justify-between">
              <h3 className="text-sm font-semibold text-slate-900">Sync Activity Log</h3>
              <span className="text-xs text-slate-500 tabular-nums">
                {activities.length} events
              </span>
            </div>

            {activities.length === 0 ? (
              <p className="text-xs text-slate-500 py-2">
                Trigger an incoming SMS or click Backup All Now to observe online/offline queue sync events.
              </p>
            ) : (
              <div className="divide-y divide-slate-100 max-h-56 overflow-y-auto">
                {activities.map((item) => (
                  <div key={item.id} className="py-2.5 text-xs space-y-0.5">
                    <div className="flex items-center justify-between gap-2">
                      <span
                        className={`font-medium ${
                          item.outcome === 'success'
                            ? 'text-emerald-700'
                            : item.outcome === 'queued'
                            ? 'text-amber-700'
                            : 'text-red-700'
                        }`}
                      >
                        {item.action}
                      </span>
                      <span className="font-mono text-[11px] text-slate-400 tabular-nums">
                        {new Date(item.timestamp).toLocaleTimeString()}
                      </span>
                    </div>
                    <p className="text-slate-600 leading-relaxed">{item.detail}</p>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
