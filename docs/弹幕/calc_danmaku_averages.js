#!/usr/bin/env node

// 运行命令：node docs/弹幕/calc_danmaku_averages.js docs/弹幕/优化前的.txt
// 指定只统计前 N 条匹配日志：node docs/弹幕/calc_danmaku_averages.js docs/弹幕/优化前的.txt 100

const fs = require('fs');
const path = require('path');

const inputPath = process.argv[2];
const maxLogsArg = process.argv[3];

if (!inputPath) {
  console.error('Usage: node docs/弹幕/calc_danmaku_averages.js <log-file> [max-logs]');
  process.exit(1);
}

const maxLogs = parseMaxLogs(maxLogsArg);

const resolvedPath = path.resolve(process.cwd(), inputPath);

let content;
try {
  content = fs.readFileSync(resolvedPath, 'utf8');
} catch (error) {
  console.error(`Failed to read file: ${resolvedPath}`);
  console.error(error.message);
  process.exit(1);
}

const pattern = /\[(Draw|Action)\]\s+fps=(\d+(?:\.\d+)?)\s+frames=(\d+)\s+dropped=(\d+)/;
const memoryPattern = /mem=heap=(\d+(?:\.\d+)?)\/(\d+(?:\.\d+)?)MB\s+native=(\d+(?:\.\d+)?)MB/;
const cacheQPattern = /cacheQ=(\d+)/;
const actMsPattern = /actMs\(avg\/p50\/p95\/max\)=(\d+(?:\.\d+)?)\/(\d+(?:\.\d+)?)\/(\d+(?:\.\d+)?)\/(\d+(?:\.\d+)?)/;
const snapPattern = /sameSnap=(\d+)\s+maxSameSnapStreak=(\d+)\s+snapAgeMs\(avg\/max\)=(\d+(?:\.\d+)?)\/(\d+(?:\.\d+)?)/;
const stats = {
  Draw: createBucket(),
  Action: createBucket(),
};
const memoryStats = createMemoryBucket();
const cacheQStats = { count: 0, total: 0, min: Infinity, max: -Infinity };
let matchedLogs = 0;

for (const line of content.split(/\r?\n/)) {
  const match = line.match(pattern);
  if (!match) {
    continue;
  }
  if (maxLogs !== null && matchedLogs >= maxLogs) {
    break;
  }

  matchedLogs += 1;

  const memoryMatch = line.match(memoryPattern);
  if (memoryMatch) {
    const [, heapUsed, heapMax, nativeUsed] = memoryMatch;
    memoryStats.count += 1;
    memoryStats.heapUsed += Number.parseFloat(heapUsed);
    memoryStats.heapMax += Number.parseFloat(heapMax);
    memoryStats.nativeUsed += Number.parseFloat(nativeUsed);
  }

  const cacheQMatch = line.match(cacheQPattern);
  if (cacheQMatch) {
    const val = Number.parseInt(cacheQMatch[1], 10);
    cacheQStats.count += 1;
    cacheQStats.total += val;
    if (val < cacheQStats.min) cacheQStats.min = val;
    if (val > cacheQStats.max) cacheQStats.max = val;
  }

  const [, type, fps, frames, dropped] = match;
  const bucket = stats[type];
  bucket.count += 1;
  bucket.fps += Number.parseFloat(fps);
  bucket.frames += Number.parseInt(frames, 10);
  bucket.dropped += Number.parseInt(dropped, 10);

  if (type === 'Action') {
    const actMsMatch = line.match(actMsPattern);
    if (actMsMatch) {
      bucket.actMsCount += 1;
      bucket.actMsAvg += Number.parseFloat(actMsMatch[1]);
      bucket.actMsP50 += Number.parseFloat(actMsMatch[2]);
      bucket.actMsP95 += Number.parseFloat(actMsMatch[3]);
      bucket.actMsMax += Number.parseFloat(actMsMatch[4]);
    }
  }

  if (type === 'Draw') {
    const snapMatch = line.match(snapPattern);
    if (snapMatch) {
      const sameSnap = Number.parseInt(snapMatch[1], 10);
      const maxSameSnapStreak = Number.parseInt(snapMatch[2], 10);
      bucket.snapCount += 1;
      bucket.sameSnap += sameSnap;
      bucket.maxSameSnapStreak += maxSameSnapStreak;
      if (sameSnap > 0) bucket.sameSnapPositiveCount += 1;
      if (maxSameSnapStreak > bucket.maxMaxSameSnapStreak) bucket.maxMaxSameSnapStreak = maxSameSnapStreak;
      bucket.snapAgeAvg += Number.parseFloat(snapMatch[3]);
      bucket.snapAgeMax += Number.parseFloat(snapMatch[4]);
    }
  }
}

printStats('Draw', stats.Draw);
printStats('Action', stats.Action);
printMemoryStats(memoryStats);
printCacheQStats(cacheQStats);

function parseMaxLogs(value) {
  if (value == null) {
    return null;
  }

  if (!/^\d+$/.test(value)) {
    console.error('max-logs must be a positive integer');
    process.exit(1);
  }

  const parsed = Number.parseInt(value, 10);
  if (parsed <= 0) {
    console.error('max-logs must be greater than 0');
    process.exit(1);
  }

  return parsed;
}

function createBucket() {
  return {
    count: 0,
    fps: 0,
    frames: 0,
    dropped: 0,
    actMsCount: 0,
    actMsAvg: 0,
    actMsP50: 0,
    actMsP95: 0,
    actMsMax: 0,
    snapCount: 0,
    sameSnap: 0,
    maxSameSnapStreak: 0,
    maxMaxSameSnapStreak: 0,
    sameSnapPositiveCount: 0,
    snapAgeAvg: 0,
    snapAgeMax: 0,
  };
}

function createMemoryBucket() {
  return {
    count: 0,
    heapUsed: 0,
    heapMax: 0,
    nativeUsed: 0,
  };
}

function average(total, count) {
  if (count === 0) {
    return 'N/A';
  }

  return (total / count).toFixed(2);
}

function printStats(type, bucket) {
  console.log(`${type}:`);

  if (bucket.count === 0) {
    console.log('  no matched lines');
    return;
  }

  console.log(`  samples: ${bucket.count}`);
  console.log(`  avg fps: ${average(bucket.fps, bucket.count)}`);
  console.log(`  avg frames: ${average(bucket.frames, bucket.count)}`);
  console.log(`  avg dropped: ${average(bucket.dropped, bucket.count)}`);

  if (bucket.actMsCount > 0) {
    console.log(`  avg actMs(avg): ${average(bucket.actMsAvg, bucket.actMsCount)}ms`);
    console.log(`  avg actMs(p50): ${average(bucket.actMsP50, bucket.actMsCount)}ms`);
    console.log(`  avg actMs(p95): ${average(bucket.actMsP95, bucket.actMsCount)}ms`);
    console.log(`  avg actMs(max): ${average(bucket.actMsMax, bucket.actMsCount)}ms`);
  }

  if (bucket.snapCount > 0) {
    console.log(`  avg sameSnap: ${average(bucket.sameSnap, bucket.snapCount)}`);
    console.log(`  avg maxSameSnapStreak: ${average(bucket.maxSameSnapStreak, bucket.snapCount)}`);
    console.log(`  max maxSameSnapStreak: ${bucket.maxMaxSameSnapStreak}`);
    console.log(`  records with sameSnap>0: ${bucket.sameSnapPositiveCount}`);
    console.log(`  avg snapAgeMs(avg): ${average(bucket.snapAgeAvg, bucket.snapCount)}ms`);
    console.log(`  avg snapAgeMs(max): ${average(bucket.snapAgeMax, bucket.snapCount)}ms`);
  }
}

function printCacheQStats(bucket) {
  console.log('CacheQ:');

  if (bucket.count === 0) {
    console.log('  no matched lines');
    return;
  }

  console.log(`  samples: ${bucket.count}`);
  console.log(`  avg cacheQ: ${average(bucket.total, bucket.count)}`);
  console.log(`  min cacheQ: ${bucket.min}`);
  console.log(`  max cacheQ: ${bucket.max}`);
}

function printMemoryStats(bucket) {
  console.log('Memory:');

  if (bucket.count === 0) {
    console.log('  no matched lines');
    return;
  }

  console.log(`  samples: ${bucket.count}`);
  console.log(`  avg heap used: ${average(bucket.heapUsed, bucket.count)}MB`);
  console.log(`  avg heap max: ${average(bucket.heapMax, bucket.count)}MB`);
  console.log(`  avg native used: ${average(bucket.nativeUsed, bucket.count)}MB`);
}