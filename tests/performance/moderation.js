import encoding from 'k6/encoding';
import exec from 'k6/execution';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://gateway:8000';
const workload = __ENV.WORKLOAD || 'mixed';
const targetRate = Number(__ENV.RATE || 10);
const duration = __ENV.DURATION || '2m';
const preAllocatedVUs = Number(__ENV.PRE_ALLOCATED_VUS || 50);
const maxVUs = Number(__ENV.MAX_VUS || 100);
const thinkTimeSeconds = Number(__ENV.THINK_TIME_SECONDS || 0);
const stampedeWindowMs = Math.max(1, Number(__ENV.STAMPEDE_WINDOW_MS || 1000));

const textImagePng = encoding.b64decode(open('./text-image.png.b64').trim(), 'std');

const moderationLatency = new Trend('moderation_latency', true);
const moderationFailures = new Rate('moderation_failures');
const unknownDecisions = new Counter('unknown_decisions');

const scenarios = workload === 'seed-reference'
  ? {
    moderation: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '1m',
    },
  }
  : {
    moderation: {
      executor: 'constant-arrival-rate',
      rate: targetRate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs,
      maxVUs,
    },
  };

export const options = {
  scenarios,
  thresholds: {
    moderation_failures: ['rate<0.01'],
    moderation_latency: [__ENV.P95_THRESHOLD || 'p(95)<5000'],
    dropped_iterations: ['count==0'],
  },
  noConnectionReuse: false,
  userAgent: 'moderation-performance-harness/1',
};

function requestKind(iteration) {
  if (workload !== 'mixed') {
    return workload;
  }
  const selector = iteration % 10;
  if (selector < 4) return 'unique-text';
  if (selector < 7) return 'repeated-text';
  if (selector < 9) return 'stampede-text';
  return 'repeated-image';
}

function crc32(bytes) {
  let crc = 0xffffffff;
  for (const value of bytes) {
    crc ^= value;
    for (let bit = 0; bit < 8; bit += 1) {
      crc = (crc >>> 1) ^ ((crc & 1) ? 0xedb88320 : 0);
    }
  }
  return (crc ^ 0xffffffff) >>> 0;
}

function ascii(value) {
  return Uint8Array.from(Array.from(value, (character) => character.charCodeAt(0)));
}

function writeUint32(bytes, offset, value) {
  bytes[offset] = (value >>> 24) & 0xff;
  bytes[offset + 1] = (value >>> 16) & 0xff;
  bytes[offset + 2] = (value >>> 8) & 0xff;
  bytes[offset + 3] = value & 0xff;
}

function pngTextChunk(value) {
  const kind = ascii('tEXt');
  const data = ascii(`load-id\u0000${value}`);
  const chunk = new Uint8Array(12 + data.length);
  writeUint32(chunk, 0, data.length);
  chunk.set(kind, 4);
  chunk.set(data, 8);
  const protectedBytes = new Uint8Array(kind.length + data.length);
  protectedBytes.set(kind, 0);
  protectedBytes.set(data, kind.length);
  writeUint32(chunk, 8 + data.length, crc32(protectedBytes));
  return chunk;
}

function uniquelyTaggedPng(value) {
  const original = new Uint8Array(textImagePng);
  const iendLength = 12;
  const metadata = pngTextChunk(value);
  const tagged = new Uint8Array(original.length + metadata.length);
  tagged.set(original.subarray(0, original.length - iendLength), 0);
  tagged.set(metadata, original.length - iendLength);
  tagged.set(
    original.subarray(original.length - iendLength),
    original.length - iendLength + metadata.length,
  );
  return tagged.buffer;
}

function payload(kind, iteration) {
  const contentId = kind === 'seed-reference'
    ? 'perf-seed-reference'
    : `perf-${kind}-${__VU}-${iteration}-${Date.now()}`;
  if (kind === 'unique-text') {
    return {
      contentId,
      contentType: 'POST',
      text: `Unique benchmark market commentary ${__VU}-${iteration}-${Date.now()}`,
    };
  }
  if (['repeated-image', 'unique-image', 'candidate-image', 'seed-reference'].includes(kind)) {
    const imageBytes = kind === 'unique-image' || kind === 'candidate-image'
      ? uniquelyTaggedPng(contentId)
      : textImagePng;
    return {
      contentId,
      contentType: 'POST',
      text: kind === 'unique-image'
        ? `Unique image benchmark ${contentId}`
        : kind === 'candidate-image'
          ? 'Seeded candidate adjudication benchmark'
          : 'Repeated text-bearing image benchmark',
      image: http.file(imageBytes, `${contentId}.png`, 'image/png'),
    };
  }
  return {
    contentId,
    contentType: 'POST',
    text: kind === 'stampede-text'
      ? `Concurrent cold-key benchmark ${Math.floor(Date.now() / stampedeWindowMs)}`
      : 'Repeated warm-cache benchmark',
  };
}

function textMultipart(fields, iteration) {
  const boundary = `moderation-k6-${__VU}-${iteration}-${Date.now()}`;
  const parts = [];
  for (const [name, value] of Object.entries(fields)) {
    parts.push(
      `--${boundary}\r\n`
      + `Content-Disposition: form-data; name="${name}"\r\n\r\n`
      + `${value}\r\n`,
    );
  }
  parts.push(`--${boundary}--\r\n`);
  return {
    body: parts.join(''),
    headers: { 'Content-Type': `multipart/form-data; boundary=${boundary}` },
  };
}

export default function () {
  const iteration = exec.scenario.iterationInTest;
  const kind = requestKind(iteration);
  const requestPayload = payload(kind, iteration);
  const textRequest = requestPayload.image === undefined
    ? textMultipart(requestPayload, iteration)
    : null;
  const response = http.post(
    `${baseUrl}/v1/moderate`,
    textRequest === null ? requestPayload : textRequest.body,
    {
      headers: textRequest === null ? undefined : textRequest.headers,
      tags: { workload: kind },
      timeout: __ENV.REQUEST_TIMEOUT || '75s',
    },
  );
  moderationLatency.add(response.timings.duration, { workload: kind });
  const ok = check(response, {
    'HTTP 200': (value) => value.status === 200,
    'valid decision': (value) => {
      try {
        return ['ALLOW', 'BLOCK', 'UNKNOWN'].includes(value.json('decision'));
      } catch (_) {
        return false;
      }
    },
  });
  moderationFailures.add(!ok, { workload: kind });
  if (ok && response.json('decision') === 'UNKNOWN') {
    unknownDecisions.add(1, { workload: kind });
  }
  if (thinkTimeSeconds > 0) {
    sleep(thinkTimeSeconds);
  }
}
