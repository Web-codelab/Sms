import { useState } from 'react';
import {
  Database,
  Download,
  RefreshCw,
  Send,
  Trash2,
  CheckCircle2,
  AlertCircle,
  Search,
} from 'lucide-react';
import type { EndpointConfig, ServerSmsRecord } from '../types/sms';

interface ServerVaultPanelProps {
  records: ServerSmsRecord[];
  isLoading: boolean;
  endpointConfig: EndpointConfig;
  onRefresh: () => Promise<void>;
  onClearServer: () => Promise<void>;
  onDeleteRecord: (hash: string) => Promise<void>;
}

export function ServerVaultPanel({
  records,
  isLoading,
  endpointConfig,
  onRefresh,
  onClearServer,
  onDeleteRecord,
}: ServerVaultPanelProps) {
  const [searchQuery, setSearchQuery] = useState('');
  const [relayTesting, setRelayTesting] = useState(false);
  const [relayResult, setRelayResult] = useState<{
    ok: boolean;
    message: string;
    preview?: string;
  } | null>(null);

  const filteredRecords = records.filter((rec) => {
    if (!searchQuery.trim()) return true;
    const q = searchQuery.toLowerCase();
    return (
      rec.sender.toLowerCase().includes(q) ||
      rec.body.toLowerCase().includes(q) ||
      rec.deviceId.toLowerCase().includes(q)
    );
  });

  const handleExportCsv = () => {
    const header = ['server_id', 'device_id', 'sender', 'message_body', 'received_at', 'synced_at', 'sms_hash'];
    const escapeCsv = (val: string | number) =>
      `"${String(val).replace(/"/g, '""')}"`;

    const rows = filteredRecords.map((r) =>
      [r.serverId, r.deviceId, r.sender, r.body, r.receivedAt, r.syncedAt, r.smsHash]
        .map(escapeCsv)
        .join(',')
    );
    const csv = [header.join(','), ...rows].join('\n');
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `sms_backups_${new Date().toISOString().slice(0, 10)}.csv`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
  };

  const handleTestRemotePhp = async () => {
    setRelayTesting(true);
    setRelayResult(null);

    const targetUrl = `${endpointConfig.baseUrl.replace(/\/+$/, '')}/backup_sms.php`;
    const samplePayload = [
      {
        sender: 'TEST-VERIFY',
        body: 'SMS Vault connection test from console.',
        received_at: new Date().toISOString(),
        sms_hash: 'a'.repeat(64),
      },
    ];

    try {
      const res = await fetch('/api/relay/test', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          endpointUrl: targetUrl,
          apiKey: endpointConfig.apiKey,
          deviceId: endpointConfig.deviceId,
          messages: samplePayload,
        }),
      });
      const data = await res.json();
      if (!res.ok) {
        setRelayResult({
          ok: false,
          message: data.error || `HTTP ${res.status}`,
        });
      } else {
        setRelayResult({
          ok: Boolean(data.ok),
          message: `Remote server responded with HTTP ${data.httpStatus}`,
          preview: data.responsePreview || '(empty response)',
        });
      }
    } catch (err) {
      setRelayResult({
        ok: false,
        message: err instanceof Error ? err.message : 'Network error during endpoint test.',
      });
    } finally {
      setRelayTesting(false);
    }
  };

  return (
    <div className="space-y-6">
      <div className="bg-white border border-slate-200 rounded-xl p-6">
        <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4">
          <div className="space-y-1">
            <h2 className="text-xl font-semibold text-slate-900 tracking-tight">
              Server Backup Database
            </h2>
            <p className="text-sm text-slate-600">
              Live server-side SMS storage mirroring the cPanel <span className="font-mono text-slate-800">sms_backups</span> MySQL table with hash deduplication.
            </p>
          </div>

          <div className="flex flex-wrap items-center gap-2 shrink-0">
            <button
              type="button"
              onClick={onRefresh}
              disabled={isLoading}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-medium bg-white border border-slate-200 text-slate-700 hover:bg-slate-50 transition-colors whitespace-nowrap shrink-0 cursor-pointer disabled:opacity-50"
            >
              <RefreshCw className={`w-3.5 h-3.5 ${isLoading ? 'animate-spin' : ''}`} aria-hidden="true" />
              <span>Refresh Records</span>
            </button>
            <button
              type="button"
              onClick={handleExportCsv}
              disabled={filteredRecords.length === 0}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-medium bg-white border border-slate-200 text-slate-700 hover:bg-slate-50 transition-colors whitespace-nowrap shrink-0 cursor-pointer disabled:opacity-50"
            >
              <Download className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Export CSV</span>
            </button>
            <button
              type="button"
              onClick={onClearServer}
              disabled={records.length === 0}
              className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg text-xs font-medium bg-white border border-red-200 text-red-700 hover:bg-red-50 transition-colors whitespace-nowrap shrink-0 cursor-pointer disabled:opacity-50"
            >
              <Trash2 className="w-3.5 h-3.5" aria-hidden="true" />
              <span>Clear Vault</span>
            </button>
          </div>
        </div>

        <div className="mt-5 pt-5 border-t border-slate-200 flex flex-col sm:flex-row sm:items-center justify-between gap-4">
          <div className="text-xs text-slate-600 space-y-0.5">
            <div className="font-medium text-slate-800">cPanel Endpoint Relay Check</div>
            <div>
              Tests live POST delivery to{' '}
              <span className="font-mono text-slate-900">
                {endpointConfig.baseUrl.replace(/\/+$/, '')}/backup_sms.php
              </span>
            </div>
          </div>

          <button
            type="button"
            onClick={handleTestRemotePhp}
            disabled={relayTesting}
            className="inline-flex items-center gap-1.5 px-4 py-2 rounded-lg text-xs font-medium bg-slate-900 text-white hover:bg-slate-800 transition-colors whitespace-nowrap shrink-0 cursor-pointer disabled:opacity-60"
          >
            <Send className="w-3.5 h-3.5" aria-hidden="true" />
            <span>{relayTesting ? 'Testing Endpoint...' : 'Test cPanel PHP Endpoint'}</span>
          </button>
        </div>

        {relayResult && (
          <div
            className={`mt-4 p-3.5 rounded-lg border text-xs ${
              relayResult.ok
                ? 'bg-emerald-50 border-emerald-200 text-emerald-900'
                : 'bg-amber-50 border-amber-200 text-amber-900'
            }`}
          >
            <div className="flex items-center gap-2 font-medium">
              {relayResult.ok ? (
                <CheckCircle2 className="w-4 h-4 text-emerald-700 shrink-0" aria-hidden="true" />
              ) : (
                <AlertCircle className="w-4 h-4 text-amber-700 shrink-0" aria-hidden="true" />
              )}
              <span>{relayResult.message}</span>
            </div>
            {relayResult.preview && (
              <pre className="mt-2 font-mono text-[11px] bg-white/80 border border-slate-200 rounded p-2 overflow-x-auto text-slate-800">
                {relayResult.preview}
              </pre>
            )}
          </div>
        )}
      </div>

      <div className="bg-white border border-slate-200 rounded-xl overflow-hidden">
        <div className="px-4 py-3 border-b border-slate-200 flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div className="relative w-full sm:w-80">
            <Search className="w-4 h-4 text-slate-400 absolute left-3 top-1/2 -translate-y-1/2" aria-hidden="true" />
            <input
              type="text"
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              placeholder="Search sender, message, or device ID..."
              className="w-full pl-9 pr-3 py-1.5 text-sm bg-slate-50 border border-slate-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500 focus:bg-white"
            />
          </div>
          <div className="text-xs text-slate-500 tabular-nums">
            Showing {filteredRecords.length} of {records.length} backed-up rows
          </div>
        </div>

        {filteredRecords.length === 0 ? (
          <div className="p-12 text-center space-y-2">
            <Database className="w-8 h-8 text-slate-300 mx-auto" aria-hidden="true" />
            <div className="text-sm font-medium text-slate-800">No backed-up SMS in server database yet</div>
            <p className="text-xs text-slate-500 max-w-md mx-auto">
              Switch to Inbox & Queue and click Backup All Now, or enable Auto-Backup Always On and receive a test SMS to watch rows appear here.
            </p>
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left border-collapse">
              <thead>
                <tr className="border-b border-slate-200 bg-slate-50 text-xs font-medium text-slate-600">
                  <th className="py-2.5 px-4 whitespace-nowrap">ID</th>
                  <th className="py-2.5 px-4 whitespace-nowrap">Sender</th>
                  <th className="py-2.5 px-4 min-w-[280px]">Message Body</th>
                  <th className="py-2.5 px-4 whitespace-nowrap">Received</th>
                  <th className="py-2.5 px-4 whitespace-nowrap">Synced At</th>
                  <th className="py-2.5 px-4 whitespace-nowrap">SHA-256 Hash</th>
                  <th className="py-2.5 px-4 text-right whitespace-nowrap">Action</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 text-xs">
                {filteredRecords.map((row) => (
                  <tr key={row.smsHash} className="hover:bg-slate-50/80 transition-colors">
                    <td className="py-2.5 px-4 font-mono text-slate-500 tabular-nums">
                      #{row.serverId}
                    </td>
                    <td className="py-2.5 px-4 font-medium text-slate-900 whitespace-nowrap">
                      {row.sender}
                    </td>
                    <td className="py-2.5 px-4 text-slate-700 leading-relaxed max-w-md">
                      {row.body}
                    </td>
                    <td className="py-2.5 px-4 font-mono text-slate-500 tabular-nums whitespace-nowrap">
                      {new Date(row.receivedAt).toLocaleString()}
                    </td>
                    <td className="py-2.5 px-4 font-mono text-emerald-700 tabular-nums whitespace-nowrap">
                      {new Date(row.syncedAt).toLocaleTimeString()}
                    </td>
                    <td className="py-2.5 px-4 font-mono text-slate-500 whitespace-nowrap">
                      {row.smsHash.slice(0, 12)}…
                    </td>
                    <td className="py-2.5 px-4 text-right whitespace-nowrap">
                      <button
                        type="button"
                        onClick={() => onDeleteRecord(row.smsHash)}
                        className="text-slate-400 hover:text-red-600 p-1 rounded transition-colors cursor-pointer"
                        title="Delete from server vault"
                        aria-label={`Delete SMS from ${row.sender}`}
                      >
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </div>
  );
}
