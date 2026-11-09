import { chromium, webkit, expect } from "@playwright/test";
import { upsPages } from "../src/ups-model.js";
import { mkdir } from "node:fs/promises";
const browser = await (
  process.env.BROWSER === "webkit" ? webkit : chromium
).launch(
  process.env.BROWSER === "webkit" || process.env.CI
    ? {}
    : { channel: "chrome" },
);
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } }),
  errors = [],
  out = process.env.SCREENSHOT_DIR || "test-results";
await mkdir(out, { recursive: true });
page.on("pageerror", (e) => errors.push(e.message));
const button = (text, scope = page) =>
  scope.locator(".x-button").filter({
    has: page.locator(".x-text-el", {
      hasText: new RegExp("^" + text + "$"),
    }),
  });
async function ready() {
  await expect(page.locator(".native-dialog")).toHaveCSS("opacity", "1");
  await page.waitForTimeout(350);
}
try {
  await page.goto("http://127.0.0.1:5181");
  if (
    !(await page.evaluate(
      async () => (await (await fetch("/api/meta")).json()).demo,
    ))
  )
    throw Error("Demo only");
  await button("填入演示账户").click();
  await button("登录工作空间").click();
  await expect(page.locator(".workspace")).toBeVisible();
  const pages = [
    "overview",
    "battery",
    "power",
    "voltage",
    "energy",
    "history",
    "events",
    "devices",
    "commands",
    "variables",
    "reports",
    "settings",
    "scripts",
    "nut",
    "users",
    "system",
  ];
  for (const name of pages) {
    await page.goto("http://127.0.0.1:5181/#/ups-" + name);
    await page.waitForFunction(
      (expected) =>
        Ext.ComponentQuery.query("panel").some(
          (p) => p.hasCls?.("ups-workspace") && p.getTitle() === expected,
        ),
      upsPages.find((p) => p[0] === name)[1],
    );
    await expect(page.locator(".ups-workspace")).toBeVisible();
    await expect(page.locator(".ups-workspace")).toContainText("更新于");
    if (["overview", "battery"].includes(name))
      await page.screenshot({ path: out + "/ups-" + name + ".png" });
    console.log("PASS page " + name);
  }
  const formCount = await page.evaluate(async () => {
    const { createSchemaForm } = await import("/src/gateway-form.js");
    const catalog = await (await fetch("/api/ups/catalog")).json();
    let count = 0;
    for (const op of catalog.operations) {
      for (const part of ["params", "query", "body"]) {
        const schema = op[part];
        if (!Object.keys(schema.properties || {}).length) continue;
        const form = createSchemaForm(schema, {});
        try {
          if (!form.component) throw Error(op.id);
          count++;
        } finally {
          form.component.destroy();
        }
      }
    }
    return count;
  });
  console.log("PASS native schema forms " + formCount);
  await page.goto("http://127.0.0.1:5181/#/ups-devices");
  await expect(page.locator(".ups-workspace")).toContainText("更新于");
  await button("添加设备").click();
  await ready();
  for (const [name, value] of Object.entries({
    "body.name": "UPS UI test",
    "body.ups_name": "testups",
    "body.host": "192.0.2.10",
    "body.port": "3493",
  }))
    await page.locator('.native-dialog input[name="' + name + '"]').fill(value);
  await page.screenshot({ path: out + "/ups-device-form.png" });
  await button("校验并预览").click();
  await expect(button("确认执行")).toBeVisible();
  await button("确认执行").click();
  await expect(page.locator(".native-dialog")).toContainText("UPS UI test");
  await button("关闭", page.locator(".native-dialog")).click();
  await expect(page.locator(".ups-workspace")).toContainText("UPS UI test");
  await page.goto("http://127.0.0.1:5181/#/ups-users");
  await expect(page.locator(".ups-workspace")).toContainText("更新于");
  await button("添加用户").click();
  await ready();
  await expect(
    page.locator('.native-dialog input[name="body.username"]'),
  ).toBeVisible();
  await button("取消", page.locator(".native-dialog")).click();
  await page.goto("http://127.0.0.1:5181/#/ups-battery");
  await expect(page.locator(".ups-workspace")).toContainText("更新于");
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.locator(".ups-workspace")).toContainText("更新于");
  await page.waitForTimeout(1500);
  await expect
    .poll(() =>
      page.evaluate(() => {
        return Ext.ComponentQuery.query("selectfield")
          .find((x) => x.element.dom.closest(".ups-workspace"))
          .getWidth();
      }),
    )
    .toBe(200);
  await expect
    .poll(() =>
      page.evaluate(() => {
        const p = Ext.ComponentQuery.query("container").find((x) =>
          x.hasCls?.("metrics"),
        );
        return p.getItems().first().getItems().getCount();
      }),
    )
    .toBe(2);
  await expect
    .poll(() =>
      page.evaluate(() => Ext.ComponentQuery.query("cartesian").length),
    )
    .toBeGreaterThan(0);
  await page.waitForFunction(() =>
    [...document.querySelectorAll(".ups-workspace canvas")].some((canvas) => {
      if (!canvas.width || !canvas.height) return false;
      const pixels = canvas
        .getContext("2d")
        .getImageData(0, 0, canvas.width, canvas.height).data;
      for (let i = 0; i < pixels.length; i += 4)
        if (
          pixels[i + 3] > 128 &&
          Math.min(pixels[i], pixels[i + 1], pixels[i + 2]) < 200
        )
          return true;
      return false;
    }),
  );
  await page.screenshot({ path: out + "/ups-mobile.png" });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect
    .poll(() =>
      page.evaluate(() => {
        return Ext.ComponentQuery.query("selectfield")
          .find((x) => x.element.dom.closest(".ups-workspace"))
          .getWidth();
      }),
    )
    .toBe(360);
  await expect
    .poll(() =>
      page.evaluate(() => {
        const p = Ext.ComponentQuery.query("container").find((x) =>
          x.hasCls?.("metrics"),
        );
        return p.getItems().first().getItems().getCount();
      }),
    )
    .toBe(4);
  expect(errors).toEqual([]);
  console.log(
    "PASS charts, all pages, native forms, demo target creation, mobile; no production actions",
  );
} finally {
  if (errors.length) console.error(errors);
  await browser.close();
}
