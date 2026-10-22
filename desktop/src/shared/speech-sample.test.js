import test from "node:test";
import assert from "node:assert/strict";
import {
  MAX_SECONDS, MIN_SECONDS, TARGET_RATE, downmix, durationHint, encodeWav16,
  resample, sampleFromAudioBuffer, secondsOf, trimSilence,
} from "./speech-sample.js";

function buffer(channels, rate, seconds, tone = 0.2) {
  const data = [];
  for (let channel = 0; channel < channels; channel += 1) {
    const samples = new Float32Array(Math.round(rate * seconds));
    for (let index = 0; index < samples.length; index += 1) {
      const audible = index > rate * 0.4 && index < samples.length - rate * 0.4;
      samples[index] = audible ? (index % 80 < 40 ? tone : -tone) : 0;
    }
    data.push(samples);
  }
  return { numberOfChannels: channels, length: data[0].length, sampleRate: rate, getChannelData: (index) => data[index] };
}

test("downmix averages channels instead of picking the first one", () => {
  const source = { numberOfChannels: 2, length: 2, sampleRate: 8000, getChannelData: (index) => (index === 0 ? Float32Array.from([1, 1]) : Float32Array.from([0, -1])) };
  assert.deepEqual(Array.from(downmix(source)), [0.5, 0]);
});

test("resample halves the sample count when the rate halves", () => {
  const samples = new Float32Array(32000).fill(0.5);
  assert.equal(resample(samples, 32000, 16000).length, 16000);
  assert.equal(secondsOf(resample(samples, 32000, 16000)), 1);
});

test("trimSilence drops the silent head and tail but keeps the middle pause", () => {
  const samples = new Float32Array(16000 * 5);
  for (let index = 16000; index < 16000 * 4; index += 1) samples[index] = index % 40 < 20 ? 0.3 : -0.3;
  const trimmed = trimSilence(samples, 16000);
  assert.ok(secondsOf(trimmed) < 3.6, `kept ${secondsOf(trimmed)}s`);
  assert.ok(secondsOf(trimmed) > 2.8, `kept ${secondsOf(trimmed)}s`);
});

test("encodeWav16 writes a mono 16-bit PCM header a decoder can trust", () => {
  const view = new DataView(encodeWav16(new Float32Array(16000), 16000));
  const tag = (offset) => String.fromCharCode(view.getUint8(offset), view.getUint8(offset + 1), view.getUint8(offset + 2), view.getUint8(offset + 3));
  assert.equal(tag(0), "RIFF");
  assert.equal(tag(8), "WAVE");
  assert.equal(tag(12), "fmt ");
  assert.equal(tag(36), "data");
  assert.equal(view.getUint16(20, true), 1);
  assert.equal(view.getUint16(22, true), 1);
  assert.equal(view.getUint32(24, true), TARGET_RATE);
  assert.equal(view.getUint16(34, true), 16);
  assert.equal(view.getUint32(40, true), 32000);
  assert.equal(view.byteLength, 44 + 32000);
});

test("a stereo 44.1 kHz recording becomes a 16 kHz mono sample", () => {
  const result = sampleFromAudioBuffer(buffer(2, 44100, 5));
  const view = new DataView(result.wav);
  assert.equal(view.getUint32(24, true), TARGET_RATE);
  assert.equal(view.getUint16(22, true), 1);
  assert.ok(result.seconds > 3 && result.seconds < 4.6, `got ${result.seconds}s`);
});

test("durationHint reports the same bounds the server enforces", () => {
  assert.match(durationHint(1), /太短/);
  assert.match(durationHint(MAX_SECONDS + 5), /太长/);
  assert.match(durationHint(10), /可以录入/);
  assert.match(durationHint(0), /没有可用/);
  assert.equal(MIN_SECONDS, 3);
});
