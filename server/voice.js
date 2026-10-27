'use strict';
// Voice notes: the phone records plain 16-bit PCM in a WAV file; WhatsApp wants Ogg Opus.
// Chromium (the one already running WhatsApp Web) can encode Opus with WebCodecs, so the encoding
// happens there and this module only wraps the packets in an Ogg container. No ffmpeg needed.

const RATE = 16000; // WhatsApp's own voice notes are 16 kHz mono Opus
const FRAME = 320; // 20 ms at 16 kHz
const PRESKIP = 312;
const MAX_SECONDS = 600;

/** Reads a mono/stereo 16-bit PCM WAV and returns { rate, samples: Int16Array (mono) }. */
function parseWav(buf) {
  if (buf.length < 44 || buf.toString('latin1', 0, 4) !== 'RIFF' || buf.toString('latin1', 8, 12) !== 'WAVE') {
    throw new Error('not a WAV file');
  }
  let pos = 12;
  let fmt = null;
  let data = null;
  while (pos + 8 <= buf.length) {
    const id = buf.toString('latin1', pos, pos + 4);
    const size = buf.readUInt32LE(pos + 4);
    const body = pos + 8;
    if (id === 'fmt ') {
      fmt = { format: buf.readUInt16LE(body), channels: buf.readUInt16LE(body + 2), rate: buf.readUInt32LE(body + 4), bits: buf.readUInt16LE(body + 14) };
    } else if (id === 'data') {
      data = buf.subarray(body, Math.min(buf.length, body + size));
      break;
    }
    pos = body + size + (size & 1);
  }
  if (!fmt || !data) throw new Error('WAV has no audio');
  if (fmt.format !== 1 || fmt.bits !== 16 || fmt.channels < 1 || fmt.channels > 2) throw new Error('need 16-bit PCM WAV');
  const frames = Math.floor(data.length / (2 * fmt.channels));
  const mono = new Int16Array(frames);
  for (let i = 0; i < frames; i++) {
    let sum = 0;
    for (let c = 0; c < fmt.channels; c++) sum += data.readInt16LE((i * fmt.channels + c) * 2);
    mono[i] = Math.round(sum / fmt.channels);
  }
  return { rate: fmt.rate, samples: mono };
}

/** Linear resampling to 16 kHz (the phone normally records at 16 kHz already, so this is a fallback). */
function toRate(samples, from) {
  if (from === RATE) return samples;
  const n = Math.floor((samples.length * RATE) / from);
  const out = new Int16Array(n);
  const step = from / RATE;
  for (let i = 0; i < n; i++) {
    const p = i * step;
    const k = Math.floor(p);
    const f = p - k;
    const a = samples[k];
    const b = k + 1 < samples.length ? samples[k + 1] : a;
    out[i] = Math.round(a + (b - a) * f);
  }
  return out;
}

/** Encodes 16 kHz mono samples to Opus packets inside the given Puppeteer page (WebCodecs). */
async function encodeInPage(page, samples) {
  const b64 = Buffer.from(samples.buffer, samples.byteOffset, samples.byteLength).toString('base64');
  const res = await page.evaluate(async (data, rate, frame) => {
    if (typeof AudioEncoder === 'undefined') throw new Error('WebCodecs AudioEncoder is not available');
    const bin = atob(data);
    const bytes = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
    const pcm = new Int16Array(bytes.buffer);
    const packets = [];
    let failure = null;
    const enc = new AudioEncoder({
      output: (chunk) => { const u = new Uint8Array(chunk.byteLength); chunk.copyTo(u); packets.push(u); },
      error: (e) => { failure = e; },
    });
    enc.configure({ codec: 'opus', sampleRate: rate, numberOfChannels: 1, bitrate: 24000, opus: { frameDuration: 20000, application: 'voip' } });
    for (let i = 0; i < pcm.length; i += frame) {
      const chunk = new Int16Array(frame);
      chunk.set(pcm.subarray(i, Math.min(pcm.length, i + frame)));
      enc.encode(new AudioData({ format: 's16', sampleRate: rate, numberOfFrames: frame, numberOfChannels: 1, timestamp: Math.round((i / rate) * 1e6), data: chunk }));
    }
    await enc.flush();
    enc.close();
    if (failure) throw new Error('encoder failed: ' + failure.message);
    const total = packets.reduce((n, p) => n + p.length, 0);
    const all = new Uint8Array(total);
    let off = 0;
    for (const p of packets) { all.set(p, off); off += p.length; }
    let s = '';
    for (let i = 0; i < all.length; i += 0x8000) s += String.fromCharCode.apply(null, all.subarray(i, i + 0x8000));
    return { lens: packets.map((p) => p.length), data: btoa(s) };
  }, b64, RATE, FRAME);
  const all = Buffer.from(res.data, 'base64');
  const packets = [];
  let off = 0;
  for (const n of res.lens) { packets.push(all.subarray(off, off + n)); off += n; }
  return packets;
}

// ---- Ogg container ----
const CRC = (() => {
  const t = new Uint32Array(256);
  for (let i = 0; i < 256; i++) {
    let r = (i << 24) >>> 0;
    for (let j = 0; j < 8; j++) r = (r & 0x80000000) ? (((r << 1) ^ 0x04c11db7) >>> 0) : ((r << 1) >>> 0);
    t[i] = r;
  }
  return t;
})();
const crc32 = (buf) => { let c = 0; for (let i = 0; i < buf.length; i++) c = (((c << 8) >>> 0) ^ CRC[((c >>> 24) ^ buf[i]) & 0xff]) >>> 0; return c; };

function page(serial, seq, granule, flags, packets) {
  const segs = [];
  for (const p of packets) {
    let n = p.length;
    while (n >= 255) { segs.push(255); n -= 255; }
    segs.push(n);
  }
  const head = Buffer.alloc(27 + segs.length);
  head.write('OggS', 0, 'latin1');
  head[4] = 0;
  head[5] = flags;
  head.writeBigUInt64LE(BigInt(granule), 6);
  head.writeUInt32LE(serial, 14);
  head.writeUInt32LE(seq, 18);
  head[26] = segs.length;
  segs.forEach((s, i) => { head[27 + i] = s; });
  const out = Buffer.concat([head, ...packets]);
  out.writeUInt32LE(crc32(out), 22);
  return out;
}

function muxOgg(packets, sampleCount) {
  const serial = Math.floor(Math.random() * 0xffffffff) >>> 0;
  const opusHead = Buffer.alloc(19);
  opusHead.write('OpusHead', 0, 'latin1');
  opusHead[8] = 1;
  opusHead[9] = 1; // mono
  opusHead.writeUInt16LE(PRESKIP, 10);
  opusHead.writeUInt32LE(RATE, 12);
  opusHead.writeUInt16LE(0, 16);
  opusHead[18] = 0;
  const vendor = Buffer.from('BB Whatsapp');
  const opusTags = Buffer.alloc(8 + 4 + vendor.length + 4);
  opusTags.write('OpusTags', 0, 'latin1');
  opusTags.writeUInt32LE(vendor.length, 8);
  vendor.copy(opusTags, 12);
  opusTags.writeUInt32LE(0, 12 + vendor.length);

  const pages = [page(serial, 0, 0, 0x02, [opusHead]), page(serial, 1, 0, 0, [opusTags])];
  const perPage = 25;
  const totalGranule = PRESKIP + sampleCount * 3; // granule positions are always in 48 kHz units
  for (let i = 0, seq = 2; i < packets.length; i += perPage, seq++) {
    const group = packets.slice(i, i + perPage);
    const last = i + perPage >= packets.length;
    const granule = last ? totalGranule : PRESKIP + (i + group.length) * FRAME * 3;
    pages.push(page(serial, seq, granule, last ? 0x04 : 0, group));
  }
  return Buffer.concat(pages);
}

/** WAV bytes in, Ogg Opus bytes out (plus the duration in seconds). */
async function wavToOggOpus(page_, wav) {
  const { rate, samples } = parseWav(wav);
  if (rate < 8000 || rate > 96000) throw new Error('unsupported sample rate');
  const mono = toRate(samples, rate);
  if (mono.length < RATE / 4) throw new Error('recording too short');
  if (mono.length > RATE * MAX_SECONDS) throw new Error('recording too long');
  const packets = await encodeInPage(page_, mono);
  if (!packets.length) throw new Error('nothing was encoded');
  return { ogg: muxOgg(packets, mono.length), seconds: mono.length / RATE };
}

module.exports = { wavToOggOpus, parseWav, muxOgg };
