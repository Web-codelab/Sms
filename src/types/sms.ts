export type SmsSyncStatus = 'synced' | 'queued' | 'local';

export interface SmsMessage {
  id: string;
  sender: string;
  body: string;
  receivedAt: string;
  syncedAt?: string;
  status: SmsSyncStatus;
  smsHash: string;
  category: 'Personal' | 'Banking' | 'OTP' | 'Delivery';
}

export interface ServerSmsRecord {
  serverId: number;
  deviceId: string;
  sender: string;
  body: string;
  receivedAt: string;
  syncedAt: string;
  smsHash: string;
}

export interface SyncActivityItem {
  id: string;
  timestamp: string;
  action: string;
  detail: string;
  outcome: 'success' | 'queued' | 'error';
}

export interface EndpointConfig {
  baseUrl: string;
  apiKey: string;
  deviceId: string;
}

export interface CodeFileSpec {
  id: string;
  filename: string;
  language: string;
  summary: string;
  content: string;
}
