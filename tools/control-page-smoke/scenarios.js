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
    slideshow: state.slideshow
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

function buildConfig(html, overrides) {
  overrides = overrides || {};
  const state = {
    device: Object.assign({}, DEFAULT_DEVICE, overrides.device),
    screen: Object.assign({}, DEFAULT_SCREEN, overrides.screen),
    volume: Object.assign({}, DEFAULT_VOLUME, overrides.volume),
    playing: Object.assign({}, DEFAULT_PLAYING, overrides.playing),
    slideshow: Object.assign({}, DEFAULT_SLIDESHOW, overrides.slideshow)
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
    filtersPutResponder: overrides.filtersPutResponder || defaultFiltersPutResponder
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

          for (const cardId of ['screen-card', 'volume-card', 'playing-card', 'filters-card']) {
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
        for (const available of [false, true]) {
          const mock = createMockServer(buildConfig(html, { screen: { available } }));
          const { url } = await mock.start();
          const page = await browser.newPage();
          try {
            await page.navigate(url);
            await page.waitFor(notDisabledExpr('filters-save'), 5000, 'filters-save enabled after load');
            const hidden = await page.evaluate(hiddenExpr('screen-availability-note'));
            if (available === false) {
              assert(hidden === false, 'screen.available:false should show the unavailable notice, not hide it');
              const text = await page.evaluate(textExpr('screen-availability-note'));
              assert(text.trim().length > 0, 'unavailable notice should have visible text');
            } else {
              assert(hidden === true, 'screen.available:true should hide the unavailable notice');
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
    }
  ];
}

module.exports = { getScenarios, buildConfig };
