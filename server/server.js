'use strict';
// Home-network WhatsApp bridge for the BlackBerry Q20 companion app.
// Runs whatsapp-web.js (headless Chromium + real WhatsApp Web) and exposes a small
// token-authenticated JSON API. Intended for the home LAN only: requests from
// non-private addresses are refused, and /admin is reachable from this machine only.

const crypto = require('crypto');
const os = require('os');
const fs = require('fs');
const path = require('path');
const express = require('express');
const QRCode = require('qrcode');
const { DatabaseSync } = require('node:sqlite');
require('./patches/apply');
const { Client, LocalAuth, MessageMedia } = require('whatsapp-web.js');
const { wavToOggOpus } = require('./voice');

const PORT = Number(process.env.PORT || 3000);
const HOST = process.env.HOST || '0.0.0.0';
const DATA = path.resolve(process.env.DATA_DIR || path.join(__dirname, 'data'));
const MEDIA = path.join(DATA, 'media');
const MAX_MEDIA_BYTES = 25 * 1024 * 1024;
const INLINE_MEDIA = /^(image\/(jpeg|png|gif|webp)|audio\/|video\/)/;
const CHAT_ID = /^[\w.\-]+@(c\.us|g\.us|lid)$/;
const MSG_ID = /^[\w.@\-]+$/;
const LABELS = {
  image: '[Photo]', video: '[Video]', ptt: '[Voice message]', audio: '[Audio]', sticker: '[Sticker]',
  document: '[Document]', location: '[Location]', vcard: '[Contact]', multi_vcard: '[Contact]',
  poll_creation: '[Poll]', call_log: '[Call]', revoked: '[This message was deleted]',
  biz_content_placeholder: '[Business message]',
};
const AVATARS = path.join(DATA, 'avatars');
const AVATAR_TTL_MS = 24 * 3600 * 1000;
const AVATAR_HOSTS = /\.(whatsapp\.net|fbcdn\.net|whatsapp\.com)$/;

fs.mkdirSync(MEDIA, { recursive: true });
fs.mkdirSync(AVATARS, { recursive: true });

// ---------- storage ----------
const db = new DatabaseSync(path.join(DATA, 'bridge.db'));
db.exec(`
  CREATE TABLE IF NOT EXISTS messages (
    seq INTEGER PRIMARY KEY AUTOINCREMENT,
    wa_id TEXT UNIQUE NOT NULL,
    chat_id TEXT NOT NULL,
    from_me INTEGER NOT NULL,
    sender TEXT,
    body TEXT,
    type TEXT,
    ts INTEGER NOT NULL,
    has_media INTEGER NOT NULL DEFAULT 0
  );
  CREATE INDEX IF NOT EXISTS idx_messages_chat ON messages (chat_id, ts);
  CREATE TABLE IF NOT EXISTS devices (
    id TEXT PRIMARY KEY,
    name TEXT,
    token_hash TEXT UNIQUE NOT NULL,
    created INTEGER NOT NULL,
    last_seen INTEGER
  );
`);
// `live` marks messages seen as they happened (message_create), as opposed to history fetched
// when a chat is opened, so /api/updates only reports genuinely new messages.
try { db.exec('ALTER TABLE messages ADD COLUMN live INTEGER NOT NULL DEFAULT 0'); } catch (_) { /* already there */ }
try { db.exec('ALTER TABLE messages ADD COLUMN raw TEXT'); } catch (_) { /* already there */ }
db.exec("DELETE FROM messages WHERE type IN ('unknown', 'album')"); // hidden/container rows stored earlier
try { db.exec('ALTER TABLE messages ADD COLUMN quoted TEXT'); } catch (_) { /* already there */ }
try { db.exec('ALTER TABLE messages ADD COLUMN meta TEXT'); } catch (_) { /* already there */ }
try { db.exec('ALTER TABLE messages ADD COLUMN ack INTEGER'); } catch (_) { /* already there */ }
const q = {
  // Re-fetched history refreshes type/body/raw but never touches seq or live.
  insertMsg: db.prepare(`INSERT INTO messages
    (wa_id, chat_id, from_me, sender, body, type, ts, has_media, live, raw, quoted, meta, ack)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(wa_id) DO UPDATE SET type = excluded.type, body = excluded.body,
      has_media = excluded.has_media, raw = excluded.raw, quoted = excluded.quoted, meta = excluded.meta,
      ack = COALESCE(excluded.ack, ack)`),
  fromMeByStanza: db.prepare('SELECT from_me FROM messages WHERE wa_id LIKE ? LIMIT 1'),
  since: db.prepare('SELECT * FROM messages WHERE seq > ? AND live = 1 ORDER BY seq ASC LIMIT 200'),
  delMsg: db.prepare('DELETE FROM messages WHERE wa_id = ?'),
  metaOf: db.prepare('SELECT meta FROM messages WHERE wa_id = ?'),
  byId: db.prepare('SELECT * FROM messages WHERE wa_id = ?'),
  lastPerChat: db.prepare("SELECT chat_id, MAX(ts) AS ts FROM messages WHERE type NOT IN ('deleted', 'e2e_notification', 'notification_template') GROUP BY chat_id"),
  maxSeq: db.prepare('SELECT COALESCE(MAX(seq), 0) AS seq FROM messages'),
  deviceByHash: db.prepare('SELECT * FROM devices WHERE token_hash = ?'),
  addDevice: db.prepare('INSERT INTO devices (id, name, token_hash, created) VALUES (?, ?, ?, ?)'),
  touchDevice: db.prepare('UPDATE devices SET last_seen = ? WHERE id = ?'),
  listDevices: db.prepare('SELECT id, name, created, last_seen FROM devices ORDER BY created'),
  delDevice: db.prepare('DELETE FROM devices WHERE id = ?'),
};

const sha256 = (s) => crypto.createHash('sha256').update(s).digest('hex');
const now = () => Math.floor(Date.now() / 1000);

// ---------- WhatsApp ----------
const wa = { state: 'starting', qr: null };
const client = new Client({
  authStrategy: new LocalAuth({ dataPath: path.join(DATA, 'wwebjs') }),
  puppeteer: { headless: true },
});
client.on('qr', (qr) => { wa.qr = qr; wa.state = 'qr'; });
client.on('authenticated', () => { wa.qr = null; wa.state = 'authenticated'; });
client.on('ready', () => { wa.qr = null; wa.state = 'ready'; console.log('WhatsApp ready'); });
client.on('auth_failure', () => { wa.state = 'auth_failure'; });
client.on('disconnected', (reason) => {
  wa.state = 'disconnected';
  console.log('WhatsApp disconnected:', reason);
});
client.on('message_create', (m) => {
  try { store(m, true); } catch (e) {
    const t = (v) => (v === null ? 'null' : typeof v);
    console.error('store failed:', e.message, '| id', t(m.id && m.id._serialized), 'from', t(m.from), 'to', t(m.to),
      'body', t(m.body), 'type', t(m.type), 'ts', t(m.timestamp), 'notify', t(m._data && m._data.notifyName));
  }
});
// An edited message is stored again as a brand-new row (new seq) so polling clients notice the change.
function restoreEdited(m, body) {
  if (body !== undefined) m.body = body;
  m._edited = true;
  q.delMsg.run(m.id._serialized);
  return store(m, true);
}
// Deleting: a message deleted for everyone becomes type "revoked" (the app shows "This message was deleted");
// one deleted just for you becomes a "deleted" marker the app removes. Either way the row is stored again as
// a new row (new seq) so polling clients notice. Returns the new row, or null when the message was never stored.
function rewrite(id, type) {
  const r = q.byId.get(id);
  if (!r) return null;
  q.delMsg.run(id);
  q.insertMsg.run(id, r.chat_id, r.from_me, r.sender, '', type, r.ts, 0, 1, type === 'revoked' ? 'revoked' : '', null, null, r.ack === undefined ? null : r.ack);
  return fromRow(q.byId.get(id));
}
client.on('message_revoke_everyone', (after, before) => {
  try {
    const id = (before && before.id && before.id._serialized) || (after && after.id && after.id._serialized);
    if (id) rewrite(id, 'revoked');
  } catch (e) { console.error('revoke event failed:', e.message); }
});
client.on('message_revoke_me', (m) => {
  try { if (m && m.id) rewrite(m.id._serialized, 'deleted'); } catch (e) { console.error('revoke-me event failed:', e.message); }
});
// Ticks: WhatsApp reports delivery/read state changes of your own messages. Stored again as a new row so
// polling clients notice (the same trick as edits).
client.on('message_ack', (m, ack) => {
  try {
    if (!m.fromMe || !Number.isInteger(ack)) return;
    m.ack = ack;
    const prev = q.metaOf.get(m.id._serialized);
    if (prev && prev.meta && JSON.parse(prev.meta).edited) m._edited = true; // deleting the row would forget it
    q.delMsg.run(m.id._serialized);
    store(m, true);
  } catch (e) { console.error('ack event failed:', e.message); }
});
client.on('message_edit', (m, newBody) => {
  try { restoreEdited(m, newBody); } catch (e) { console.error('edit event failed:', e.message); }
});
// Reactions: WhatsApp reports them as separate events. The message's current reactions are read back and the
// row is stored again (new seq) so polling clients notice, like edits and ticks.
async function reactionsOf(m) {
  if (!m || !m.hasReaction) return [];
  const list = await m.getReactions().catch(() => undefined);
  return (list || []).map((r) => ({ e: r.aggregateEmoji || r.id, n: (r.senders || []).length, me: !!r.hasReactionByMe }))
    .filter((r) => r.e && r.n > 0);
}
async function refreshReactions(id) {
  const m = await client.getMessageById(id);
  if (!m) return null;
  m._reactions = await reactionsOf(m);
  const prev = q.metaOf.get(id);
  if (prev && prev.meta && JSON.parse(prev.meta).edited) m._edited = true;
  q.delMsg.run(id);
  return store(m, true);
}
client.on('message_reaction', (reaction) => {
  const k = reaction && reaction.msgId;
  const id = k && (k._serialized || k.$1);
  if (!id) return;
  refreshReactions(id).catch((e) => console.error('reaction event failed:', e.message));
});

client.initialize().catch((e) => { wa.state = 'error'; console.error('initialize failed:', e.message); });

const widStr = (x) => (x ? (x._serialized || x.$1 || (typeof x === 'string' ? x : (x.user && x.server ? `${x.user}@${x.server}` : ''))) : '');

// The message this one replies to: text (or a bracketed label for media) and who wrote it.
function quotedOf(m) {
  const d = m._data || {};
  if (!m.hasQuotedMsg || !d.quotedMsg) return null;
  const qm = d.quotedMsg;
  const type = qm.type || 'chat';
  const caption = typeof qm.caption === 'string' ? qm.caption : '';
  const text = type === 'chat' ? String(qm.body || '')
    : (LABELS[type] || '[Message]') + (caption ? ' ' + caption : '');
  const participant = widStr(d.quotedParticipant);
  let mine = false;
  const stanza = d.quotedStanzaID ? String(d.quotedStanzaID) : '';
  if (stanza) { const r = q.fromMeByStanza.get('%_' + stanza + '%'); if (r) mine = !!r.from_me; }
  if (!mine && participant && client.info && client.info.wid) {
    mine = participant.split('@')[0] === widStr(client.info.wid).split('@')[0];
  }
  return { from: mine ? 'You' : '', participant, text: text.slice(0, 160) };
}

// Small facts the app needs to lay out media bubbles without downloading them.
function metaOf(m) {
  const d = m._data || {};
  const meta = {};
  if (d.duration) meta.duration = Number(d.duration) || 0;
  if (d.size) meta.size = Number(d.size) || 0;
  if (d.mimetype) meta.mime = String(d.mimetype).split(';')[0];
  if (d.width && d.height) { meta.w = Number(d.width); meta.h = Number(d.height); }
  if (d.isGif) meta.gif = true;
  if (m._edited || d.latestEditMsgKey || d.latestEditSenderTimestampMs) meta.edited = true;
  return Object.keys(meta).length ? meta : null;
}

function store(m, live = false) {
  const chatId = m.fromMe ? m.to : m.from;
  if (!chatId || chatId === 'status@broadcast') return null;
  // Internal artifacts come through as type "unknown" with viewMode HIDDEN; albums are just containers
  // for photos that arrive as their own messages. (Don't key on `invis`: real call logs and even some
  // normal messages carry invis=true.)
  if (m._data && m._data.viewMode === 'HIDDEN') return null;
  if (m.type === 'unknown' || m.type === 'album') return null;
  const row = {
    id: m.id._serialized,
    chatId,
    fromMe: m.fromMe,
    sender: (m._data && m._data.notifyName) || m.author || m.from || '',
    body: m.body || (m.type === 'call_log' ? ((m._data && m._data.isVideoCall) ? '[Video call]' : '[Voice call]') : ''),
    type: m.type,
    ts: m.timestamp,
    hasMedia: !!m.hasMedia,
    // WhatsApp's own type/subtype, for messages whatsapp-web.js can't classify ("unknown")
    raw: m._data ? `${m._data.type || ''}${m._data.subtype ? ':' + m._data.subtype : ''}` : '',
    quoted: quotedOf(m),
    meta: metaOf(m),
    // delivery state of your own messages: 0 sending, 1 sent, 2 delivered, 3 read, 4 played, -1 failed
    ack: m.fromMe && Number.isInteger(m.ack) ? m.ack : null,
  };
  // an "edited" mark we already know about must survive when WhatsApp's data for the message doesn't carry it
  const prev = q.metaOf.get(row.id);
  const prevMeta = prev && prev.meta ? JSON.parse(prev.meta) : {};
  if (prevMeta.edited && !(row.meta && row.meta.edited)) row.meta = { ...(row.meta || {}), edited: true };
  // reactions: [{e: emoji, n: how many, me: true if you reacted}]. Fresh data wins; otherwise keep what we knew.
  const reactions = m._reactions !== undefined ? m._reactions : prevMeta.reactions;
  if (reactions && reactions.length) row.meta = { ...(row.meta || {}), reactions };
  q.insertMsg.run(row.id, row.chatId, row.fromMe ? 1 : 0, row.sender, row.body, row.type, row.ts, row.hasMedia ? 1 : 0, live ? 1 : 0, row.raw,
    row.quoted ? JSON.stringify(row.quoted) : null, row.meta ? JSON.stringify(row.meta) : null, row.ack);
  return row;
}

const fromRow = (r) => ({
  id: r.wa_id, chatId: r.chat_id, fromMe: !!r.from_me, sender: r.sender,
  body: r.body, type: r.type, raw: r.raw || '', ts: r.ts, hasMedia: !!r.has_media, seq: r.seq,
  quoted: r.quoted ? JSON.parse(r.quoted) : null, meta: r.meta ? JSON.parse(r.meta) : null,
  ack: r.ack === null || r.ack === undefined ? null : r.ack,
});

// ---------- access control ----------
function isPrivate(addr) {
  const ip = (addr || '').replace(/^::ffff:/, '');
  if (ip === '::1' || /^127\./.test(ip)) return true;
  if (/^10\./.test(ip) || /^192\.168\./.test(ip) || /^169\.254\./.test(ip)) return true;
  if (/^172\.(1[6-9]|2\d|3[01])\./.test(ip)) return true;
  return /^f[cd][0-9a-f]{2}:/i.test(ip) || /^fe80:/i.test(ip);
}
const isLoopback = (addr) => {
  const ip = (addr || '').replace(/^::ffff:/, '');
  return ip === '::1' || /^127\./.test(ip);
};

const app = express();
app.disable('x-powered-by');
app.use((req, res, next) => {
  res.set('X-Content-Type-Options', 'nosniff');
  if (!isPrivate(req.socket.remoteAddress)) return res.status(403).json({ error: 'home network only' });
  next();
});
app.use(express.json({ limit: '64kb' }));

// Admin is loopback-only. The Host/Origin checks also stop a web page open in a browser on this
// machine from driving it (cross-site requests, DNS rebinding).
const LOCAL_HOST = /^(localhost|127\.0\.0\.1|\[::1\])(:\d+)?$/i;
function localOnly(req, res, next) {
  if (!isLoopback(req.socket.remoteAddress)) return res.status(403).json({ error: 'admin is local only' });
  if (!LOCAL_HOST.test(req.get('host') || '')) return res.status(403).json({ error: 'admin: bad host' });
  const origin = req.get('origin');
  if (origin && origin !== 'null' && !LOCAL_HOST.test(origin.replace(/^https?:\/\//, ''))) return res.status(403).json({ error: 'admin: bad origin' });
  if (origin === 'null') return res.status(403).json({ error: 'admin: bad origin' });
  next();
}

function auth(req, res, next) {
  const m = /^Bearer ([0-9a-f]{64})$/.exec(req.get('authorization') || '');
  const dev = m && q.deviceByHash.get(sha256(m[1]));
  if (!dev) return res.status(401).json({ error: 'unauthorized' });
  q.touchDevice.run(now(), dev.id);
  req.device = dev;
  next();
}
const ready = (req, res, next) =>
  wa.state === 'ready' ? next() : res.status(503).json({ error: 'whatsapp not ready', state: wa.state });
const wrap = (fn) => (req, res) => fn(req, res).catch((e) => {
  console.error(req.method, req.path, e && e.stack ? e.stack.split('\n').slice(0, 6).join('\n') : e);
  res.status(500).json({ error: 'internal error' });
});
const validChat = (req, res, next) =>
  CHAT_ID.test(req.params.chatId) ? next() : res.status(400).json({ error: 'bad chat id' });

// ---------- pairing ----------
let pairCode = null; // { code, expires }
const fails = { n: 0, until: 0, windowStart: 0 };

app.post('/api/pair', (req, res) => {
  const t = Date.now();
  if (t < fails.until) return res.status(429).json({ error: 'too many attempts, wait a minute' });
  const code = String((req.body && req.body.code) || '');
  const name = String((req.body && req.body.name) || 'device').slice(0, 40);
  const ok = pairCode && t < pairCode.expires && code.length === pairCode.code.length &&
    crypto.timingSafeEqual(Buffer.from(code), Buffer.from(pairCode.code));
  if (!ok) {
    if (t - fails.windowStart > 60000) { fails.n = 0; fails.windowStart = t; }
    if (++fails.n >= 5) { fails.until = t + 60000; fails.n = 0; }
    return res.status(403).json({ error: 'invalid or expired code' });
  }
  pairCode = null; // single use
  const token = crypto.randomBytes(32).toString('hex');
  q.addDevice.run(crypto.randomUUID(), name, sha256(token), now());
  console.log(`paired device "${name}"`);
  res.json({ token });
});

// ---------- API ----------
app.get('/api/ping', (req, res) => res.json({ ok: true }));

app.get('/api/status', auth, (req, res) => res.json({ state: wa.state }));

// Name and archived state per chat, for notifications. Archived state can change, so it is re-read
// every INFO_TTL_MS; the app stays silent for archived chats.
const chatInfo = new Map();
const INFO_TTL_MS = 15 * 1000;
async function infoOf(chatId) {
  const hit = chatInfo.get(chatId);
  if (hit && Date.now() - hit.at < INFO_TTL_MS) return hit;
  let info = hit || { name: '', archived: false, at: 0 };
  try {
    const c = await client.getChatById(chatId);
    info = { name: c.name || (c.id && c.id.user) || info.name, archived: !!c.archived, at: Date.now() };
  } catch (_) {
    info = { ...info, at: Date.now() - INFO_TTL_MS + 5000 }; // lookup failed: try again shortly
  }
  chatInfo.set(chatId, info);
  return info;
}

// A chat is "real" when it holds at least one message that is not a system notice. Chats whose newest message
// is a real one are real at once; the rest are checked by looking at their recent messages (remembered for a while).
const NOTICE_TYPES = new Set(['e2e_notification', 'notification_template']);
const realCache = new Map();
async function isRealChat(c) {
  const last = c.lastMessage;
  if (last && !NOTICE_TYPES.has(last.type)) return true;
  const id = c.id._serialized;
  const hit = realCache.get(id);
  if (hit && Date.now() - hit.at < 30 * 60 * 1000) return hit.real;
  let real = true; // when in doubt, show the chat
  try {
    const msgs = await c.fetchMessages({ limit: 25 });
    real = msgs.some((m) => !NOTICE_TYPES.has(m.type) && m.type !== 'unknown' && !(m._data && m._data.viewMode === 'HIDDEN'));
  } catch (_) { /* keep it */ }
  realCache.set(id, { real, at: Date.now() });
  return real;
}

// What the chat list shows as a chat's last message. WhatsApp Web's own `lastMessage` is the last one in its message
// collection, which is not always the newest (history-synced call logs sit at the end, some entries have no text),
// so when it looks wrong the recent messages are read and the newest real one wins. Remembered per chat.
const lastCache = new Map();
const previewOf = (m) => (m.body || LABELS[m.type] || '').slice(0, 120);
async function lastOf(c) {
  const lm = c.lastMessage;
  const ackOf = (m) => (m && m.fromMe && Number.isInteger(m.ack) ? m.ack : null);
  if (lm && lm.type !== 'call_log' && previewOf(lm) && (lm.timestamp || 0) >= (c.timestamp || 0)) {
    return { text: previewOf(lm), ack: ackOf(lm) };
  }
  const id = c.id._serialized;
  const stamp = `${c.timestamp || 0}:${lm && lm.id ? lm.id._serialized : ''}`;
  const hit = lastCache.get(id);
  if (hit && hit.stamp === stamp) return hit;
  let msgs = [];
  try { msgs = await c.fetchMessages({ limit: 15 }); } catch (_) { /* fall back to lastMessage */ }
  const real = msgs.filter((m) => !NOTICE_TYPES.has(m.type) && m.type !== 'unknown' && m.type !== 'album'
    && !(m._data && m._data.viewMode === 'HIDDEN'));
  real.sort((a, b) => b.timestamp - a.timestamp);
  const m = real[0] || lm;
  const out = { stamp, text: m ? (previewOf(m) || '[Message]') : '', ack: ackOf(m) };
  lastCache.set(id, out);
  return out;
}

let chatCache = { at: 0, data: null };
app.get('/api/chats', auth, ready, wrap(async (req, res) => {
  const limit = Math.min(Number(req.query.limit) || 30, 300);
  if (!chatCache.data || Date.now() - chatCache.at > 5000) {
    const chats = await client.getChats();
    // WhatsApp Web's chat timestamps can lag (a chat you just wrote in may still look old), so the newest real
    // message this bridge stored for a chat counts too.
    const seen = new Map(q.lastPerChat.all().map((r) => [r.chat_id, r.ts]));
    // WhatsApp Web also keeps chats that hold nothing but system notices (e.g. "messages are end-to-end encrypted");
    // the phone doesn't list those, so neither do we.
    const visible = [];
    for (let i = 0; i < chats.length; i += 6) {
      const group = chats.slice(i, i + 6);
      const flags = await Promise.all(group.map(isRealChat));
      group.forEach((c, k) => { if (flags[k]) visible.push(c); });
    }
    const lasts = new Map();
    for (let i = 0; i < visible.length; i += 6) {
      const group = visible.slice(i, i + 6);
      const got = await Promise.all(group.map((c) => lastOf(c).catch(() => ({ text: previewOf(c.lastMessage || {}), ack: null }))));
      group.forEach((c, k) => lasts.set(c.id._serialized, got[k]));
    }
    for (const c of visible) chatInfo.set(c.id._serialized, { name: c.name || c.id.user, archived: !!c.archived, at: Date.now() });
    const data = visible.map((c, idx) => ({
      idx, // WhatsApp's own position, which keeps pinned chats in the order you pinned them
      id: c.id._serialized, name: c.name || c.id.user, isGroup: !!c.isGroup, archived: !!c.archived, pinned: !!c.pinned,
      unread: c.unreadCount || 0, ts: Math.max(c.timestamp || 0, seen.get(c.id._serialized) || 0),
      last: lasts.get(c.id._serialized).text,
      // when the last message is yours: its delivery state, for the ticks in the chat list
      lastAck: lasts.get(c.id._serialized).ack,
    }));
    // pinned chats first, in WhatsApp's own (pin) order; everything else newest first
    data.sort((a, b) => (Number(b.pinned) - Number(a.pinned)) || (a.pinned ? a.idx - b.idx : b.ts - a.ts));
    for (const d of data) delete d.idx;
    chatCache = { at: Date.now(), data };
  }
  res.json(chatCache.data.slice(0, limit));
}));

app.get('/api/chats/:chatId/messages', auth, ready, validChat, wrap(async (req, res) => {
  const limit = Math.min(Number(req.query.limit) || 30, 300); // the app pages back by raising the limit
  const chat = await client.getChatById(req.params.chatId);
  // WhatsApp Web's message collection isn't strictly chronological (history-synced call logs can sit
  // at the end), so a small limit can return the wrong slice. Over-fetch, sort, keep the newest.
  const msgs = await chat.fetchMessages({ limit: Math.min(600, limit * 3) });
  await Promise.all(msgs.map(async (m) => { m._reactions = await reactionsOf(m); })); // only touches messages that have any
  const out = [];
  for (const m of msgs) { const row = store(m); if (row) out.push(row); }
  out.sort((a, b) => a.ts - b.ts);
  out.splice(0, Math.max(0, out.length - limit));
  if (req.query.markRead === '1') await chat.sendSeen();
  res.json(out);
}));

app.post('/api/chats/:chatId/messages', auth, ready, validChat, wrap(async (req, res) => {
  const text = req.body && typeof req.body.text === 'string' ? req.body.text : '';
  if (!text.trim() || text.length > 4096) return res.status(400).json({ error: 'text required (max 4096)' });
  // an optional quotedId makes it a reply to that message
  const quotedId = req.body && typeof req.body.quotedId === 'string' && MSG_ID.test(req.body.quotedId) ? req.body.quotedId : '';
  const m = await client.sendMessage(req.params.chatId, text, quotedId ? { quotedMessageId: quotedId } : {});
  res.json(store(m, true) || { id: m.id._serialized });
}));

// Edit one of your own text messages (WhatsApp allows this for 15 minutes after sending).
app.post('/api/chats/:chatId/messages/:id/edit', auth, ready, validChat, wrap(async (req, res) => {
  const id = req.params.id;
  const text = req.body && typeof req.body.text === 'string' ? req.body.text : '';
  if (!MSG_ID.test(id)) return res.status(400).json({ error: 'bad id' });
  if (!text.trim() || text.length > 4096) return res.status(400).json({ error: 'text required (max 4096)' });
  const m = await client.getMessageById(id);
  if (!m || !m.fromMe || m.to !== req.params.chatId) return res.status(404).json({ error: 'message not found' });
  if (m.type !== 'chat') return res.status(409).json({ error: 'only text messages can be edited' });
  const edited = await m.edit(text);
  if (!edited) return res.status(409).json({ error: 'cannot be edited any more' });
  const row = restoreEdited(m, text);
  res.json(row || { id });
}));

// A message of this chat, looked up by id; answers 400/404 itself and returns null when it can't.
async function chatMessage(req, res) {
  const id = req.params.id;
  if (!MSG_ID.test(id)) { res.status(400).json({ error: 'bad id' }); return null; }
  // The message id identifies it on its own. (Comparing the chat id as well broke for chats that WhatsApp
  // addresses by two different ids, e.g. @lid and @c.us.)
  const m = await client.getMessageById(id).catch((e) => { console.error('message lookup failed:', e.message); return null; });
  if (!m) { console.error('message not found by WhatsApp (id length ' + id.length + ')'); res.status(404).json({ error: 'message not found' }); return null; }
  return m;
}

// Delete a message: {"everyone": true} removes it for everyone (own messages, within WhatsApp's time limit),
// otherwise only from this account's view. The library quietly falls back to "delete for me" when everyone-delete
// isn't allowed, so that case is checked first and refused.
app.post('/api/chats/:chatId/messages/:id/delete', auth, ready, validChat, wrap(async (req, res) => {
  const m = await chatMessage(req, res);
  if (!m) return;
  const id = m.id._serialized;
  store(m); // make sure a row exists to be rewritten
  if (req.body && req.body.everyone) {
    const allowed = await client.pupPage.evaluate(async (mid) => {
      const { Msg } = window.require('WAWebCollections');
      const msg = Msg.get(mid) || (await Msg.getMessagesById([mid]))?.messages?.[0];
      if (!msg) return false;
      const cap = window.require('WAWebMsgActionCapability');
      return !!(cap.canSenderRevokeMsg(msg) || cap.canAdminRevokeMsg(msg));
    }, id);
    if (!allowed) return res.status(409).json({ error: 'cannot be deleted for everyone (too old, or not yours)' });
    await m.delete(true);
    return res.json(rewrite(id, 'revoked') || { id, type: 'revoked' });
  }
  await m.delete(false);
  rewrite(id, 'deleted');
  res.json({ id, type: 'deleted' });
}));

// Forward a message to another chat: {"to": "<chat id>"}. whatsapp-web.js's own forward() relies on a WhatsApp Web
// module that no longer exists, so the message is sent again instead: text as text, and files by downloading
// them and sending them on. It arrives as a normal message (without WhatsApp's "Forwarded" tag).
const FORWARDABLE_MEDIA = new Set(['image', 'video', 'audio', 'ptt', 'document', 'sticker']);
app.post('/api/chats/:chatId/messages/:id/forward', auth, ready, validChat, wrap(async (req, res) => {
  const to = req.body && typeof req.body.to === 'string' ? req.body.to : '';
  if (!CHAT_ID.test(to)) return res.status(400).json({ error: 'bad destination chat' });
  const m = await chatMessage(req, res);
  if (!m) return;
  try {
    if (m.type === 'chat' && m.body) {
      await client.sendMessage(to, m.body);
    } else if (FORWARDABLE_MEDIA.has(m.type) && m.hasMedia) {
      const media = await m.downloadMedia();
      if (!media) return res.status(409).json({ error: 'the file is no longer available to forward' });
      await client.sendMessage(to, media, {
        caption: m.body || undefined,
        sendAudioAsVoice: m.type === 'ptt',
        sendMediaAsSticker: m.type === 'sticker',
      });
    } else {
      return res.status(409).json({ error: 'this kind of message cannot be forwarded here' });
    }
  } catch (e) {
    console.error('forward failed:', e.message);
    return res.status(409).json({ error: 'could not forward: ' + String(e.message).split('\n')[0].slice(0, 140) });
  }
  res.json({ ok: true });
}));

// Profile picture as a JPEG, cached for a day (a missing/private photo is cached too).
app.get('/api/avatar/:chatId', auth, ready, validChat, wrap(async (req, res) => {
  const file = path.join(AVATARS, sha256(req.params.chatId) + '.jpg');
  const none = file + '.none';
  const fresh = (p) => { try { return Date.now() - fs.statSync(p).mtimeMs < AVATAR_TTL_MS; } catch (_) { return false; } };
  if (fresh(file)) return res.type('image/jpeg').sendFile(file);
  if (fresh(none)) return res.status(404).json({ error: 'no photo' });
  const miss = () => { fs.writeFileSync(none, ''); return res.status(404).json({ error: 'no photo' }); };
  const url = await client.getProfilePicUrl(req.params.chatId).catch(() => null);
  if (!url || !AVATAR_HOSTS.test(new URL(url).hostname)) return miss();
  const r = await fetch(url);
  const buf = r.ok ? Buffer.from(await r.arrayBuffer()) : null;
  if (!buf || buf.length > 2 * 1024 * 1024 || buf[0] !== 0xff || buf[1] !== 0xd8) return miss();
  fs.writeFileSync(file, buf);
  res.type('image/jpeg').sendFile(file);
}));

// Send a photo (JPEG/PNG body, already resized by the app); optional ?caption=
app.post('/api/chats/:chatId/photo', auth, ready, validChat, express.raw({ type: '*/*', limit: '12mb' }), wrap(async (req, res) => {
  const buf = req.body;
  if (!Buffer.isBuffer(buf) || buf.length < 100) return res.status(400).json({ error: 'photo required' });
  const mime = buf[0] === 0xff && buf[1] === 0xd8 ? 'image/jpeg'
    : buf.slice(0, 4).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47])) ? 'image/png' : null;
  if (!mime) return res.status(415).json({ error: 'JPEG or PNG only' });
  const caption = String(req.query.caption || '').slice(0, 1024);
  const media = new MessageMedia(mime, buf.toString('base64'), mime === 'image/png' ? 'photo.png' : 'photo.jpg');
  const m = await client.sendMessage(req.params.chatId, media, caption ? { caption } : {});
  res.json(store(m, true) || { id: m.id._serialized });
}));

// Send a voice note: the body is a 16-bit PCM WAV recorded on the phone; it is encoded to Ogg Opus here.
app.post('/api/chats/:chatId/voice', auth, ready, validChat, express.raw({ type: '*/*', limit: '16mb' }), wrap(async (req, res) => {
  const buf = req.body;
  if (!Buffer.isBuffer(buf) || buf.length < 100) return res.status(400).json({ error: 'recording required' });
  let enc;
  try { enc = await wavToOggOpus(client.pupPage, buf); } catch (e) { return res.status(400).json({ error: e.message }); }
  const media = new MessageMedia('audio/ogg; codecs=opus', enc.ogg.toString('base64'), 'voice.ogg');
  const m = await client.sendMessage(req.params.chatId, media, { sendAudioAsVoice: true });
  res.json(store(m, true) || { id: m.id._serialized });
}));

// Cheap polling: ?since=<seq> returns messages stored after that sequence number.
// React to a message with an emoji; an empty emoji removes your reaction.
app.post('/api/chats/:chatId/messages/:id/react', auth, ready, validChat, wrap(async (req, res) => {
  const emoji = req.body && typeof req.body.emoji === 'string' ? req.body.emoji : null;
  if (emoji === null || emoji.length > 16) return res.status(400).json({ error: 'emoji required (or empty to remove)' });
  const m = await chatMessage(req, res);
  if (!m) return;
  await m.react(emoji);
  // Answer from what we know (your reaction swapped in) rather than waiting for WhatsApp Web to settle; the
  // real list is read back a moment later and the app picks it up through the normal updates.
  const prevRow = q.byId.get(req.params.id);
  const prev = prevRow && prevRow.meta ? (JSON.parse(prevRow.meta).reactions || []) : [];
  const next = [];
  for (const r of prev) {
    if (r.me) { if (r.n - 1 > 0) next.push({ ...r, n: r.n - 1, me: false }); } else next.push(r);
  }
  if (emoji) {
    const same = next.find((r) => r.e === emoji);
    if (same) { same.n += 1; same.me = true; } else next.push({ e: emoji, n: 1, me: true });
  }
  m._reactions = next;
  q.delMsg.run(req.params.id);
  const row = store(m, true);
  setTimeout(() => refreshReactions(req.params.id).catch((e) => console.error('reaction refresh failed:', e.message)), 1500);
  res.json(row || { id: req.params.id });
}));

app.get('/api/updates', auth, wrap(async (req, res) => {
  const since = Math.max(0, Number(req.query.since) || 0);
  const messages = q.since.all(since).map(fromRow);
  for (const m of messages) { // names for notifications (cached after the first lookup)
    const info = wa.state === 'ready' ? await infoOf(m.chatId) : { name: '', archived: false };
    m.chatName = info.name;
    m.archived = info.archived;
    m.isGroup = m.chatId.endsWith('@g.us');
  }
  res.json({ seq: q.maxSeq.get().seq, messages });
}));

// Android 4.x can't play Opus, so voice notes are decoded by the bridge's own Chromium (which can)
// and handed over as plain 16 kHz mono WAV.
async function toWav(buf) {
  const b64 = await client.pupPage.evaluate(async (data) => {
    const bytes = Uint8Array.from(atob(data), (c) => c.charCodeAt(0));
    const ctx = new (window.AudioContext || window.webkitAudioContext)();
    const decoded = await ctx.decodeAudioData(bytes.buffer);
    ctx.close();
    if (decoded.duration > 600) throw new Error('audio too long');
    const rate = 16000;
    const off = new OfflineAudioContext(1, Math.max(1, Math.ceil(decoded.duration * rate)), rate);
    const src = off.createBufferSource();
    src.buffer = decoded;
    src.connect(off.destination);
    src.start();
    const samples = (await off.startRendering()).getChannelData(0);
    const out = new ArrayBuffer(44 + samples.length * 2);
    const v = new DataView(out);
    const tag = (o, t) => { for (let i = 0; i < t.length; i++) v.setUint8(o + i, t.charCodeAt(i)); };
    tag(0, 'RIFF'); v.setUint32(4, 36 + samples.length * 2, true); tag(8, 'WAVE'); tag(12, 'fmt ');
    v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
    tag(36, 'data'); v.setUint32(40, samples.length * 2, true);
    for (let i = 0; i < samples.length; i++) {
      const x = Math.max(-1, Math.min(1, samples[i]));
      v.setInt16(44 + i * 2, x < 0 ? x * 0x8000 : x * 0x7fff, true);
    }
    const u8 = new Uint8Array(out);
    let s = '';
    for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
    return btoa(s);
  }, buf.toString('base64'));
  return Buffer.from(b64, 'base64');
}

// First frame of a WebP as a PNG (at most 320 px), decoded by the bridge's own Chromium.
async function webpToPng(buf) {
  const out = await client.pupPage.evaluate(async (b64) => {
    const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    const bmp = await createImageBitmap(new Blob([bytes], { type: 'image/webp' }));
    const k = Math.min(1, 320 / Math.max(bmp.width, bmp.height));
    const c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(bmp.width * k));
    c.height = Math.max(1, Math.round(bmp.height * k));
    c.getContext('2d').drawImage(bmp, 0, 0, c.width, c.height);
    return c.toDataURL('image/png').split(',')[1];
  }, buf.toString('base64'));
  return Buffer.from(out, 'base64');
}

app.get('/api/media/:id', auth, ready, wrap(async (req, res) => {
  const id = req.params.id;
  if (!MSG_ID.test(id)) return res.status(400).json({ error: 'bad id' });
  const base = path.join(MEDIA, sha256(id));
  let meta;
  try { meta = JSON.parse(fs.readFileSync(base + '.json', 'utf8')); } catch (_) { /* not cached */ }
  if (!meta) {
    const m = await client.getMessageById(id);
    if (!m || !m.hasMedia) return res.status(404).json({ error: 'no media' });
    const media = await m.downloadMedia();
    if (!media || media.data.length > MAX_MEDIA_BYTES * 1.4) return res.status(404).json({ error: 'media unavailable' });
    meta = { mime: String(media.mimetype || '').split(';')[0], name: path.basename(media.filename || 'file') };
    fs.writeFileSync(base + '.bin', Buffer.from(media.data, 'base64'));
    fs.writeFileSync(base + '.json', JSON.stringify(meta));
  }
  // Android 4.3 cannot decode animated (or some other) WebP stickers; Chromium can, so hand the phone a still PNG.
  if (req.query.fmt === 'png' && meta.mime === 'image/webp') {
    const pngFile = base + '.png';
    if (!fs.existsSync(pngFile)) fs.writeFileSync(pngFile, await webpToPng(fs.readFileSync(base + '.bin')));
    return res.type('image/png').sendFile(pngFile);
  }
  if (req.query.fmt === 'wav' && /ogg|opus|webm/.test(meta.mime)) {
    const wavFile = base + '.wav';
    if (!fs.existsSync(wavFile)) fs.writeFileSync(wavFile, await toWav(fs.readFileSync(base + '.bin')));
    return res.type('audio/wav').sendFile(wavFile);
  }
  res.set('Content-Type', INLINE_MEDIA.test(meta.mime) ? meta.mime : 'application/octet-stream');
  if (!INLINE_MEDIA.test(meta.mime)) res.set('Content-Disposition', `attachment; filename="${meta.name.replace(/[^\w.\- ]/g, '_')}"`);
  res.sendFile(base + '.bin');
}));

// ---------- admin (this machine only) ----------
// The LAN address other devices should use to reach this bridge (what the Q20 app is told in the QR).
function lanAddress() {
  for (const list of Object.values(os.networkInterfaces())) {
    for (const i of list || []) {
      if (i.family === 'IPv4' && !i.internal && isPrivate(i.address)) return i.address;
    }
  }
  return null;
}

app.get('/admin/state', localOnly, wrap(async (req, res) => {
  const code = pairCode && Date.now() < pairCode.expires ? pairCode.code : null;
  const host = lanAddress();
  // One scan gives the app both the server address and the one-time code.
  const pairUrl = code && host ? `bbchat://pair?h=${host}:${PORT}&c=${code}` : null;
  res.json({
    state: wa.state,
    qrSvg: wa.qr ? await QRCode.toString(wa.qr, { type: 'svg', margin: 1 }) : null,
    pairCode: code,
    pairHost: host ? `${host}:${PORT}` : null,
    pairUrl,
    pairQrSvg: pairUrl ? await QRCode.toString(pairUrl, { type: 'svg', margin: 2, errorCorrectionLevel: 'M' }) : null,
    devices: q.listDevices.all(),
  });
}));
app.post('/admin/pair-code', localOnly, (req, res) => {
  pairCode = { code: String(crypto.randomInt(0, 1000000)).padStart(6, '0'), expires: Date.now() + 10 * 60000 };
  res.json({ code: pairCode.code });
});
app.delete('/admin/devices/:id', localOnly, (req, res) => {
  q.delDevice.run(req.params.id);
  res.json({ ok: true });
});
app.get('/admin', localOnly, (req, res) => res.sendFile(path.join(__dirname, 'admin.html')));

app.listen(PORT, HOST, () => console.log(`Bridge listening on http://${HOST}:${PORT} (admin: http://localhost:${PORT}/admin)`));

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, async () => { try { await client.destroy(); } finally { process.exit(0); } });
}
