<?php
// Upload to: https://webcodelab.in/WCL/backup_sms.php
// Preserves all existing SMS backup API behavior while updating device last sync timestamp safely.

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
$messages = $payload['messages'] ?? null;

if ($deviceId === '' || strlen($deviceId) > 100 || !is_array($messages) || count($messages) === 0) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'message' => 'Valid device_id and non-empty messages array required.']);
    exit;
}

if (count($messages) > 200) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'message' => 'Maximum 200 messages per batch.']);
    exit;
}

try {
    $pdo = getDbConnection();
    $pdo->beginTransaction();

    $stmt = $pdo->prepare(
        'INSERT IGNORE INTO sms_backups (device_id, sender, message_body, received_at, synced_at, sms_hash)
         VALUES (:device_id, :sender, :message_body, :received_at, NOW(), :sms_hash)'
    );

    $inserted = 0;
    $skipped = 0;
    $syncedHashes = [];

    foreach ($messages as $item) {
        if (!is_array($item)) {
            continue;
        }
        $sender = mb_substr(trim((string)($item['sender'] ?? '')), 0, 64);
        $body = mb_substr((string)($item['body'] ?? ''), 0, 4000);
        $receivedRaw = trim((string)($item['received_at'] ?? ''));
        $smsHash = trim((string)($item['sms_hash'] ?? ''));

        if ($sender === '' || $body === '' || strlen($smsHash) !== 64) {
            continue;
        }

        $timestamp = strtotime($receivedRaw);
        $receivedSql = $timestamp !== false ? date('Y-m-d H:i:s', $timestamp) : date('Y-m-d H:i:s');

        $stmt->execute([
            ':device_id'    => $deviceId,
            ':sender'       => $sender,
            ':message_body' => $body,
            ':received_at'  => $receivedSql,
            ':sms_hash'     => $smsHash,
        ]);

        if ($stmt->rowCount() > 0) {
            $inserted++;
        } else {
            $skipped++;
        }
        $syncedHashes[] = $smsHash;
    }

    $pdo->commit();

    safeTouchDeviceRegistry($pdo, $deviceId, 'sms');

    echo json_encode([
        'status'             => 'success',
        'inserted'           => $inserted,
        'skipped_duplicates' => $skipped,
        'synced_hashes'      => $syncedHashes,
        'server_time'        => gmdate('c'),
    ]);
} catch (Throwable $e) {
    if (isset($pdo) && $pdo->inTransaction()) {
        $pdo->rollBack();
    }
    error_log('SMS backup error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['status' => 'error', 'message' => 'Internal server error while saving SMS.']);
}
