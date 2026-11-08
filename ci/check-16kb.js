#!/usr/bin/env node
// Verifies that the arm64 native libraries are built for 16 KB page devices
// (Android 15+ can run on 16 KB page kernels, and Play requires it for apps
// targeting SDK 35+). The ELF PT_LOAD alignment must be at least 0x4000.
const fs = require('fs');
const path = require('path');

const root = process.argv[2] || 'core/build/intermediates/cxx';

function walk(dir, out) {
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch (e) {
    return out;
  }
  for (const e of entries) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.isFile() && e.name === 'libZeroTierOneJNI.so') out.push(p);
  }
  return out;
}

const files = walk(root, []).filter((f) => f.includes('arm64-v8a'));
if (files.length === 0) {
  console.error(`16 KB check: no arm64 libZeroTierOneJNI.so found under ${root}`);
  process.exit(1);
}

let failed = false;
for (const file of files) {
  const b = fs.readFileSync(file);
  const isElf = b[0] === 0x7f && b[1] === 0x45 && b[2] === 0x4c && b[3] === 0x46;
  const is64 = b[4] === 2;
  if (!isElf || !is64) {
    console.error(`16 KB check: ${file} is not a 64-bit ELF`);
    failed = true;
    continue;
  }
  const le = b[5] === 1;
  const u16 = (o) => (le ? b.readUInt16LE(o) : b.readUInt16BE(o));
  const u32 = (o) => (le ? b.readUInt32LE(o) : b.readUInt32BE(o));
  const u64 = (o) => Number(le ? b.readBigUInt64LE(o) : b.readBigUInt64BE(o));
  const phoff = u64(0x20);
  const phentsize = u16(0x36);
  const phnum = u16(0x38);
  let minAlign = Infinity;
  for (let i = 0; i < phnum; i++) {
    const off = phoff + i * phentsize;
    if (u32(off) !== 1) continue; // PT_LOAD
    minAlign = Math.min(minAlign, u64(off + 0x30));
  }
  const ok = minAlign >= 0x4000;
  console.log(`16 KB check: ${file} PT_LOAD align=0x${minAlign.toString(16)} ${ok ? 'ok' : 'INSUFFICIENT'}`);
  if (!ok) failed = true;
}
process.exit(failed ? 1 : 0);
