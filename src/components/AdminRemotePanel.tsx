import { useCallback, useEffect, useState } from 'react';
import { Bell, Lock, RefreshCw, Send, ShieldCheck, Trash2 } from 'lucide-react';

interface DeviceItem {
  deviceId: string;
  deviceName: string;
  smsPermission: boolean;
  contactsPermission: boolean;
  smsAutoEnabled: boolean;
  contactsAutoEnabled: boolean;
  lastSmsSyncAt: string | null;
  lastContactsSyncAt: string | null;
  lastSeenAt: string;
  latestCommand: {
    commandUid: string;
    syncType: string;
    status: string;
    createdAt: string;
  } | null;
}

interface LogItem {
  id: number;
  deviceId: string;
  syncType: string;
  triggerSource: string;
  status: string;
  itemsSynced: number;
  errorMessage: string | null;
  createdAt: string;
}

interface AnnouncementItem {
  id: number;
  announcementUid: string;
  title: string;
  message: string;
  targetType: 'all' | 'selected';
  targetDevices: string[];
  createdAt: string;
  readCount: number;
}

export function AdminRemotePanel() {
  const [token, setToken] = useState<string | null>(null);
  const [needsClaim, setNeedsClaim] = useState(false);
  const [passcodeInput, setPasscodeInput] = useState('');
  const [authError, setAuthError] = useState('');

  const [devices, setDevices] = useState<DeviceItem[]>([]);
  const [logs, setLogs] = useState<LogItem[]>([]);
  const [announcements, setAnnouncements] = useState<AnnouncementItem[]>([]);
  const [intervalMinutes, setIntervalMinutes] = useState(15);
  const [remoteSyncEnabled, setRemoteSyncEnabled] = useState(true);
  const [selectedDeviceIds, setSelectedDeviceIds] = useState<string[]>([]);
  const [syncType, setSyncType] = useState<'both' | 'sms' | 'contacts'>('both');

  const [annTitle, setAnnTitle] = useState('');
  const [annMessage, setAnnMessage] = useState('');
  const [annTargetType, setAnnTargetType] = useState<'all' | 'selected'>('all');
  const [statusMessage, setStatusMessage] = useState('');

  useEffect(() => {
    fetch('/api/admin/auth/status')
      .then((r) => r.json())
      .then((data) => {
        setNeedsClaim(Boolean(data.needsClaim));
      })
      .catch(() => {});
  }, []);

  const fetchOverview = useCallback(async (authToken: string) => {
    const res = await fetch('/api/admin/overview', {
      headers: { Authorization: `Bearer ${authToken}` },
    });
    if (res.status === 401) {
      setToken(null);
      return;
    }
    if (!res.ok) return;
    const data = await res.json();
    setDevices(Array.isArray(data.devices) ? data.devices : []);
    setLogs(Array.isArray(data.logs) ? data.logs : []);
    setAnnouncements(Array.isArray(data.announcements) ? data.announcements : []);
    if (data.settings) {
      setIntervalMinutes(Number(data.settings.autoSyncIntervalMinutes) || 15);
      setRemoteSyncEnabled(Boolean(data.settings.remoteSyncEnabled));
    }
  }, []);

  useEffect(() => {
    if (token) {
      void fetchOverview(token);
    }
  }, [token, fetchOverview]);

  const handleAuthSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setAuthError('');
    const endpoint = needsClaim ? '/api/admin/auth/claim' : '/api/admin/auth/login';
    try {
      const res = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ passcode: passcodeInput }),
      });
      const data = await res.json();
      if (!res.ok) {
        setAuthError(data.error || 'Authentication failed');
        return;
      }
      setPasscodeInput('');
      setNeedsClaim(false);
      setToken(data.token);
    } catch {
      setAuthError('Network error while signing in.');
    }
  };

  const toggleSelectDevice = (id: string) => {
    setSelectedDeviceIds((prev) =>
      prev.includes(id) ? prev.filter((item) => item !== id) : [...prev, id]
    );
  };

  const handleDispatchSync = async (
    targetMode: 'single' | 'multiple' | 'all',
    singleId?: string
  ) => {
    if (!token) return;
    const res = await fetch('/api/admin/sync-commands', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        syncType,
        targetMode,
        deviceIds: targetMode === 'single' && singleId ? [singleId] : selectedDeviceIds,
      }),
    });
    const data = await res.json();
    if (!res.ok) {
      setStatusMessage(data.error || 'Could not queue command.');
    } else {
      setStatusMessage(
        `Queued ${data.queued} authorized device(s), skipped ${data.skipped} without permission.`
      );
      await fetchOverview(token);
    }
  };

  const handleSimulatePoll = async (deviceId: string) => {
    if (!token) return;
    const res = await fetch('/api/device/poll', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId }),
    });
    const data = await res.json();
    setStatusMessage(
      `Device ${deviceId} polled server: ${data.executedCommands ?? 0} pending command(s) processed.`
    );
    await fetchOverview(token);
  };

  const handleTogglePermission = async (
    deviceId: string,
    field: 'smsPermission' | 'contactsPermission',
    value: boolean
  ) => {
    if (!token) return;
    await fetch('/api/admin/devices/permissions', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ deviceId, [field]: value }),
    });
    await fetchOverview(token);
  };

  const handleSaveSettings = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!token) return;
    await fetch('/api/admin/settings', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        autoSyncIntervalMinutes: intervalMinutes,
        remoteSyncEnabled,
      }),
    });
    setStatusMessage(`Saved auto-sync interval (${intervalMinutes} min).`);
    await fetchOverview(token);
  };

  const handleCreateAnnouncement = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!token) return;
    const res = await fetch('/api/admin/announcements', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({
        title: annTitle,
        message: annMessage,
        targetType: annTargetType,
        targetDevices: selectedDeviceIds,
      }),
    });
    const data = await res.json();
    if (!res.ok) {
      setStatusMessage(data.error || 'Could not publish announcement.');
      return;
    }
    setAnnTitle('');
    setAnnMessage('');
    setStatusMessage('Announcement published to target devices.');
    await fetchOverview(token);
  };

  const handleDeleteAnnouncement = async (id: number) => {
    if (!token) return;
    await fetch(`/api/admin/announcements/${id}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${token}` },
    });
    await fetchOverview(token);
  };

  if (!token) {
    return (
      <div className="bg-white border border-slate-200 rounded-xl p-6 max-w-md mx-auto">
        <div className="flex items-center gap-2 mb-3">
          <Lock className="w-4 h-4 text-blue-600" />
          <h2 className="text-base font-bold text-slate-900">
            {needsClaim ? 'First-Run Admin Setup' : 'Admin Console Sign In'}
          </h2>
        </div>
        <p className="text-xs text-slate-600 mb-4">
          {needsClaim
            ? 'Set an admin passcode (min 6 characters) to unlock Remote Sync & Announcements preview.'
            : 'Enter your Admin Passcode to manage Remote Device Sync & Announcements.'}
        </p>
        <form onSubmit={handleAuthSubmit} className="space-y-3">
          <input
            type="password"
            required
            minLength={6}
            value={passcodeInput}
            onChange={(e) => setPasscodeInput(e.target.value)}
            placeholder={needsClaim ? 'Create admin passcode' : 'Enter admin passcode'}
            className="w-full px-3 py-2 text-sm border border-slate-200 rounded-lg"
          />
          {authError && <p className="text-xs font-medium text-red-600">{authError}</p>}
          <button
            type="submit"
            className="w-full py-2 px-4 rounded-lg bg-blue-600 hover:bg-blue-700 text-white text-sm font-semibold cursor-pointer"
          >
            {needsClaim ? 'Claim & Unlock Admin Panel' : 'Unlock Admin Panel'}
          </button>
        </form>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {statusMessage && (
        <div className="bg-blue-50 border border-blue-200 text-blue-900 text-xs font-medium px-4 py-2.5 rounded-xl flex items-center justify-between">
          <span>{statusMessage}</span>
          <button
            type="button"
            onClick={() => setStatusMessage('')}
            className="text-blue-700 font-bold cursor-pointer"
          >
            Dismiss
          </button>
        </div>
      )}

      {/* Sync Settings Card */}
      <form
        onSubmit={handleSaveSettings}
        className="bg-white border border-slate-200 rounded-xl p-5 flex flex-wrap items-end gap-4"
      >
        <div className="flex-1 min-w-[200px]">
          <label className="block text-xs font-semibold text-slate-700 mb-1">
            Automatic WorkManager Interval
          </label>
          <select
            value={intervalMinutes}
            onChange={(e) => setIntervalMinutes(Number(e.target.value))}
            className="w-full px-3 py-2 text-xs border border-slate-200 rounded-lg bg-slate-50"
          >
            <option value={15}>Every 15 Minutes (Default)</option>
            <option value={30}>Every 30 Minutes</option>
            <option value={60}>Every 60 Minutes</option>
            <option value={120}>Every 120 Minutes</option>
          </select>
        </div>
        <label className="inline-flex items-center gap-2 text-xs font-semibold text-slate-700 pb-2">
          <input
            type="checkbox"
            checked={remoteSyncEnabled}
            onChange={(e) => setRemoteSyncEnabled(e.target.checked)}
          />
          <span>Remote Admin Sync Enabled</span>
        </label>
        <button
          type="submit"
          className="px-4 py-2 rounded-lg bg-slate-900 hover:bg-slate-800 text-white text-xs font-semibold cursor-pointer"
        >
          Save Schedule
        </button>
      </form>

      {/* Remote Device Sync */}
      <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div className="flex items-center gap-2">
            <ShieldCheck className="w-4 h-4 text-blue-600" />
            <h3 className="text-sm font-bold text-slate-900">
              Remote Device Sync (Permission-Guarded)
            </h3>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <select
              value={syncType}
              onChange={(e) => setSyncType(e.target.value as 'both' | 'sms' | 'contacts')}
              className="px-3 py-1.5 text-xs border border-slate-200 rounded-lg bg-slate-50"
            >
              <option value="both">SMS + Contacts</option>
              <option value="sms">SMS Only</option>
              <option value="contacts">Contacts Only</option>
            </select>
            <button
              type="button"
              onClick={() => handleDispatchSync('multiple')}
              className="px-3 py-1.5 rounded-lg bg-blue-600 text-white text-xs font-semibold cursor-pointer"
            >
              Sync Selected ({selectedDeviceIds.length})
            </button>
            <button
              type="button"
              onClick={() => handleDispatchSync('all')}
              className="px-3 py-1.5 rounded-lg bg-slate-900 text-white text-xs font-semibold cursor-pointer"
            >
              Sync All Devices
            </button>
          </div>
        </div>

        <div className="overflow-x-auto">
          <table className="w-full text-xs text-left">
            <thead>
              <tr className="border-b border-slate-200 text-slate-500">
                <th className="py-2 pr-2">Pick</th>
                <th className="py-2 pr-2">Device</th>
                <th className="py-2 pr-2">User Permissions (Toggle to Test)</th>
                <th className="py-2 pr-2">Last Sync</th>
                <th className="py-2 pr-2">Command Status</th>
                <th className="py-2">Actions</th>
              </tr>
            </thead>
            <tbody>
              {devices.map((d) => (
                <tr key={d.deviceId} className="border-b border-slate-100">
                  <td className="py-2.5 pr-2">
                    <input
                      type="checkbox"
                      checked={selectedDeviceIds.includes(d.deviceId)}
                      onChange={() => toggleSelectDevice(d.deviceId)}
                    />
                  </td>
                  <td className="py-2.5 pr-2">
                    <div className="font-semibold text-slate-900">{d.deviceName}</div>
                    <div className="font-mono text-[11px] text-slate-500">{d.deviceId}</div>
                  </td>
                  <td className="py-2.5 pr-2 space-y-1">
                    <button
                      type="button"
                      onClick={() =>
                        handleTogglePermission(d.deviceId, 'smsPermission', !d.smsPermission)
                      }
                      className={`block px-2 py-0.5 rounded text-[11px] font-semibold cursor-pointer ${
                        d.smsPermission
                          ? 'bg-emerald-100 text-emerald-800'
                          : 'bg-red-100 text-red-800'
                      }`}
                    >
                      SMS: {d.smsPermission ? 'Granted' : 'Revoked'}
                    </button>
                    <button
                      type="button"
                      onClick={() =>
                        handleTogglePermission(
                          d.deviceId,
                          'contactsPermission',
                          !d.contactsPermission
                        )
                      }
                      className={`block px-2 py-0.5 rounded text-[11px] font-semibold cursor-pointer ${
                        d.contactsPermission
                          ? 'bg-emerald-100 text-emerald-800'
                          : 'bg-red-100 text-red-800'
                      }`}
                    >
                      Contacts: {d.contactsPermission ? 'Granted' : 'Revoked'}
                    </button>
                  </td>
                  <td className="py-2.5 pr-2 font-mono text-[11px] text-slate-600">
                    <div>SMS: {d.lastSmsSyncAt ? d.lastSmsSyncAt.slice(0, 19) : 'Never'}</div>
                    <div>
                      Contacts: {d.lastContactsSyncAt ? d.lastContactsSyncAt.slice(0, 19) : 'Never'}
                    </div>
                  </td>
                  <td className="py-2.5 pr-2">
                    {d.latestCommand ? (
                      <span className="px-2 py-0.5 rounded bg-blue-100 text-blue-800 font-semibold text-[11px]">
                        {d.latestCommand.status.toUpperCase()} ({d.latestCommand.syncType})
                      </span>
                    ) : (
                      <span className="text-slate-400">Idle</span>
                    )}
                  </td>
                  <td className="py-2.5 space-x-1 whitespace-nowrap">
                    <button
                      type="button"
                      onClick={() => handleDispatchSync('single', d.deviceId)}
                      className="px-2.5 py-1 rounded border border-slate-300 text-slate-700 hover:bg-slate-50 font-semibold cursor-pointer"
                    >
                      Sync
                    </button>
                    <button
                      type="button"
                      onClick={() => handleSimulatePoll(d.deviceId)}
                      className="inline-flex items-center gap-1 px-2.5 py-1 rounded bg-emerald-600 text-white font-semibold cursor-pointer"
                    >
                      <RefreshCw className="w-3 h-3" />
                      <span>Poll</span>
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {/* Announcements */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <form
          onSubmit={handleCreateAnnouncement}
          className="bg-white border border-slate-200 rounded-xl p-5 space-y-3"
        >
          <div className="flex items-center gap-2">
            <Bell className="w-4 h-4 text-blue-600" />
            <h3 className="text-sm font-bold text-slate-900">Send Push Announcement</h3>
          </div>
          <input
            type="text"
            required
            value={annTitle}
            onChange={(e) => setAnnTitle(e.target.value)}
            placeholder="Announcement Title"
            className="w-full px-3 py-2 text-xs border border-slate-200 rounded-lg"
          />
          <select
            value={annTargetType}
            onChange={(e) => setAnnTargetType(e.target.value as 'all' | 'selected')}
            className="w-full px-3 py-2 text-xs border border-slate-200 rounded-lg bg-slate-50"
          >
            <option value="all">Send to All Devices</option>
            <option value="selected">
              Send to Selected Devices ({selectedDeviceIds.length} checked above)
            </option>
          </select>
          <textarea
            rows={3}
            required
            value={annMessage}
            onChange={(e) => setAnnMessage(e.target.value)}
            placeholder="Announcement message body..."
            className="w-full px-3 py-2 text-xs border border-slate-200 rounded-lg"
          />
          <button
            type="submit"
            className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg bg-blue-600 hover:bg-blue-700 text-white text-xs font-semibold cursor-pointer"
          >
            <Send className="w-3.5 h-3.5" />
            <span>Publish Announcement</span>
          </button>
        </form>

        <div className="bg-white border border-slate-200 rounded-xl p-5 space-y-3">
          <h3 className="text-sm font-bold text-slate-900">Announcement History</h3>
          <div className="space-y-2 max-h-72 overflow-y-auto">
            {announcements.map((a) => (
              <div key={a.id} className="border border-slate-200 rounded-lg p-3 text-xs space-y-1">
                <div className="flex items-center justify-between gap-2">
                  <strong className="text-slate-900">{a.title}</strong>
                  <button
                    type="button"
                    onClick={() => handleDeleteAnnouncement(a.id)}
                    className="text-red-600 hover:text-red-700 cursor-pointer"
                    title="Delete announcement"
                  >
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
                <p className="text-slate-600">{a.message}</p>
                <div className="text-[11px] text-slate-400 font-mono">
                  {a.targetType === 'all' ? 'All Devices' : `${a.targetDevices.length} Device(s)`} ·{' '}
                  {a.readCount} reads · {a.createdAt.slice(0, 19)}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Sync Logs */}
      <div className="bg-white border border-slate-200 rounded-xl p-5">
        <h3 className="text-sm font-bold text-slate-900 mb-3">Sync &amp; Error Logs</h3>
        <div className="overflow-x-auto max-h-60 overflow-y-auto">
          <table className="w-full text-xs text-left">
            <thead>
              <tr className="border-b border-slate-200 text-slate-500">
                <th className="py-1.5 pr-2">Time</th>
                <th className="py-1.5 pr-2">Device</th>
                <th className="py-1.5 pr-2">Type</th>
                <th className="py-1.5 pr-2">Status</th>
                <th className="py-1.5">Details</th>
              </tr>
            </thead>
            <tbody>
              {logs.map((l) => (
                <tr key={l.id} className="border-b border-slate-100">
                  <td className="py-1.5 pr-2 font-mono text-[11px]">{l.createdAt.slice(0, 19)}</td>
                  <td className="py-1.5 pr-2 font-mono text-[11px]">{l.deviceId}</td>
                  <td className="py-1.5 pr-2">{l.syncType}</td>
                  <td className="py-1.5 pr-2 font-semibold">{l.status}</td>
                  <td className="py-1.5 text-slate-600">{l.errorMessage || `${l.itemsSynced} synced`}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
