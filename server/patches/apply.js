'use strict';
// whatsapp-web.js 1.34.7 predates a WhatsApp Web change: message keys no longer expose
// `_serialized` and carry the same string as `$1` instead (verified against the live page).
// That made getChats() throw and left message ids undefined. A second fix makes media downloads pass
// an explicit mimetype, which the current downloader now requires, and a third stops media sends from
// copying internal model fields into the outgoing message. This applies minimal,
// idempotent fallbacks to the installed library. Re-run automatically on every start;
// drop it once an upstream release includes the fix.

const fs = require('fs');
const path = require('path');

const LIB = path.join(__dirname, '..', 'node_modules', 'whatsapp-web.js', 'src');
const UTILS = path.join(LIB, 'util', 'Injected', 'Utils.js');
const MESSAGE = path.join(LIB, 'structures', 'Message.js');

const EDITS = [
  {
    // WhatsApp Web's downloader now rejects media without an explicit mimetype
    // ("Unexpected mimetype application/octet-stream"), which broke downloadMedia().
    file: MESSAGE,
    old: '                        type: msg.type,\n                        signal: new AbortController().signal,',
    now: '                        type: msg.type,\n                        mimetype: msg.mimetype,\n                        signal: new AbortController().signal,',
  },
  {
    file: UTILS,
    // every serialized message gets its key string back under the name the library reads
    old: 'const msg = message.serialize();\n',
    now: 'const msg = message.serialize();\n' +
      "        if (msg.id && msg.id._serialized === undefined && typeof msg.id.$1 === 'string') msg.id._serialized = msg.id.$1;\n",
  },
  {
    // Sending media: the outgoing message spread the whole media model into itself, and the current
    // WhatsApp Web build gives models internal fields (parent, collection, __x_*) that then break the
    // message ("Data passed to getter must include an id property"). Use the model's JSON only,
    // keeping the two values the library sets on it directly.
    file: UTILS,
    old: '            ...mediaOptions,\n            ...(mediaOptions.toJSON ? mediaOptions.toJSON() : {}),',
    now: '            ...(mediaOptions.toJSON\n                ? { caption: mediaOptions.caption, isViewOnce: mediaOptions.isViewOnce }\n                : mediaOptions),\n            ...(mediaOptions.toJSON ? mediaOptions.toJSON() : {}),',
  },
  { file: UTILS, old: '.Msg.get(chat.lastReceivedKey._serialized)', now: '.Msg.get(chat.lastReceivedKey._serialized || chat.lastReceivedKey.$1)' },
  { file: UTILS, old: 'chat.lastReceivedKey._serialized,\n', now: '(chat.lastReceivedKey._serialized || chat.lastReceivedKey.$1),\n' },
  { file: UTILS, old: '.Msg.get(newMsgKey._serialized)', now: '.Msg.get(newMsgKey._serialized || newMsgKey.$1)' },
  { file: UTILS, old: '.Msg.get(msg.id._serialized)', now: '.Msg.get(msg.id._serialized || msg.id.$1)' },
];

function apply() {
  const sources = {};
  let changed = 0;
  for (const e of EDITS) {
    sources[e.file] = sources[e.file] !== undefined ? sources[e.file] : fs.readFileSync(e.file, 'utf8');
    if (sources[e.file].includes(e.now)) continue;
    if (!sources[e.file].includes(e.old)) { console.warn('patch: anchor not found, skipped:', e.old.trim().slice(0, 60)); continue; }
    sources[e.file] = sources[e.file].replace(e.old, e.now);
    changed++;
  }
  if (changed) for (const f of Object.keys(sources)) fs.writeFileSync(f, sources[f]);
  console.log(changed ? `patched whatsapp-web.js (${changed} edits)` : 'whatsapp-web.js patch already applied');
}

apply();
