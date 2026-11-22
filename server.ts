import express from 'express';
import fs from 'fs';
import path from 'path';
import dns from 'dns/promises';
import net from 'net';
import dotenv from 'dotenv';

dotenv.config();

const PORT = Number(process.env.PORT) || 3000;
const DATA_DIR = path.resolve(process.cwd(), '.data');
const VAULT_FILE = path.join(DATA_DIR, 'sms_backup_vault.json');

export interface ServerSmsRecord {
  serverId: number;
  deviceId: string;
  sender: string;
  body: string;
  receivedAt: string;
  syncedAt: string;
  smsHash: string;
}

interface VaultStore {
  nextId: number;
  records: ServerSmsRecord[];
}

function ensureVault(): VaultStore {
  if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
  }
  if (!fs.existsSync(VAULT_FILE)) {
    const initial: VaultStore = { nextId: 1, records: [] };
    fs.writeFileSync(VAULT_FILE, JSON.stringify(initial, null, 2), 'utf8');
    return initial;
  }
  try {
    const raw = fs.readFileSync(VAULT_FILE, 'utf8');
    const parsed = JSON.parse(raw) as VaultStore;
    if (!Array.isArray(parsed.records)) {
      return { nextId: 1, records: [] };
    }
    return parsed;
  } catch {
    return { nextId: 1, records: [] };
  }
}

function saveVault(store: VaultStore): void {
  if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
  }
  fs.writeFileSync(VAULT_FILE, JSON.stringify(store, null, 2), 'utf8');
}

function isPrivateOrBlockedIp(ip: string): boolean {
  const normalized = ip.toLowerCase();
  if (normalized === '::1' || normalized === '::' || normalized.startsWith('fe80:') || normalized.startsWith('fc') || normalized.startsWith('fd')) {
    return true;
  }
  if (normalized.startsWith('::ffff:')) {
    const v4 = normalized.slice(7);
    return isPrivateOrBlockedIp(v4);
  }
  const parts = normalized.split('.').map(Number);
  if (parts.length !== 4 || parts.some((n) => Number.isNaN(n) || n < 0 || n > 255)) {
    return true;
  }
  const [a, b] = parts;
  if (a === 0 || a === 10 || a === 127) return true;
  if (a === 169 && b === 254) return true;
  if (a === 172 && b >= 16 && b <= 31) return true;
  if (a === 192 && b === 168) return true;
  if (a === 100 && b >= 64 && b <= 127) return true;
  return false;
}

async function validateExternalUrl(rawUrl: string): Promise<URL> {
  const parsed = new URL(rawUrl);
  if (parsed.protocol !== 'https:' && parsed.protocol !== 'http:') {
    throw new Error('Only http and https URLs are permitted.');
  }
  const host = parsed.hostname.toLowerCase();
  if (!host || host === 'localhost' || host.endsWith('.local') || host.endsWith('.internal')) {
    throw new Error('Localhost and internal hostnames are blocked.');
  }
  if (net.isIP(host)) {
    if (isPrivateOrBlockedIp(host)) {
      throw new Error('Private or loopback IP addresses are blocked.');
    }
    return parsed;
  }
  const resolved = await dns.lookup(host, { all: true });
  if (!resolved.length || resolved.some((entry) => isPrivateOrBlockedIp(entry.address))) {
    throw new Error('Destination resolves to a private or blocked IP address.');
  }
  return parsed;
}

async function startServer() {
  const app = express();
  app.use(express.json({ limit: '1mb' }));

  app.get('/api/health', (_req, res) => {
    res.json({ ok: true, timestamp: new Date().toISOString() });
  });

  app.get('/api/sms/server-records', (_req, res) => {
    const vault = ensureVault();
    const sorted = [...vault.records].sort(
      (a, b) => new Date(b.receivedAt).getTime() - new Date(a.receivedAt).getTime()
    );
    res.json({
      records: sorted,
      totalCount: sorted.length,
    });
  });

  app.post('/api/sms/sync', (req, res) => {
    const { deviceId, messages } = req.body ?? {};
    if (typeof deviceId !== 'string' || !deviceId.trim() || deviceId.length > 100) {
      res.status(400).json({ error: 'Valid deviceId string is required.' });
      return;
    }
    if (!Array.isArray(messages) || messages.length === 0 || messages.length > 200) {
      res.status(400).json({ error: 'messages must be a non-empty array (max 200 per batch).' });
      return;
    }

    const cleanDeviceId = deviceId.trim();
    const vault = ensureVault();
    const existingHashes = new Set(
      vault.records.filter((r) => r.deviceId === cleanDeviceId).map((r) => r.smsHash)
    );

    let insertedCount = 0;
    let skippedDuplicates = 0;
    const syncedIds: string[] = [];
    const syncedAt = new Date().toISOString();

    for (const item of messages) {
      if (!item || typeof item !== 'object') continue;
      const id = typeof item.id === 'string' ? item.id.slice(0, 120) : '';
      const sender = typeof item.sender === 'string' ? item.sender.trim().slice(0, 64) : '';
      const body = typeof item.body === 'string' ? item.body.slice(0, 4000) : '';
      const receivedAt = typeof item.receivedAt === 'string' ? item.receivedAt.slice(0, 64) : syncedAt;
      const smsHash = typeof item.smsHash === 'string' ? item.smsHash.trim().slice(0, 128) : '';

      if (!sender || !body || !smsHash) {
        continue;
      }

      if (existingHashes.has(smsHash)) {
        skippedDuplicates += 1;
        if (id) syncedIds.push(id);
        continue;
      }

      existingHashes.add(smsHash);
      vault.records.push({
        serverId: vault.nextId++,
        deviceId: cleanDeviceId,
        sender,
        body,
        receivedAt,
        syncedAt,
        smsHash,
      });
      insertedCount += 1;
      if (id) syncedIds.push(id);
    }

    saveVault(vault);
    res.json({
      status: 'success',
      insertedCount,
      skippedDuplicates,
      syncedAt,
      syncedIds,
      totalServerRecords: vault.records.length,
    });
  });

  app.delete('/api/sms/server-records/:hash', (req, res) => {
    const hash = String(req.params.hash || '').trim();
    const vault = ensureVault();
    const before = vault.records.length;
    vault.records = vault.records.filter((r) => r.smsHash !== hash);
    saveVault(vault);
    res.json({ deleted: before - vault.records.length, totalServerRecords: vault.records.length });
  });

  app.delete('/api/sms/server-records', (_req, res) => {
    saveVault({ nextId: 1, records: [] });
    res.json({ cleared: true, totalServerRecords: 0 });
  });

  app.post('/api/relay/test', async (req, res) => {
    const { endpointUrl, apiKey, deviceId, messages } = req.body ?? {};
    if (typeof endpointUrl !== 'string' || !endpointUrl.trim()) {
      res.status(400).json({ error: 'Endpoint URL is required.' });
      return;
    }

    let validatedUrl: URL;
    try {
      validatedUrl = await validateExternalUrl(endpointUrl.trim());
    } catch (err) {
      res.status(400).json({
        error: err instanceof Error ? err.message : 'Invalid endpoint URL.',
      });
      return;
    }

    const payload = {
      device_id: typeof deviceId === 'string' ? deviceId.slice(0, 100) : 'android-demo-device',
      messages: Array.isArray(messages) ? messages.slice(0, 20) : [],
    };

    try {
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      };
      if (typeof apiKey === 'string' && apiKey.trim()) {
        headers['X-API-Key'] = apiKey.trim().slice(0, 256);
      }

      const upstream = await fetch(validatedUrl.toString(), {
        method: 'POST',
        headers,
        body: JSON.stringify(payload),
        signal: AbortSignal.timeout(8000),
      });

      const rawText = await upstream.text();
      res.json({
        reachable: true,
        httpStatus: upstream.status,
        ok: upstream.ok,
        responsePreview: rawText.slice(0, 1200),
      });
    } catch (err) {
      res.status(502).json({
        reachable: false,
        error: err instanceof Error ? err.message : 'Failed to connect to remote endpoint.',
      });
    }
  });

  if (process.env.NODE_ENV !== 'production') {
    const { createServer: createViteServer } = await import('vite');
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'spa',
    });
    app.use(vite.middlewares);
  } else {
    const distDir = path.resolve(process.cwd(), 'dist');
    app.use(express.static(distDir));
    app.get('*', (_req, res) => {
      res.sendFile(path.join(distDir, 'index.html'));
    });
  }

  app.listen(PORT, '0.0.0.0', () => {
    console.log(`SMS Vault Sync server running on http://localhost:${PORT}`);
  });
}

startServer();
