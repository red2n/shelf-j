// REPL driver for the Shelf-J web UI (storefront + POS + admin + platform
// console), driven against the already-running `shelfj-web` Docker container.
// Designed for agents: run it directly (pipe a heredoc script to stdin) or
// wrap it in tmux and `send-keys` one command at a time for iterative work.
//
// Why this exists instead of `chromium-cli`: this container doesn't have
// chromium-cli installed. This is the fallback the run-skill-generator
// prescribes — a hand-rolled Playwright-core driver pointed at the system
// Chrome (`/usr/bin/google-chrome`), so no ~300MB Chromium download is needed.
//
// IMPORTANT — this app is CANVAS-RENDERED (Flutter web, CanvasKit), not DOM
// content. There are ZERO real <input>/<button> elements to select by CSS —
// `document.querySelectorAll('input,textarea')` returns 0 even on a page full
// of visible text fields. So there is no `click-text` / `fill` command here
// the way chromium-cli or a DOM-based driver would have one. Every `click` is
// a raw (x, y) coordinate against the last screenshot. Re-derive coordinates
// from a fresh `ss` whenever you land on a screen you haven't clicked before.

import { chromium } from 'playwright-core';
import * as readline from 'node:readline';
import * as fs from 'node:fs';
import * as path from 'node:path';

const BASE_URL = process.env.SHELFJ_WEB_URL || 'http://localhost:8088';
const SHOT_DIR = process.env.SCREENSHOT_DIR || '/tmp/shelfj-shots';
fs.mkdirSync(SHOT_DIR, { recursive: true });

let browser = null;
let page = null;
const consoleErrors = [];

const COMMANDS = {
  async launch() {
    if (browser) return console.log('already launched');
    browser = await chromium.launch({
      executablePath: '/usr/bin/google-chrome',
      args: ['--no-sandbox', '--disable-gpu', '--headless=new'],
      timeout: 30_000,
    });
    page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
    page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
    page.on('pageerror', (e) => consoleErrors.push('pageerror: ' + e.message));
    console.log('launched. base URL:', BASE_URL);
  },

  // nav <hashPath>  e.g.  nav #/platform/login   or   nav #/store/products
  async nav(hashPath) {
    if (!page) return console.log('ERROR: launch first');
    const url = BASE_URL + '/' + (hashPath || '');
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 20_000 });
    // Flutter removes #loading on window 'flutter-first-frame' (see web/index.html).
    // This is the only reliable "app is actually rendered" signal — don't use
    // waitUntil:'load'/'networkidle', the engine keeps background connections open.
    await page.waitForSelector('#loading', { state: 'detached', timeout: 30_000 })
      .catch(() => console.log('WARN: #loading never detached — app may not have booted'));
    await page.waitForTimeout(500); // first-frame fires before the frame is actually painted
    console.log('nav →', url, ' hash:', await page.evaluate(() => location.hash));
  },

  // click <x> <y>  — coordinate click. See file header: no CSS selectors work here.
  async click(x, y) {
    if (!page) return console.log('ERROR: launch first');
    await page.mouse.click(Number(x), Number(y));
    console.log('click', x, y);
  },

  async type(text) {
    if (!page) return console.log('ERROR: launch first');
    await page.keyboard.type(text, { delay: 15 });
    console.log('typed', JSON.stringify(text));
  },

  async press(key) {
    if (!page) return console.log('ERROR: launch first');
    await page.keyboard.press(key);
    console.log('pressed', key);
  },

  async wait(ms) {
    await new Promise((r) => setTimeout(r, Number(ms) || 1000));
  },

  async ss(name) {
    if (!page) return console.log('ERROR: launch first');
    const f = path.join(SHOT_DIR, (name || `ss-${Date.now()}`) + '.png');
    await page.screenshot({ path: f });
    console.log('screenshot:', f);
  },

  async url() {
    if (!page) return console.log('ERROR: launch first');
    console.log(await page.evaluate(() => location.href));
  },

  async console() {
    console.log(consoleErrors.length ? consoleErrors.join('\n') : '(no console errors captured)');
  },

  // login — convenience: the full platform-admin login sequence, using the
  // bootstrap credentials from .env (PLATFORM_ADMIN_EMAIL/PASSWORD). Verified
  // coordinates for the /#/platform/login screen at the 1280x800 viewport above.
  async login() {
    if (!page) return console.log('ERROR: launch first');
    await COMMANDS.nav('#/platform/login');
    await page.mouse.click(640, 414); // Email field
    await page.keyboard.type(process.env.PLATFORM_ADMIN_EMAIL || 'admin@shelf-j.dev', { delay: 15 });
    await page.mouse.click(640, 478); // Password field
    await page.keyboard.type(process.env.PLATFORM_ADMIN_PASSWORD || '', { delay: 15 });
    await page.mouse.click(640, 542); // Sign in button
    await page.waitForTimeout(2000);
    console.log('login → hash:', await page.evaluate(() => location.hash));
  },

  async quit() {
    if (browser) await browser.close();
    process.exit(0);
  },
};
COMMANDS.exit = COMMANDS.quit;

console.log('Shelf-J web driver. Commands:', Object.keys(COMMANDS).join(', '));
console.log('Start with: launch');

// IMPORTANT: readline's 'line' event fires for every piped line as fast as
// stdin delivers them — it does NOT wait for an async handler to resolve
// before firing the next one. With a heredoc (all lines available instantly),
// that races every command against `launch` and against each other. Every
// command below MUST be chained onto `queue`, not just awaited inside the
// handler, or you get exactly what this looked like on first try: every
// command after `launch` failing with "launch first" because they all ran
// before Chromium had actually started.
let queue = Promise.resolve();

const rl = readline.createInterface({ input: process.stdin, terminal: false });
rl.on('line', (line) => {
  const trimmed = line.trim();
  if (!trimmed || trimmed.startsWith('#')) return;
  queue = queue.then(async () => {
    const [cmd, ...rest] = trimmed.split(/\s+/);
    const arg = trimmed.slice(cmd.length).trim();
    const fn = COMMANDS[cmd];
    if (!fn) { console.log('unknown command:', cmd); return; }
    try {
      // Most commands take one whitespace-joined arg (text to type, a name);
      // click takes two numeric args.
      if (cmd === 'click') await fn(rest[0], rest[1]);
      else await fn(arg);
    } catch (e) {
      console.log('ERROR:', e.message);
    }
  });
});
rl.on('close', async () => { await queue; if (browser) await browser.close(); });
