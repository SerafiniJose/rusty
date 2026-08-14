# control-page-smoke

A headless-Chrome smoke harness for `app/src/main/assets/control.html` (the device's on-page
remote-control UI). It loads the real page against a same-origin mock of the Task 5 API contract
and asserts 11 named behaviours — including the Task 7 regression (an early "Save Filters" click
racing the filter-list fetches and PUTting empty arrays over the user's real saved filters).

This exists because the plan shipped `control.html` as "device-exercised only" (no automated
tests), and that gap is exactly how the Task 7 data-loss bug slipped past a fast localhost mock —
it was only caught by a human reading 692 lines. This harness makes that class of bug fail a
command instead.

## Running it

```sh
node tools/control-page-smoke/run.js
```

No npm install, no `package.json`, no `node_modules` — this is plain Node 22 standard library
(`http`, `child_process`, `fs`) plus the built-in `WebSocket` global talking Chrome DevTools
Protocol. Node 22+ is required.

It needs a local Chrome or Chromium binary. It looks, in order, for:
1. `$CONTROL_SMOKE_CHROME` (explicit override — set this if none of the below apply)
2. `google-chrome-stable`, `google-chrome`, `chromium-browser`, `chromium`, `chrome` on `$PATH`
3. Playwright's, Puppeteer's, and this environment's agent-browser tooling caches
   (`~/.cache/ms-playwright`, `~/.cache/puppeteer`, `~/.agent-browser`)
4. a few common absolute install locations (`/usr/bin/google-chrome`, etc.)

If none is found, the harness prints `SKIPPED: no Chrome found at …` and exits **2** — never a
false green, never a crash. A pass/fail run exits **0** (all checks passed) or **1** (at least one
failed).

To point the harness at a different copy of the page (e.g. a scratch copy with a deliberately
broken behaviour, to prove the harness actually catches something):

```sh
CONTROL_SMOKE_HTML=/path/to/some-copy.html node tools/control-page-smoke/run.js
```

## What it does NOT do

- It does **not** run under `./gradlew :app:testDebugUnitTest` — CI builds this repo offline with
  no browser installed, so wiring a Chrome dependency into the Gradle test task would break the
  build for everyone. Run this manually, and during on-device acceptance passes.
- It never modifies `app/src/main/assets/control.html`. If a scenario surfaces a real bug in the
  page, that's a finding to report, not something for this harness to patch around.
- It's a smoke harness, not a general test framework: no config files, no plugin system, no
  reporters beyond the pass/fail lines printed to stdout.

## How it's built

- `mock-server.js` — a same-origin Node `http` server implementing the exact Task 5 API contract
  (`GET /`, `GET /api/state`, `POST /api/screen`, `POST /api/volume`, `GET`/`PUT
  /api/slideshow/filters`, `GET /api/immich/{albums,people,tags}`), configurable per scenario
  (response bodies, status codes, per-endpoint artificial delay). It also enforces two router-level
  behaviours no scenario should be able to opt out of, because the real device router does this
  regardless of scenario: any request whose raw target contains `?` gets a 404, and a POST/PUT
  under `/api/` without `Content-Type: application/json` gets a 415. Every request is logged
  (method, raw url, path, query-string flag, content-type, timestamp) so scenarios can assert on
  what the page actually sent.
- `chrome-driver.js` — launches headless Chrome (`--headless=new --no-sandbox --remote-debugging-
  port=0`) and drives it over one flattened-session WebSocket connection using the Chrome DevTools
  Protocol directly (`Target.createTarget`/`attachToTarget`, `Page.navigate`, `Runtime.evaluate`,
  plus `Runtime`/`Network` event listeners for console errors, uncaught exceptions, and failed
  requests). One CDP target (tab) per scenario.
- `scenarios.js` — the 11 named checks. Each scenario builds its own mock-server config (starting
  from a shared happy-path default, overriding just what it needs), opens a fresh page, drives it
  (click, or a slider drag simulated as several `input` events then one `change` — matching how
  the page's listeners are actually wired), and asserts against both the DOM and the mock's
  request/PUT log.
- `run.js` — entry point: finds Chrome (or exits 2 with the SKIPPED message), reads
  `control.html` (or `$CONTROL_SMOKE_HTML` for testing against a scratch copy), runs each
  scenario, prints one `PASS`/`FAIL` line per scenario with its own error, and exits 0/1/2.

## The 11 scenarios

1. **Renders** — all 4 card sections plus the header (device name/version) are present after
   `/api/state` resolves; zero console errors, uncaught exceptions, or failed requests.
2. **Volume fixed** — `volume.fixed:true` hides the volume card (`display:none`); `false` shows
   it.
3. **Window brightness mode** — `screen.mode:"window"` shows the exact permission hint text;
   `"system"` hides it.
4. **Screen unavailable** — `screen.available:false` shows the "no screen attached" notice rather
   than hiding it (and `true` hides it).
5. **Writes carry the JSON content type** — driving the screen toggle and both sliders, every
   write request the mock received used `Content-Type: application/json`.
6. **No query strings** — none of the requests from a full interaction pass carry a `?`.
7. **Sliders send on change, not input** — a programmatic drag (several `input` events, one
   trailing `change`) produces exactly one write, for both sliders.
8. **Saved-but-unlisted IDs survive** — a saved filter ID absent from the Immich list still
   renders as a checked literal-UUID row, and Save PUTs it back.
9. **Immich down, Save still works** — all three Immich endpoints 502; each list shows its error,
   saved IDs still render checked, Save stays enabled, and PUTs exactly the saved IDs.
10. **The Task 7 regression** — with a 1.8s delay on all three `/api/immich/*` endpoints, clicking
    Save immediately after load produces zero PUTs before the lists finish loading, and the
    post-load Save PUTs the correct non-empty IDs.
11. **Volume 409** — the mock answers 409 `{"error":"volume is fixed"}`; the page surfaces that
    message instead of failing silently.

See `task-7b-report.md` in `.superpowers/sdd/2026-08-01-remote-control-android/` for the full
verification run, including deliberately-broken-page evidence that the harness actually
discriminates (fails against a broken page, passes against the real one).
