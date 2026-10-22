/**
 * 录音模式让用户照着读的固定文本。
 *
 * 这样做是为了从结构上消除"样例文字和录音对不上"的问题：文字已知，
 * 用户只负责读，不需要事后打字，也不用担心漏字。
 * 选词时避开数字、英文缩写和生僻字，避免文本规范化改变 token。
 */
export const RECORDING_SCRIPTS = [
  {
    id: "zh-daily",
    language: "zh",
    label: "日常",
    text: "今天的天气很好，我打算去公园走一走。路上会经过一家小店，那里卖的豆浆特别香，每次路过都想停下脚步。",
  },
  {
    id: "zh-story",
    language: "zh",
    label: "叙事",
    text: "这几只小鸟在窗台上停留了很久，它们时而低头啄食，时而抬头张望。我没有惊动它们，只是安静地看着，直到阳光慢慢移开。",
  },
  {
    id: "en-short",
    language: "en",
    label: "English",
    text: "Good morning. I am testing a new voice for my audio player. Please read this sentence exactly as it is written, without changing any words.",
  },
  {
    id: "en-long",
    language: "en",
    label: "English · 稍长",
    text: "The train leaves at half past eight, so we should have breakfast before we go. Do not forget the tickets that are lying on the table near the door.",
  },
];

export function scriptsFor(language) {
  const matched = RECORDING_SCRIPTS.filter((script) => script.language === language);
  return matched.length ? matched : RECORDING_SCRIPTS;
}
