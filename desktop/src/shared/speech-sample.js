/**
 * 把麦克风录音或音频文件统一成 CosyVoice 需要的样例格式：
 * 16 kHz、单声道、16 位 PCM WAV，并裁掉首尾静音。
 * 纯函数，便于在 node --test 中验证。
 */
export const TARGET_RATE = 16000;
export const MIN_SECONDS = 3;
export const MAX_SECONDS = 30;
// 与后端 SpeechTiming 保持一致：中文按字、英文按词，实测中文约 193 字/分钟。
export const CJK_PER_MINUTE = 195;
export const WORDS_PER_MINUTE = 150;

/** 估算一段文字读出来需要多久，用来判断录音是否大致读完了这段话。 */
export function expectedSeconds(text) {
  const value = String(text || "");
  const cjk = (value.match(/[\u3400-\u9fff]/g) || []).length;
  const words = (value.replace(/[\u3400-\u9fff]/g, " ").match(/[A-Za-z0-9']+/g) || []).length;
  return (cjk / CJK_PER_MINUTE) * 60 + (words / WORDS_PER_MINUTE) * 60;
}

/**
 * 录音是否覆盖了给定文字。明显偏短通常意味着没读完，这样的样例会让音色走样；
 * 放宽到 0.55 是为了容忍语速快的人，低于此值就要求重录。
 */
export function readingVerdict(actualSeconds, expected) {
  if (!expected || !Number.isFinite(actualSeconds)) return "ok";
  const ratio = actualSeconds / expected;
  if (ratio < 0.55) return "short";
  if (ratio > 2.5) return "long";
  return "ok";
}


export function downmix(buffer) {
  const channels = buffer.numberOfChannels;
  const length = buffer.length;
  if (channels === 1) return Float32Array.from(buffer.getChannelData(0));
  const mixed = new Float32Array(length);
  for (let channel = 0; channel < channels; channel += 1) {
    const data = buffer.getChannelData(channel);
    for (let index = 0; index < length; index += 1) mixed[index] += data[index] / channels;
  }
  return mixed;
}

/** 线性重采样；对语音样例足够，且不引入额外依赖。 */
export function resample(samples, fromRate, toRate = TARGET_RATE) {
  if (!samples.length || fromRate === toRate) return samples;
  const ratio = fromRate / toRate;
  const length = Math.max(1, Math.round(samples.length / ratio));
  const output = new Float32Array(length);
  for (let index = 0; index < length; index += 1) {
    const position = index * ratio;
    const base = Math.floor(position);
    const next = Math.min(samples.length - 1, base + 1);
    const fraction = position - base;
    output[index] = samples[base] * (1 - fraction) + samples[next] * fraction;
  }
  return output;
}

/** 按窗口去掉首尾静音，中间停顿保留；整段静音时原样返回，交给时长校验报错。 */
export function trimSilence(samples, rate = TARGET_RATE, windowMs = 50, threshold = 0.012) {
  const step = Math.max(1, Math.round((rate * windowMs) / 1000));
  const voiced = [];
  for (let start = 0; start < samples.length; start += step) {
    const end = Math.min(samples.length, start + step);
    let energy = 0;
    for (let index = start; index < end; index += 1) energy += samples[index] * samples[index];
    if (Math.sqrt(energy / Math.max(1, end - start)) >= threshold) voiced.push(start / step);
  }
  if (!voiced.length) return samples;
  const start = Math.max(0, Math.floor(voiced[0]) - 2) * step;
  const end = Math.min(samples.length, (Math.floor(voiced[voiced.length - 1]) + 3) * step);
  return samples.slice(start, end);
}

export function encodeWav16(samples, rate = TARGET_RATE) {
  const buffer = new ArrayBuffer(44 + samples.length * 2);
  const view = new DataView(buffer);
  const text = (offset, value) => {
    for (let index = 0; index < value.length; index += 1) view.setUint8(offset + index, value.charCodeAt(index));
  };
  text(0, "RIFF");
  view.setUint32(4, 36 + samples.length * 2, true);
  text(8, "WAVE");
  text(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true);
  view.setUint16(22, 1, true);
  view.setUint32(24, rate, true);
  view.setUint32(28, rate * 2, true);
  view.setUint16(32, 2, true);
  view.setUint16(34, 16, true);
  text(36, "data");
  view.setUint32(40, samples.length * 2, true);
  let offset = 44;
  for (let index = 0; index < samples.length; index += 1, offset += 2) {
    const value = Math.max(-1, Math.min(1, samples[index]));
    view.setInt16(offset, value < 0 ? value * 0x8000 : value * 0x7fff, true);
  }
  return buffer;
}

export function toBase64(arrayBuffer) {
  const bytes = new Uint8Array(arrayBuffer);
  let binary = "";
  const chunk = 0x8000;
  for (let index = 0; index < bytes.length; index += chunk) {
    binary += String.fromCharCode(...bytes.subarray(index, index + chunk));
  }
  return btoa(binary);
}

export function secondsOf(samples, rate = TARGET_RATE) {
  return samples.length / rate;
}

/** 完整管线：已解码的 AudioBuffer → base64 WAV 与时长。 */
export function sampleFromAudioBuffer(buffer, { rate = TARGET_RATE, trim = true } = {}) {
  let samples = downmix(buffer);
  samples = resample(samples, buffer.sampleRate, rate);
  if (trim) samples = trimSilence(samples, rate);
  return { wav: encodeWav16(samples, rate), seconds: secondsOf(samples, rate) };
}

/** 时长是否在可录入范围内，供界面直接提示。 */
export function durationHint(seconds) {
  if (!Number.isFinite(seconds) || seconds <= 0) return "还没有可用的样例音频";
  const value = seconds.toFixed(1);
  if (seconds < MIN_SECONDS) return `样例太短（${value} 秒），至少需要 ${MIN_SECONDS} 秒`;
  if (seconds > MAX_SECONDS) return `样例太长（${value} 秒），最多 ${MAX_SECONDS} 秒`;
  return `样例时长 ${value} 秒，可以录入`;
}
