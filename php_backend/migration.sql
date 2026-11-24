-- WCL Complete System Upgrade - Idempotent MySQL Migration Script
-- Safe to run multiple times in cPanel -> phpMyAdmin.
-- Never drops, deletes, or resets existing sms_backups or contacts_backups data.

-- 1. Preserve existing core tables if not already created
CREATE TABLE IF NOT EXISTS sms_backups (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  device_id VARCHAR(100) NOT NULL,
  sender VARCHAR(64) NOT NULL,
  message_body TEXT NOT NULL,
  received_at DATETIME NOT NULL,
  synced_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  sms_hash CHAR(64) NOT NULL,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uniq_device_hash (device_id, sms_hash),
  INDEX idx_device_received (device_id, received_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS contacts_backups (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  device_id VARCHAR(100) NOT NULL,
  contact_name VARCHAR(160) NOT NULL,
  phone_number VARCHAR(64) NOT NULL,
  contact_hash CHAR(64) NOT NULL,
  synced_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uniq_device_contact_hash (device_id, contact_hash),
  INDEX idx_device_name (device_id, contact_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 2. Device registry (tracks permissions, auto-sync consent, and last sync timestamps)
CREATE TABLE IF NOT EXISTS device_registry (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  device_id VARCHAR(100) NOT NULL,
  device_name VARCHAR(120) NOT NULL DEFAULT '',
  sms_permission TINYINT(1) NOT NULL DEFAULT 0,
  contacts_permission TINYINT(1) NOT NULL DEFAULT 0,
  sms_auto_enabled TINYINT(1) NOT NULL DEFAULT 0,
  contacts_auto_enabled TINYINT(1) NOT NULL DEFAULT 0,
  last_sms_sync_at DATETIME NULL,
  last_contacts_sync_at DATETIME NULL,
  last_seen_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uniq_device_id (device_id),
  INDEX idx_device_last_seen (last_seen_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 3. Automatic Sync & Global App Settings
CREATE TABLE IF NOT EXISTS app_settings (
  setting_key VARCHAR(80) PRIMARY KEY,
  setting_value VARCHAR(255) NOT NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO app_settings (setting_key, setting_value) VALUES
  ('auto_sync_interval_minutes', '15'),
  ('remote_sync_enabled', '1');

-- 4. Remote Sync Commands (Unique command_uid prevents duplicate execution)
CREATE TABLE IF NOT EXISTS device_sync_commands (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  command_uid CHAR(36) NOT NULL,
  device_id VARCHAR(100) NOT NULL,
  sync_type ENUM('sms', 'contacts', 'both') NOT NULL DEFAULT 'both',
  target_mode ENUM('single', 'multiple', 'all') NOT NULL DEFAULT 'single',
  status ENUM('pending', 'in_progress', 'completed', 'failed', 'denied') NOT NULL DEFAULT 'pending',
  requested_by VARCHAR(64) NOT NULL DEFAULT 'admin',
  result_message VARCHAR(500) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  executed_at DATETIME NULL,
  UNIQUE KEY uniq_command_uid (command_uid),
  INDEX idx_cmd_device_status (device_id, status, created_at DESC),
  CONSTRAINT fk_cmd_device
    FOREIGN KEY (device_id) REFERENCES device_registry(device_id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 5. Sync History & Error Logs
CREATE TABLE IF NOT EXISTS sync_logs (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  device_id VARCHAR(100) NOT NULL,
  command_uid CHAR(36) NULL,
  sync_type ENUM('sms', 'contacts', 'both') NOT NULL,
  trigger_source ENUM('auto', 'manual', 'remote_admin', 'workmanager') NOT NULL DEFAULT 'auto',
  status ENUM('success', 'failed', 'skipped_permission', 'queued_offline') NOT NULL,
  items_synced INT UNSIGNED NOT NULL DEFAULT 0,
  error_message VARCHAR(1000) NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_logs_device_time (device_id, created_at DESC),
  INDEX idx_logs_status_time (status, created_at DESC),
  CONSTRAINT fk_log_device
    FOREIGN KEY (device_id) REFERENCES device_registry(device_id)
    ON DELETE CASCADE ON UPDATE CASCADE,
  CONSTRAINT fk_log_cmd
    FOREIGN KEY (command_uid) REFERENCES device_sync_commands(command_uid)
    ON DELETE SET NULL ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 6. Admin Announcements
CREATE TABLE IF NOT EXISTS admin_announcements (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  announcement_uid CHAR(36) NOT NULL,
  title VARCHAR(180) NOT NULL,
  message TEXT NOT NULL,
  target_type ENUM('all', 'selected') NOT NULL DEFAULT 'all',
  target_devices TEXT NULL,
  created_by VARCHAR(64) NOT NULL DEFAULT 'admin',
  is_active TINYINT(1) NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uniq_announcement_uid (announcement_uid),
  INDEX idx_ann_active_time (is_active, created_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 7. Announcement Read Receipts
CREATE TABLE IF NOT EXISTS announcement_reads (
  id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  announcement_id BIGINT UNSIGNED NOT NULL,
  device_id VARCHAR(100) NOT NULL,
  read_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uniq_ann_device (announcement_id, device_id),
  INDEX idx_read_device_time (device_id, read_at DESC),
  CONSTRAINT fk_read_ann
    FOREIGN KEY (announcement_id) REFERENCES admin_announcements(id)
    ON DELETE CASCADE,
  CONSTRAINT fk_read_device
    FOREIGN KEY (device_id) REFERENCES device_registry(device_id)
    ON DELETE CASCADE ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 8. Backfill device_registry safely from existing SMS & Contacts records
INSERT IGNORE INTO device_registry (device_id, last_sms_sync_at, last_seen_at)
SELECT device_id, MAX(synced_at), MAX(synced_at)
FROM sms_backups
GROUP BY device_id;

INSERT IGNORE INTO device_registry (device_id, last_contacts_sync_at, last_seen_at)
SELECT device_id, MAX(synced_at), MAX(synced_at)
FROM contacts_backups
GROUP BY device_id;

-- 9. Safe idempotent column verification procedure (never duplicates columns on re-run)
DROP PROCEDURE IF EXISTS wcl_safe_migrate;
DELIMITER $$
CREATE PROCEDURE wcl_safe_migrate()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'device_registry' AND COLUMN_NAME = 'device_name'
  ) THEN
    ALTER TABLE device_registry ADD COLUMN device_name VARCHAR(120) NOT NULL DEFAULT '' AFTER device_id;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM INFORMATION_SCHEMA.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'device_registry' AND COLUMN_NAME = 'last_contacts_sync_at'
  ) THEN
    ALTER TABLE device_registry ADD COLUMN last_contacts_sync_at DATETIME NULL AFTER last_sms_sync_at;
  END IF;
END$$
DELIMITER ;
CALL wcl_safe_migrate();
DROP PROCEDURE IF EXISTS wcl_safe_migrate;
