// Renders every mockup screen to PNG at 2x (≈ BOOX Note Air panel resolution).
// Usage: node docs/mockups/render.mjs   (needs the `playwright` package resolvable from here)
import { chromium } from "playwright";
import { fileURLToPath, pathToFileURL } from "node:url";
import path from "node:path";

const dir = path.dirname(fileURLToPath(import.meta.url));
const url = pathToFileURL(path.join(dir, "mockup.html")).href;
const screens = ["closed", "panel", "notes-picker", "remove", "settings-1", "settings-2", "settings-3", "add-note", "app-picker"];

const browser = await chromium.launch();
const page = await browser.newPage({ deviceScaleFactor: 2, viewport: { width: 1200, height: 1100 } });
for (const [i, s] of screens.entries()) {
  await page.goto(`${url}#${s}`);
  await page.reload();
  await page.evaluate(() => document.fonts.ready);
  const file = path.join(dir, `${String(i + 1).padStart(2, "0")}-${s}.png`);
  await page.locator(".stage").screenshot({ path: file });
  console.log(file);
}
await browser.close();
