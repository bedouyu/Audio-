import test from "node:test";
import assert from "node:assert/strict";
import { createRequestWatchdog } from "./request-watchdog.js";

function clock() {
  let now = 0, nextId = 0;
  const timers = new Map(), callbacks = new Map();
  return {
    setTimer(callback, delay) {
      const id = ++nextId;
      timers.set(id, { at: now + delay, callback }); callbacks.set(id, callback);
      return id;
    },
    clearTimer(id) { timers.delete(id); },
    advance(ms) {
      const until = now + ms;
      while (true) {
        const first = [...timers.entries()].sort((a, b) => a[1].at - b[1].at)[0];
        if (!first || first[1].at > until) break;
        timers.delete(first[0]); now = first[1].at; first[1].callback();
      }
      now = until;
    },
    callbacks,
  };
}

test("continued tool progress keeps a long request active beyond the old whole-request deadline", () => {
  const time = clock(), expired = [];
  const watchdog = createRequestWatchdog({ ...time, timeoutMs: 150000 });
  watchdog.begin("long-edit", id => expired.push(id));
  for (let i = 0; i < 36; i++) {
    time.advance(10000);
    assert.equal(watchdog.touch("long-edit"), true, "each tool heartbeat renews the idle deadline");
  }
  assert.equal(watchdog.isCurrent("long-edit"), true);
  assert.deepEqual(expired, []);
  assert.equal(watchdog.finish("long-edit"), true);
  time.advance(150000);
  assert.deepEqual(expired, []);
});

test("only an uninterrupted idle interval expires a request, and it expires once", () => {
  const time = clock(), expired = [];
  const watchdog = createRequestWatchdog({ ...time, timeoutMs: 150000 });
  watchdog.begin("quiet", id => expired.push(id));
  time.advance(149999);
  assert.deepEqual(expired, []);
  watchdog.touch("quiet");
  time.advance(149999);
  assert.deepEqual(expired, []);
  time.advance(1);
  assert.deepEqual(expired, ["quiet"]);
  assert.equal(watchdog.isCurrent("quiet"), false);
  time.advance(300000);
  assert.deepEqual(expired, ["quiet"]);
});

test("old watchdog callbacks, events and finally cannot cancel or clear a newer request", () => {
  const time = clock(), expired = [];
  const watchdog = createRequestWatchdog({ ...time, timeoutMs: 150000 });
  watchdog.begin("old", id => expired.push(id));
  const oldCallback = time.callbacks.get(1);
  time.advance(150000);
  assert.deepEqual(expired, ["old"]);
  watchdog.begin("new", id => expired.push(id));
  oldCallback();
  assert.equal(watchdog.touch("old"), false);
  assert.equal(watchdog.finish("old"), false, "late old finally must leave the new request active");
  assert.equal(watchdog.isCurrent("new"), true);
  time.advance(149999);
  assert.deepEqual(expired, ["old"]);
  watchdog.dispose();
  time.advance(1);
  assert.deepEqual(expired, ["old"]);
});
