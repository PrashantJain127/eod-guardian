// End-to-end UI demonstration with Playwright: drives the real dashboard through
// Scenario 1 (successful EOD) and Scenario 2 -> 4 -> 5 (failure, investigation, maker-checker patch, resume).
const { chromium } = require('playwright');
const BASE = process.env.BASE || 'http://localhost:8080';
const PW = process.env.EOD_DEMO_PASSWORD || 'Guardian@2026';
const OUT = process.argv[2] || 'shots';
const fs = require('fs');
fs.mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let n = 0;
async function shot(page, name, full = false) {
  const f = `${OUT}/${String(++n).padStart(2, '0')}-${name}.png`;
  await page.screenshot({ path: f, fullPage: full });
  console.log('saved', f);
}
async function login(page, user) {
  await page.goto(BASE + '/#monitor');
  if (await page.locator('.who').count()) {
    await page.click('text=Switch user');
  }
  await page.waitForSelector('.login-form select');
  await page.waitForFunction(() => document.querySelectorAll('.login-form option').length > 0);
  await page.selectOption('.login-form select', user);
  await page.fill('.login-form input[type=password]', PW);
  await page.click('.login-form button');
  await page.waitForSelector('.topbar');
  await page.waitForSelector('.kpis', { timeout: 15000 });
}
async function nav(page, label) {
  await page.click(`.nav-item:has-text("${label}")`);
  await sleep(700);
}
async function waitText(page, sel, text, timeout = 120000) {
  await page.waitForFunction(([s, t]) => [...document.querySelectorAll(s)].some((e) => e.textContent.includes(t)), [sel, text], { timeout });
}

(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  page.on('pageerror', (e) => console.log('PAGE ERROR', e.message));

  // ---------------- login screen
  await page.goto(BASE);
  await page.waitForSelector('.login-form');
  await shot(page, 'login');

  // ---------------- Scenario 1: successful EOD
  await login(page, 'meera.operator');
  await page.selectOption('.card select', 'SUCCESS');
  await page.click('button:has-text("Start EOD")');
  await sleep(9000);
  await shot(page, 'scenario1-running');
  await waitText(page, '.kpi', 'COMPLETED', 120000);
  await sleep(1500);
  await shot(page, 'scenario1-completed');

  // ---------------- Scenario 2: account exception
  await page.selectOption('.card select', 'ACCOUNT_EXCEPTION');
  await page.click('button:has-text("Start EOD")');
  await waitText(page, '.kpi', 'HALTED', 120000);
  await sleep(2500);
  await shot(page, 'scenario2-failure-detected');
  await nav(page, 'Investigation');
  await waitText(page, '.badge', 'PATCH RECOMMENDED', 60000);
  await sleep(1500);
  await shot(page, 'scenario2-investigation', true);

  // ---------------- Scenario 4: maker -> checker -> operator
  await login(page, 'priya.maker');
  await nav(page, 'Data Patches');
  await page.waitForSelector('.maker-checker');
  await shot(page, 'scenario4-patch-recommended', true);
  await page.fill('textarea', 'Reviewed evidence; RM confirmed customer requested CASA 0010045679 for EMIs');
  await page.click('button:has-text("Submit for approval")');
  await waitText(page, '.hero .badge', 'PENDING APPROVAL', 10000);

  await login(page, 'arjun.checker');
  await nav(page, 'Data Patches');
  await page.waitForSelector('.maker-checker');
  await page.fill('textarea', 'Verified before/after values and pre-checks; approved per INC-2291 precedent');
  await page.click('button:has-text("Approve")');
  await waitText(page, '.hero .badge', 'APPROVED', 10000);
  await sleep(800);
  await shot(page, 'scenario4-approved');

  await login(page, 'meera.operator');
  await nav(page, 'Data Patches');
  await page.waitForSelector('.maker-checker');
  await page.click('button:has-text("Execute patch")');
  await waitText(page, '.hero .badge', 'EXECUTED', 15000);
  await sleep(800);
  await shot(page, 'scenario4-executed', true);

  // ---------------- Scenario 5: recovery
  await page.click('button:has-text("Go to recovery console")');
  await page.waitForSelector('.checks');
  await page.fill('.field input', 'RESUME CLBATCH');
  await page.waitForSelector('button:has-text("Resume CLBATCH"):enabled', { timeout: 20000 });
  await sleep(500);
  await shot(page, 'scenario5-recovery-ready', true);
  await page.click('button:has-text("Resume CLBATCH")');
  await sleep(4000);
  await shot(page, 'scenario5-resumed-running', true);
  await waitText(page, '.alert', 'COMPLETED', 120000);
  await sleep(1500);
  await shot(page, 'scenario5-eod-completed', true);
  await nav(page, 'EOD Monitor');
  await shot(page, 'scenario5-monitor-completed');

  // ---------------- audit + performance
  await login(page, 'sanjay.auditor');
  await nav(page, 'Audit Trail');
  await page.fill('input[placeholder="PATCH-0001"]', 'PATCH-0001');
  await page.click('button:has-text("Search")');
  await sleep(1200);
  await page.click('td:has-text("PATCH_EXECUTED")');
  await sleep(600);
  await shot(page, 'audit-trail', true);
  await nav(page, 'Performance');
  await sleep(1500);
  await shot(page, 'performance', true);
  await nav(page, 'Agents');
  await sleep(1000);
  await shot(page, 'architecture', true);
  await browser.close();
})().catch((e) => { console.error(e); process.exit(1); });
