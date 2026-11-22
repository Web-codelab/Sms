import type { SmsMessage } from '../types/sms';

export async function computeSmsHash(
  sender: string,
  body: string,
  receivedAt: string
): Promise<string> {
  const raw = `${sender.trim()}|${body.trim()}|${receivedAt.trim()}`;
  if (typeof crypto !== 'undefined' && crypto.subtle) {
    const encoded = new TextEncoder().encode(raw);
    const digest = await crypto.subtle.digest('SHA-256', encoded);
    return Array.from(new Uint8Array(digest))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
  }
  let hash = 0;
  for (let i = 0; i < raw.length; i += 1) {
    hash = (hash << 5) - hash + raw.charCodeAt(i);
    hash |= 0;
  }
  return `fallback-${Math.abs(hash).toString(16)}`;
}

export const INITIAL_SMS_SEED: Omit<SmsMessage, 'smsHash'>[] = [
  {
    id: 'sms-101',
    sender: 'VM-HDFCBK',
    body: 'Rs. 2,450.00 debited from A/c **4821 on 22-11-26 to UPI/Webcodelab Hosting. Avl Bal: Rs. 41,290.50. Ref: 6328194021.',
    receivedAt: '2026-11-22T12:48:10.000Z',
    status: 'local',
    category: 'Banking',
  },
  {
    id: 'sms-102',
    sender: 'AD-CPANEL',
    body: 'Your cPanel verification code is 849210. Do not share this OTP with anyone. Valid for 10 minutes.',
    receivedAt: '2026-11-22T12:35:00.000Z',
    status: 'local',
    category: 'OTP',
  },
  {
    id: 'sms-103',
    sender: '+91 98765 43210',
    body: 'Bhai server par db.php upload kar diya hai, ek baar test karke confirm kar dena.',
    receivedAt: '2026-11-22T11:52:18.000Z',
    status: 'local',
    category: 'Personal',
  },
  {
    id: 'sms-104',
    sender: 'AX-BLUDRT',
    body: 'Order #BD-99412 is out for delivery today. Share delivery PIN 4418 with the executive upon arrival.',
    receivedAt: '2026-11-22T10:15:44.000Z',
    status: 'local',
    category: 'Delivery',
  },
  {
    id: 'sms-105',
    sender: 'JX-SBIBNK',
    body: 'Dear Customer, Rs. 15,000.00 credited to your A/c XXXXX3109 by NEFT Ref 9912834412.',
    receivedAt: '2026-11-22T09:10:05.000Z',
    status: 'local',
    category: 'Banking',
  },
];

export const INCOMING_SMS_PRESETS: Array<{
  sender: string;
  body: string;
  category: SmsMessage['category'];
}> = [
  {
    sender: 'VM-ICICIB',
    body: 'Acct XX901 debited for Rs 1,299.00 on 22-Nov-26; Cloud Renewal credited. UPI:6329018842.',
    category: 'Banking',
  },
  {
    sender: 'JD-VERIFY',
    body: '519042 is your one-time password for Webcodelab Admin Login. Expires in 5 mins.',
    category: 'OTP',
  },
  {
    sender: '+91 98112 77409',
    body: 'Office pahunch ke call karna, client meeting 4 baje schedule hui hai.',
    category: 'Personal',
  },
  {
    sender: 'AZ-DELHIV',
    body: 'Package AWB 772819402 from Jaipur Hub has reached your local facility.',
    category: 'Delivery',
  },
];
