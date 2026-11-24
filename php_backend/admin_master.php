<?php
// Upload to: https://webcodelab.in/WCL/admin_master.php
// Master Admin Console for WCL:
// - Existing SMS Backups, Contacts Backups, CSV Export, Delete, and db.php X-API-Key Editor
// - Remote Device Sync (Single / Multiple Selected / All Devices, SMS / Contacts / Both)
// - Global Automatic Sync Interval (default 15 Minutes)
// - Sync History & Error Logs
// - Admin Announcements & Push Notifications (All / Selected Devices, Read Counts, Delete)

declare(strict_types=1);

require_once __DIR__ . '/db.php';

ini_set('session.use_strict_mode', '1');
session_set_cookie_params([
    'httponly' => true,
    'samesite' => 'Lax',
    'secure'   => (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off'),
]);
session_start();

// Default admin password SHA-256 corresponds to WCLSMSKEY; override via WCL_ADMIN_PASS_HASH env if desired.
const WCL_DEFAULT_PASS_SHA256 = 'a130723fc206463ec37d9a33e8d5b2ed867f87377c0b7f7abbddf5143bd24677';

function h(string $value): string {
    return htmlspecialchars($value, ENT_QUOTES | ENT_SUBSTITUTE, 'UTF-8');
}

function verifyAdminPassword(string $input): bool {
    $envHash = getenv('WCL_ADMIN_PASS_HASH');
    if (is_string($envHash) && $envHash !== '') {
        return password_verify($input, $envHash);
    }
    return hash_equals(WCL_DEFAULT_PASS_SHA256, hash('sha256', $input));
}

if (empty($_SESSION['csrf_token'])) {
    $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
}

$flashSuccess = '';
$flashError = '';

if (isset($_GET['action']) && $_GET['action'] === 'logout') {
    $_SESSION = [];
    session_destroy();
    header('Location: admin_master.php');
    exit;
}

if ($_SERVER['REQUEST_METHOD'] === 'POST' && ($_POST['action'] ?? '') === 'login') {
    $submittedCsrf = (string)($_POST['csrf_token'] ?? '');
    if (!hash_equals($_SESSION['csrf_token'], $submittedCsrf)) {
        $flashError = 'Security token mismatch. Please refresh and try again.';
    } else {
        $pass = (string)($_POST['admin_password'] ?? '');
        if ($pass !== '' && verifyAdminPassword($pass)) {
            session_regenerate_id(true);
            $_SESSION['wcl_admin_auth'] = true;
            $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
            header('Location: admin_master.php');
            exit;
        } else {
            $flashError = 'Invalid admin password.';
        }
    }
}

$isAuthenticated = !empty($_SESSION['wcl_admin_auth']);
$activeTab = (string)($_GET['tab'] ?? 'remote');
if (!in_array($activeTab, ['remote', 'announcements', 'logs', 'sms', 'contacts', 'settings'], true)) {
    $activeTab = 'remote';
}

if ($isAuthenticated) {
    $pdo = getDbConnection();

    // CSV export handlers
    if (isset($_GET['export']) && $_GET['export'] === 'sms') {
        header('Content-Type: text/csv; charset=utf-8');
        header('Content-Disposition: attachment; filename="wcl_sms_backups_' . date('Ymd_His') . '.csv"');
        $out = fopen('php://output', 'w');
        fputcsv($out, ['ID', 'Device ID', 'Sender', 'Message', 'Received At', 'Synced At']);
        $rows = $pdo->query('SELECT id, device_id, sender, message_body, received_at, synced_at FROM sms_backups ORDER BY received_at DESC LIMIT 5000');
        while ($row = $rows->fetch()) {
            fputcsv($out, $row);
        }
        fclose($out);
        exit;
    }

    if (isset($_GET['export']) && $_GET['export'] === 'contacts') {
        header('Content-Type: text/csv; charset=utf-8');
        header('Content-Disposition: attachment; filename="wcl_contacts_backups_' . date('Ymd_His') . '.csv"');
        $out = fopen('php://output', 'w');
        fputcsv($out, ['ID', 'Device ID', 'Name', 'Phone Number', 'Synced At']);
        $rows = $pdo->query('SELECT id, device_id, contact_name, phone_number, synced_at FROM contacts_backups ORDER BY contact_name ASC LIMIT 5000');
        while ($row = $rows->fetch()) {
            fputcsv($out, $row);
        }
        fclose($out);
        exit;
    }

    // Handle authenticated POST actions
    if ($_SERVER['REQUEST_METHOD'] === 'POST') {
        $action = (string)($_POST['action'] ?? '');
        $submittedCsrf = (string)($_POST['csrf_token'] ?? '');
        if (!hash_equals($_SESSION['csrf_token'], $submittedCsrf)) {
            $flashError = 'Invalid CSRF token. Please reload the page.';
        } else {
            try {
                if ($action === 'update_api_key') {
                    $newKey = trim((string)($_POST['new_api_key'] ?? ''));
                    if (strlen($newKey) < 8 || strlen($newKey) > 128) {
                        throw new RuntimeException('API key must be between 8 and 128 characters.');
                    }
                    $dbPath = __DIR__ . '/db.php';
                    $dbContent = file_get_contents($dbPath);
                    if ($dbContent === false) {
                        throw new RuntimeException('Could not read db.php');
                    }
                    $exported = var_export($newKey, true);
                    $updated = preg_replace(
                        "/define\\('SMS_SYNC_API_KEY',\\s*.*?\\);/s",
                        "define('SMS_SYNC_API_KEY', {$exported});",
                        $dbContent,
                        1,
                        $count
                    );
                    if ($updated === null || $count !== 1) {
                        throw new RuntimeException('Could not locate SMS_SYNC_API_KEY definition in db.php');
                    }
                    file_put_contents($dbPath, $updated, LOCK_EX);
                    $flashSuccess = 'SMS_SYNC_API_KEY updated successfully inside db.php.';
                    $activeTab = 'settings';
                } elseif ($action === 'save_sync_settings') {
                    $interval = max(15, min(1440, (int)($_POST['auto_sync_interval_minutes'] ?? 15)));
                    $remoteEnabled = isset($_POST['remote_sync_enabled']) ? '1' : '0';

                    $stmt = $pdo->prepare(
                        'INSERT INTO app_settings (setting_key, setting_value)
                         VALUES (:k, :v)
                         ON DUPLICATE KEY UPDATE setting_value = :v2'
                    );
                    $stmt->execute([':k' => 'auto_sync_interval_minutes', ':v' => (string)$interval, ':v2' => (string)$interval]);
                    $stmt->execute([':k' => 'remote_sync_enabled', ':v' => $remoteEnabled, ':v2' => $remoteEnabled]);
                    $flashSuccess = "Sync settings saved (Interval: {$interval} min, Remote Sync: " . ($remoteEnabled === '1' ? 'ON' : 'OFF') . ').';
                    $activeTab = 'remote';
                } elseif ($action === 'dispatch_sync') {
                    $syncType = (string)($_POST['sync_type'] ?? 'both');
                    if (!in_array($syncType, ['sms', 'contacts', 'both'], true)) {
                        $syncType = 'both';
                    }
                    $targetMode = (string)($_POST['target_mode'] ?? 'single');
                    if (!in_array($targetMode, ['single', 'multiple', 'all'], true)) {
                        $targetMode = 'single';
                    }

                    $targetIds = [];
                    if ($targetMode === 'all') {
                        $targetIds = $pdo->query('SELECT device_id FROM device_registry')->fetchAll(PDO::FETCH_COLUMN);
                    } elseif ($targetMode === 'single') {
                        $singleId = trim((string)($_POST['single_device_id'] ?? ''));
                        if ($singleId !== '') {
                            $targetIds = [$singleId];
                        }
                    } else {
                        $selected = $_POST['selected_devices'] ?? [];
                        if (is_array($selected)) {
                            foreach ($selected as $id) {
                                $clean = trim((string)$id);
                                if ($clean !== '') $targetIds[] = $clean;
                            }
                        }
                    }

                    $targetIds = array_values(array_unique($targetIds));
                    if (count($targetIds) === 0) {
                        throw new RuntimeException('Select at least one registered device to send a sync command.');
                    }

                    $devStmt = $pdo->prepare(
                        'SELECT device_id, sms_permission, contacts_permission, sms_auto_enabled, contacts_auto_enabled
                         FROM device_registry WHERE device_id = :id LIMIT 1'
                    );
                    $insCmd = $pdo->prepare(
                        'INSERT INTO device_sync_commands
                         (command_uid, device_id, sync_type, target_mode, status, requested_by, created_at)
                         VALUES (:uid, :device_id, :sync_type, :target_mode, \'pending\', \'admin\', NOW())'
                    );
                    $insLog = $pdo->prepare(
                        'INSERT INTO sync_logs
                         (device_id, command_uid, sync_type, trigger_source, status, items_synced, error_message, created_at)
                         VALUES (:device_id, NULL, :sync_type, \'remote_admin\', \'skipped_permission\', 0, :msg, NOW())'
                    );

                    $queued = 0;
                    $skippedPerm = 0;
                    foreach ($targetIds as $devId) {
                        $devStmt->execute([':id' => $devId]);
                        $dev = $devStmt->fetch();
                        if (!$dev) continue;

                        $smsAuthorized = ((int)$dev['sms_permission'] === 1 && (int)$dev['sms_auto_enabled'] === 1);
                        $contactsAuthorized = ((int)$dev['contacts_permission'] === 1 && (int)$dev['contacts_auto_enabled'] === 1);

                        $effectiveType = $syncType;
                        if ($syncType === 'sms' && !$smsAuthorized) {
                            $skippedPerm++;
                            $insLog->execute([
                                ':device_id' => $devId,
                                ':sync_type' => 'sms',
                                ':msg'       => 'Skipped: User has not granted SMS permission/consent.',
                            ]);
                            continue;
                        }
                        if ($syncType === 'contacts' && !$contactsAuthorized) {
                            $skippedPerm++;
                            $insLog->execute([
                                ':device_id' => $devId,
                                ':sync_type' => 'contacts',
                                ':msg'       => 'Skipped: User has not granted Contacts permission/consent.',
                            ]);
                            continue;
                        }
                        if ($syncType === 'both') {
                            if ($smsAuthorized && $contactsAuthorized) {
                                $effectiveType = 'both';
                            } elseif ($smsAuthorized) {
                                $effectiveType = 'sms';
                            } elseif ($contactsAuthorized) {
                                $effectiveType = 'contacts';
                            } else {
                                $skippedPerm++;
                                $insLog->execute([
                                    ':device_id' => $devId,
                                    ':sync_type' => 'both',
                                    ':msg'       => 'Skipped: User has not granted SMS or Contacts permission/consent.',
                                ]);
                                continue;
                            }
                        }

                        $insCmd->execute([
                            ':uid'         => generateUuidV4(),
                            ':device_id'   => $devId,
                            ':sync_type'   => $effectiveType,
                            ':target_mode' => $targetMode,
                        ]);
                        $queued++;
                    }

                    $flashSuccess = "Queued remote sync command for {$queued} authorized device(s)." .
                        ($skippedPerm > 0 ? " Skipped {$skippedPerm} device(s) without user permission/consent." : '');
                    $activeTab = 'remote';
                } elseif ($action === 'create_announcement') {
                    $title = mb_substr(trim((string)($_POST['ann_title'] ?? '')), 0, 180);
                    $message = mb_substr(trim((string)($_POST['ann_message'] ?? '')), 0, 4000);
                    $targetType = ((string)($_POST['ann_target_type'] ?? 'all')) === 'selected' ? 'selected' : 'all';
                    $selectedDevs = [];
                    if ($targetType === 'selected' && is_array($_POST['ann_devices'] ?? null)) {
                        foreach ($_POST['ann_devices'] as $d) {
                            $clean = trim((string)$d);
                            if ($clean !== '') $selectedDevs[] = $clean;
                        }
                        $selectedDevs = array_values(array_unique($selectedDevs));
                        if (count($selectedDevs) === 0) {
                            throw new RuntimeException('Select at least one device for targeted announcement.');
                        }
                    }
                    if ($title === '' || $message === '') {
                        throw new RuntimeException('Announcement title and message are required.');
                    }

                    $stmt = $pdo->prepare(
                        'INSERT INTO admin_announcements
                         (announcement_uid, title, message, target_type, target_devices, created_by, is_active, created_at)
                         VALUES (:uid, :title, :message, :target_type, :target_devices, \'admin\', 1, NOW())'
                    );
                    $stmt->execute([
                        ':uid'            => generateUuidV4(),
                        ':title'          => $title,
                        ':message'        => $message,
                        ':target_type'    => $targetType,
                        ':target_devices' => $targetType === 'selected' ? json_encode($selectedDevs) : null,
                    ]);
                    $flashSuccess = 'Announcement published! Eligible devices will show an instant notification on next poll.';
                    $activeTab = 'announcements';
                } elseif ($action === 'delete_announcement') {
                    $id = (int)($_POST['ann_id'] ?? 0);
                    if ($id > 0) {
                        $stmt = $pdo->prepare('DELETE FROM admin_announcements WHERE id = :id');
                        $stmt->execute([':id' => $id]);
                        $flashSuccess = 'Announcement deleted.';
                    }
                    $activeTab = 'announcements';
                } elseif ($action === 'toggle_announcement') {
                    $id = (int)($_POST['ann_id'] ?? 0);
                    if ($id > 0) {
                        $stmt = $pdo->prepare('UPDATE admin_announcements SET is_active = 1 - is_active WHERE id = :id');
                        $stmt->execute([':id' => $id]);
                        $flashSuccess = 'Announcement status updated.';
                    }
                    $activeTab = 'announcements';
                } elseif ($action === 'delete_sms') {
                    $id = (int)($_POST['id'] ?? 0);
                    if ($id > 0) {
                        $stmt = $pdo->prepare('DELETE FROM sms_backups WHERE id = :id');
                        $stmt->execute([':id' => $id]);
                        $flashSuccess = 'SMS record deleted.';
                    }
                    $activeTab = 'sms';
                } elseif ($action === 'delete_contact') {
                    $id = (int)($_POST['id'] ?? 0);
                    if ($id > 0) {
                        $stmt = $pdo->prepare('DELETE FROM contacts_backups WHERE id = :id');
                        $stmt->execute([':id' => $id]);
                        $flashSuccess = 'Contact record deleted.';
                    }
                    $activeTab = 'contacts';
                }
            } catch (Throwable $e) {
                $flashError = $e->getMessage();
            }
        }
    }

    // Fetch dashboard data safely
    $settings = ['auto_sync_interval_minutes' => '15', 'remote_sync_enabled' => '1'];
    try {
        foreach ($pdo->query('SELECT setting_key, setting_value FROM app_settings')->fetchAll() as $s) {
            $settings[$s['setting_key']] = $s['setting_value'];
        }
    } catch (Throwable $ignored) {}

    $devices = [];
    try {
        $devices = $pdo->query(
            'SELECT d.*,
                    (SELECT c.status FROM device_sync_commands c
                     WHERE c.device_id = d.device_id
                     ORDER BY c.created_at DESC LIMIT 1) AS last_cmd_status,
                    (SELECT c.sync_type FROM device_sync_commands c
                     WHERE c.device_id = d.device_id
                     ORDER BY c.created_at DESC LIMIT 1) AS last_cmd_type,
                    (SELECT c.created_at FROM device_sync_commands c
                     WHERE c.device_id = d.device_id
                     ORDER BY c.created_at DESC LIMIT 1) AS last_cmd_at
             FROM device_registry d
             ORDER BY d.last_seen_at DESC'
        )->fetchAll();
    } catch (Throwable $ignored) {}

    $announcements = [];
    try {
        $announcements = $pdo->query(
            'SELECT a.*,
                    (SELECT COUNT(*) FROM announcement_reads r WHERE r.announcement_id = a.id) AS read_count
             FROM admin_announcements a
             ORDER BY a.created_at DESC
             LIMIT 100'
        )->fetchAll();
    } catch (Throwable $ignored) {}

    $syncLogs = [];
    try {
        $syncLogs = $pdo->query(
            'SELECT * FROM sync_logs ORDER BY created_at DESC LIMIT 150'
        )->fetchAll();
    } catch (Throwable $ignored) {}

    $smsRows = [];
    $smsSearch = trim((string)($_GET['q_sms'] ?? ''));
    try {
        if ($smsSearch !== '') {
            $like = '%' . $smsSearch . '%';
            $stmt = $pdo->prepare('SELECT * FROM sms_backups WHERE sender LIKE :q1 OR message_body LIKE :q2 OR device_id LIKE :q3 ORDER BY received_at DESC LIMIT 200');
            $stmt->execute([':q1' => $like, ':q2' => $like, ':q3' => $like]);
            $smsRows = $stmt->fetchAll();
        } else {
            $smsRows = $pdo->query('SELECT * FROM sms_backups ORDER BY received_at DESC LIMIT 200')->fetchAll();
        }
    } catch (Throwable $ignored) {}

    $contactRows = [];
    $contactSearch = trim((string)($_GET['q_contact'] ?? ''));
    try {
        if ($contactSearch !== '') {
            $like = '%' . $contactSearch . '%';
            $stmt = $pdo->prepare('SELECT * FROM contacts_backups WHERE contact_name LIKE :q1 OR phone_number LIKE :q2 OR device_id LIKE :q3 ORDER BY contact_name ASC LIMIT 250');
            $stmt->execute([':q1' => $like, ':q2' => $like, ':q3' => $like]);
            $contactRows = $stmt->fetchAll();
        } else {
            $contactRows = $pdo->query('SELECT * FROM contacts_backups ORDER BY synced_at DESC LIMIT 250')->fetchAll();
        }
    } catch (Throwable $ignored) {}

    $totalSms = 0;
    $totalContacts = 0;
    try {
        $totalSms = (int)$pdo->query('SELECT COUNT(*) FROM sms_backups')->fetchColumn();
    } catch (Throwable $ignored) {}
    try {
        $totalContacts = (int)$pdo->query('SELECT COUNT(*) FROM contacts_backups')->fetchColumn();
    } catch (Throwable $ignored) {}
}
?>
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>WCL Master Admin Console</title>
  <style>
    * { box-sizing: border-box; }
    body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; background: #f1f5f9; color: #0f172a; }
    .topbar { background: #0f172a; color: #fff; padding: 14px 22px; display: flex; justify-content: space-between; align-items: center; }
    .topbar h1 { margin: 0; font-size: 18px; letter-spacing: 0.3px; }
    .wrap { max-width: 1280px; margin: 20px auto; padding: 0 16px; }
    .card { background: #fff; border: 1px solid #e2e8f0; border-radius: 14px; padding: 18px; margin-bottom: 18px; }
    .tabs { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 18px; }
    .tab { text-decoration: none; padding: 9px 15px; border-radius: 10px; font-size: 13px; font-weight: 600; background: #fff; border: 1px solid #cbd5e1; color: #334155; }
    .tab.active { background: #2563eb; border-color: #2563eb; color: #fff; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(190px, 1fr)); gap: 12px; margin-bottom: 18px; }
    .stat { background: #fff; border: 1px solid #e2e8f0; border-radius: 12px; padding: 14px; }
    .stat .label { font-size: 12px; color: #64748b; }
    .stat .val { font-size: 24px; font-weight: 700; color: #0f172a; margin-top: 4px; }
    .btn { display: inline-block; padding: 8px 14px; border: 0; border-radius: 9px; font-size: 13px; font-weight: 600; cursor: pointer; text-decoration: none; }
    .btn-primary { background: #2563eb; color: #fff; }
    .btn-dark { background: #0f172a; color: #fff; }
    .btn-danger { background: #dc2626; color: #fff; }
    .btn-outline { background: #fff; color: #334155; border: 1px solid #cbd5e1; }
    input[type=text], input[type=password], input[type=number], select, textarea { width: 100%; padding: 9px 11px; border: 1px solid #cbd5e1; border-radius: 9px; font-size: 13px; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th, td { text-align: left; padding: 10px 8px; border-bottom: 1px solid #e2e8f0; vertical-align: top; }
    th { color: #475569; font-weight: 600; background: #f8fafc; }
    .badge { display: inline-block; padding: 3px 8px; border-radius: 6px; font-size: 11px; font-weight: 700; }
    .ok { background: #dcfce7; color: #166534; }
    .warn { background: #fef3c7; color: #92400e; }
    .err { background: #fee2e2; color: #991b1b; }
    .info { background: #dbeafe; color: #1e40af; }
    .alert { padding: 12px 14px; border-radius: 10px; margin-bottom: 14px; font-size: 13px; font-weight: 600; }
    .alert-ok { background: #dcfce7; color: #166534; border: 1px solid #86efac; }
    .alert-err { background: #fee2e2; color: #991b1b; border: 1px solid #fca5a5; }
    .row { display: flex; flex-wrap: wrap; gap: 12px; align-items: flex-end; }
    .col { flex: 1; min-width: 200px; }
    .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }
  </style>
</head>
<body>
<div class="topbar">
  <h1>WCL Master Admin Panel</h1>
  <?php if ($isAuthenticated): ?>
    <a class="btn btn-outline" href="admin_master.php?action=logout">Logout</a>
  <?php endif; ?>
</div>

<div class="wrap">
  <?php if ($flashSuccess !== ''): ?>
    <div class="alert alert-ok"><?= h($flashSuccess) ?></div>
  <?php endif; ?>
  <?php if ($flashError !== ''): ?>
    <div class="alert alert-err"><?= h($flashError) ?></div>
  <?php endif; ?>

  <?php if (!$isAuthenticated): ?>
    <div class="card" style="max-width:420px;margin:60px auto;">
      <h2 style="margin-top:0;">Admin Sign In</h2>
      <form method="post">
        <input type="hidden" name="action" value="login" />
        <input type="hidden" name="csrf_token" value="<?= h($_SESSION['csrf_token']) ?>" />
        <label style="display:block;font-size:13px;font-weight:600;margin-bottom:6px;">Admin Password</label>
        <input type="password" name="admin_password" required autofocus placeholder="Enter Master Password" />
        <button class="btn btn-primary" style="width:100%;margin-top:14px;" type="submit">Unlock Admin Console</button>
      </form>
    </div>
  <?php else: ?>
    <div class="grid">
      <div class="stat"><div class="label">Registered Devices</div><div class="val"><?= count($devices) ?></div></div>
      <div class="stat"><div class="label">Total SMS Backed Up</div><div class="val"><?= $totalSms ?></div></div>
      <div class="stat"><div class="label">Total Contacts Backed Up</div><div class="val"><?= $totalContacts ?></div></div>
      <div class="stat"><div class="label">Auto-Sync Interval</div><div class="val"><?= h($settings['auto_sync_interval_minutes']) ?> min</div></div>
    </div>

    <div class="tabs">
      <a class="tab <?= $activeTab === 'remote' ? 'active' : '' ?>" href="?tab=remote">Remote Device Sync</a>
      <a class="tab <?= $activeTab === 'announcements' ? 'active' : '' ?>" href="?tab=announcements">Announcements &amp; Push</a>
      <a class="tab <?= $activeTab === 'logs' ? 'active' : '' ?>" href="?tab=logs">Sync &amp; Error Logs</a>
      <a class="tab <?= $activeTab === 'sms' ? 'active' : '' ?>" href="?tab=sms">SMS Backups</a>
      <a class="tab <?= $activeTab === 'contacts' ? 'active' : '' ?>" href="?tab=contacts">Contacts Backups</a>
      <a class="tab <?= $activeTab === 'settings' ? 'active' : '' ?>" href="?tab=settings">db.php Key Editor</a>
    </div>

    <?php if ($activeTab === 'remote'): ?>
      <div class="card">
        <h3 style="margin-top:0;">Automatic Sync Schedule &amp; Remote Control</h3>
        <form method="post" class="row">
          <input type="hidden" name="action" value="save_sync_settings" />
          <input type="hidden" name="csrf_token" value="<?= h($_SESSION['csrf_token']) ?>" />
          <div class="col">
            <label style="display:block;font-size:12px;font-weight:600;margin-bottom:5px;">Automatic Background Interval (Minutes)</label>
            <select name="auto_sync_interval_minutes">
              <?php foreach ([15, 30, 60, 120] as $mins): ?>
                <option value="<?= $mins ?>" <?= (int)$settings['auto_sync_interval_minutes'] === $mins ? 'selected' : '' ?>>
                  Every <?= $mins ?> Minutes <?= $mins === 15 ? '(Recommended)' : '' ?>
                </option>
              <?php endforeach; ?>
            </select>
          </div>
          <div class="col">
            <label style="display:flex;align-items:center;gap:8px;font-size:13px;font-weight:600;padding-bottom:8px;">
              <input type="checkbox" name="remote_sync_enabled" value="1" <?= $settings['remote_sync_enabled'] === '1' ? 'checked' : '' ?> />
              Allow Remote Sync Commands from Admin
            </label>
          </div>
          <div><button class="btn btn-dark" type="submit">Save Sync Settings</button></div>
        </form>
      </div>

      <div class="card">
        <h3 style="margin-top:0;">Remote Device Sync (Single / Selected / All Authorized Devices)</h3>
        <p style="font-size:12px;color:#475569;margin-top:0;">Note: Only devices where the user has actively granted OS permissions and backup consent will execute remote commands.</p>
        <form method="post">
          <input type="hidden" name="action" value="dispatch_sync" />
          <input type="hidden" name="csrf_token" value="<?= h($_SESSION['csrf_token']) ?>" />
          <div class="row" style="margin-bottom:14px;">
            <div class="col">
              <label style="display:block;font-size:12px;font-weight:600;margin-bottom:5px;">Sync Target Data</label>
              <select name="sync_type">
                <option value="both">SMS + Contacts (Both)</option>
                <option value="sms">SMS Only</option>
                <option value="contacts">Contacts Only</option>
              </select>
            </div>
            <div>
              <button class="btn btn-primary" type="submit" name="target_mode" value="multiple">Sync Selected Devices</button>
              <button class="btn btn-dark" type="submit" name="target_mode" value="all">Sync All Devices</button>
            </div>
          </div>

          <div style="overflow-x:auto;">
            <table>
              <thead>
                <tr>
                  <th>Select</th>
                  <th>Device</th>
                  <th>User Permissions</th>
                  <th>Last SMS Sync</th>
                  <th>Last Contacts Sync</th>
                  <th>Latest Remote Status</th>
                  <th>Quick Single Sync</th>
                </tr>
              </thead>
              <tbody>
                <?php if (empty($devices)): ?>
                  <tr><td colspan="7" style="text-align:center;color:#64748b;">No registered devices yet.</td></tr>
                <?php endif; ?>
                <?php foreach ($devices as $dev): ?>
                  <tr>
                    <td><input type="checkbox" name="selected_devices[]" value="<?= h($dev['device_id']) ?>" /></td>
                    <td>
                      <strong><?= h($dev['device_name'] !== '' ? $dev['device_name'] : 'Android Device') ?></strong><br/>
                      <span class="mono" style="color:#64748b;"><?= h($dev['device_id']) ?></span><br/>
                      <span style="font-size:11px;color:#64748b;">Seen: <?= h((string)$dev['last_seen_at']) ?></span>
                    </td>
                    <td>
                      <span class="badge <?= ((int)$dev['sms_permission'] === 1 && (int)$dev['sms_auto_enabled'] === 1) ? 'ok' : 'err' ?>">
                        SMS: <?= ((int)$dev['sms_permission'] === 1 && (int)$dev['sms_auto_enabled'] === 1) ? 'Authorized' : 'Off/Denied' ?>
                      </span><br/>
                      <span class="badge <?= ((int)$dev['contacts_permission'] === 1 && (int)$dev['contacts_auto_enabled'] === 1) ? 'ok' : 'err' ?>" style="margin-top:4px;">
                        Contacts: <?= ((int)$dev['contacts_permission'] === 1 && (int)$dev['contacts_auto_enabled'] === 1) ? 'Authorized' : 'Off/Denied' ?>
                      </span>
                    </td>
                    <td class="mono"><?= h((string)($dev['last_sms_sync_at'] ?? 'Never')) ?></td>
                    <td class="mono"><?= h((string)($dev['last_contacts_sync_at'] ?? 'Never')) ?></td>
                    <td>
                      <?php if (!empty($dev['last_cmd_status'])): ?>
                        <span class="badge <?= $dev['last_cmd_status'] === 'completed' ? 'ok' : ($dev['last_cmd_status'] === 'pending' ? 'info' : 'warn') ?>">
                          <?= h(strtoupper((string)$dev['last_cmd_status'])) ?> (<?= h((string)$dev['last_cmd_type']) ?>)
                        </span><br/>
                        <span class="mono" style="color:#64748b;"><?= h((string)$dev['last_cmd_at']) ?></span>
                      <?php else: ?>
                        <span style="color:#94a3b8;">No remote cmds</span>
                      <?php endif; ?>
                    </td>
                    <td>
                      <button class="btn btn-outline" type="submit" name="target_mode" value="single"
                        onclick="this.form.single_device_id.value='<?= h($dev['device_id']) ?>'">Sync Device</button>
                    </td>
                  </tr>
                <?php endforeach; ?>
              </tbody>
            </table>
          </div>
          <input type="hidden" name="single_device_id" value="" />
        </form>
      </div>

    <?php elseif ($activeTab === 'announcements'): ?>
      <div class="card">
        <h3 style="margin-top:0;">Send Announcement &amp; Instant Notification</h3>
        <form method="post">
          <input type="hidden" name="action" value="create_announcement" />
          <input type="hidden" name="csrf_token" value="<?= h($_SESSION['csrf_token']) ?>" />
          <div class="row" style="margin-bottom:12px;">
            <div class="col">
              <label style="display:block;font-size:12px;font-weight:600;margin-bottom:5px;">Announcement Title</label>
              <input type="text" name="ann_title" required maxlength="180" placeholder="e.g. Scheduled Sync Update" />
            </div>
            <div class="col">
              <label style="display:block;font-size:12px;font-weight:600;margin-bottom:5px;">Target Audience</label>
              <select name="ann_target_type" onchange="document.getElementById('annDevBox').style.display=this.value==='selected'?'block':'none';">
                <option value="all">Send to All Devices</option>
                <option value="selected">Send to Selected Devices</option>
              </select>
            </div>
          </div>

          <div id="annDevBox" style="display:none;margin-bottom:12px;padding:10px;background:#f8fafc;border:1px solid #e2e8f0;border-radius:10px;">
            <strong style="font-size:12px;">Select Target Devices:</strong>
            <div style="display:flex;flex-wrap:wrap;gap:10px;margin-top:6px;">
              <?php foreach ($devices as $dev): ?>
                <label style="font-size:12px;">
                  <input type="checkbox" name="ann_devices[]" value="<?= h($dev['device_id']) ?>" />
                  <?= h($dev['device_name'] !== '' ? $dev['device_name'] : $dev['device_id']) ?>
                </label>
              <?php endforeach; ?>
            </div>
          </div>

          <label style="display:block;font-size:12px;font-weight:600;margin-bottom:5px;">Message</label>
          <textarea name="ann_message" rows="3" required maxlength="4000" placeholder="Enter full announcement details..."></textarea>
          <button class="btn btn-primary" style="margin-top:12px;" type="submit">Publish Announcement</button>
        </form>
      </div>

      <div class="card">
        <h3 style="margin-top:0;">Announcement History</h3>
        <div style="overflow-x:auto;">
          <table>
            <thead>
              <tr><th>Title &amp; Message</th><th>Target</th><th>Reads</th><th>Date &amp; Time</th><th>Status</th><th>Manage</th></tr>
            </thead>
            <tbody>
              <?php if (empty($announcements)): ?>
                <tr><td colspan="6" style="text-align:center;color:#64748b;">No announcements yet.</td></tr>
              <?php endif; ?>
              <?php foreach ($announcements as $ann): ?>
                <tr>
                  <td>
                    <strong><?= h($ann['title']) ?></strong><br/>
                    <span style="color:#475569;"><?= nl2br(h($ann['message'])) ?></span>
                  </td>
                  <td><?= h($ann['target_type'] === 'all' ? 'All Devices' : 'Selected') ?></td>
                  <td><span class="badge info"><?= (int)$ann['read_count'] ?> reads</span></td>
                  <td class="mono"><?= h((string)$ann['created_at']) ?></td>
                  <td><span class="badge <?= (int)$ann['is_active'] === 1 ? 'ok' : 'warn' ?>"><?= (int)$ann['is_active'] === 1 ? 'Active' : 'Paused' ?></span></td>
                  <td>
                    <form method="post" style="display:inline;">
                      <input type="hidden" name="csrf_token" value="<?= h($_SESSION['csrf_token']) ?>" />
                      <input type="hidden" name="ann_id" value="<?= (int)$ann['id'] ?>" />
                      <button class="btn btn-outline" type="submit" name="action" value="toggle_announcement">Toggle</button>
                      <button class="btn btn-danger" type="submit" name="action" value="delete_announcement">Delete</button>
                    </form>
                  </td>
                </tr>
              <?php endforeach; ?>
            </tbody>
          </table>
        </div>
      </div>

    <?php elseif ($activeTab === 'logs'): ?>
      <div class="card">
        <h3 style="margin-top:0;">Sync History &amp; Error Logs</h3>
        <div style="overflow-x:auto;">
          <table>
            <thead><tr><th>Time</th><th>Device ID</th><th>Type</th><th>Trigger</th><th>Status</th><th>Items</th><th>Details / Error</th></tr></thead>
            <tbody>
              <?php if (empty($syncLogs)): ?>
                <tr><td colspan="7" style="text-align:center;color:#64748b;">No sync logs recorded yet.</td></tr>
              <?php endif; ?>
              <?php foreach ($syncLogs as $log): ?>
                <tr>
                  <td class="mono"><?= h((string)$log['created_at']) ?></td>
                  <td class="mono"><?= h((string)$log['device_id']) ?></td>
                  <td><?= h((string)$log['sync_type']) ?></td>
                  <td><?= h((string)$log['trigger_source']) ?></td>
                  <td>
                    <span class="badge <?= $log['status'] === 'success' ? 'ok' : ($log['status'] === 'failed' ? 'err' : 'warn') ?>">
                      <?= h((string)$log['status']) ?>
                    </span>
                  </td>
                  <td><?= (int)$log['items_synced'] ?></td>
                  <td><?= h((string)($log['error_message'] ?? '-')) ?></td>
                </tr>
              <?php endforeach; ?>
            </tbody>
          </table>
        </div>
      </div>

    <?php elseif ($activeTab === 'sms'): ?>
      <div class="card">
        <div class="row" style="justify-content:space-between;margin-bottom:12px;">
          <form method="get" class="row" style="flex:1;">
            <input type="hidden" name="tab" value="sms" />
            <div class="col"><input type="text" name="q_sms" value="<?= h($smsSearch) ?>" placeholder="Search sender, message, or device ID..." /></div>
            <button class="btn btn-primary" type="submit">Search</button>
          </form>
          <a class="btn btn-dark" href="admin_master.php?export=sms">Export CSV</a>
        </div>
        <div style="overflow-x:auto;">
          <table>
            <thead><tr><th>Device</th><th>Sender</th><th>Message</th><th>Received</th><th>Action</th></tr></thead>
            <tbody>
              <?php foreach ($smsRows as $r): ?>
                <tr>
                  <td class="mono"><?= h((string)$r['device_id']) ?></td>
                  <td><strong><?= h((string)$r['sender']) ?></strong></td>
                  <td><?= nl2br(h((string)$r['message_body'])) ?></td>
                  <td class="mono"><?= h((string)$r['received_at']) ?></td>
                  <td>
                    <form method="post">
                      <input type="hidden" name="csrf_token" value="<?= h($_SESSION['csrf_token']) ?>" />
                      <input type="hidden" name="action" value="delete_sms" />
                      <input type="hidden" name="id" value="<?= (int)$r['id'] ?>" />
                      <button class="btn btn-danger" type="submit">Delete</button>
                    </form>
                  </td>
                </tr>
              <?php endforeach; ?>
            </tbody>
          </table>
        </div>
      </div>

    <?php elseif ($activeTab === 'contacts'): ?>
      <div class="card">
        <div class="row" style="justify-content:space-between;margin-bottom:12px;">
          <form method="get" class="row" style="flex:1;">
            <input type="hidden" name="tab" value="contacts" />
            <div class="col"><input type="text" name="q_contact" value="<?= h($contactSearch) ?>" placeholder="Search contact name, phone, or device ID..." /></div>
            <button class="btn btn-primary" type="submit">Search</button>
          </form>
          <a class="btn btn-dark" href="admin_master.php?export=contacts">Export CSV</a>
        </div>
        <div style="overflow-x:auto;">
          <table>
            <thead><tr><th>Device</th><th>Name</th><th>Phone Number</th><th>Synced</th><th>Action</th></tr></thead>
            <tbody>
              <?php foreach ($contactRows as $c): ?>
                <tr>
                  <td class="mono"><?= h((string)$c['device_id']) ?></td>
                  <td><strong><?= h((string)$c['contact_name']) ?></strong></td>
                  <td class="mono"><?= h((string)$c['phone_number']) ?></td>
                  <td class="mono"><?= h((string)$c['synced_at']) ?></td>
                  <td>
                    <form method="post">
                      <input type="hidden" name="csrf_token" value="<?= h($_SESSION['csrf_token']) ?>" />
                      <input type="hidden" name="action" value="delete_contact" />
                      <input type="hidden" name="id" value="<?= (int)$c['id'] ?>" />
                      <button class="btn btn-danger" type="submit">Delete</button>
                    </form>
                  </td>
                </tr>
              <?php endforeach; ?>
            </tbody>
          </table>
        </div>
      </div>

    <?php elseif ($activeTab === 'settings'): ?>
      <div class="card" style="max-width:560px;">
        <h3 style="margin-top:0;">Edit SMS_SYNC_API_KEY in db.php</h3>
        <form method="post">
          <input type="hidden" name="action" value="update_api_key" />
          <input type="hidden" name="csrf_token" value="<?= h($_SESSION['csrf_token']) ?>" />
          <label style="display:block;font-size:12px;font-weight:600;margin-bottom:6px;">New X-API-Key (min 8 chars)</label>
          <input type="text" name="new_api_key" required minlength="8" maxlength="128" value="<?= h(SMS_SYNC_API_KEY) ?>" />
          <button class="btn btn-primary" style="margin-top:12px;" type="submit">Save API Key to db.php</button>
        </form>
      </div>
    <?php endif; ?>
  <?php endif; ?>
</div>
</body>
</html>
