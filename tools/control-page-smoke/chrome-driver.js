'use strict';

/**
 * Minimal, zero-dependency Chrome DevTools Protocol driver.
 *
 * Uses Node 22's built-in `WebSocket` global (undici) plus `child_process`/`fs`/`net` from the
 * standard library. No npm packages. Talks to headless Chrome over one flattened-session
 * WebSocket connection (Target.attachToTarget with flatten:true), opening one CDP "target" (tab)
 * per scenario so scenarios never share page-global state.
 */

const { spawn } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

// ---- finding a Chrome/Chromium binary ---------------------------------------------------

function which(name) {
  const dirs = (process.env.PATH || '').split(path.delimiter);
  for (const dir of dirs) {
    const candidate = path.join(dir, name);
    if (isExecutableFile(candidate)) return candidate;
  }
  return null;
}

function isExecutableFile(p) {
  try {
    const st = fs.statSync(p);
    if (!st.isFile()) return false;
    fs.accessSync(p, fs.constants.X_OK);
    return true;
  } catch (_) {
    return false;
  }
}

/**
 * Looks for a Chrome/Chromium binary in, in order:
 *   1. $CONTROL_SMOKE_CHROME (explicit override)
 *   2. common binary names on $PATH
 *   3. well-known dev-tool download caches (Playwright, Puppeteer, agent browser tooling),
 *      whose install directory names embed a version number we don't want to hardcode
 *   4. a handful of common absolute install locations
 * Returns the first hit, or null if nothing was found.
 */
function findChrome() {
  const candidates = [];

  if (process.env.CONTROL_SMOKE_CHROME) {
    candidates.push(process.env.CONTROL_SMOKE_CHROME);
  }

  for (const name of ['google-chrome-stable', 'google-chrome', 'chromium-browser', 'chromium', 'chrome']) {
    const found = which(name);
    if (found) candidates.push(found);
  }

  const home = os.homedir();
  const cacheRoots = [
    { dir: path.join(home, '.cache/ms-playwright'), match: /^chromium-/, suffix: 'chrome-linux/chrome' },
    { dir: path.join(home, '.cache/puppeteer/chrome'), match: /^linux-/, suffix: 'chrome-linux64/chrome' },
    {
      dir: path.join(home, '.cache/puppeteer/chrome-headless-shell'),
      match: /^linux-/,
      suffix: 'chrome-headless-shell-linux64/chrome-headless-shell'
    },
    { dir: path.join(home, '.agent-browser/browsers'), match: /^chrome-/, suffix: 'chrome' }
  ];
  for (const { dir, match, suffix } of cacheRoots) {
    let entries;
    try {
      entries = fs.readdirSync(dir);
    } catch (_) {
      continue;
    }
    for (const entry of entries.filter((e) => match.test(e))) {
      candidates.push(path.join(dir, entry, suffix));
    }
  }

  candidates.push(
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    '/opt/google/chrome/chrome'
  );

  for (const c of candidates) {
    if (c && isExecutableFile(c)) return c;
  }
  return null;
}

// ---- browser lifecycle + CDP session -----------------------------------------------------

/**
 * Signals `child`'s whole process group, not just the single tracked pid. Chrome (launched below
 * with `detached: true`, making it its own process-group leader) forks a zygote plus renderer/
 * GPU/utility child processes that are children of *Chrome*, not of this Node process — Node's
 * `child.kill()` only ever reaches the one pid it tracks. Those grandchildren inherit Chrome's
 * new process group, so `process.kill(-child.pid, signal)` reaches the whole tree in one signal,
 * instead of relying on Chrome's own (sometimes-not-instant) internal shutdown of its children
 * after the top-level process is told to die — which otherwise leaves a real race where a
 * grandchild is still writing into `user-data-dir` after `killAndWait()` has already resolved.
 */
function signalGroup(child, signal) {
  if (!child.pid) return;
  try {
    process.kill(-child.pid, signal);
  } catch (_) {
    // Group already gone (everyone exited), or this platform doesn't support negative-pid
    // group signalling -- fall back to signalling just the tracked pid.
    try {
      child.kill(signal);
    } catch (_) {
      // Nothing left to signal.
    }
  }
}

/**
 * Terminates `child`'s whole process group and waits for the tracked pid to actually exit:
 * SIGTERM first, escalating to SIGKILL if it hasn't exited within `timeoutMs`. Resolves once the
 * process is confirmed gone (or, as an absolute last resort if even SIGKILL somehow doesn't land
 * within a further grace window, resolves anyway rather than hanging the caller forever). Safe to
 * call on an already-exited child.
 */
function killAndWait(child, timeoutMs = 2000) {
  return new Promise((resolve) => {
    if (child.exitCode !== null || child.signalCode !== null) {
      resolve();
      return;
    }

    let giveUpTimer;
    const onExit = () => {
      clearTimeout(escalateTimer);
      clearTimeout(giveUpTimer);
      resolve();
    };
    child.once('exit', onExit);

    signalGroup(child, 'SIGTERM');

    const escalateTimer = setTimeout(() => {
      if (child.exitCode === null && child.signalCode === null) {
        signalGroup(child, 'SIGKILL');
      }
    }, timeoutMs);

    // Last-resort safety net: SIGKILL is not interceptable on POSIX, so this should never fire,
    // but never hang the harness on a wedged/zombie process either.
    giveUpTimer = setTimeout(() => {
      child.removeListener('exit', onExit);
      resolve();
    }, timeoutMs + 3000);
  });
}

/**
 * Removes `userDataDir`, retrying briefly if it's still there afterwards. `rmSync(..., {force:
 * true})` swallows individual-entry errors rather than throwing, so a grandchild process that's
 * still mid-write when the first pass runs can leave the directory non-empty without this
 * function ever seeing an exception — hence the "did it actually go away" check-and-retry instead
 * of trusting a single best-effort pass. `killAndWait()`/`signalGroup()` already make that window
 * small (the whole process group is dead, not just the top-level pid), so this only needs a few
 * short retries, not a long poll.
 */
async function removeUserDataDir(userDataDir, attempts = 5, delayMs = 150) {
  for (let i = 0; i < attempts; i++) {
    try {
      fs.rmSync(userDataDir, { recursive: true, force: true });
    } catch (_) {
      // best effort; fall through to the existsSync check below regardless
    }
    if (!fs.existsSync(userDataDir)) return;
    await sleep(delayMs);
  }
  // Retries exhausted; leave whatever remains rather than looping forever. Callers treat this as
  // best-effort cleanup, same as before.
}

/** Launches headless Chrome and returns a Browser handle once its DevTools WebSocket is up. */
function launchBrowser(chromePath, { timeoutMs = 10000 } = {}) {
  const userDataDir = fs.mkdtempSync(path.join(os.tmpdir(), 'control-smoke-chrome-'));

  const child = spawn(
    chromePath,
    [
      '--headless=new',
      '--no-sandbox',
      '--disable-gpu',
      '--disable-extensions',
      '--disable-background-networking',
      '--hide-scrollbars',
      '--window-size=500,900',
      '--remote-debugging-port=0',
      '--user-data-dir=' + userDataDir,
      'about:blank'
    ],
    // `detached: true` makes Chrome the leader of a new process group (see signalGroup() above)
    // so its zygote/renderer/GPU children can be reached with one group-wide signal on cleanup.
    { stdio: ['ignore', 'ignore', 'pipe'], detached: true }
  );

  return new Promise((resolve, reject) => {
    let stderrBuf = '';
    let settled = false;

    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      killAndWait(child).then(() => removeUserDataDir(userDataDir)).then(() => {
        reject(new Error('Timed out waiting for Chrome DevTools listener.\nstderr:\n' + stderrBuf));
      });
    }, timeoutMs);

    child.stderr.on('data', (chunk) => {
      stderrBuf += chunk.toString();
      if (settled) return;
      const m = stderrBuf.match(/DevTools listening on (ws:\/\/[^\s]+)/);
      if (m) {
        settled = true;
        clearTimeout(timer);
        resolve(new Browser(child, m[1], userDataDir));
      }
    });

    child.on('exit', (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      // The tracked pid already exited, but a grandchild (zygote/renderer) may not have yet --
      // signal the group before cleaning up so removeUserDataDir()'s retries have less to do.
      signalGroup(child, 'SIGKILL');
      removeUserDataDir(userDataDir).then(() => {
        reject(new Error('Chrome exited early (code ' + code + ') before a DevTools listener appeared.\nstderr:\n' + stderrBuf));
      });
    });
  });
}

class Browser {
  constructor(child, wsUrl, userDataDir) {
    this.child = child;
    this.userDataDir = userDataDir;
    // Test-only fault injection for the leaked-process regression test (see README.md /
    // task-7b-report.md): forces the CDP WebSocket handshake to fail against an already-spawned
    // Chrome, so the run.js "launchBrowser() resolved but ready() rejected" cleanup path can be
    // exercised deterministically. Never set in normal operation.
    const connectUrl = process.env.CONTROL_SMOKE_FORCE_BAD_WS ? 'ws://127.0.0.1:1/devtools/page/forced-failure' : wsUrl;
    this.ws = new WebSocket(connectUrl);
    this._id = 0;
    this._pending = new Map();
    this._eventHandlers = [];
    this._ready = new Promise((resolve, reject) => {
      this.ws.addEventListener('open', () => resolve());
      this.ws.addEventListener('error', (e) => reject(new Error('CDP WebSocket error: ' + (e.message || e))));
    });
    this.ws.addEventListener('message', (ev) => this._onMessage(ev));
  }

  _onMessage(ev) {
    const msg = JSON.parse(ev.data);
    if (msg.id !== undefined && this._pending.has(msg.id)) {
      const p = this._pending.get(msg.id);
      this._pending.delete(msg.id);
      if (msg.error) p.reject(new Error('CDP error for ' + p.method + ': ' + JSON.stringify(msg.error)));
      else p.resolve(msg.result);
    } else if (msg.method) {
      for (const handler of this._eventHandlers) handler(msg);
    }
  }

  async ready() {
    await this._ready;
    return this;
  }

  send(method, params, sessionId) {
    const id = ++this._id;
    const payload = { id, method, params: params || {} };
    if (sessionId) payload.sessionId = sessionId;
    return new Promise((resolve, reject) => {
      this._pending.set(id, { resolve, reject, method });
      this.ws.send(JSON.stringify(payload));
    });
  }

  onEvent(handler) {
    this._eventHandlers.push(handler);
  }

  offEvent(handler) {
    this._eventHandlers = this._eventHandlers.filter((h) => h !== handler);
  }

  /** Opens a fresh tab and returns a Page bound to it. */
  async newPage() {
    const { targetId } = await this.send('Target.createTarget', { url: 'about:blank' });
    const { sessionId } = await this.send('Target.attachToTarget', { targetId, flatten: true });
    const page = new Page(this, targetId, sessionId);
    await page._enableDomains();
    return page;
  }

  /**
   * Terminates the Chrome process (SIGTERM, escalating to SIGKILL if it doesn't exit) and removes
   * its temp user-data-dir. Safe to call even if the WebSocket never finished connecting — the
   * only things this depends on are `this.child` and `this.userDataDir`, both set in the
   * constructor before the WebSocket handshake is attempted, so a handshake failure never leaves
   * this half-usable.
   */
  async close() {
    try {
      this.ws.close();
    } catch (_) {
      // already closed, or never finished opening
    }
    await killAndWait(this.child);
    await removeUserDataDir(this.userDataDir);
  }
}

class Page {
  constructor(browser, targetId, sessionId) {
    this.browser = browser;
    this.targetId = targetId;
    this.sessionId = sessionId;
    this.consoleErrors = [];
    this.exceptions = [];
    this.failedRequests = []; // {url, status} for HTTP >= 400, or {url, errorText} for network-level failures

    this._loadFired = false;
    this._handler = (msg) => {
      if (msg.sessionId !== this.sessionId) return;
      if (msg.method === 'Page.loadEventFired') this._loadFired = true;
      if (msg.method === 'Runtime.exceptionThrown') this.exceptions.push(msg.params);
      if (msg.method === 'Runtime.consoleAPICalled' && msg.params.type === 'error') {
        this.consoleErrors.push(msg.params.args.map((a) => a.value || a.description || '').join(' '));
      }
      if (msg.method === 'Network.loadingFailed') {
        this.failedRequests.push({ url: msg.params.requestId, errorText: msg.params.errorText });
      }
      if (msg.method === 'Network.responseReceived' && msg.params.response.status >= 400) {
        this.failedRequests.push({ url: msg.params.response.url, status: msg.params.response.status });
      }
    };
    browser.onEvent(this._handler);
  }

  async _enableDomains() {
    await this.browser.send('Page.enable', {}, this.sessionId);
    await this.browser.send('Runtime.enable', {}, this.sessionId);
    await this.browser.send('Network.enable', {}, this.sessionId);
  }

  async navigate(url, { timeoutMs = 10000 } = {}) {
    this._loadFired = false;
    await this.browser.send('Page.navigate', { url }, this.sessionId);
    const start = Date.now();
    while (!this._loadFired) {
      if (Date.now() - start > timeoutMs) {
        throw new Error('Timed out waiting for the page load event on ' + url);
      }
      await sleep(25);
    }
  }

  /** Evaluates `expression` in the page and returns its JSON-serializable value. Throws on a page-side exception. */
  async evaluate(expression) {
    const result = await this.browser.send(
      'Runtime.evaluate',
      { expression, returnByValue: true, awaitPromise: true },
      this.sessionId
    );
    if (result.exceptionDetails) {
      const desc =
        (result.exceptionDetails.exception && result.exceptionDetails.exception.description) ||
        result.exceptionDetails.text;
      throw new Error('Page evaluate() threw: ' + desc);
    }
    return result.result ? result.result.value : undefined;
  }

  /** Polls a boolean-returning `expression` until it is truthy or `timeoutMs` elapses. */
  async waitFor(expression, timeoutMs = 5000, intervalMs = 25) {
    const start = Date.now();
    for (;;) {
      const value = await this.evaluate(expression);
      if (value) return value;
      if (Date.now() - start > timeoutMs) {
        throw new Error('waitFor() timed out after ' + timeoutMs + 'ms on: ' + expression);
      }
      await sleep(intervalMs);
    }
  }

  async close() {
    this.browser.offEvent(this._handler);
    try {
      await this.browser.send('Target.closeTarget', { targetId: this.targetId });
    } catch (_) {
      // best effort
    }
  }
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

module.exports = { findChrome, launchBrowser, killAndWait, sleep };
