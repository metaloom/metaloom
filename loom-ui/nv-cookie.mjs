import { chromium } from "playwright";
const sleep = ms => new Promise(r => setTimeout(r, ms));
const b = await chromium.launch();
const ctx = await b.newContext({ viewport: { width: 1400, height: 900 } });
const p = await ctx.newPage();
await p.goto("http://localhost:8092/ui/", { waitUntil: "networkidle" });
await p.getByPlaceholder("Username").fill("admin");
await p.getByPlaceholder("Password").fill("finger");
await p.getByRole("button", { name: "Sign in" }).click();
await sleep(2500);
console.log("cookies:", (await ctx.cookies()).map(c => `${c.name} secure=${c.secure} sameSite=${c.sameSite} path=${c.path}`));
const res = await p.evaluate(async () => {
  const r = await fetch("/api/v1/assets/32169f15-7b44-420c-a001-3bca1bb45a57/binary/data", { credentials: "include" });
  return r.status;
});
console.log("fetch with credentials:", res);
const withBearer = await p.evaluate(async () => {
  const t = localStorage.getItem("loom-ui-token") || sessionStorage.getItem("loom-ui-token");
  const keys = Object.keys(localStorage);
  const r = await fetch("/api/v1/assets/32169f15-7b44-420c-a001-3bca1bb45a57/binary/data", { headers: t ? { Authorization: `Bearer ${JSON.parse(t)}` } : {} });
  return { status: r.status, keys };
});
console.log("localStorage keys / bearer fetch:", withBearer);
await b.close();
