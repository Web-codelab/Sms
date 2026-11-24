<?php
// Upload to: https://webcodelab.in/WCL/device_sync_poll.php
// Device endpoint (authenticated via X-API-Key):
// - Registers/updates device permission & consent state
// - Receives command acknowledgements (idempotent by command_uid)
// - Stores client sync error/history logs
// - Stores announcement read receipts
// - Returns pending remote sync commands, app_settings, and active announcements

declare(strict_types=1);

require_once __DIR__ . '/db.php';
sendJsonHeader();

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(['status' => 'error', 'message' => 'Only POST requests are allowed.']);
    exit;
}

verifyApiKeyOrExit();

$rawBody = file_get_contents('php://input');
$payload = json_decode($rawBody ?: '', true);

if (!is_array($payload)) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'message' => 'Invalid JSON payload.']);
    exit;
}

$deviceId = trim((string)($payload['device_id'] ?? ''));
if ($deviceId === '' || strlen($deviceId) > 100 || !preg_match('/^[A-Za-z0-9._:-]+$/', $deviceId)) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'message' => 'Valid device_id is required.']);
    exit;
}

$deviceName = mb_substr(trim((string)($payload['device_name'] ?? '')), 0, 120);
$smsPerm = !empty($payload['sms_permission']) ? 1 : 0;
$contactsPerm = !empty($payload['contacts_permission']) ? 1 : 0;
$smsAuto = !empty($payload['sms_auto_enabled']) ? 1 : 0;
$contactsAuto = !empty($payload['contacts_auto_enabled']) ? 1 : 0;

try {
    $pdo = getDbConnection();
    $pdo->beginTransaction();

    // 1. Upsert device permission & consent state
    $upsertDevice = $pdo->prepare(
        'INSERT INTO device_registry
         (device_id, device_name, sms_permission, contacts_permission, sms_auto_enabled, contacts_auto_enabled, last_seen_at)
         VALUES (:device_id, :device_name, :sms_perm, :contacts_perm, :sms_auto, :contacts_auto, NOW())
         ON DUPLICATE KEY UPDATE
           device_name = IF(:device_name_u <> \'\', :device_name_u2, device_name),
           sms_permission = :sms_perm_u,
           contacts_permission = :contacts_perm_u,
           sms_auto_enabled = :sms_auto_u,
           contacts_auto_enabled = :contacts_auto_u,
           last_seen_at = NOW()'
    );
    $upsertDevice->execute([
        ':device_id'       => $deviceId,
        ':device_name'     => $deviceName,
        ':sms_perm'        => $smsPerm,
        ':contacts_perm'   => $contactsPerm,
        ':sms_auto'        => $smsAuto,
        ':contacts_auto'   => $contactsAuto,
        ':device_name_u'   => $deviceName,
        ':device_name_u2'  => $deviceName,
        ':sms_perm_u'      => $smsPerm,
        ':contacts_perm_u' => $contactsPerm,
        ':sms_auto_u'      => $smsAuto,
        ':contacts_auto_u' => $contactsAuto,
    ]);

    // 2. Process command acknowledgements (idempotent by command_uid)
    $acks = is_array($payload['command_acks'] ?? null) ? array_slice($payload['command_acks'], 0, 50) : [];
    $allowedAckStatuses = ['completed', 'failed', 'denied'];
    if (count($acks) > 0) {
        $ackStmt = $pdo->prepare(
            'UPDATE device_sync_commands
             SET status = :status,
                 result_message = :result_message,
                 executed_at = COALESCE(executed_at, NOW())
             WHERE command_uid = :command_uid
               AND device_id = :device_id
               AND status IN (\'pending\', \'in_progress\')'
        );
        foreach ($acks as $ack) {
            if (!is_array($ack)) continue;
            $cmdUid = trim((string)($ack['command_uid'] ?? ''));
            $status = trim((string)($ack['status'] ?? ''));
            $msg = mb_substr(trim((string)($ack['result_message'] ?? '')), 0, 500);
            if ($cmdUid === '' || strlen($cmdUid) > 36 || !in_array($status, $allowedAckStatuses, true)) {
                continue;
            }
            $ackStmt->execute([
                ':status'         => $status,
                ':result_message' => $msg,
                ':command_uid'    => $cmdUid,
                ':device_id'      => $deviceId,
            ]);
        }
    }

    // 3. Record device sync & error logs
    $logs = is_array($payload['sync_logs'] ?? null) ? array_slice($payload['sync_logs'], 0, 50) : [];
    $allowedTypes = ['sms', 'contacts', 'both'];
    $allowedTriggers = ['auto', 'manual', 'remote_admin', 'workmanager'];
    $allowedLogStatuses = ['success', 'failed', 'skipped_permission', 'queued_offline'];
    if (count($logs) > 0) {
        $logStmt = $pdo->prepare(
            'INSERT INTO sync_logs
             (device_id, command_uid, sync_type, trigger_source, status, items_synced, error_message, created_at)
             VALUES (:device_id, :command_uid, :sync_type, :trigger_source, :status, :items_synced, :error_message, NOW())'
        );
        $checkCmdStmt = $pdo->prepare('SELECT command_uid FROM device_sync_commands WHERE command_uid = :uid LIMIT 1');

        foreach ($logs as $log) {
            if (!is_array($log)) continue;
            $syncType = in_array($log['sync_type'] ?? '', $allowedTypes, true) ? $log['sync_type'] : 'both';
            $trigger = in_array($log['trigger_source'] ?? '', $allowedTriggers, true) ? $log['trigger_source'] : 'auto';
            $logStatus = in_array($log['status'] ?? '', $allowedLogStatuses, true) ? $log['status'] : 'failed';
            $itemsSynced = max(0, (int)($log['items_synced'] ?? 0));
            $errMsg = mb_substr(trim((string)($log['error_message'] ?? '')), 0, 1000);
            $cmdUid = trim((string)($log['command_uid'] ?? ''));

            $validCmdUid = null;
            if ($cmdUid !== '' && strlen($cmdUid) <= 36) {
                $checkCmdStmt->execute([':uid' => $cmdUid]);
                if ($checkCmdStmt->fetchColumn()) {
                    $validCmdUid = $cmdUid;
                }
            }

            $logStmt->execute([
                ':device_id'      => $deviceId,
                ':command_uid'    => $validCmdUid,
                ':sync_type'      => $syncType,
                ':trigger_source' => $trigger,
                ':status'         => $logStatus,
                ':items_synced'   => $itemsSynced,
                ':error_message'  => $errMsg !== '' ? $errMsg : null,
            ]);
        }
    }

    // 4. Record read announcements
    $readUids = is_array($payload['read_announcement_uids'] ?? null)
        ? array_slice($payload['read_announcement_uids'], 0, 50)
        : [];
    if (count($readUids) > 0) {
        $findAnn = $pdo->prepare('SELECT id FROM admin_announcements WHERE announcement_uid = :uid LIMIT 1');
        $markRead = $pdo->prepare(
            'INSERT IGNORE INTO announcement_reads (announcement_id, device_id, read_at)
             VALUES (:ann_id, :device_id, NOW())'
        );
        foreach ($readUids as $rawUid) {
            $uid = trim((string)$rawUid);
            if ($uid === '' || strlen($uid) > 36) continue;
            $findAnn->execute([':uid' => $uid]);
            $annId = $findAnn->fetchColumn();
            if ($annId) {
                $markRead->execute([
                    ':ann_id'    => (int)$annId,
                    ':device_id' => $deviceId,
                ]);
            }
        }
    }

    // 5. Read global app_settings
    $settingsStmt = $pdo->query('SELECT setting_key, setting_value FROM app_settings');
    $settingsMap = [
        'auto_sync_interval_minutes' => 15,
        'remote_sync_enabled'        => true,
    ];
    foreach ($settingsStmt->fetchAll() as $row) {
        if ($row['setting_key'] === 'auto_sync_interval_minutes') {
            $settingsMap['auto_sync_interval_minutes'] = max(15, (int)$row['setting_value']);
        } elseif ($row['setting_key'] === 'remote_sync_enabled') {
            $settingsMap['remote_sync_enabled'] = $row['setting_value'] === '1';
        }
    }

    // 6. Fetch pending commands for this device (only when remote_sync_enabled is active)
    $pendingCommands = [];
    if ($settingsMap['remote_sync_enabled']) {
        $cmdStmt = $pdo->prepare(
            'SELECT command_uid, sync_type, target_mode, created_at
             FROM device_sync_commands
             WHERE device_id = :device_id AND status = \'pending\'
             ORDER BY created_at ASC
             LIMIT 10'
        );
        $cmdStmt->execute([':device_id' => $deviceId]);
        $pendingCommands = $cmdStmt->fetchAll();
    }

    // 7. Fetch active announcements targeted to 'all' or this device_id
    $annStmt = $pdo->prepare(
        'SELECT a.id, a.announcement_uid, a.title, a.message, a.target_type, a.target_devices, a.created_at,
                CASE WHEN ar.id IS NOT NULL THEN 1 ELSE 0 END AS is_read
         FROM admin_announcements a
         LEFT JOIN announcement_reads ar
           ON ar.announcement_id = a.id AND ar.device_id = :device_id
         WHERE a.is_active = 1
         ORDER BY a.created_at DESC
         LIMIT 50'
    );
    $annStmt->execute([':device_id' => $deviceId]);
    $announcements = [];
    foreach ($annStmt->fetchAll() as $ann) {
        $include = ($ann['target_type'] === 'all');
        if (!$include && !empty($ann['target_devices'])) {
            $targets = json_decode((string)$ann['target_devices'], true);
            if (is_array($targets) && in_array($deviceId, $targets, true)) {
                $include = true;
            }
        }
        if ($include) {
            $announcements[] = [
                'announcement_uid' => $ann['announcement_uid'],
                'title'            => $ann['title'],
                'message'          => $ann['message'],
                'created_at'       => $ann['created_at'],
                'is_read'          => ((int)$ann['is_read']) === 1,
            ];
        }
    }

    $pdo->commit();

    echo json_encode([
        'status'           => 'success',
        'server_time'      => gmdate('c'),
        'settings'         => $settingsMap,
        'pending_commands' => $pendingCommands,
        'announcements'    => $announcements,
    ]);
} catch (Throwable $e) {
    if (isset($pdo) && $pdo->inTransaction()) {
        $pdo->rollBack();
    }
    error_log('Device poll error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['status' => 'error', 'message' => 'Internal server error during device sync poll.']);
}
