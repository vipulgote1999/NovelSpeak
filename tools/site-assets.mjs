#!/usr/bin/env node
// Derives the website's images from repo media with nothing but Node's zlib.
//
// The project ships no npm dependencies and this device has no ImageMagick, ffmpeg or Python, so
// the one operation the site actually needs — an exact 2x (or 4x) box downscale of a PNG — is
// implemented here. It is a build-time convenience: the outputs are committed under site/assets/img
// and served as-is. Run `node tools/site-assets.mjs` after changing a source image.
//
// Supported input: PNG, 8-bit, non-interlaced, colour type 0/2/3/4/6. Anything else is refused
// rather than guessed at.

import { readFileSync, writeFileSync, mkdirSync, existsSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import zlib from 'node:zlib';

const ROOT = dirname(dirname(fileURLToPath(import.meta.url)));
const OUT = join(ROOT, 'site', 'assets', 'img');

const jobs = [
  ['docs/play-store/screenshots/phone-02-reading.png', 'reader-540.png', 2],
  ['docs/play-store/screenshots/phone-01-home.png', 'home-540.png', 2],
  ['docs/play-store/screenshots/phone-03-settings.png', 'settings-540.png', 2],
  ['docs/play-store/screenshots/phone-04-privacy.png', 'privacy-540.png', 2],
  // Cropped below the browser chrome: the address bar in these captures shows an emulator host.
  ['docs/media/ui-audit/phone-cast.png', 'cast-540.png', 2, 212],
  ['docs/media/ui-audit/chrome-compact-phone.png', 'player-540.png', 1, 112],
  ['docs/play-store/graphics/icon-512.png', 'icon-192.png', 2.6667],
  ['docs/play-store/graphics/icon-512.png', 'icon-96.png', 5.3334],
];

// ---------- PNG decode ----------

function crcTable() {
  const t = new Int32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c;
  }
  return t;
}
const CRC = crcTable();

function crc32(buf) {
  let c = -1;
  for (let i = 0; i < buf.length; i++) c = CRC[(c ^ buf[i]) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}

function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
  return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
}

function decodePng(buf) {
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error('not a PNG');
  let pos = 8;
  let ihdr = null, plte = null, trns = null;
  const idat = [];
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos);
    const type = buf.toString('ascii', pos + 4, pos + 8);
    const data = buf.subarray(pos + 8, pos + 8 + len);
    if (type === 'IHDR') {
      ihdr = {
        w: data.readUInt32BE(0),
        h: data.readUInt32BE(4),
        depth: data[8],
        color: data[9],
        interlace: data[12],
      };
    } else if (type === 'IDAT') idat.push(data);
    else if (type === 'PLTE') plte = data;
    else if (type === 'tRNS') trns = data;
    pos += 12 + len;
  }
  if (!ihdr) throw new Error('no IHDR');
  if (ihdr.depth !== 8 || ihdr.interlace !== 0) {
    throw new Error(`unsupported PNG: depth ${ihdr.depth}, interlace ${ihdr.interlace}`);
  }
  const channels = { 0: 1, 2: 3, 3: 1, 4: 2, 6: 4 }[ihdr.color];
  if (!channels) throw new Error(`unsupported colour type ${ihdr.color}`);

  const raw = zlib.inflateSync(Buffer.concat(idat));
  const bpp = channels;
  const stride = ihdr.w * bpp;
  const out = Buffer.alloc(ihdr.h * stride);
  let p = 0;
  for (let y = 0; y < ihdr.h; y++) {
    const filter = raw[p++];
    const row = y * stride;
    const prev = row - stride;
    for (let x = 0; x < stride; x++) {
      const v = raw[p++];
      const a = x >= bpp ? out[row + x - bpp] : 0;
      const b = y > 0 ? out[prev + x] : 0;
      const c = x >= bpp && y > 0 ? out[prev + x - bpp] : 0;
      let val;
      switch (filter) {
        case 0: val = v; break;
        case 1: val = (v + a) & 0xff; break;
        case 2: val = (v + b) & 0xff; break;
        case 3: val = (v + ((a + b) >> 1)) & 0xff; break;
        case 4: val = (v + paeth(a, b, c)) & 0xff; break;
        default: throw new Error(`bad filter ${filter} at row ${y}`);
      }
      out[row + x] = val;
    }
  }

  // Normalise to RGBA8.
  const rgba = Buffer.alloc(ihdr.w * ihdr.h * 4);
  const n = ihdr.w * ihdr.h;
  if (ihdr.color === 6) return { w: ihdr.w, h: ihdr.h, rgba: out };
  for (let i = 0; i < n; i++) {
    const s = i * bpp;
    const d = i * 4;
    if (ihdr.color === 2) {
      rgba[d] = out[s]; rgba[d + 1] = out[s + 1]; rgba[d + 2] = out[s + 2]; rgba[d + 3] = 255;
    } else if (ihdr.color === 0) {
      rgba[d] = rgba[d + 1] = rgba[d + 2] = out[s]; rgba[d + 3] = 255;
    } else if (ihdr.color === 4) {
      rgba[d] = rgba[d + 1] = rgba[d + 2] = out[s]; rgba[d + 3] = out[s + 1];
    } else {
      const idx = out[s];
      rgba[d] = plte[idx * 3]; rgba[d + 1] = plte[idx * 3 + 1]; rgba[d + 2] = plte[idx * 3 + 2];
      rgba[d + 3] = trns && idx < trns.length ? trns[idx] : 255;
    }
  }
  return { w: ihdr.w, h: ihdr.h, rgba };
}

// ---------- box downscale ----------

function cropTop({ w, h, rgba }, pixels) {
  if (!pixels) return { w, h, rgba };
  const keep = h - pixels;
  if (keep <= 0) throw new Error("cropTop removes the whole image");
  return { w, h: keep, rgba: rgba.subarray(pixels * w * 4) };
}

function downscale({ w, h, rgba }, scale) {
  const outW = Math.max(1, Math.round(w / scale));
  const outH = Math.max(1, Math.round(h / scale));
  const out = Buffer.alloc(outW * outH * 4);
  const fx = w / outW, fy = h / outH;
  for (let y = 0; y < outH; y++) {
    const y0 = Math.floor(y * fy), y1 = Math.min(h, Math.max(y0 + 1, Math.floor((y + 1) * fy)));
    for (let x = 0; x < outW; x++) {
      const x0 = Math.floor(x * fx), x1 = Math.min(w, Math.max(x0 + 1, Math.floor((x + 1) * fx)));
      let r = 0, g = 0, b = 0, a = 0, count = 0;
      for (let sy = y0; sy < y1; sy++) {
        for (let sx = x0; sx < x1; sx++) {
          const i = (sy * w + sx) * 4;
          const alpha = rgba[i + 3] / 255;
          r += rgba[i] * alpha; g += rgba[i + 1] * alpha; b += rgba[i + 2] * alpha;
          a += rgba[i + 3]; count++;
        }
      }
      const d = (y * outW + x) * 4;
      const aAvg = a / count;
      const unpremultiply = aAvg > 0 ? 255 / aAvg : 0;
      out[d] = Math.min(255, Math.round((r / count) * unpremultiply));
      out[d + 1] = Math.min(255, Math.round((g / count) * unpremultiply));
      out[d + 2] = Math.min(255, Math.round((b / count) * unpremultiply));
      out[d + 3] = Math.round(aAvg);
    }
  }
  return { w: outW, h: outH, rgba: out };
}

// ---------- PNG encode ----------

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function encodePng({ w, h, rgba }) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;   // bit depth
  ihdr[9] = 6;   // RGBA
  ihdr[10] = 0;  // deflate
  ihdr[11] = 0;  // adaptive filtering
  ihdr[12] = 0;  // no interlace
  const raw = Buffer.alloc(h * (w * 4 + 1));
  for (let y = 0; y < h; y++) {
    const row = y * (w * 4 + 1);
    raw[row] = 0; // filter: none (screenshots compress well anyway, and this keeps the tool small)
    rgba.copy(raw, row + 1, y * w * 4, (y + 1) * w * 4);
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', zlib.deflateSync(raw, { level: 9, memLevel: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ---------- run ----------

mkdirSync(OUT, { recursive: true });
let total = 0;
const rows = [];
for (const [src, dest, scale, crop] of jobs) {
  const abs = join(ROOT, src);
  if (!existsSync(abs)) throw new Error(`missing source ${src}`);
  const decoded = decodePng(readFileSync(abs));
  const scaled = downscale(cropTop(decoded, crop || 0), scale);
  const png = encodePng(scaled);
  writeFileSync(join(OUT, dest), png);
  total += png.length;
  rows.push([dest, `${scaled.w}x${scaled.h}`, `${(png.length / 1024).toFixed(1)} KB`]);
}
for (const [name, dims, kb] of rows) console.log(`${kb.padStart(9)}  ${dims.padStart(10)}  ${name}`);
console.log(`\n${rows.length} images, ${(total / 1024).toFixed(1)} KB total → site/assets/img/`);
const budget = 900 * 1024;
if (total > budget) {
  console.error(`OVER BUDGET: ${(total / 1024).toFixed(0)} KB > 900 KB`);
  process.exit(1);
}
const poster = join(ROOT, 'site/assets/img/film-poster.jpg');
if (existsSync(poster)) console.log(`film poster (native, not generated): ${(statSync(poster).size / 1024).toFixed(1)} KB`);
