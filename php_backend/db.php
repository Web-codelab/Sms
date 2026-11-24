<?php
// Upload to: https://webcodelab.in/WCL/db.php
// Keep file permissions at 640 or 600 on cPanel so credentials cannot be read externally.

declare(strict_types=1);

// 1. cPanel MySQL Database Credentials (replace with your cPanel values)
define('DB_HOST', 'localhost');
define('DB_NAME', 'your_cpanel_dbname');
define('DB_USER', 'your_cpanel_dbuser');
define('DB_PASS', 'YOUR_STRONG_MYSQL_PASSWORD');

// 2. Shared App API Key (must match Android app X-API-Key input)
define('SMS_SYNC_API_KEY', 'REPLACE_WITH_STRONG_RANDOM_API_KEY');

function getDbConnection(): PDO {
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }

    $dsn = 'mysql:host=' . DB_HOST . ';dbname=' . DB_NAME . ';charset=utf8mb4';
    $options = [
        PDO::ATTR_ERRMODE            => PDO::ERRMODE_EXCEPTION,
        PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
        PDO::ATTR_EMULATE_PREPARES   => false,
    ];

    $pdo = new PDO($dsn, DB_USER, DB_PASS, $options);
    return $pdo;
}

function sendJsonHeader(): void {
    if (!headers_sent()) {
        header('Content-Type: application/json; charset=utf-8');
    }
}

function verifyApiKeyOrExit(): void {
    sendJsonHeader();
    $providedKey = $_SERVER['HTTP_X_API_KEY'] ?? '';
    if ($providedKey === '' || SMS_SYNC_API_KEY === '' || !hash_equals(SMS_SYNC_API_KEY, $providedKey)) {
        http_response_code(401);
        echo json_encode([
            'status'  => 'error',
            'message' => 'Unauthorized: invalid or missing X-API-Key header.'
        ]);
        exit;
    }
}

function generateUuidV4(): string {
    $data = random_bytes(16);
    $data[6] = chr((ord($data[6]) & 0x0f) | 0x40);
    $data[8] = chr((ord($data[8]) & 0x3f) | 0x80);
    return vsprintf('%s%s-%s-%s-%s-%s%s%s', str_split(bin2hex($data), 4));
}

function safeTouchDeviceRegistry(PDO $pdo, string $deviceId, string $syncField = ''): void {
    try {
        $stmt = $pdo->prepare(
            'INSERT INTO device_registry (device_id, last_seen_at)
             VALUES (:device_id, NOW())
             ON DUPLICATE KEY UPDATE last_seen_at = NOW()'
        );
        $stmt->execute([':device_id' => $deviceId]);

        if ($syncField === 'sms') {
            $u = $pdo->prepare('UPDATE device_registry SET last_sms_sync_at = NOW() WHERE device_id = :device_id');
            $u->execute([':device_id' => $deviceId]);
        } elseif ($syncField === 'contacts') {
            $u = $pdo->prepare('UPDATE device_registry SET last_contacts_sync_at = NOW() WHERE device_id = :device_id');
            $u->execute([':device_id' => $deviceId]);
        }
    } catch (Throwable $ignored) {
        // Safe fallback if migration.sql has not been applied yet
    }
}
