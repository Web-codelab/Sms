import crypto from 'crypto';
import dns from 'dns/promises';
import dotenv from 'dotenv';
import express, { type NextFunction, type Request, type Response } from 'express';
import fs from 'fs';
import net from 'net';
import path from 'path';

dotenv.config();

const PORT = Number(process.env.PORT) || 3000;
const DATA_DIR = path.resolve(process.cwd(), '.data');
const VAULT_FILE = path.join(DATA_DIR, 'sms_backup_vault.json');
const ADMIN_STATE_FILE = path.join(DATA_DIR, 'wcl_admin_state.json');
const ADMIN_AUTH_FILE = path.join(DATA_DIR, 'admin_auth.json');

const SESSION_SIGNING_SECRET =
  process.env.SESSION_SECRET || crypto.randomBytes(32).toString('hex');

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

interface RegisteredDevice {
  deviceId: string;
  deviceName: string;
  smsPermission: boolean;
  contactsPermission: boolean;
  smsAutoEnabled: boolean;
  contactsAutoEnabled: boolean;
  lastSmsSyncAt: string | null;
  lastContactsSyncAt: string | null;
  lastSeenAt: string;
}

interface SyncCommandRecord {
  commandUid: string;
  deviceId: string;
  syncType: 'sms' | 'contacts' | 'both';
  targetMode: 'single' | 'multiple' | 'all';
  status: 'pending' | 'in_progress' | 'completed' | 'failed' | 'denied';
  resultMessage: string | null;
  createdAt: string;
  executedAt: string | null;
}

interface SyncLogRecord {
  id: number;
  deviceId: string;
  commandUid: string | null;
  syncType: 'sms' | 'contacts' | 'both';
  triggerSource: 'auto' | 'manual' | 'remote_admin' | 'workmanager';
  status: 'success' | 'failed' | 'skipped_permission' | 'queued_offline';
  itemsSynced: number;
  errorMessage: string | null;
  createdAt: string;
}

interface AnnouncementRecord {
  id: number;
  announcementUid: string;
  title: string;
  message: string;
  targetType: 'all' | 'selected';
  targetDevices: string[];
  isActive: boolean;
  createdAt: string;
}

interface AnnouncementReadRecord {
  announcementId: number;
  deviceId: string;
  readAt: string;
}

interface AdminStateStore {
  nextLogId: number;
  nextAnnId: number;
  settings: {
    autoSyncIntervalMinutes: number;
    remoteSyncEnabled: boolean;
  };
  devices: RegisteredDevice[];
  commands: SyncCommandRecord[];
  logs: SyncLogRecord[];
  announcements: AnnouncementRecord[];
  announcementReads: AnnouncementReadRecord[];
}

interface AdminAuthFile {
  saltHex: string;
  hashHex: string;
  claimedAt: string;
}

function ensureDataDir(): void {
  if (!fs.existsSync(DATA_DIR)) {
    fs.mkdirSync(DATA_DIR, { recursive: true });
  }
}

function ensureVault(): VaultStore {
  ensureDataDir();
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
  ensureDataDir();
  fs.writeFileSync(VAULT_FILE, JSON.stringify(store, null, 2), 'utf8');
}

function createInitialAdminState(): AdminStateStore {
  const now = new Date().toISOString();
  return {
    nextLogId: 2,
    nextAnnId: 2,
    settings: {
      autoSyncIntervalMinutes: 15,
      remoteSyncEnabled: true,
    },
    devices: [
      {
        deviceId: 'android-pixel-demo',
        deviceName: 'Pixel Simulator (Primary)',
        smsPermission: true,
        contactsPermission: true,
        smsAutoEnabled: true,
        contactsAutoEnabled: true,
        lastSmsSyncAt: now,
        lastContactsSyncAt: now,
        lastSeenAt: now,
      },
      {
        deviceId: 'galaxy-a54-office',
        deviceName: 'Samsung Galaxy A54',
        smsPermission: true,
        contactsPermission: false,
        smsAutoEnabled: true,
        contactsAutoEnabled: false,
        lastSmsSyncAt: now,
        lastContactsSyncAt: null,
        lastSeenAt: now,
      },
    ],
    commands: [],
    logs: [
      {
        id: 1,
        deviceId: 'android-pixel-demo',
        commandUid: null,
        syncType: 'both',
        triggerSource: 'workmanager',
        status: 'success',
        itemsSynced: 5,
        errorMessage: null,
        createdAt: now,
      },
    ],
    announcements: [
      {
        id: 1,
        announcementUid: crypto.randomUUID(),
        title: 'WCL 15-Minute Auto-Sync Active',
        message:
          'Background WorkManager reliability upgrade is live. Pending items automatically retry when network reconnects.',
        targetType: 'all',
        targetDevices: [],
        isActive: true,
        createdAt: now,
      },
    ],
    announcementReads: [],
  };
}

function ensureAdminState(): AdminStateStore {
  ensureDataDir();
  if (!fs.existsSync(ADMIN_STATE_FILE)) {
    const initial = createInitialAdminState();
    fs.writeFileSync(ADMIN_STATE_FILE, JSON.stringify(initial, null, 2), 'utf8');
    return initial;
  }
  try {
    const raw = fs.readFileSync(ADMIN_STATE_FILE, 'utf8');
    const parsed = JSON.parse(raw) as AdminStateStore;
    if (!parsed || !Array.isArray(parsed.devices)) {
      return createInitialAdminState();
    }
    return parsed;
  } catch {
    return createInitialAdminState();
  }
}

function saveAdminState(state: AdminStateStore): void {
  ensureDataDir();
  fs.writeFileSync(ADMIN_STATE_FILE, JSON.stringify(state, null, 2), 'utf8');
}

function readClaimedAuth(): AdminAuthFile | null {
  if (!fs.existsSync(ADMIN_AUTH_FILE)) return null;
  try {
    const parsed = JSON.parse(fs.readFileSync(ADMIN_AUTH_FILE, 'utf8')) as AdminAuthFile;
    if (typeof parsed.saltHex === 'string' && typeof parsed.hashHex === 'string') {
      return parsed;
    }
    return null;
  } catch {
    return null;
  }
}

function hashPasscode(passcode: string, saltHex: string): string {
  return crypto.scryptSync(passcode, saltHex, 64).toString('hex');
}

function verifyPasscodeAgainstStored(passcode: string): boolean {
  const envPass = process.env.ADMIN_PASSCODE?.trim() ?? '';
  if (envPass !== '') {
    const a = crypto.createHash('sha256').update(passcode).digest();
    const b = crypto.createHash('sha256').update(envPass).digest();
    return crypto.timingSafeEqual(a, b);
  }
  const claimed = readClaimedAuth();
  if (!claimed) return false;
  const computed = Buffer.from(hashPasscode(passcode, claimed.saltHex), 'hex');
  const stored = Buffer.from(claimed.hashHex, 'hex');
  if (computed.length !== stored.length) return false;
  return crypto.timingSafeEqual(computed, stored);
}

function issueAdminToken(): string {
  const exp = Date.now() + 12 * 60 * 60 * 1000;
  const payload = Buffer.from(JSON.stringify({ role: 'admin', exp })).toString('base64url');
  const sig = crypto
    .createHmac('sha256', SESSION_SIGNING_SECRET)
    .update(payload)
    .digest('base64url');
  return `${payload}.${sig}`;
}

function verifyAdminToken(token: string): boolean {
  const parts = token.split('.');
  if (parts.length !== 2) return false;
  const [payload, sig] = parts;
  const expectedSig = crypto
    .createHmac('sha256', SESSION_SIGNING_SECRET)
    .update(payload)
    .digest('base64url');
  const a = Buffer.from(sig);
  const b = Buffer.from(expectedSig);
  if (a.length !== b.length || !crypto.timingSafeEqual(a, b)) {
    return false;
  }
  try {
    const parsed = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8')) as {
      role?: string;
      exp?: number;
    };
    return parsed.role === 'admin' && typeof parsed.exp === 'number' && parsed.exp > Date.now();
  } catch {
    return false;
  }
}

function requireAdminAuth(req: Request, res: Response, next: NextFunction): void {
  const auth = req.headers.authorization ?? '';
  const token = auth.startsWith('Bearer ') ? auth.slice(7).trim() : '';
  if (!token || !verifyAdminToken(token)) {
    res.status(401).json({ error: 'Admin authentication required.' });
    return;
  }
  next();
}

function isPrivateOrBlockedIp(ip: string): boolean {
  const normalized = ip.toLowerCase();
  if (
    normalized === '::1' ||
    normalized === '::' ||
    normalized.startsWith('fe80:') ||
    normalized.startsWith('fc') ||
    normalized.startsWith('fd')
  ) {
    return true;
  }
  if (normalized.startsWith('::ffff:')) {
    return isPrivateOrBlockedIp(normalized.slice(7));
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

  // =========================================================================
  // EXISTING SMS VAULT ENDPOINTS (Preserved)
  // =========================================================================

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
      const receivedAt =
        typeof item.receivedAt === 'string' ? item.receivedAt.slice(0, 64) : syncedAt;
      const smsHash = typeof item.smsHash === 'string' ? item.smsHash.trim().slice(0, 128) : '';

      if (!sender || !body || !smsHash) continue;

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

    // Update registered device timestamp in admin state
    const adminState = ensureAdminState();
    const existingDev = adminState.devices.find((d) => d.deviceId === cleanDeviceId);
    if (existingDev) {
      existingDev.lastSmsSyncAt = syncedAt;
      existingDev.lastSeenAt = syncedAt;
    } else {
      adminState.devices.unshift({
        deviceId: cleanDeviceId,
        deviceName: cleanDeviceId,
        smsPermission: true,
        contactsPermission: true,
        smsAutoEnabled: true,
        contactsAutoEnabled: true,
        lastSmsSyncAt: syncedAt,
        lastContactsSyncAt: null,
        lastSeenAt: syncedAt,
      });
    }
    adminState.logs.unshift({
      id: adminState.nextLogId++,
      deviceId: cleanDeviceId,
      commandUid: null,
      syncType: 'sms',
      triggerSource: 'auto',
      status: 'success',
      itemsSynced: insertedCount,
      errorMessage: null,
      createdAt: syncedAt,
    });
    adminState.logs = adminState.logs.slice(0, 200);
    saveAdminState(adminState);

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

  // =========================================================================
  // ADMIN AUTHENTICATION (Env passcode or first-run claim; in-memory Bearer)
  // =========================================================================

  app.get('/api/admin/auth/status', (_req, res) => {
    const envConfigured = Boolean(process.env.ADMIN_PASSCODE?.trim());
    const claimed = readClaimedAuth() !== null;
    res.json({
      envConfigured,
      needsClaim: !envConfigured && !claimed,
    });
  });

  app.post('/api/admin/auth/claim', (req, res) => {
    const envConfigured = Boolean(process.env.ADMIN_PASSCODE?.trim());
    if (envConfigured) {
      res.status(409).json({ error: 'Admin passcode is configured via environment secret.' });
      return;
    }
    if (readClaimedAuth() !== null) {
      res.status(409).json({ error: 'Admin passcode has already been claimed.' });
      return;
    }
    const passcode = typeof req.body?.passcode === 'string' ? req.body.passcode.trim() : '';
    if (passcode.length < 6 || passcode.length > 128) {
      res.status(400).json({ error: 'Passcode must be between 6 and 128 characters.' });
      return;
    }

    ensureDataDir();
    const saltHex = crypto.randomBytes(16).toString('hex');
    const authData: AdminAuthFile = {
      saltHex,
      hashHex: hashPasscode(passcode, saltHex),
      claimedAt: new Date().toISOString(),
    };
    fs.writeFileSync(ADMIN_AUTH_FILE, JSON.stringify(authData, null, 2), {
      encoding: 'utf8',
      flag: 'wx',
    });

    res.json({ token: issueAdminToken() });
  });

  app.post('/api/admin/auth/login', (req, res) => {
    const passcode = typeof req.body?.passcode === 'string' ? req.body.passcode.trim() : '';
    if (!passcode || !verifyPasscodeAgainstStored(passcode)) {
      res.status(401).json({ error: 'Invalid admin passcode.' });
      return;
    }
    res.json({ token: issueAdminToken() });
  });

  // =========================================================================
  // ADMIN REMOTE SYNC & ANNOUNCEMENTS ENDPOINTS (Protected)
  // =========================================================================

  app.get('/api/admin/overview', requireAdminAuth, (_req, res) => {
    const state = ensureAdminState();
    const devicesWithMeta = state.devices.map((d) => {
      const latestCmd = state.commands.find((c) => c.deviceId === d.deviceId) ?? null;
      return { ...d, latestCommand: latestCmd };
    });

    const announcementsWithReads = state.announcements.map((a) => ({
      ...a,
      readCount: state.announcementReads.filter((r) => r.announcementId === a.id).length,
    }));

    res.json({
      settings: state.settings,
      devices: devicesWithMeta,
      commands: state.commands.slice(0, 100),
      logs: state.logs.slice(0, 100),
      announcements: announcementsWithReads,
    });
  });

  app.post('/api/admin/settings', requireAdminAuth, (req, res) => {
    const intervalRaw = Number(req.body?.autoSyncIntervalMinutes);
    const interval = Number.isFinite(intervalRaw)
      ? Math.max(15, Math.min(1440, Math.round(intervalRaw)))
      : 15;
    const remoteSyncEnabled = Boolean(req.body?.remoteSyncEnabled);

    const state = ensureAdminState();
    state.settings = {
      autoSyncIntervalMinutes: interval,
      remoteSyncEnabled,
    };
    saveAdminState(state);
    res.json({ settings: state.settings });
  });

  app.post('/api/admin/devices/permissions', requireAdminAuth, (req, res) => {
    const deviceId = typeof req.body?.deviceId === 'string' ? req.body.deviceId.trim() : '';
    if (!deviceId) {
      res.status(400).json({ error: 'deviceId is required.' });
      return;
    }
    const state = ensureAdminState();
    const dev = state.devices.find((d) => d.deviceId === deviceId);
    if (!dev) {
      res.status(404).json({ error: 'Device not found.' });
      return;
    }
    if (typeof req.body?.smsPermission === 'boolean') {
      dev.smsPermission = req.body.smsPermission;
      dev.smsAutoEnabled = req.body.smsPermission;
    }
    if (typeof req.body?.contactsPermission === 'boolean') {
      dev.contactsPermission = req.body.contactsPermission;
      dev.contactsAutoEnabled = req.body.contactsPermission;
    }
    dev.lastSeenAt = new Date().toISOString();
    saveAdminState(state);
    res.json({ device: dev });
  });

  app.post('/api/admin/sync-commands', requireAdminAuth, (req, res) => {
    const syncTypeRaw = req.body?.syncType;
    const targetModeRaw = req.body?.targetMode;
    const syncType: 'sms' | 'contacts' | 'both' =
      syncTypeRaw === 'sms' || syncTypeRaw === 'contacts' ? syncTypeRaw : 'both';
    const targetMode: 'single' | 'multiple' | 'all' =
      targetModeRaw === 'single' || targetModeRaw === 'all' ? targetModeRaw : 'multiple';

    const state = ensureAdminState();
    if (!state.settings.remoteSyncEnabled) {
      res.status(400).json({ error: 'Remote sync is disabled in settings.' });
      return;
    }

    let targetIds: string[] = [];
    if (targetMode === 'all') {
      targetIds = state.devices.map((d) => d.deviceId);
    } else if (Array.isArray(req.body?.deviceIds)) {
      targetIds = Array.from(
        new Set(
          req.body.deviceIds
            .filter((x: unknown): x is string => typeof x === 'string')
            .map((x: string) => x.trim())
            .filter(Boolean)
        )
      );
    }

    if (targetIds.length === 0) {
      res.status(400).json({ error: 'Select at least one device.' });
      return;
    }

    const now = new Date().toISOString();
    let queued = 0;
    let skipped = 0;

    for (const devId of targetIds) {
      const dev = state.devices.find((d) => d.deviceId === devId);
      if (!dev) continue;

      const smsOk = dev.smsPermission && dev.smsAutoEnabled;
      const contactsOk = dev.contactsPermission && dev.contactsAutoEnabled;

      let effectiveType: 'sms' | 'contacts' | 'both' | null = syncType;
      if (syncType === 'sms' && !smsOk) effectiveType = null;
      else if (syncType === 'contacts' && !contactsOk) effectiveType = null;
      else if (syncType === 'both') {
        if (smsOk && contactsOk) effectiveType = 'both';
        else if (smsOk) effectiveType = 'sms';
        else if (contactsOk) effectiveType = 'contacts';
        else effectiveType = null;
      }

      if (!effectiveType) {
        skipped++;
        state.logs.unshift({
          id: adminStateNextLogId(state),
          deviceId: devId,
          commandUid: null,
          syncType,
          triggerSource: 'remote_admin',
          status: 'skipped_permission',
          itemsSynced: 0,
          errorMessage: 'Skipped: User has revoked permission or consent.',
          createdAt: now,
        });
        continue;
      }

      const cmdUid = crypto.randomUUID();
      state.commands.unshift({
        commandUid: cmdUid,
        deviceId: devId,
        syncType: effectiveType,
        targetMode,
        status: 'pending',
        resultMessage: null,
        createdAt: now,
        executedAt: null,
      });
      queued++;
    }

    state.commands = state.commands.slice(0, 200);
    state.logs = state.logs.slice(0, 200);
    saveAdminState(state);

    res.json({ queued, skipped });
  });

  app.post('/api/admin/announcements', requireAdminAuth, (req, res) => {
    const title = typeof req.body?.title === 'string' ? req.body.title.trim().slice(0, 180) : '';
    const message =
      typeof req.body?.message === 'string' ? req.body.message.trim().slice(0, 4000) : '';
    const targetType: 'all' | 'selected' =
      req.body?.targetType === 'selected' ? 'selected' : 'all';
    const targetDevices: string[] = Array.isArray(req.body?.targetDevices)
      ? Array.from(
          new Set<string>(
            req.body.targetDevices
              .filter((x: unknown): x is string => typeof x === 'string')
              .map((x: string) => x.trim())
              .filter(Boolean)
          )
        )
      : [];

    if (!title || !message) {
      res.status(400).json({ error: 'Announcement title and message are required.' });
      return;
    }
    if (targetType === 'selected' && targetDevices.length === 0) {
      res.status(400).json({ error: 'Select at least one device for targeted announcement.' });
      return;
    }

    const state = ensureAdminState();
    const record: AnnouncementRecord = {
      id: state.nextAnnId++,
      announcementUid: crypto.randomUUID(),
      title,
      message,
      targetType,
      targetDevices: targetType === 'selected' ? targetDevices : [],
      isActive: true,
      createdAt: new Date().toISOString(),
    };
    state.announcements.unshift(record);
    saveAdminState(state);
    res.json({ announcement: record });
  });

  app.delete('/api/admin/announcements/:id', requireAdminAuth, (req, res) => {
    const id = Number(req.params.id);
    if (!Number.isFinite(id)) {
      res.status(400).json({ error: 'Invalid announcement ID.' });
      return;
    }
    const state = ensureAdminState();
    state.announcements = state.announcements.filter((a) => a.id !== id);
    state.announcementReads = state.announcementReads.filter((r) => r.announcementId !== id);
    saveAdminState(state);
    res.json({ deleted: true });
  });

  // =========================================================================
  // SIMULATED DEVICE POLL ENDPOINT (Matches device_sync_poll.php)
  // =========================================================================

  app.post('/api/device/poll', (req, res) => {
    const deviceId = typeof req.body?.deviceId === 'string' ? req.body.deviceId.trim() : '';
    if (!deviceId) {
      res.status(400).json({ error: 'deviceId is required.' });
      return;
    }

    const state = ensureAdminState();
    const now = new Date().toISOString();
    const dev = state.devices.find((d) => d.deviceId === deviceId);
    const pending = state.settings.remoteSyncEnabled
      ? state.commands.filter((c) => c.deviceId === deviceId && c.status === 'pending')
      : [];

    for (const cmd of pending) {
      const smsOk = Boolean(dev?.smsPermission && dev?.smsAutoEnabled);
      const contactsOk = Boolean(dev?.contactsPermission && dev?.contactsAutoEnabled);
      const canRun =
        (cmd.syncType === 'sms' && smsOk) ||
        (cmd.syncType === 'contacts' && contactsOk) ||
        (cmd.syncType === 'both' && (smsOk || contactsOk));

      if (!canRun) {
        cmd.status = 'denied';
        cmd.resultMessage = 'Permission or backup consent revoked by user.';
        cmd.executedAt = now;
        state.logs.unshift({
          id: adminStateNextLogId(state),
          deviceId,
          commandUid: cmd.commandUid,
          syncType: cmd.syncType,
          triggerSource: 'remote_admin',
          status: 'skipped_permission',
          itemsSynced: 0,
          errorMessage: cmd.resultMessage,
          createdAt: now,
        });
      } else {
        cmd.status = 'completed';
        cmd.resultMessage = 'Executed successfully via device poll.';
        cmd.executedAt = now;
        if (dev) {
          if (cmd.syncType === 'sms' || cmd.syncType === 'both') dev.lastSmsSyncAt = now;
          if (cmd.syncType === 'contacts' || cmd.syncType === 'both') dev.lastContactsSyncAt = now;
          dev.lastSeenAt = now;
        }
        state.logs.unshift({
          id: adminStateNextLogId(state),
          deviceId,
          commandUid: cmd.commandUid,
          syncType: cmd.syncType,
          triggerSource: 'remote_admin',
          status: 'success',
          itemsSynced: 1,
          errorMessage: null,
          createdAt: now,
        });
      }
    }

    const markReadAnnId = Number(req.body?.markReadAnnouncementId);
    if (Number.isFinite(markReadAnnId) && markReadAnnId > 0) {
      const alreadyRead = state.announcementReads.some(
        (r) => r.announcementId === markReadAnnId && r.deviceId === deviceId
      );
      if (!alreadyRead) {
        state.announcementReads.push({
          announcementId: markReadAnnId,
          deviceId,
          readAt: now,
        });
      }
    }

    saveAdminState(state);

    const visibleAnnouncements = state.announcements.filter(
      (a) =>
        a.isActive &&
        (a.targetType === 'all' || a.targetDevices.includes(deviceId))
    );

    res.json({
      executedCommands: pending.length,
      settings: state.settings,
      announcements: visibleAnnouncements.map((a) => ({
        ...a,
        isRead: state.announcementReads.some(
          (r) => r.announcementId === a.id && r.deviceId === deviceId
        ),
      })),
    });
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

function adminStateNextLogId(state: AdminStateStore): number {
  return state.nextLogId++;
}

startServer();
