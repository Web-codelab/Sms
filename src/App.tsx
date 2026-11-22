import { useCallback, useEffect, useMemo, useState } from 'react';
import { CloudUpload, RotateCcw, Server } from 'lucide-react';
import { CodeViewerPanel } from './components/CodeViewerPanel';
import { InboxWorkspace } from './components/InboxWorkspace';
import { ServerVaultPanel } from './components/ServerVaultPanel';
import { getAndroidCodeFiles, getPhpCodeFiles } from './data/codeTemplates';
import { INITIAL_SMS_SEED, computeSmsHash } from './data/sampleSms';
import type {
  EndpointConfig,
  ServerSmsRecord,
  SmsMessage,
  SyncActivityItem,
} from './types/sms';

type ActiveTab = 'inbox' | 'server' | 'php' | 'android';

const STORAGE_KEYS = {
  inbox: 'sms_vault_device_inbox_v1',
  autoBackup: 'sms_vault_auto_backup_v1',
  config: 'sms_vault_endpoint_config_v1',
};

const DEFAULT_CONFIG: EndpointConfig = {
  baseUrl: 'https://Webcodelab.in/WCL',
  apiKey: '',
  deviceId: 'android-pixel-demo',
};

export default function App() {
  const [activeTab, setActiveTab] = useState<ActiveTab>('inbox');
  const [messages, setMessages] = useState<SmsMessage[]>([]);
  const [inboxReady, setInboxReady] = useState(false);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [autoBackupAllTime, setAutoBackupAllTime] = useState<boolean>(() => {
    const saved = localStorage.getItem(STORAGE_KEYS.autoBackup);
    return saved === null ? true : saved === 'true';
  });
  const [isPhoneOnline, setIsPhoneOnline] = useState<boolean>(() =>
    typeof navigator !== 'undefined' ? navigator.onLine : true
  );
  const [isSyncing, setIsSyncing] = useState(false);
  const [serverRecords, setServerRecords] = useState<ServerSmsRecord[]>([]);
  const [serverLoading, setServerLoading] = useState(false);
  const [activities, setActivities] = useState<SyncActivityItem[]>([]);
  const [endpointConfig, setEndpointConfig] = useState<EndpointConfig>(() => {
    try {
      const saved = localStorage.getItem(STORAGE_KEYS.config);
      if (saved) {
        return { ...DEFAULT_CONFIG, ...JSON.parse(saved) };
      }
    } catch {
      // Ignore malformed storage
    }
    return DEFAULT_CONFIG;
  });

  const pushActivity = useCallback(
    (action: string, detail: string, outcome: SyncActivityItem['outcome']) => {
      setActivities((prev) =>
        [
          {
            id: `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
            timestamp: new Date().toISOString(),
            action,
            detail,
            outcome,
          },
          ...prev,
        ].slice(0, 30)
      );
    },
    []
  );

  const fetchServerRecords = useCallback(async () => {
    setServerLoading(true);
    try {
      const res = await fetch('/api/sms/server-records');
      if (res.ok) {
        const data = await res.json();
        setServerRecords(Array.isArray(data.records) ? data.records : []);
      }
    } catch {
      // Server unreachable while offline
    } finally {
      setServerLoading(false);
    }
  }, []);

  // Initialize local device inbox from localStorage or seed
  useEffect(() => {
    let cancelled = false;
    async function init() {
      try {
        const saved = localStorage.getItem(STORAGE_KEYS.inbox);
        if (saved) {
          const parsed = JSON.parse(saved) as SmsMessage[];
          if (Array.isArray(parsed) && parsed.length > 0 && !cancelled) {
            setMessages(parsed);
            setSelectedId(parsed[0].id);
            setInboxReady(true);
            return;
          }
        }
      } catch {
        // Fallback to seed
      }

      const seeded: SmsMessage[] = await Promise.all(
        INITIAL_SMS_SEED.map(async (item) => ({
          ...item,
          smsHash: await computeSmsHash(item.sender, item.body, item.receivedAt),
        }))
      );
      if (!cancelled) {
        setMessages(seeded);
        setSelectedId(seeded[0]?.id ?? null);
        setInboxReady(true);
      }
    }
    init();
    fetchServerRecords();
    return () => {
      cancelled = true;
    };
  }, [fetchServerRecords]);

  // Persist local device inbox & settings
  useEffect(() => {
    if (!inboxReady) return;
    localStorage.setItem(STORAGE_KEYS.inbox, JSON.stringify(messages));
  }, [messages, inboxReady]);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEYS.autoBackup, String(autoBackupAllTime));
  }, [autoBackupAllTime]);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEYS.config, JSON.stringify(endpointConfig));
  }, [endpointConfig]);

  // Listen to real browser online/offline transitions
  useEffect(() => {
    const handleOnline = () => setIsPhoneOnline(true);
    const handleOffline = () => setIsPhoneOnline(false);
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, []);

  const syncMessageBatch = useCallback(
    async (batch: SmsMessage[], reason: string) => {
      if (batch.length === 0) return;

      if (!isPhoneOnline) {
        const batchIds = new Set(batch.map((m) => m.id));
        setMessages((prev) =>
          prev.map((m) =>
            batchIds.has(m.id) && m.status !== 'synced' ? { ...m, status: 'queued' } : m
          )
        );
        pushActivity(
          'Queued Offline',
          `${batch.length} SMS saved locally while offline (${reason}). Will auto-upload when online.`,
          'queued'
        );
        return;
      }

      setIsSyncing(true);
      try {
        const res = await fetch('/api/sms/sync', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            deviceId: endpointConfig.deviceId.trim() || 'android-pixel-demo',
            messages: batch.map((m) => ({
              id: m.id,
              sender: m.sender,
              body: m.body,
              receivedAt: m.receivedAt,
              smsHash: m.smsHash,
            })),
          }),
        });

        if (!res.ok) {
          throw new Error(`Server returned HTTP ${res.status}`);
        }

        const data = await res.json();
        const syncedSet = new Set<string>(data.syncedIds || batch.map((m) => m.id));
        const syncedAt = data.syncedAt || new Date().toISOString();

        setMessages((prev) =>
          prev.map((m) =>
            syncedSet.has(m.id) ? { ...m, status: 'synced', syncedAt } : m
          )
        );

        pushActivity(
          'Server Backup Complete',
          `Uploaded ${data.insertedCount ?? batch.length} new SMS (${data.skippedDuplicates ?? 0} duplicates skipped) via ${reason}.`,
          'success'
        );
        await fetchServerRecords();
      } catch (err) {
        const batchIds = new Set(batch.map((m) => m.id));
        setMessages((prev) =>
          prev.map((m) =>
            batchIds.has(m.id) && m.status !== 'synced' ? { ...m, status: 'queued' } : m
          )
        );
        pushActivity(
          'Sync Deferred to Queue',
          err instanceof Error ? err.message : 'Network unavailable; kept in offline queue.',
          'error'
        );
      } finally {
        setIsSyncing(false);
      }
    },
    [isPhoneOnline, endpointConfig.deviceId, pushActivity, fetchServerRecords]
  );

  // Automatic Offline -> Online Queue Flush
  useEffect(() => {
    if (!inboxReady || !isPhoneOnline || isSyncing) return;

    const pending = messages.filter((m) =>
      autoBackupAllTime ? m.status !== 'synced' : m.status === 'queued'
    );

    if (pending.length > 0) {
      void syncMessageBatch(pending, 'Auto-Sync on Connection Restore');
    }
  }, [inboxReady, isPhoneOnline, autoBackupAllTime, messages, isSyncing, syncMessageBatch]);

  const handleBackupSingle = async (id: string) => {
    const target = messages.find((m) => m.id === id);
    if (!target) return;
    await syncMessageBatch([target], 'Single SMS Backup');
  };

  const handleBackupAllNow = async () => {
    const unsynced = messages.filter((m) => m.status !== 'synced');
    if (unsynced.length === 0) {
      pushActivity('All SMS Already Backed Up', 'Zero pending SMS left on device.', 'success');
      return;
    }
    await syncMessageBatch(unsynced, 'Backup All Now');
  };

  const handleSimulateIncoming = async (
    sender: string,
    body: string,
    category: SmsMessage['category']
  ) => {
    const receivedAt = new Date().toISOString();
    const smsHash = await computeSmsHash(sender, body, receivedAt);
    const newSms: SmsMessage = {
      id: `sms-${Date.now()}-${Math.random().toString(36).slice(2, 6)}`,
      sender,
      body,
      receivedAt,
      status: autoBackupAllTime ? (isPhoneOnline ? 'queued' : 'queued') : 'local',
      smsHash,
      category,
    };

    setMessages((prev) => [newSms, ...prev]);
    setSelectedId(newSms.id);

    if (autoBackupAllTime) {
      if (isPhoneOnline) {
        await syncMessageBatch([newSms], 'Instant Auto-Backup');
      } else {
        pushActivity(
          'Incoming SMS Queued Offline',
          `SMS from ${sender} stored offline. Will auto-backup when phone returns online.`,
          'queued'
        );
      }
    } else {
      pushActivity(
        'Incoming SMS Received',
        `SMS from ${sender} saved to local inbox (Auto-Backup is OFF).`,
        'queued'
      );
    }
  };

  const handleClearServer = async () => {
    await fetch('/api/sms/server-records', { method: 'DELETE' });
    setServerRecords([]);
    setMessages((prev) => prev.map((m) => ({ ...m, status: 'local', syncedAt: undefined })));
    pushActivity('Server Vault Cleared', 'Reset server table and marked device SMS as local.', 'queued');
  };

  const handleDeleteServerRecord = async (hash: string) => {
    await fetch(`/api/sms/server-records/${encodeURIComponent(hash)}`, {
      method: 'DELETE',
    });
    setServerRecords((prev) => prev.filter((r) => r.smsHash !== hash));
    setMessages((prev) =>
      prev.map((m) => (m.smsHash === hash ? { ...m, status: 'local', syncedAt: undefined } : m))
    );
  };

  const handleResetDemoInbox = async () => {
    const seeded: SmsMessage[] = await Promise.all(
      INITIAL_SMS_SEED.map(async (item) => ({
        ...item,
        smsHash: await computeSmsHash(item.sender, item.body, item.receivedAt),
      }))
    );
    setMessages(seeded);
    setSelectedId(seeded[0]?.id ?? null);
    pushActivity('Demo Inbox Reset', 'Restored initial 5 sample SMS messages.', 'queued');
  };

  const phpFiles = useMemo(() => getPhpCodeFiles(endpointConfig), [endpointConfig]);
  const androidFiles = useMemo(() => getAndroidCodeFiles(endpointConfig), [endpointConfig]);

  const syncedCount = messages.filter((m) => m.status === 'synced').length;
  const queuedCount = messages.filter((m) => m.status === 'queued').length;

  return (
    <div className="min-h-screen flex flex-col bg-slate-50 text-slate-900">
      {/* Strict 3-Zone Top Header */}
      <header className="bg-white border-b border-slate-200 sticky top-0 z-30">
        <div className="max-w-[1440px] mx-auto px-4 sm:px-6 h-16 flex items-center justify-between gap-8">
          {/* Zone 1: Single-line Wordmark */}
          <button
            type="button"
            onClick={() => setActiveTab('inbox')}
            className="text-lg font-bold tracking-tight text-slate-900 whitespace-nowrap shrink-0 cursor-pointer"
          >
            SMS Vault Sync
          </button>

          {/* Zone 2: 4 Clean Single-Line Nav Links */}
          <nav className="hidden md:flex items-center gap-6 text-sm font-medium">
            {(
              [
                { id: 'inbox', label: 'Inbox & Queue' },
                { id: 'server', label: 'Server Database' },
                { id: 'php', label: 'PHP & MySQL Code' },
                { id: 'android', label: 'Android App Code' },
              ] as const
            ).map((item) => (
              <button
                key={item.id}
                type="button"
                onClick={() => setActiveTab(item.id)}
                className={`py-1 border-b-2 transition-colors whitespace-nowrap shrink-0 cursor-pointer ${
                  activeTab === item.id
                    ? 'border-blue-600 text-slate-900'
                    : 'border-transparent text-slate-600 hover:text-slate-900'
                }`}
              >
                {item.label}
              </button>
            ))}
          </nav>

          {/* Zone 3: 1 Primary CTA */}
          <div className="flex items-center shrink-0">
            <button
              type="button"
              onClick={handleBackupAllNow}
              disabled={isSyncing}
              className="inline-flex items-center gap-2 px-4 py-2 rounded-lg text-xs font-medium bg-blue-600 text-white hover:bg-blue-700 transition-colors whitespace-nowrap shrink-0 cursor-pointer disabled:opacity-60"
            >
              <CloudUpload className="w-3.5 h-3.5" aria-hidden="true" />
              <span>{isSyncing ? 'Syncing...' : 'Backup All Now'}</span>
            </button>
          </div>
        </div>

        {/* Mobile Tab Row */}
        <div className="md:hidden border-t border-slate-100 px-4 py-2 flex items-center gap-2 overflow-x-auto">
          {(
            [
              { id: 'inbox', label: 'Inbox' },
              { id: 'server', label: 'Database' },
              { id: 'php', label: 'PHP Code' },
              { id: 'android', label: 'Android Code' },
            ] as const
          ).map((item) => (
            <button
              key={item.id}
              type="button"
              onClick={() => setActiveTab(item.id)}
              className={`px-3 py-1.5 rounded-md text-xs font-medium whitespace-nowrap shrink-0 cursor-pointer ${
                activeTab === item.id
                  ? 'bg-slate-900 text-white'
                  : 'text-slate-600 hover:bg-slate-100'
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>
      </header>

      {/* Desktop Sidebar + Main Viewport */}
      <div className="flex-1 max-w-[1440px] w-full mx-auto px-4 sm:px-6 py-6 grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        <aside className="lg:col-span-3 space-y-5">
          {/* Sync Metrics Summary */}
          <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-4">
            <h2 className="text-sm font-semibold text-slate-900">Sync Overview</h2>
            <div className="grid grid-cols-3 lg:grid-cols-1 gap-3">
              <div className="flex items-center justify-between text-xs py-1.5 border-b border-slate-100">
                <span className="text-slate-600">Synced SMS</span>
                <span className="font-mono font-semibold text-emerald-700 tabular-nums">
                  {syncedCount}
                </span>
              </div>
              <div className="flex items-center justify-between text-xs py-1.5 border-b border-slate-100">
                <span className="text-slate-600">Offline Queue</span>
                <span className="font-mono font-semibold text-amber-700 tabular-nums">
                  {queuedCount}
                </span>
              </div>
              <div className="flex items-center justify-between text-xs py-1.5">
                <span className="text-slate-600">Server Rows</span>
                <span className="font-mono font-semibold text-slate-900 tabular-nums">
                  {serverRecords.length}
                </span>
              </div>
            </div>
          </div>

          {/* cPanel Endpoint Settings */}
          <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-4">
            <div className="flex items-center gap-2">
              <Server className="w-4 h-4 text-slate-500" aria-hidden="true" />
              <h2 className="text-sm font-semibold text-slate-900">cPanel Target Config</h2>
            </div>

            <div className="space-y-3">
              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">
                  Folder Base URL
                </label>
                <input
                  type="url"
                  value={endpointConfig.baseUrl}
                  onChange={(e) =>
                    setEndpointConfig((prev) => ({ ...prev, baseUrl: e.target.value }))
                  }
                  placeholder="https://Webcodelab.in/WCL"
                  className="w-full px-3 py-1.5 text-xs font-mono bg-slate-50 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:bg-white"
                />
                <p className="mt-1 text-[11px] text-slate-500 leading-relaxed">
                  Files: <span className="font-mono">db.php</span> and{' '}
                  <span className="font-mono">backup_sms.php</span> inside this folder.
                </p>
              </div>

              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">
                  Device Identifier
                </label>
                <input
                  type="text"
                  value={endpointConfig.deviceId}
                  onChange={(e) =>
                    setEndpointConfig((prev) => ({ ...prev, deviceId: e.target.value }))
                  }
                  className="w-full px-3 py-1.5 text-xs font-mono bg-slate-50 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:bg-white"
                />
              </div>

              <div>
                <label className="block text-xs font-medium text-slate-700 mb-1">
                  App API Key (for PHP & Test)
                </label>
                <input
                  type="password"
                  value={endpointConfig.apiKey}
                  onChange={(e) =>
                    setEndpointConfig((prev) => ({ ...prev, apiKey: e.target.value }))
                  }
                  placeholder="Optional secret key"
                  className="w-full px-3 py-1.5 text-xs font-mono bg-slate-50 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:bg-white"
                />
              </div>
            </div>

            <div className="pt-2 border-t border-slate-100">
              <button
                type="button"
                onClick={handleResetDemoInbox}
                className="w-full inline-flex items-center justify-center gap-1.5 px-3 py-2 rounded-lg text-xs font-medium bg-slate-50 border border-slate-200 text-slate-700 hover:bg-slate-100 transition-colors cursor-pointer"
              >
                <RotateCcw className="w-3.5 h-3.5" aria-hidden="true" />
                <span>Reset Sample SMS Inbox</span>
              </button>
            </div>
          </div>
        </aside>

        <main className="lg:col-span-9 min-w-0">
          {activeTab === 'inbox' && (
            <InboxWorkspace
              messages={messages}
              selectedId={selectedId}
              onSelectMessage={setSelectedId}
              autoBackupAllTime={autoBackupAllTime}
              onToggleAutoBackup={setAutoBackupAllTime}
              isPhoneOnline={isPhoneOnline}
              onTogglePhoneOnline={setIsPhoneOnline}
              isSyncing={isSyncing}
              onBackupSingle={handleBackupSingle}
              onBackupAllNow={handleBackupAllNow}
              onSimulateIncoming={handleSimulateIncoming}
              activities={activities}
              onSwitchToCodeTab={(tab) => setActiveTab(tab)}
            />
          )}

          {activeTab === 'server' && (
            <ServerVaultPanel
              records={serverRecords}
              isLoading={serverLoading}
              endpointConfig={endpointConfig}
              onRefresh={fetchServerRecords}
              onClearServer={handleClearServer}
              onDeleteRecord={handleDeleteServerRecord}
            />
          )}

          {activeTab === 'php' && (
            <CodeViewerPanel
              title="cPanel PHP & MySQL Server Code"
              subtitle="Upload these files to your cPanel directory (e.g. public_html/WCL/). Uses PDO prepared statements, UTF-8MB4 for Hindi/emoji SMS, X-API-Key authentication, and SHA-256 deduplication."
              steps={[
                'Create a MySQL database & user in cPanel, then run schema.sql inside phpMyAdmin.',
                'Edit DB_NAME, DB_USER, DB_PASS, and SMS_SYNC_API_KEY in db.php and upload it with chmod 640.',
                'Upload backup_sms.php & list_sms.php in the same folder and verify with Server Database -> Test Endpoint.',
              ]}
              files={phpFiles}
              endpointConfig={endpointConfig}
            />
          )}

          {activeTab === 'android' && (
            <CodeViewerPanel
              title="Native Android Kotlin App Code (Offline + Auto-Sync)"
              subtitle="Complete Android Studio implementation using BroadcastReceiver for instant incoming SMS capture, Room SQLite for offline queuing, and WorkManager with NetworkType.CONNECTED for automatic sync when internet returns."
              steps={[
                'Create an Android Studio Kotlin project and add Room, WorkManager, and OkHttp dependencies from build.gradle.kts.',
                'Put SMS_SYNC_API_KEY in local.properties and add AndroidManifest.xml SMS permissions.',
                'Paste SmsLocalStore.kt, SmsReceiver.kt, SmsSyncWorker.kt, and MainActivity.kt, then build APK.',
              ]}
              files={androidFiles}
              endpointConfig={endpointConfig}
            />
          )}
        </main>
      </div>
    </div>
  );
}
