'use strict';

/**
 * A same-origin mock of the Task 5 control-page API contract (see
 * `app/src/main/assets/control.html` and `.superpowers/sdd/2026-08-01-remote-control-android/
 * task-5-brief.md`), configurable per test scenario.
 *
 * Contract points this enforces regardless of scenario config, because they're router-level
 * behaviour the page depends on and a scenario should never need to opt out of:
 *   - any request whose raw target contains "?" gets a 404 (the real router matches the raw
 *     request target, not a parsed/normalized path — a cache-busting query string 404s for real)
 *   - a POST/PUT under /api/ without `Content-Type: application/json` gets a 415
 *
 * Every request is logged (method, raw url, path, query flag, content-type, timestamp) so
 * scenarios can assert on what the page actually sent, not just what the mock answered.
 */

const http = require('http');

function createMockServer(config) {
  const state = deepClone(config.state);
  const requests = [];
  const putBodies = []; // { body, ts } for PUT /api/slideshow/filters
  const immichDoneAt = {}; // kind -> timestamp the response was flushed

  function snapshotState() {
    return {
      device: state.device,
      screen: state.screen,
      volume: state.volume,
      playing: state.playing,
      slideshow: state.slideshow
    };
  }

  function sendJSON(res, status, body) {
    const payload = JSON.stringify(body);
    res.writeHead(status, { 'Content-Type': 'application/json' });
    res.end(payload);
  }

  function readBody(req) {
    return new Promise((resolve, reject) => {
      let data = '';
      req.on('data', (chunk) => (data += chunk));
      req.on('end', () => resolve(data));
      req.on('error', reject);
    });
  }

  function isJSONContentType(req) {
    const ct = req.headers['content-type'] || '';
    return ct.toLowerCase().includes('application/json');
  }

  async function respondImmich(kind, req, res) {
    const spec = config.immich[kind];
    if (spec.delayMs) await sleep(spec.delayMs);
    immichDoneAt[kind] = Date.now();
    sendJSON(res, spec.status, spec.body);
  }

  const server = http.createServer(async (req, res) => {
    const rawUrl = req.url;
    const hasQuery = rawUrl.includes('?');
    const pathname = hasQuery ? rawUrl.slice(0, rawUrl.indexOf('?')) : rawUrl;

    const entry = {
      method: req.method,
      rawUrl,
      path: pathname,
      hasQuery,
      contentType: req.headers['content-type'] || null,
      ts: Date.now()
    };
    requests.push(entry);

    if (hasQuery) {
      sendJSON(res, 404, { error: 'not found' });
      return;
    }

    const isWrite = req.method === 'POST' || req.method === 'PUT';
    if (isWrite && pathname.startsWith('/api/') && !isJSONContentType(req)) {
      sendJSON(res, 415, { error: 'unsupported media type' });
      return;
    }

    if (req.method === 'GET' && pathname === '/') {
      res.writeHead(200, { 'Content-Type': 'text/html' });
      res.end(config.html);
      return;
    }

    if (req.method === 'GET' && pathname === '/api/state') {
      sendJSON(res, 200, snapshotState());
      return;
    }

    if (req.method === 'POST' && pathname === '/api/screen') {
      const body = JSON.parse(await readBody(req));
      const result = config.screenResponder(body, state);
      sendJSON(res, result.status, result.body);
      return;
    }

    if (req.method === 'POST' && pathname === '/api/volume') {
      const body = JSON.parse(await readBody(req));
      const result = config.volumeResponder(body, state);
      sendJSON(res, result.status, result.body);
      return;
    }

    if (req.method === 'GET' && pathname === '/api/slideshow/filters') {
      if (config.filters.delayMs) await sleep(config.filters.delayMs);
      sendJSON(res, config.filters.status, config.filters.body);
      return;
    }

    if (req.method === 'PUT' && pathname === '/api/slideshow/filters') {
      const raw = await readBody(req);
      const body = JSON.parse(raw);
      putBodies.push({ body, ts: Date.now() });
      const result = config.filtersPutResponder(body);
      sendJSON(res, result.status, result.body);
      return;
    }

    if (req.method === 'GET' && pathname === '/api/immich/albums') return respondImmich('albums', req, res);
    if (req.method === 'GET' && pathname === '/api/immich/people') return respondImmich('people', req, res);
    if (req.method === 'GET' && pathname === '/api/immich/tags') return respondImmich('tags', req, res);

    sendJSON(res, 404, { error: 'not found' });
  });

  return {
    requests,
    putBodies,
    immichDoneAt,
    state,
    start() {
      return new Promise((resolve) => {
        server.listen(0, '127.0.0.1', () => {
          const port = server.address().port;
          resolve({ port, url: 'http://127.0.0.1:' + port + '/' });
        });
      });
    },
    stop() {
      return new Promise((resolve) => server.close(() => resolve()));
    }
  };
}

function deepClone(obj) {
  return JSON.parse(JSON.stringify(obj));
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

module.exports = { createMockServer };
