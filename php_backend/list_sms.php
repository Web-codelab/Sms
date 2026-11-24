<?php
// Upload to: https://webcodelab.in/WCL/list_sms.php

declare(strict_types=1);

require_once __DIR__ . '/db.php';
sendJsonHeader();

if ($_SERVER['REQUEST_METHOD'] !== 'GET') {
    http_response_code(405);
    echo json_encode(['status' => 'error', 'message' => 'Only GET requests are allowed.']);
    exit;
}

verifyApiKeyOrExit();

$deviceId = trim((string)($_GET['device_id'] ?? ''));
$limit = max(1, min(200, (int)($_GET['limit'] ?? 100)));

try {
    $pdo = getDbConnection();

    if ($deviceId !== '') {
        $stmt = $pdo->prepare(
            'SELECT id, device_id, sender, message_body, received_at, synced_at, sms_hash
             FROM sms_backups
             WHERE device_id = :device_id
             ORDER BY received_at DESC
             LIMIT :limit_rows'
        );
        $stmt->bindValue(':device_id', $deviceId, PDO::PARAM_STR);
        $stmt->bindValue(':limit_rows', $limit, PDO::PARAM_INT);
        $stmt->execute();
    } else {
        $stmt = $pdo->prepare(
            'SELECT id, device_id, sender, message_body, received_at, synced_at, sms_hash
             FROM sms_backups
             ORDER BY received_at DESC
             LIMIT :limit_rows'
        );
        $stmt->bindValue(':limit_rows', $limit, PDO::PARAM_INT);
        $stmt->execute();
    }

    echo json_encode([
        'status'  => 'success',
        'records' => $stmt->fetchAll(),
    ]);
} catch (Throwable $e) {
    error_log('SMS list error: ' . $e->getMessage());
    http_response_code(500);
    echo json_encode(['status' => 'error', 'message' => 'Failed to read backed up SMS records.']);
}
