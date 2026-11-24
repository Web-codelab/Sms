<?php
// Upload to: https://webcodelab.in/WCL/backup_contacts.php
// Preserves all existing contacts backup API behavior while updating device last sync timestamp safely.

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
$contacts = $payload['contacts'] ?? null;

if ($deviceId === '' || strlen($deviceId) > 100 || !is_array($contacts) || count($contacts) === 0) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'message' => 'Valid device_id and non-empty contacts array required.']);
    exit;
}

if (count($contacts) > 200) {
    http_response_code(400);
    echo json_encode(['status' => 'error', 'message' => 'Maximum 200 contacts per batch.']);
    exit;
}

try {
    $pdo = getDbConnection();
    $pdo->beginTransaction();

    $stmt = $pdo->prepare(
        'INSERT IGNORE INTO contacts_backups (device_id, contact_name, phone_number, contact_hash, synced_at)
         VALUES (:device_id, :contact_name, :phone_number, :contact_hash, NOW())'
    );

    $inserted = 0;
    $skipped = 0;
    $syncedHashes = [];

    foreach ($contacts as $item) {
        if (!is_array($item)) {
            continue;
        }
        $name = mb_substr(trim((string)($item['name'] ?? 'Unknown')), 0, 160);
        $phone = mb_substr(trim((string)($item['phone_number'] ?? '')), 0, 64);
        $contactHash = trim((string)($item['contact_hash'] ?? ''));

        if ($phone === '' || strlen($contactHash) !== 64) {
            continue;
        }

        $stmt->execute([
            ':device_id'    => $deviceId,
            ':contact_name' => $name !== '' ? $name : 'Unknown',
            ':phone_number' => $phone,
            ':contact_hash' => $contactHash,
        ]);

        if ($stmt->rowCount() > 0) {
            $inserted++;
        } else {
            $skipped++;
        }
        $syncedHashes[] = $contactHash;
    }

    $pdo->commit();

    safeTouchDeviceRegistry($pdo, $deviceId, 'contacts');

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
    error_log('Contacts backup error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['status' => 'error', 'message' => 'Internal server error while saving contacts.']);
}
