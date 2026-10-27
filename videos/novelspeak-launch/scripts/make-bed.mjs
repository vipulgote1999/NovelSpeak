
// make-bed.mjs — dependency-free procedural music bed for the NovelSpeak launch (9:16, 24 s)
// Pure Node (no npm, no python, no ffmpeg). Writes 24-bit-free 16-bit stereo WAV @44.1 kHz.
import fs from "node:fs";

const SR = 44100;
const DUR = 24.0;
const BPM = 120;
const BEAT = 60 / BPM;          // 0.5 s
const BAR = BEAT * 4;           // 2.0 s  -> 12 bars = 24 s exactly
const N = Math.round(SR * DUR);

const L = new Float32Array(N);
const R = new Float32Array(N);
const SEND = new Float32Array(N);   // reverb send

const rnd = (() => { let s = 0x9e3779b9; return () => (s = (s * 1664525 + 1013904223) >>> 0, (s / 4294967296) * 2 - 1); })();

function put(i, v, pan, send) {
  if (i < 0 || i >= N) return;
  const gl = Math.cos((pan + 1) * Math.PI / 4), gr = Math.sin((pan + 1) * Math.PI / 4);
  L[i] += v * gl; R[i] += v * gr;
  if (send) SEND[i] += v * send;
}
function idx(t) { return Math.round(t * SR); }

// ---------- voices ----------
function kick(t0, vel = 1) {
  const dur = 0.42, n = Math.round(dur * SR), i0 = idx(t0);
  let ph = 0;
  for (let k = 0; k < n; k++) {
    const t = k / SR;
    const f = 118 * Math.exp(-t * 34) + 44;
    ph += 2 * Math.PI * f / SR;
    const amp = Math.exp(-t * 7.5) * (1 - Math.exp(-t * 900));
    put(i0 + k, Math.sin(ph) * amp * vel * 0.62, 0, 0.05);
  }
}
function hat(t0, vel = 0.35, open = false) {
  const dur = open ? 0.16 : 0.045, n = Math.round(dur * SR), i0 = idx(t0);
  let lp = 0, hp = 0, prev = 0;
  for (let k = 0; k < n; k++) {
    const t = k / SR;
    const w = rnd();
    lp += 0.55 * (w - lp);          // lowpass
    const band = w - lp;            // crude highpass
    const amp = Math.exp(-t * (open ? 34 : 90));
    put(i0 + k, band * amp * vel * 0.5, (k % 2 ? 0.28 : -0.28), 0.02);
  }
}
function clap(t0, vel = 0.5) {
  const taps = [0, 0.011, 0.023], n = Math.round(0.24 * SR), i0 = idx(t0);
  for (const off of taps) {
    const s0 = i0 + idx(off);
    for (let k = 0; k < n; k++) {
      const t = k / SR;
      const w = rnd();
      const amp = Math.exp(-t * (off === 0.023 ? 16 : 55));
      put(s0 + k, w * amp * vel * 0.30, 0, 0.16);
    }
  }
}
function bass(t0, dur, f, vel = 1) {
  const n = Math.round(dur * SR), i0 = idx(t0);
  let ph = 0, ph2 = 0;
  for (let k = 0; k < n; k++) {
    const t = k / SR;
    ph += 2 * Math.PI * f / SR; ph2 += 2 * Math.PI * f * 2 / SR;
    const env = Math.min(1, t * 200) * Math.exp(-t * 2.2) * (t > dur - 0.05 ? Math.max(0, (dur - t) / 0.05) : 1);
    const v = Math.sin(ph) * 0.85 + Math.sin(ph2) * 0.18;
    put(i0 + k, v * env * vel * 0.34, 0, 0.03);
  }
}
function pluck(t0, f, vel = 0.8, pan = -0.3) {
  const dur = 0.55, n = Math.round(dur * SR), i0 = idx(t0);
  let p1 = 0, p2 = 0, p3 = 0;
  for (let k = 0; k < n; k++) {
    const t = k / SR;
    p1 += 2 * Math.PI * f / SR; p2 += 2 * Math.PI * f * 2.002 / SR; p3 += 2 * Math.PI * f * 3.01 / SR;
    const env = (1 - Math.exp(-t * 900)) * Math.exp(-t * 9);
    const v = Math.sin(p1) * 0.6 + Math.sin(p2) * 0.22 + Math.sin(p3) * 0.09;
    put(i0 + k, v * env * vel * 0.19, pan, 0.30);
  }
}
function pad(t0, t1, freqs, vel = 0.5) {
  const n = Math.round((t1 - t0) * SR), i0 = idx(t0);
  for (let v = 0; v < freqs.length; v++) {
    const f = freqs[v];
    for (let k = 0; k < n; k++) {
      const t = k / SR, T = (t1 - t0);
      const drift = 1 + 0.0016 * Math.sin(2 * Math.PI * 0.13 * t + v);
      const trem = 0.5 + 0.5 * Math.sin(2 * Math.PI * (0.09 + 0.02 * v) * t + v * 2.1);
      const env = Math.min(1, t / 0.6) * Math.min(1, Math.max(0, (T - t) / 0.8));
      const ph = 2 * Math.PI * f * drift * t;
      const val = (Math.sin(ph) + 0.32 * Math.sin(ph * 2.004) + 0.16 * Math.sin(ph * 3.01)) * env * (0.55 + 0.45 * trem);
      put(i0 + k, val * vel * 0.055, (v % 2 ? 0.42 : -0.42), 0.5);
    }
  }
}
function crash(t0, vel = 0.5) {
  const dur = 1.8, n = Math.round(dur * SR), i0 = idx(t0);
  let lp = 0;
  for (let k = 0; k < n; k++) {
    const t = k / SR;
    const w = rnd();
    lp += 0.35 * (w - lp);
    const hp = w - lp;
    const env = Math.exp(-t * 2.4) * (1 - Math.exp(-t * 320));
    put(i0 + k, hp * env * vel * 0.32, 0, 0.7);
  }
}
function riser(t0, t1, vel = 0.5) {
  const n = Math.round((t1 - t0) * SR), i0 = idx(t0);
  let lp = 0;
  for (let k = 0; k < n; k++) {
    const x = k / n, t = k / SR;
    const w = rnd();
    lp += (0.02 + 0.5 * x * x) * (w - lp);
    const env = Math.pow(x, 2.2) * (1 - Math.exp(-t * 30));
    put(i0 + k, lp * env * vel * 0.5, 0, 0.6);
  }
}

// ---------- arrangement: A minor i - VI - III - VII (Am F C G), 12 bars ----------
const CH = [
  { root: 55.00, notes: [220.00, 261.63, 329.63] },   // Am
  { root: 43.65, notes: [174.61, 220.00, 261.63] },   // F
  { root: 65.41, notes: [196.00, 261.63, 329.63] },   // C
  { root: 49.00, notes: [196.00, 246.94, 293.66] },   // G
];
const bar = (b) => CH[b % 4];
const at = (b, beat) => b * BAR + beat * BEAT;

// pads across the whole piece (12 bars); full from the first frame (no dead intro)
for (let b = 0; b < 12; b++) {
  const vel = b === 0 ? 0.42 : (b >= 10 ? 0.46 : 0.4);
  pad(at(b, 0), at(b, 4), bar(b).notes, vel);
}
// risers: a short build into the reveal (4 s) and a long one into the final act
riser(0.0, 2.0, 0.22);
riser(3.1, 4.0, 0.30);
riser(at(10, 0), at(11, 0), 0.42);
crash(0.0, 0.6);
crash(at(2, 0), 0.42);
crash(at(11, 0), 0.5);

// drums: four-on-the-floor from the first beat so the film opens with energy
for (let b = 0; b <= 10; b++) {
  for (let beat = 0; beat < 4; beat++) {
    kick(at(b, beat), b === 0 ? 0.92 : 1.0);
    hat(at(b, beat + 0.5), 0.30, false);
    if (beat === 1 || beat === 3) clap(at(b, beat), b < 2 ? 0.32 : 0.52);
    hat(at(b, beat + 0.25), 0.13, false);
    hat(at(b, beat + 0.75), 0.16, false);
  }
}
// final hit: bar 11 downbeat + tail
kick(at(11, 0), 1.0); clap(at(11, 0), 0.55);
for (let b = 10; b <= 11; b++) { hat(at(b, 2), 0.2, true); }

// bass: 8th-note pulse from bar 3, whole notes underneath from bar 0
for (let b = 0; b <= 9; b++) bass(at(b, 0), BAR - 0.02, bar(b).root, 0.75);
for (let b = 3; b <= 10; b++) {
  for (let e = 0; e < 8; e++) {
    if (e % 2 === 1) bass(at(b, e * 0.5), 0.20, bar(b).root * 2, 0.42);
  }
}
bass(at(11, 0), 1.6, CH[0].root, 0.85);

// pluck arp: 16ths, entering bar 2, accent pattern
const arpPat = [0, 2, 1, 2, 0, 2, 1, 2];
for (let b = 2; b <= 11; b++) {
  const ch = bar(b);
  for (let s = 0; s < 16; s++) {
    const t = at(b, s * 0.25);
    const note = ch.notes[arpPat[s % arpPat.length] % ch.notes.length] * (s >= 8 ? 2 : 1);
    pluck(t, note, s % 4 === 0 ? 0.85 : 0.45, s % 2 ? 0.34 : -0.34);
  }
}
// a couple of mint-bright bell accents for the "reveal" beats
pluck(at(6, 0), 1318.5, 0.5, 0.5);
pluck(at(10, 0), 1567.98, 0.5, -0.5);

// ---------- mix: reverb (Schroeder), gentle EQ tilt, soft limiter ----------
function reverb(src, combs, aps) {
  const out = new Float32Array(N);
  for (const [d, g] of combs) {
    const buf = new Float32Array(d); let p = 0, lp = 0;
    for (let i = 0; i < N; i++) {
      const y = buf[p];
      lp = 0.6 * lp + 0.4 * y;              // damping in the loop
      buf[p] = src[i] + lp * g;
      out[i] += y;
      p = (p + 1) % d;
    }
  }
  for (const [d, g] of aps) {
    const buf = new Float32Array(d); let p = 0;
    for (let i = 0; i < N; i++) {
      const b = buf[p], x = out[i];
      buf[p] = x + b * g;
      out[i] = b - x * g;
      p = (p + 1) % d;
    }
  }
  return out;
}
const wetL = reverb(SEND, [[1687, 0.79], [1601, 0.78], [2053, 0.77], [2251, 0.76]], [[347, 0.5], [113, 0.5]]);
const wetR = reverb(SEND, [[1693, 0.79], [1613, 0.78], [2059, 0.77], [2257, 0.76]], [[353, 0.5], [127, 0.5]]);

// one-pole tilt to shed harshest highs
let hL = 0, hR = 0;
const hpL = new Float32Array(N), hpR = new Float32Array(N);
for (let i = 0; i < N; i++) {
  hL += 0.28 * (L[i] - hL); hpL[i] = L[i] - hL;
  hR += 0.28 * (R[i] - hR); hpR[i] = R[i] - hR;
}
// master: fade in 0.35 s, fade out last 2.2 s, soft limit
let peak = 0;
for (let i = 0; i < N; i++) {
  const t = i / SR;
  const fin = Math.min(1, t / 0.35);
  const fout = t > DUR - 2.2 ? Math.max(0, (DUR - t) / 2.2) : 1;
  const g = fin * fout;
  let l = (L[i] + wetL[i] * 0.55) * 0.82 * g;
  let r = (R[i] + wetR[i] * 0.55) * 0.82 * g;
  l = Math.tanh(l * 1.25); r = Math.tanh(r * 1.25);
  L[i] = l; R[i] = r;
  peak = Math.max(peak, Math.abs(l), Math.abs(r));
}
const norm = peak > 0 ? 0.89 / peak : 1;   // -1 dBFS ceiling-ish
for (let i = 0; i < N; i++) { L[i] *= norm; R[i] *= norm; }

// ---------- write 16-bit stereo WAV ----------
const bytes = N * 4;
const buf = Buffer.alloc(44 + bytes);
buf.write("RIFF", 0); buf.writeUInt32LE(36 + bytes, 4); buf.write("WAVE", 8);
buf.write("fmt ", 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20);
buf.writeUInt16LE(2, 22); buf.writeUInt32LE(SR, 24); buf.writeUInt32LE(SR * 4, 28);
buf.writeUInt16LE(4, 32); buf.writeUInt16LE(16, 34);
buf.write("data", 36); buf.writeUInt32LE(bytes, 40);
let o = 44;
for (let i = 0; i < N; i++) {
  buf.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(L[i] * 32767))), o); o += 2;
  buf.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(R[i] * 32767))), o); o += 2;
}
const out = process.argv[2] || "music-bed-24s.wav";
fs.writeFileSync(out, buf);
console.log(JSON.stringify({ out, seconds: +(N / SR).toFixed(3), sampleRate: SR, channels: 2, bits: 16, bytes: buf.length, peakBeforeNorm: +peak.toFixed(4) }));
