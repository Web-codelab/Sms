import migrationSqlRaw from '../../php_backend/migration.sql?raw';
import dbPhpRaw from '../../php_backend/db.php?raw';
import backupSmsPhpRaw from '../../php_backend/backup_sms.php?raw';
import backupContactsPhpRaw from '../../php_backend/backup_contacts.php?raw';
import listSmsPhpRaw from '../../php_backend/list_sms.php?raw';
import deviceSyncPollPhpRaw from '../../php_backend/device_sync_poll.php?raw';
import adminMasterPhpRaw from '../../php_backend/admin_master.php?raw';

import androidManifestRaw from '../../app/src/main/AndroidManifest.xml?raw';
import smsDatabaseHelperRaw from '../../app/src/main/java/com/webcodelab/smsvault/SmsDatabaseHelper.kt?raw';
import remoteSyncManagerRaw from '../../app/src/main/java/com/webcodelab/smsvault/RemoteSyncManager.kt?raw';
import smsSyncWorkerRaw from '../../app/src/main/java/com/webcodelab/smsvault/SmsSyncWorker.kt?raw';
import smsBackupServiceRaw from '../../app/src/main/java/com/webcodelab/smsvault/SmsBackupForegroundService.kt?raw';
import mainActivityRaw from '../../app/src/main/java/com/webcodelab/smsvault/MainActivity.kt?raw';

import type { CodeFileSpec, EndpointConfig } from '../types/sms';

function normalizeBaseUrl(raw: string): string {
  const trimmed = raw.trim().replace(/\/+$/, '');
  return trimmed || 'https://Webcodelab.in/WCL';
}

export function getPhpCodeFiles(config: EndpointConfig): CodeFileSpec[] {
  const baseUrl = normalizeBaseUrl(config.baseUrl);
  const apiKeyPlaceholder = config.apiKey.trim() || 'REPLACE_WITH_STRONG_RANDOM_API_KEY';

  const customizedDbPhp = dbPhpRaw.replace(
    "define('SMS_SYNC_API_KEY', 'REPLACE_WITH_STRONG_RANDOM_API_KEY');",
    `define('SMS_SYNC_API_KEY', '${apiKeyPlaceholder.replace(/'/g, "\\'")}');`
  );

  return [
    {
      id: 'migration-sql',
      filename: 'migration.sql',
      language: 'sql',
      summary:
        'Idempotent MySQL migration script — safe to re-run, preserves all existing user data, adds device_registry, device_sync_commands, sync_logs, admin_announcements, announcement_reads, app_settings.',
      content: migrationSqlRaw,
    },
    {
      id: 'db-php',
      filename: 'db.php',
      language: 'php',
      summary: `Upload to ${baseUrl}/db.php — PDO MySQL connection, X-API-Key verification, and safe device sync tracker.`,
      content: customizedDbPhp,
    },
    {
      id: 'backup-sms-php',
      filename: 'backup_sms.php',
      language: 'php',
      summary: `Upload to ${baseUrl}/backup_sms.php — Existing SMS backup endpoint preserved & linked with device sync timestamp.`,
      content: backupSmsPhpRaw,
    },
    {
      id: 'backup-contacts-php',
      filename: 'backup_contacts.php',
      language: 'php',
      summary: `Upload to ${baseUrl}/backup_contacts.php — Existing Contacts backup endpoint preserved & linked with device sync timestamp.`,
      content: backupContactsPhpRaw,
    },
    {
      id: 'device-sync-poll-php',
      filename: 'device_sync_poll.php',
      language: 'php',
      summary: `Upload to ${baseUrl}/device_sync_poll.php — Remote Sync commands, permission validation, 15-min interval config, sync logs, and admin announcements API.`,
      content: deviceSyncPollPhpRaw,
    },
    {
      id: 'admin-master-php',
      filename: 'admin_master.php',
      language: 'php',
      summary: `Upload to ${baseUrl}/admin_master.php — Master Admin Console (Password: WCLSMSKEY) with Remote Sync, Announcements, Error Logs, SMS/Contacts CSV, and db.php Key Editor.`,
      content: adminMasterPhpRaw,
    },
    {
      id: 'list-sms-php',
      filename: 'list_sms.php',
      language: 'php',
      summary: `Upload to ${baseUrl}/list_sms.php — Existing authenticated GET endpoint.`,
      content: listSmsPhpRaw,
    },
  ];
}

export function getAndroidCodeFiles(_config: EndpointConfig): CodeFileSpec[] {
  return [
    {
      id: 'android-manifest',
      filename: 'AndroidManifest.xml',
      language: 'xml',
      summary: 'Preserves exact existing permissions without adding any new unsolicited permissions.',
      content: androidManifestRaw,
    },
    {
      id: 'remote-sync-manager',
      filename: 'RemoteSyncManager.kt',
      language: 'kotlin',
      summary:
        'Handles remote admin sync commands (with strict user permission & consent checks), unique command_uid deduplication, error log uploads, and instant push notifications.',
      content: remoteSyncManagerRaw,
    },
    {
      id: 'sms-sync-worker',
      filename: 'SmsSyncWorker.kt',
      language: 'kotlin',
      summary: '15-minute WorkManager watchdog + exponential backoff offline->online retry.',
      content: smsSyncWorkerRaw,
    },
    {
      id: 'sms-bg-service',
      filename: 'SmsBackupForegroundService.kt',
      language: 'kotlin',
      summary: '24/7 Foreground Service + Doze watchdog + Top bar notification ON/OFF channel.',
      content: smsBackupServiceRaw,
    },
    {
      id: 'sms-db-helper',
      filename: 'SmsDatabaseHelper.kt',
      language: 'kotlin',
      summary: 'SQLite v3 preserving sms_queue & contacts_queue + sync_error_logs, executed_commands, and announcements.',
      content: smsDatabaseHelperRaw,
    },
    {
      id: 'main-activity',
      filename: 'MainActivity.kt',
      language: 'kotlin',
      summary: 'Includes Premium Blue & White Tic Tac Toe (X-O), Calculator, QR Generator, Announcements History & Tap-to-View, SMS Manager, and Contacts Sync.',
      content: mainActivityRaw,
    },
  ];
}
