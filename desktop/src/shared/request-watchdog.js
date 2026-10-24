/** 请求的空闲看门狗：进展会续期，旧请求的计时器与收尾不能影响新请求。 */
export function createRequestWatchdog({ timeoutMs = 150000, setTimer = setTimeout, clearTimer = clearTimeout } = {}) {
  let current = null;
  function arm(request) {
    if (request.timer) clearTimer(request.timer.handle);
    const timer = {};
    request.timer = timer;
    timer.handle = setTimer(() => {
      if (current !== request || request.timer !== timer) return;
      current = null;
      request.timer = null;
      request.onTimeout(request.id);
    }, timeoutMs);
  }
  function isCurrent(id) { return current !== null && current.id === id; }
  function finish(id) {
    if (!isCurrent(id)) return false;
    clearTimer(current.timer?.handle);
    current = null;
    return true;
  }
  return {
    begin(id, onTimeout) {
      if (current) finish(current.id);
      current = { id, onTimeout, timer: null };
      arm(current);
    },
    touch(id) {
      if (!isCurrent(id)) return false;
      arm(current);
      return true;
    },
    isCurrent,
    finish,
    dispose() { if (current) finish(current.id); },
  };
}
