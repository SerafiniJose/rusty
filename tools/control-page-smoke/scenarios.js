'use strict';

/**
 * The 11 named smoke scenarios from task-7b-brief.md, each built on the mock server + Chrome
 * driver. Every scenario owns its own mock server instance and its own tab, so scenarios never
 * share page or server state.
 */

const { createMockServer } = require('./mock-server');

const DEFAULT_DEVICE = { id: 'dev-1', name: 'Rusty Speaker', version: '2.4.0' };
const DEFAULT_SCREEN = { on: true, brightness: 55, mode: 'system', writable: true, available: true };
const DEFAULT_VOLUME = { value: 40, fixed: false };
const DEFAULT_PLAYING = { spotify: true, dlna: false };
const DEFAULT_SLIDESHOW = { enabled: true };
const DEFAULT_PANEL = {
  active: 'spotify',
  available: ['spotify', 'home_assistant', 'dlna', 'lockscreen'],
  lockscreen: { theme: 'clock', themes: ['clock', 'oled', 'canvas', 'slideshow'] }
};
const DEFAULT_APP = { foreground: true, canBringForward: true };

const DEFAULT_FILTERS = { status: 200, body: { albumIds: [], personIds: [], tagIds: [] }, delayMs: 0 };
const DEFAULT_IMMICH = {
  albums: { status: 200, body: [{ id: 'album-known-1', name: 'Album One' }], delayMs: 0 },
  people: { status: 200, body: [{ id: 'person-known-1', name: 'Person One' }], delayMs: 0 },
  tags: { status: 200, body: [{ id: 'tag-known-1', name: 'Tag One' }], delayMs: 0 }
};

function fullSnapshot(state) {
  return {
    device: state.device,
    screen: state.screen,
    volume: state.volume,
    playing: state.playing,
    slideshow: state.slideshow,
    panel: state.panel,
    app: state.app
  };
}

/**
 * Mirrors the device: bringing the window forward is an activity start applied after the answer,
 * so the response reports the PRE-command state and only a later poll agrees. `moveAfterMs < 0`
 * models the OEM builds that drop the launch silently — the page must time out, not light up.
 */
function makeForegroundResponder(options) {
  options = options || {};
  return function (body, state) {
    const pre = fullSnapshot(state);
    if (options.status && options.status !== 200) {
      return { status: options.status, body: { error: options.error || 'nope' } };
    }
    const delay = typeof options.moveAfterMs === 'number' ? options.moveAfterMs : 0;
    const apply = () => {
      state.app = Object.assign({}, state.app, { foreground: body.on });
      // A backgrounded Rusty can command nothing, exactly as the real relay reports.
      state.panel = Object.assign({}, state.panel, { active: body.on ? 'spotify' : null });
    };
    if (delay === 0) apply();
    else if (delay > 0) setTimeout(apply, delay);
    return { status: 200, body: pre };
  };
}

function defaultScreenResponder(body, state) {
  state.screen = Object.assign({}, state.screen, body);
  return { status: 200, body: fullSnapshot(state) };
}

function defaultVolumeResponder(body, state) {
  state.volume = Object.assign({}, state.volume, { value: body.value });
  return { status: 200, body: fullSnapshot(state) };
}

function defaultFiltersPutResponder(body) {
  return { status: 200, body };
}

/**
 * Mirrors the device: a panel switch is applied asynchronously, so the response reports the
 * snapshot BEFORE the switch. The state only moves after `switchAfterMs`, which is what the
 * page's pending lamp is waiting on.
 */
function makePanelResponder(options) {
  options = options || {};
  return function (body, state) {
    const pre = fullSnapshot(state);
    if (options.status && options.status !== 200) {
      return { status: options.status, body: { error: options.error || 'nope' } };
    }
    const delay = typeof options.switchAfterMs === 'number' ? options.switchAfterMs : 0;
    if (delay === 0) {
      state.panel = Object.assign({}, state.panel, { active: body.id });
    } else if (delay > 0) {
      setTimeout(() => {
        state.panel = Object.assign({}, state.panel, { active: body.id });
      }, delay);
    }
    // delay < 0 means "never lands" — the page must time the request out rather than lie.
    return { status: 200, body: pre };
  };
}

/** A theme IS applied before the device answers, so this responder reports the new value. */
function defaultLockscreenResponder(body, state) {
  state.panel = Object.assign({}, state.panel, {
    lockscreen: Object.assign({}, state.panel.lockscreen, { theme: body.theme })
  });
  return { status: 200, body: fullSnapshot(state) };
}

function buildConfig(html, overrides) {
  overrides = overrides || {};
  const state = {
    device: Object.assign({}, DEFAULT_DEVICE, overrides.device),
    screen: Object.assign({}, DEFAULT_SCREEN, overrides.screen),
    volume: Object.assign({}, DEFAULT_VOLUME, overrides.volume),
    playing: Object.assign({}, DEFAULT_PLAYING, overrides.playing),
    slideshow: Object.assign({}, DEFAULT_SLIDESHOW, overrides.slideshow),
    panel: Object.assign({}, DEFAULT_PANEL, overrides.panel),
    app: Object.assign({}, DEFAULT_APP, overrides.app)
  };
  const immich = {
    albums: Object.assign({}, DEFAULT_IMMICH.albums, overrides.immich && overrides.immich.albums),
    people: Object.assign({}, DEFAULT_IMMICH.people, overrides.immich && overrides.immich.people),
    tags: Object.assign({}, DEFAULT_IMMICH.tags, overrides.immich && overrides.immich.tags)
  };
  const filters = Object.assign({}, DEFAULT_FILTERS, overrides.filters);
  return {
    html,
    state,
    filters,
    immich,
    screenResponder: overrides.screenResponder || defaultScreenResponder,
    volumeResponder: overrides.volumeResponder || defaultVolumeResponder,
    panelResponder: overrides.panelResponder || makePanelResponder(),
    lockscreenResponder: overrides.lockscreenResponder || defaultLockscreenResponder,
    foregroundResponder: overrides.foregroundResponder || makeForegroundResponder(),
    filtersPutResponder: overrides.filtersPutResponder || defaultFiltersPutResponder,
    update: overrides.update,
    updateInstall: overrides.updateInstall,
    updateAfterInstall: overrides.updateAfterInstall
  };
}

// ---- small expression builders (JS run inside the page via Runtime.evaluate) --------------

function clickExpr(id) {
  return `(function(){ var el = document.getElementById(${JSON.stringify(id)}); el.click(); return true; })()`;
}

function dragExpr(id, values) {
  const stmts = values
    .map((v, i) => {
      const evt = i === values.length - 1 ? 'change' : 'input';
      return `el.value = ${JSON.stringify(String(v))}; el.dispatchEvent(new Event(${JSON.stringify(evt)}, { bubbles: true }));`;
    })
    .join(' ');
  return `(function(){ var el = document.getElementById(${JSON.stringify(id)}); ${stmts} return true; })()`;
}

function textExpr(id) {
  return `(function(){ var el = document.getElementById(${JSON.stringify(id)}); return el ? el.textContent : null; })()`;
}

function existsExpr(id) {
  return `!!document.getElementById(${JSON.stringify(id)})`;
}

function hiddenExpr(id) {
  return `document.getElementById(${JSON.stringify(id)}).hidden`;
}

function disabledExpr(id) {
  return `document.getElementById(${JSON.stringify(id)}).disabled`;
}

function notDisabledExpr(id) {
  return `document.getElementById(${JSON.stringify(id)}).disabled === false`;
}

function computedDisplayExpr(id) {
  return `getComputedStyle(document.getElementById(${JSON.stringify(id)})).display`;
}

function ariaExpr(id, attr) {
  return `document.getElementById(${JSON.stringify(id)}).getAttribute(${JSON.stringify(attr)})`;
}

function hasClassExpr(id, cls) {
  return `document.getElementById(${JSON.stringify(id)}).classList.contains(${JSON.stringify(cls)})`;
}

/** The chip whose data-theme matches, as { exists, on } — chips are rebuilt on every render, so
 *  they are addressed by attribute rather than by a stable id. */
function themeChipExpr(theme) {
  return `(function(){
    var chip = document.querySelector('#theme-chips [data-theme=' + ${JSON.stringify(JSON.stringify(theme))} + ']');
    return chip ? { exists: true, on: chip.classList.contains('on') } : { exists: false, on: false };
  })()`;
}

function themeChipCountExpr() {
  return `document.querySelectorAll('#theme-chips [data-theme]').length`;
}

function checkboxByValueExpr(listId, value) {
  return `(function(){
    var boxes = document.getElementById(${JSON.stringify(listId)}).querySelectorAll('input[type="checkbox"]');
    for (var i = 0; i < boxes.length; i++) {
      if (boxes[i].value === ${JSON.stringify(value)}) {
        var span = boxes[i].parentElement.querySelector('span');
        return { found: true, checked: boxes[i].checked, labelText: span ? span.textContent : null, labelClass: span ? span.className : null };
      }
    }
    return { found: false };
  })()`;
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

async function waitForNode(predicate, timeoutMs, description) {
  const start = Date.now();
  for (;;) {
    if (predicate()) return;
    if (Date.now() - start > timeoutMs) {
      throw new Error('Timed out after ' + timeoutMs + 'ms waiting for: ' + (description || 'condition'));
    }
    await sleep(20);
  }
}

function assert(cond, message) {
  if (!cond) throw new Error(message);
}

/** Requests the mock recorded, minus the browser's own unsolicited /favicon.ico probe. */
function realRequests(mock) {
  return mock.requests.filter((r) => r.path !== '/favicon.ico');
}

// ---- scenarios ------------------------------------------------------------------------------

function getScenarios(html) {
  return [
    {
      name: '1. Renders',
      async run({ browser }) {
        const mock = createMockServer(buildConfig(html));
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url);
          await page.waitFor(notDisabledExpr('filters-save'), 5000, 'filters-save enabled after load');

          const name = await page.evaluate(textExpr('device-name'));
          const version = await page.evaluate(textExpr('device-version'));
          assert(name === mock.state.device.name, 'device-name should show "' + mock.state.device.name + '", got "' + name + '"');
          assert(version === 'v' + mock.state.device.version, 'device-version should show "v' + mock.state.device.version + '", got "' + version + '"');

          for (const cardId of ['screen-card', 'volume-card', 'source-card', 'filters-card']) {
            assert(await page.evaluate(existsExpr(cardId)), 'expected #' + cardId + ' to exist in the DOM');
          }

          assert(page.consoleErrors.length === 0, 'expected no console errors, got: ' + JSON.stringify(page.consoleErrors));
          assert(page.exceptions.length === 0, 'expected no uncaught page exceptions, got ' + page.exceptions.length);
          const failed = page.failedRequests.filter((r) => !String(r.url || '').includes('/favicon.ico'));
          assert(failed.length === 0, 'expected no failed requests, got: ' + JSON.stringify(failed));
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '2. Volume fixed',
      async run({ browser }) {
        for (const fixed of [true, false]) {
          const mock = createMockServer(buildConfig(html, { volume: { fixed } }));
          const { url } = await mock.start();
          const page = await browser.newPage();
          try {
            await page.navigate(url);
            await page.waitFor(notDisabledExpr('filters-save'), 5000, 'filters-save enabled after load');
            const display = await page.evaluate(computedDisplayExpr('volume-card'));
            if (fixed) {
              assert(display === 'none', 'volume.fixed:true should hide #volume-card (display:none), got "' + display + '"');
            } else {
              assert(display !== 'none', 'volume.fixed:false should show #volume-card, got display:"' + display + '"');
            }
          } finally {
            await page.close();
            await mock.stop();
          }
        }
      }
    },

    {
      name: '3. Window brightness mode',
      async run({ browser }) {
        const HINT = 'System brightness permission not granted — controlling app window only.';
        for (const mode of ['window', 'system']) {
          const mock = createMockServer(buildConfig(html, { screen: { mode } }));
          const { url } = await mock.start();
          const page = await browser.newPage();
          try {
            await page.navigate(url);
            await page.waitFor(notDisabledExpr('filters-save'), 5000, 'filters-save enabled after load');
            const hidden = await page.evaluate(hiddenExpr('screen-mode-hint'));
            const text = await page.evaluate(textExpr('screen-mode-hint'));
            if (mode === 'window') {
              assert(hidden === false, 'screen.mode:"window" should show the mode hint');
              assert(text.trim() === HINT, 'unexpected hint text: "' + text + '"');
            } else {
              assert(hidden === true, 'screen.mode:"system" should hide the mode hint');
            }
          } finally {
            await page.close();
            await mock.stop();
          }
        }
      }
    },

    {
      name: '4. Screen unavailable',
      async run({ browser }) {
        // `available` is one flag answering "is a visible window attached?", but the two controls on
        // this card do not share that constraint. Turning the screen on/off is an overlay plus
        // FLAG_KEEP_SCREEN_ON and genuinely needs a visible window; brightness in system mode is a
        // Settings.System write that applies device-wide with the app backgrounded. Telling the user
        // a brightness change is "saved for later" when it already moved the panel is a lie, so the
        // notice has to read both flags.
        const cases = [
          { available: true, writable: true, expectHidden: true },
          { available: true, writable: false, expectHidden: true },
          // Window mode with nothing on screen: truly nothing applies now.
          { available: false, writable: false, expectHidden: false, expect: /saved/i, forbid: /brightness still/i },
          // System mode: brightness lands immediately, only the on/off half waits.
          { available: false, writable: true, expectHidden: false, expect: /brightness still applies/i },
        ];
        for (const c of cases) {
          const label = `available:${c.available} writable:${c.writable}`;
          const mock = createMockServer(buildConfig(html, {
            screen: { available: c.available, writable: c.writable, mode: c.writable ? 'system' : 'window' }
          }));
          const { url } = await mock.start();
          const page = await browser.newPage();
          try {
            await page.navigate(url);
            await page.waitFor(notDisabledExpr('filters-save'), 5000, 'filters-save enabled after load');
            const hidden = await page.evaluate(hiddenExpr('screen-availability-note'));
            assert(hidden === c.expectHidden, `${label}: notice hidden should be ${c.expectHidden}`);
            if (!c.expectHidden) {
              const text = await page.evaluate(textExpr('screen-availability-note'));
              assert(text.trim().length > 0, `${label}: notice should have visible text`);
              assert(c.expect.test(text), `${label}: notice should match ${c.expect} — got ${JSON.stringify(text)}`);
              if (c.forbid) {
                assert(!c.forbid.test(text),
                  `${label}: notice must not claim ${c.forbid} — got ${JSON.stringify(text)}`);
              }
            }
          } finally {
            await page.close();
            await mock.stop();
          }
        }
      }
    },

    {
      name: '5. Writes carry the JSON content type',
      async run({ browser }) {
        const mock = createMockServer(buildConfig(html, { volume: { fixed: false } }));
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url);
          await page.waitFor(notDisabledExpr('filters-save'), 5000, 'filters-save enabled after load');

          // Brightness first, while the screen is still on: the page disables that slider
          // whenever screen.on is false, so a drag after the toggle-off would be exercising a
          // control the UI has deliberately taken away.
          await page.evaluate(dragExpr('brightness-range', [10, 50, 80]));
          await page.waitFor(notDisabledExpr('brightness-range'), 5000, 'brightness write settles');

          await page.evaluate(clickExpr('screen-toggle'));
          await page.waitFor(notDisabledExpr('screen-toggle'), 5000, 'screen-toggle write settles');
          await page.waitFor(disabledExpr('brightness-range'), 5000, 'brightness slider disabled while the screen is off');

          await page.evaluate(dragExpr('volume-range', [5, 33, 77]));
          await page.waitFor(notDisabledExpr('volume-range'), 5000, 'volume write settles');

          await page.evaluate(clickExpr('filters-save'));
          await page.waitFor(notDisabledExpr('filters-save'), 5000, 'filters-save write settles');

          const writes = realRequests(mock).filter((r) => r.method === 'POST' || r.method === 'PUT');
          assert(writes.length >= 4, 'expected at least 4 write requests, saw ' + writes.length);
          for (const w of writes) {
            assert(
              (w.contentType || '').toLowerCase().includes('application/json'),
              'write ' + w.method + ' ' + w.path + ' missing Content-Type: application/json (got "' + w.contentType + '")'
            );
          }
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '6. No query strings',
      async run({ browser }) {
        const mock = createMockServer(buildConfig(html, { volume: { fixed: false } }));
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url);
          await page.waitFor(notDisabledExpr('filters-save'), 5000, 'filters-save enabled after load');

          await page.evaluate(dragExpr('brightness-range', [10, 50, 80]));
          await page.waitFor(notDisabledExpr('brightness-range'), 5000, 'brightness write settles');
          await page.evaluate(clickExpr('screen-toggle'));
          await page.waitFor(notDisabledExpr('screen-toggle'), 5000, 'screen-toggle write settles');
          await page.evaluate(dragExpr('volume-range', [5, 33, 77]));
          await page.waitFor(notDisabledExpr('volume-range'), 5000, 'volume write settles');
          await page.evaluate(clickExpr('filters-save'));
          await page.waitFor(notDisabledExpr('filters-save'), 5000, 'filters-save write settles');

          const seen = realRequests(mock);
          assert(seen.length > 5, 'expected several requests to inspect, saw ' + seen.length);
          const withQuery = seen.filter((r) => r.hasQuery);
          assert(withQuery.length === 0, 'requests with a query string: ' + JSON.stringify(withQuery.map((r) => r.rawUrl)));
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '7. Sliders send on change, not input',
      async run({ browser }) {
        const mock = createMockServer(buildConfig(html, { volume: { fixed: false } }));
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url);
          await page.waitFor(notDisabledExpr('filters-save'), 5000, 'filters-save enabled after load');

          await page.evaluate(dragExpr('brightness-range', [12, 44, 91]));
          await page.waitFor(notDisabledExpr('brightness-range'), 5000, 'brightness write settles');
          const screenWrites = realRequests(mock).filter((r) => r.method === 'POST' && r.path === '/api/screen');
          assert(screenWrites.length === 1, 'a 3-input+1-change brightness drag should send exactly 1 write, saw ' + screenWrites.length);

          await page.evaluate(dragExpr('volume-range', [3, 51, 66]));
          await page.waitFor(notDisabledExpr('volume-range'), 5000, 'volume write settles');
          const volumeWrites = realRequests(mock).filter((r) => r.method === 'POST' && r.path === '/api/volume');
          assert(volumeWrites.length === 1, 'a 3-input+1-change volume drag should send exactly 1 write, saw ' + volumeWrites.length);
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '8. Saved-but-unlisted IDs survive',
      async run({ browser }) {
        const savedKnown = 'album-known-1';
        const savedUnlisted = '99999999-9999-4999-8999-999999999999';
        const mock = createMockServer(
          buildConfig(html, {
            filters: { status: 200, body: { albumIds: [savedKnown, savedUnlisted], personIds: [], tagIds: [] } }
          })
        );
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url);
          await page.waitFor(notDisabledExpr('filters-save'), 5000, 'filters-save enabled after load');

          const row = await page.evaluate(checkboxByValueExpr('albums-list', savedUnlisted));
          assert(row.found, 'expected a checkbox for the saved-but-unlisted id ' + savedUnlisted);
          assert(row.checked === true, 'saved-but-unlisted id should render checked');
          assert(row.labelText === savedUnlisted, 'saved-but-unlisted id should show the literal id as its label, got "' + row.labelText + '"');
          assert(row.labelClass === 'id-fallback', 'saved-but-unlisted id label should carry class "id-fallback", got "' + row.labelClass + '"');

          await page.evaluate(clickExpr('filters-save'));
          await waitForNode(() => mock.putBodies.length >= 1, 5000, 'PUT /api/slideshow/filters to arrive');
          const put = mock.putBodies[mock.putBodies.length - 1].body;
          assert(put.albumIds.includes(savedUnlisted), 'Save should PUT the saved-but-unlisted id back, got ' + JSON.stringify(put.albumIds));
          assert(put.albumIds.includes(savedKnown), 'Save should also keep the known checked id, got ' + JSON.stringify(put.albumIds));
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '9. Immich down, Save still works',
      async run({ browser }) {
        const savedAlbum = 'saved-album-1';
        const savedPerson = 'saved-person-1';
        const savedTag = 'saved-tag-1';
        const mock = createMockServer(
          buildConfig(html, {
            filters: {
              status: 200,
              body: { albumIds: [savedAlbum], personIds: [savedPerson], tagIds: [savedTag] }
            },
            immich: {
              albums: { status: 502, body: { error: 'immich unreachable' } },
              people: { status: 502, body: { error: 'immich unreachable' } },
              tags: { status: 502, body: { error: 'immich unreachable' } }
            }
          })
        );
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url);
          await page.waitFor(notDisabledExpr('filters-save'), 5000, 'filters-save enabled even with Immich down');

          for (const kind of ['albums', 'people', 'tags']) {
            const hidden = await page.evaluate(hiddenExpr(kind + '-error'));
            assert(hidden === false, '#' + kind + '-error should be visible when Immich 502s');
            const text = await page.evaluate(textExpr(kind + '-error'));
            assert(text.trim().length > 0, '#' + kind + '-error should have an error message');
          }

          const albumRow = await page.evaluate(checkboxByValueExpr('albums-list', savedAlbum));
          assert(albumRow.found && albumRow.checked, 'saved album id should still render checked when Immich is down');
          const personRow = await page.evaluate(checkboxByValueExpr('people-list', savedPerson));
          assert(personRow.found && personRow.checked, 'saved person id should still render checked when Immich is down');
          const tagRow = await page.evaluate(checkboxByValueExpr('tags-list', savedTag));
          assert(tagRow.found && tagRow.checked, 'saved tag id should still render checked when Immich is down');

          assert(await page.evaluate(notDisabledExpr('filters-save')), 'Save should be enabled even when Immich is fully down');

          await page.evaluate(clickExpr('filters-save'));
          await waitForNode(() => mock.putBodies.length >= 1, 5000, 'PUT /api/slideshow/filters to arrive');
          const put = mock.putBodies[mock.putBodies.length - 1].body;
          assert(
            JSON.stringify(put) === JSON.stringify({ albumIds: [savedAlbum], personIds: [savedPerson], tagIds: [savedTag] }),
            'Save should PUT exactly the saved ids when Immich is down, got ' + JSON.stringify(put)
          );
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '10. Task 7 regression (early Save must not wipe filters)',
      async run({ browser }) {
        const savedKnown = 'album-known-1';
        const savedUnlisted = '88888888-8888-4888-8888-888888888888';
        const DELAY_MS = 1800;
        const mock = createMockServer(
          buildConfig(html, {
            filters: { status: 200, body: { albumIds: [savedKnown, savedUnlisted], personIds: [], tagIds: [] } },
            immich: {
              albums: { delayMs: DELAY_MS },
              people: { delayMs: DELAY_MS },
              tags: { delayMs: DELAY_MS }
            }
          })
        );
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url); // resolves on window 'load', well before the delayed Immich responses
          await page.evaluate(clickExpr('filters-save')); // immediately after load: button ships disabled

          assert(
            mock.putBodies.length === 0,
            'clicking Save immediately after load (before Immich lists resolve) must not PUT anything; saw ' +
              JSON.stringify(mock.putBodies)
          );

          await waitForNode(
            () => mock.immichDoneAt.albums && mock.immichDoneAt.people && mock.immichDoneAt.tags,
            DELAY_MS + 3000,
            'all three Immich lists to finish loading'
          );
          await page.waitFor(notDisabledExpr('filters-save'), 3000, 'filters-save enabled after lists load');

          assert(mock.putBodies.length === 0, 'still no PUT should have happened purely from loading, saw ' + JSON.stringify(mock.putBodies));

          await page.evaluate(clickExpr('filters-save'));
          await waitForNode(() => mock.putBodies.length >= 1, 5000, 'the post-load PUT to arrive');

          assert(mock.putBodies.length === 1, 'expected exactly 1 PUT total (early click was a no-op), saw ' + mock.putBodies.length);
          const put = mock.putBodies[0];
          const lastImmichDoneAt = Math.max(mock.immichDoneAt.albums, mock.immichDoneAt.people, mock.immichDoneAt.tags);
          assert(
            put.ts >= lastImmichDoneAt,
            'the PUT must not happen before the Immich lists finish loading (put.ts=' + put.ts + ', lastImmichDoneAt=' + lastImmichDoneAt + ')'
          );
          assert(put.body.albumIds.includes(savedKnown) && put.body.albumIds.includes(savedUnlisted), 'post-load PUT should carry the correct non-empty saved ids, got ' + JSON.stringify(put.body));
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '11. Volume 409',
      async run({ browser }) {
        const mock = createMockServer(
          buildConfig(html, {
            volume: { fixed: false },
            volumeResponder: () => ({ status: 409, body: { error: 'volume is fixed' } })
          })
        );
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url);
          await page.waitFor(notDisabledExpr('filters-save'), 5000, 'filters-save enabled after load');

          await page.evaluate(dragExpr('volume-range', [10, 20, 30]));
          await page.waitFor(notDisabledExpr('volume-range'), 5000, 'volume write settles (even on 409)');

          const hidden = await page.evaluate(hiddenExpr('volume-error'));
          assert(hidden === false, '#volume-error should be visible after a 409');
          const text = await page.evaluate(textExpr('volume-error'));
          assert(text.includes('volume is fixed'), 'expected the 409 error message to surface, got "' + text + '"');
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '12. Update flow (available → install → downloading)',
      async run({ browser }) {
        const available = {
          current: '2.4.0',
          status: 'update_available',
          latest: { version: '2.5.0', notes: '• Remote updates', url: 'https://example.com/rel', hasApk: true },
          install: { phase: 'idle' }
        };
        const mock = createMockServer(
          buildConfig(html, {
            update: { status: 200, body: available },
            updateInstall: { status: 202, body: { status: 'started' } },
            updateAfterInstall: {
              status: 200,
              body: Object.assign({}, available, { install: { phase: 'downloading', progress: 37 } })
            }
          })
        );
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url);
          await page.waitFor(notDisabledExpr('update-button'), 5000, 'Update button enabled once an update is known');

          assert((await page.evaluate(hiddenExpr('update-button'))) === false, 'Update button should be visible');
          assert((await page.evaluate(hiddenExpr('update-notes'))) === false, 'release notes should be visible');
          const status = await page.evaluate(textExpr('update-status'));
          assert(status.includes('2.5.0'), 'status should name the new version, got "' + status + '"');

          await page.evaluate(clickExpr('update-button'));
          await page.waitFor(
            `document.getElementById('update-status').textContent.indexOf('Downloading') !== -1`,
            5000,
            'status shows download progress after Update is clicked'
          );
          const downloading = await page.evaluate(textExpr('update-status'));
          assert(downloading.includes('37%'), 'progress percentage should render, got "' + downloading + '"');
          assert((await page.evaluate(disabledExpr('update-button'))) === true, 'Update button should be disabled while downloading');
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '13. Panel switch stays pending until a poll confirms it',
      async run({ browser }) {
        // The device applies a switch asynchronously and answers with the PRE-switch snapshot.
        // The lamp must not light on that response — only on a later /api/state that agrees.
        const mock = createMockServer(
          buildConfig(html, { panelResponder: makePanelResponder({ switchAfterMs: 900 }) })
        );
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url);
          await page.waitFor(hasClassExpr('lamp-spotify', 'on'), 5000, 'Spotify lamp lit on load');

          await page.evaluate(clickExpr('lamp-dlna'));
          await page.waitFor(hasClassExpr('lamp-dlna', 'pending'), 3000, 'DLNA lamp goes pending');

          assert(
            (await page.evaluate(hasClassExpr('lamp-dlna', 'on'))) === false,
            'DLNA lamp must NOT be lit while the switch is only requested'
          );
          assert(
            (await page.evaluate(hasClassExpr('lamp-spotify', 'on'))) === true,
            'Spotify must stay lit until the device reports the switch'
          );
          assert(mock.panelBodies.length === 1, 'expected exactly one POST /api/panel');
          assert(mock.panelBodies[0].body.id === 'dlna', 'expected the DLNA wire id, got ' + JSON.stringify(mock.panelBodies[0].body));

          await page.waitFor(hasClassExpr('lamp-dlna', 'on'), 6000, 'DLNA lamp lights once the poll confirms');
          assert(
            (await page.evaluate(hasClassExpr('lamp-dlna', 'pending'))) === false,
            'pending must clear once confirmed'
          );
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '14. A switch that never lands times out instead of lying',
      async run({ browser }) {
        const mock = createMockServer(
          buildConfig(html, { panelResponder: makePanelResponder({ switchAfterMs: -1 }) })
        );
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url);
          await page.waitFor(hasClassExpr('lamp-spotify', 'on'), 5000, 'Spotify lamp lit on load');

          await page.evaluate(clickExpr('lamp-lockscreen'));
          await page.waitFor(
            `document.getElementById('source-error').hidden === false`,
            12000,
            'an unconfirmed switch eventually reports that it did not happen'
          );

          const message = await page.evaluate(textExpr('source-error'));
          assert(message.indexOf('Lock screen') !== -1, 'error should name the panel, got "' + message + '"');
          assert(
            (await page.evaluate(hasClassExpr('lamp-lockscreen', 'on'))) === false,
            'a timed-out lamp must never end up lit'
          );
          assert(
            (await page.evaluate(hasClassExpr('lamp-lockscreen', 'pending'))) === false,
            'pending must clear when the request times out'
          );
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '15. Panels switched off in settings are not offered at all',
      async run({ browser }) {
        const mock = createMockServer(
          buildConfig(html, {
            panel: { active: 'spotify', available: ['spotify', 'lockscreen'] }
          })
        );
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url);
          await page.waitFor(hasClassExpr('lamp-spotify', 'on'), 5000, 'Spotify lamp lit on load');

          // Not a place you can go, so not drawn — a greyed lamp would just always refuse.
          for (const id of ['lamp-home_assistant', 'lamp-dlna']) {
            assert((await page.evaluate(existsExpr(id))) === false, id + ' should not be rendered');
          }
          for (const id of ['lamp-spotify', 'lamp-lockscreen']) {
            assert(await page.evaluate(existsExpr(id)), id + ' should be rendered');
            assert((await page.evaluate(disabledExpr(id))) === false, id + ' should be switchable');
          }

          // The row fills the width with however many destinations remain.
          const cols = await page.evaluate(
            `getComputedStyle(document.getElementById('source-lamps')).gridTemplateColumns.split(' ').length`
          );
          assert(cols === 2, 'expected 2 lamp columns for 2 available panels, got ' + cols);
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '16. Lock screen theme picker reflects and sets the theme',
      async run({ browser }) {
        const mock = createMockServer(buildConfig(html));
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url);
          await page.waitFor(themeChipCountExpr() + ' === 4', 5000, 'four theme chips render');

          const clock = await page.evaluate(themeChipExpr('clock'));
          assert(clock.on === true, 'the reported theme should be the selected chip');
          const oledBefore = await page.evaluate(themeChipExpr('oled'));
          assert(oledBefore.on === false, 'other chips should not be selected');

          await page.evaluate(
            `document.querySelector('#theme-chips [data-theme="oled"]').click()`
          );
          await page.waitFor(
            `(function(){ var c = document.querySelector('#theme-chips [data-theme="oled"]'); return !!c && c.classList.contains('on'); })()`,
            5000,
            'the picked theme becomes the selected chip'
          );

          assert(mock.lockscreenBodies.length === 1, 'expected exactly one POST /api/lockscreen');
          assert(
            mock.lockscreenBodies[0].body.theme === 'oled',
            'expected the OLED wire id, got ' + JSON.stringify(mock.lockscreenBodies[0].body)
          );
          assert(
            (await page.evaluate(themeChipExpr('clock'))).on === false,
            'the previous chip should clear'
          );
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '17. No app window: lamps go inert rather than pretending',
      async run({ browser }) {
        const mock = createMockServer(buildConfig(html, { panel: { active: null } }));
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url);
          await page.waitFor(
            `document.getElementById('source-note').hidden === false`,
            5000,
            'the "not on screen" note appears when panel.active is null'
          );

          for (const id of ['lamp-spotify', 'lamp-home_assistant', 'lamp-dlna', 'lamp-lockscreen']) {
            assert((await page.evaluate(disabledExpr(id))) === true, id + ' should be disabled with no window');
            assert((await page.evaluate(hasClassExpr(id, 'on'))) === false, id + ' must not be lit with no window');
          }

          await page.evaluate(clickExpr('lamp-dlna'));
          await sleep(400);
          assert(mock.panelBodies.length === 0, 'an inert lamp must not send anything');
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '18. Foreground switch brings Rusty back, pending until confirmed',
      async run({ browser }) {
        const mock = createMockServer(
          buildConfig(html, {
            panel: { active: null },
            app: { foreground: false, canBringForward: true },
            foregroundResponder: makeForegroundResponder({ moveAfterMs: 900 })
          })
        );
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url);
          await page.waitFor(
            ariaExpr('foreground-switch', 'aria-checked') + " === 'false'",
            5000,
            'switch reads off while Rusty is backgrounded'
          );

          await page.evaluate(clickExpr('foreground-switch'));
          await page.waitFor(hasClassExpr('foreground-switch', 'pending'), 3000, 'switch goes pending');

          // The device answers with the pre-command state; the switch must not flip on that.
          assert(
            (await page.evaluate(ariaExpr('foreground-switch', 'aria-checked'))) === 'false',
            'switch must not read on until the device confirms'
          );
          assert(mock.foregroundBodies.length === 1, 'expected exactly one POST /api/foreground');
          assert(mock.foregroundBodies[0].body.on === true, 'expected {on:true}');

          await page.waitFor(
            ariaExpr('foreground-switch', 'aria-checked') + " === 'true'",
            6000,
            'switch settles on once the poll confirms'
          );
          assert(
            (await page.evaluate(hasClassExpr('foreground-switch', 'pending'))) === false,
            'pending must clear once confirmed'
          );
          // Coming back must re-arm the lamps, which were inert with no window.
          await page.waitFor(notDisabledExpr('lamp-spotify'), 4000, 'lamps become live again');
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '19. A bring-forward the device silently drops times out',
      async run({ browser }) {
        // Models the OEM builds that block a background activity start without reporting it.
        const mock = createMockServer(
          buildConfig(html, {
            panel: { active: null },
            app: { foreground: false, canBringForward: true },
            foregroundResponder: makeForegroundResponder({ moveAfterMs: -1 })
          })
        );
        const { url } = await mock.start();
        const page = await browser.newPage();
        try {
          await page.navigate(url);
          await page.waitFor(
            ariaExpr('foreground-switch', 'aria-checked') + " === 'false'",
            5000,
            'switch reads off'
          );
          await page.evaluate(clickExpr('foreground-switch'));

          await page.waitFor(
            `document.getElementById('source-error').hidden === false`,
            12000,
            'an unconfirmed bring-forward eventually says so'
          );
          assert(
            (await page.evaluate(ariaExpr('foreground-switch', 'aria-checked'))) === 'false',
            'a timed-out switch must never end up reading on'
          );
          assert(
            (await page.evaluate(hasClassExpr('foreground-switch', 'pending'))) === false,
            'pending must clear on timeout'
          );
        } finally {
          await page.close();
          await mock.stop();
        }
      }
    },

    {
      name: '20. Without the overlay grant the switch is inert in both directions',
      async run({ browser }) {
        for (const foreground of [true, false]) {
          const mock = createMockServer(
            buildConfig(html, {
              panel: foreground ? {} : { active: null },
              app: { foreground, canBringForward: false }
            })
          );
          const { url } = await mock.start();
          const page = await browser.newPage();
          try {
            await page.navigate(url);
            await page.waitFor(
              disabledExpr('foreground-switch'),
              5000,
              'switch is disabled without the grant (foreground=' + foreground + ')'
            );
            assert(
              (await page.evaluate(hiddenExpr('foreground-note'))) === false,
              'the missing-permission note should explain why'
            );

            // Sending Rusty away when it cannot come back would strand a touchless device.
            await page.evaluate(clickExpr('foreground-switch'));
            await sleep(400);
            assert(
              mock.foregroundBodies.length === 0,
              'a disabled switch must not send anything (foreground=' + foreground + ')'
            );
          } finally {
            await page.close();
            await mock.stop();
          }
        }
      }
    }
  ];
}

module.exports = { getScenarios, buildConfig };
