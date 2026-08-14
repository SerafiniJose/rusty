#!/usr/bin/env node
'use strict';

/**
 * Headless-Chrome smoke harness for app/src/main/assets/control.html (Task 7b).
 *
 * Run manually — see README.md in this directory. NOT wired into ./gradlew, by design: CI builds
 * this repo offline with no browser installed, so a Gradle test task that needs Chrome would
 * break the build for everyone.
 *
 * Exit codes:
 *   0  every scenario passed
 *   1  at least one scenario failed
 *   2  SKIPPED — no Chrome/Chromium binary was found (never a false green, never a crash)
 */

const fs = require('fs');
const path = require('path');

const { findChrome, launchBrowser } = require('./chrome-driver');
const { getScenarios } = require('./scenarios');

const DEFAULT_HTML_PATH = path.resolve(__dirname, '..', '..', 'app', 'src', 'main', 'assets', 'control.html');

async function main() {
  const htmlPath = process.env.CONTROL_SMOKE_HTML || DEFAULT_HTML_PATH;

  if (!fs.existsSync(htmlPath)) {
    console.error('control-page-smoke: cannot find the page to test at ' + htmlPath);
    process.exit(1);
  }
  const html = fs.readFileSync(htmlPath, 'utf8');

  const chromePath = findChrome();
  if (!chromePath) {
    console.log(
      'SKIPPED: no Chrome found at $CONTROL_SMOKE_CHROME, on $PATH, or in the usual dev-tool ' +
        'caches (~/.cache/ms-playwright, ~/.cache/puppeteer, ~/.agent-browser). Install one or ' +
        'set CONTROL_SMOKE_CHROME=/path/to/chrome and re-run.'
    );
    process.exit(2);
  }
  console.log('Using Chrome at ' + chromePath);
  console.log('Testing ' + htmlPath);
  console.log('');

  // Assign `browser` as soon as launchBrowser() resolves (Chrome is spawned at that point) so
  // that if the subsequent WebSocket handshake fails in .ready(), the catch block below still
  // has a handle to close it — otherwise a spawned-but-unreachable Chrome process and its temp
  // user-data-dir would leak with no reference left to clean them up.
  let browser;
  try {
    browser = await launchBrowser(chromePath);
    await browser.ready();
  } catch (err) {
    if (browser) await browser.close();
    console.error('control-page-smoke: failed to start Chrome:');
    console.error(err && err.stack ? err.stack : err);
    process.exit(1);
  }

  const scenarios = getScenarios(html);
  let failures = 0;

  try {
    for (const scenario of scenarios) {
      const start = Date.now();
      try {
        await scenario.run({ browser });
        console.log('PASS  ' + scenario.name + '  (' + (Date.now() - start) + 'ms)');
      } catch (err) {
        failures++;
        console.log('FAIL  ' + scenario.name + '  (' + (Date.now() - start) + 'ms)');
        console.log('      ' + (err && err.stack ? err.stack.split('\n').join('\n      ') : String(err)));
      }
    }
  } finally {
    await browser.close();
  }

  console.log('');
  console.log((scenarios.length - failures) + '/' + scenarios.length + ' checks passed.');
  process.exit(failures > 0 ? 1 : 0);
}

main().catch((err) => {
  console.error('control-page-smoke: unexpected harness error:');
  console.error(err && err.stack ? err.stack : err);
  process.exit(1);
});
