// 原型截图（F4-1）：用 Playwright 打开 lab/app-sim.html，走一遍与 APP 渲染相同的状态，
// 每个状态截 .phone 元素，存到 tools/ui-compare/out/proto/<NN-name>.png。
// Playwright 用全局安装的（npm i -g playwright），不是仓库依赖。
// 用法：node tools/ui-compare/proto-shots.mjs
import { createRequire } from 'node:module';
import { execSync } from 'node:child_process';
import { mkdirSync, readdirSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, '../..');
const out = join(here, 'out', 'proto');
const require = createRequire(join(execSync('npm root -g').toString().trim(), 'x.js'));
const { chromium } = require('playwright');

mkdirSync(out, { recursive: true });
for (const f of readdirSync(out)) if (f.endsWith('.png')) rmSync(join(out, f));

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 1000 }, deviceScaleFactor: 2 });
await page.goto(pathToFileURL(join(repo, 'lab', 'app-sim.html')).href);
await page.waitForSelector('#phone');

const phone = page.locator('#phone');
const wait = (ms) => page.waitForTimeout(ms);
const shot = async (name) => {
  await wait(450); // 弹层滑入、按钮状态变化走完
  await phone.screenshot({ path: join(out, `${name}.png`), animations: 'disabled' });
  console.log('shot', name);
};
const act = (a, text) => {
  const l = page.locator(`#phone [data-act=${a}]`);
  return (text ? l.filter({ hasText: text }) : l).first();
};
const closeSheet = async () => {
  await page.evaluate(() => document.querySelector('#phone .scrim').click());
  await wait(450);
};

// 登录
await shot('01-login-empty');
await page.fill('#lp', '13900001111');
await page.fill('#lw', 'egg123');
await shot('02-login-filled');
await act('login').click();
await page.waitForSelector('#phone [data-step=farmer]');
await wait(300);

// 建单
await shot('03-create-empty');
await page.fill('#q', '1234');
await page.locator('#q').dispatchEvent('input');
await shot('04-create-1234');
await act('farmer', '李四').click();
await shot('05-create-lisi');
await act('warehouse', '1号仓库').click();
await shot('06-create-ready');
await act('start').click();
await page.waitForSelector('#phone [data-act=finish]');
await wait(1800); // 秤自动连上

// 称重：先选皮重
await shot('07-weigh-tare');
await act('tareDigital', '2号包装箱').click();
await page.selectOption('#weightSource', 'case');
await wait(300);
await page.locator('[data-hw=next]').click();
await wait(250);
await shot('08-weigh-settling');
await wait(1800);
await shot('09-weigh-recorded');
for (let i = 1; i < 10; i++) {
  await page.locator('[data-hw=remove]').click();
  await wait(1800);
  await page.locator('[data-hw=next]').click();
  await wait(1800);
}
await shot('10-weigh-10boxes');

// 弹层
await act('openLog', '全部').click();
await shot('11-ledger-sheet');
await closeSheet();
await act('openBox').click();
await shot('12-box-sheet');
await act('manualEdit').click();
await shot('13-box-manual');
await closeSheet();
await page.locator('#phone #chipScale').click();
await shot('14-devices-sheet');
await closeSheet();
await act('leave').click();
await shot('15-leave-dialog');
await closeSheet();

// 汇总、规则、完成
await act('finish').click();
await page.waitForSelector('#phone [data-act=back]');
await shot('16-summary');
await act('openRule').click();
await shot('17-rule-sheet');
await closeSheet();
await act('submit', '提交单据').click();
await page.waitForSelector('#phone [data-act=again]', { timeout: 10000 });
await wait(900);
await shot('18-done');

await browser.close();
