import { chromium, webkit, expect } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import { resolve } from "node:path";

// This suite intentionally refuses to write against a production backend.
const url = process.env.PREVIEW_URL || "http://127.0.0.1:5181";
const output = resolve(process.env.SCREENSHOT_DIR || "test-results");
const browser = await (
  process.env.BROWSER === "webkit" ? webkit : chromium
).launch(process.env.BROWSER === "webkit" ? {} : { channel: "chrome" });
const page = await browser.newPage({ viewport: { width: 1500, height: 1000 } });
page.setDefaultTimeout(10000);
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
const button = (name, scope = page) =>
  scope.locator(".x-button").filter({
    has: page.locator(".x-text-el", {
      hasText: new RegExp("^" + name + "$"),
    }),
  });
const field = (name) =>
  dialog()
    .locator(".native-field")
    .filter({ has: page.locator(".x-label-el").filter({ hasText: name }) })
    .filter({ has: page.getByText(name, { exact: true }) })
    .locator("input:not([type=hidden]),textarea")
    .first();
const dialog = () => page.locator(".native-dialog");
const row = (text) =>
  page
    .locator(".workspace .native-grid .x-gridrow")
    .filter({ hasText: text })
    .first();
async function navigate(route) {
  await page.evaluate((r) => {
    location.hash = "#/" + r;
  }, route);
  await expect(page.locator(".table-caption")).not.toContainText("正在读取");
  await page.waitForTimeout(300);
}
async function commit() {
  // Material's native dialog transition briefly suppresses pointer events.
  await expect(dialog()).toHaveCSS("opacity", "1");
  await page.waitForTimeout(250);
  await button("校验并预览", dialog()).click();
  await expect(dialog().locator(".error")).toHaveCount(0);
  await button("确认保存", dialog()).click();
  await expect(dialog()).toHaveCount(0);
}
async function removeSelected() {
  await button("删除", page.locator(".tools-actions")).click();
  await commit();
}
try {
  await mkdir(output, { recursive: true });
  await page.goto(url);
  const meta = await page.evaluate(async () =>
    (await fetch("/api/meta")).json(),
  );
  if (!meta.demo)
    throw Error(
      "This test requires an isolated demo backend. No writes performed.",
    );
  await button("填入演示账户").click();
  await button("登录工作空间").click();
  await expect(page.locator(".workspace > .x-panelheader")).toContainText(
    "网络概览",
  );
  await expect(page.locator(".x-gridrow").first()).toBeVisible();
  await page.screenshot({
    path: output + "/overview-light.png",
    fullPage: true,
  });
  console.log("PASS login and native Ext JS grid");

  await navigate("records");
  if (await row("extjs-preview").count()) {
    await row("extjs-preview").click();
    await removeSelected();
  }
  await button("添加记录").click();
  await field("名称").fill("extjs-preview");
  await field("记录值（每行一个）").fill("192.0.2.79");
  await commit();
  await row("extjs-preview").click();
  await button("编辑", page.locator(".tools-actions")).click();
  await field("记录值（每行一个）").fill("192.0.2.80");
  await commit();
  await expect(row("extjs-preview")).toContainText("192.0.2.80");
  await row("extjs-preview").click();
  await removeSelected();
  await expect(row("extjs-preview")).toHaveCount(0);
  console.log("PASS DNS create, edit, delete with revision checks");

  await navigate("dhcp-reservations");
  if (await row("extjs-preview").count()) {
    await row("extjs-preview").click();
    await removeSelected();
  }
  await button("添加保留地址").click();
  await expect(dialog()).toHaveCSS("opacity", "1");
  const editorBounds = await dialog().boundingBox();
  expect(editorBounds.y).toBeGreaterThanOrEqual(0);
  expect(editorBounds.y + editorBounds.height).toBeLessThanOrEqual(1000);
  await page.screenshot({ path: output + "/editor-light.png", fullPage: true });
  await field("MAC 地址").fill("02:11:22:33:44:79");
  await field("固定 IP").fill("198.51.100.79");
  await field("主机名").fill("extjs-preview");
  await field("网关").fill("198.51.100.1");
  await field("DNS 服务器").fill("192.0.2.10,192.0.2.11");
  await commit();
  await expect(row("extjs-preview")).toContainText("192.0.2.11");
  await page.screenshot({
    path: output + "/reservations-light.png",
    fullPage: true,
  });
  await row("extjs-preview").click();
  await button("编辑", page.locator(".tools-actions")).click();
  await expect(field("DNS 服务器")).toHaveValue(
    /192\.0\.2\.10,\s*192\.0\.2\.11/,
  );
  await field("备注").fill("Ext JS browser verification");
  await commit();
  await row("extjs-preview").click();
  await removeSelected();
  console.log("PASS reservation create, edit, delete and DNS/router options");

  await navigate("gateway-routes");
  if (await row("extjs-preview").count()) {
    await row("extjs-preview").click();
    await removeSelected();
  }
  await button("添加配置").click();
  await field("配置内容").fill(
    JSON.stringify(
      {
        id: "extjs-preview",
        value: {
          name: "Ext JS preview",
          uri: "/extjs-preview",
          status: 0,
          upstream: { type: "roundrobin", nodes: { "192.0.2.79:8080": 1 } },
        },
      },
      null,
      2,
    ),
  );
  await commit();
  await row("extjs-preview").click();
  await removeSelected();
  console.log("PASS Application Gateway validated create/delete");

  await navigate("gateway-credentials");
  await page.locator(".filters .native-field input").fill("automation");
  await expect(button("添加配置")).not.toHaveClass(/x-disabled/);
  console.log("PASS consumer-scoped credentials page");

  await navigate("forwarding");
  if (await row("extjs-preview.example.net").count()) {
    await row("extjs-preview.example.net").click();
    await removeSelected();
  }
  await button("添加规则").click();
  await field("域名").fill("extjs-preview.example.net");
  await field("上游地址（每行一个）").fill("192.0.2.53");
  await field("TLS 校验域名").fill("dns.example.net");
  await commit();
  await row("extjs-preview.example.net").click();
  await removeSelected();
  await button("变更备份").click();
  await expect(dialog().locator(".x-gridrow").first()).toBeVisible();
  await dialog().locator(".x-tool-type-close").click();
  console.log(
    "PASS conditional forwarding create/delete and native backup grid",
  );

  await navigate("bind-access");
  if (await row("extjs-preview-acl").count()) {
    await row("extjs-preview-acl").click();
    await removeSelected();
  }
  await button("添加配置").click();
  await field("原生配置").fill('acl "extjs-preview-acl" { 192.0.2.0/24; };');
  await commit();
  await row("extjs-preview-acl").click();
  await removeSelected();
  console.log(
    "PASS native DNS configuration create/delete with dual-node preview",
  );

  for (const route of [
    "dhcp-subnets",
    "dhcp-leases",
    "dhcp-ha",
    "forwarding",
    "bind-zones",
    "bind-resolver",
    "bind-access",
    "bind-dnssec",
    "bind-transport",
    "bind-logging",
    "bind-operations",
    "bind-advanced",
    "gateway-overview",
    "gateway-services",
    "gateway-upstreams",
    "gateway-consumers",
    "gateway-ssls",
    "gateway-consumer_groups",
    "gateway-global_rules",
    "gateway-plugin_configs",
    "gateway-plugin_metadata",
    "gateway-stream_routes",
    "gateway-protos",
    "gateway-secrets",
    "gateway-plugins",
    "gateway-backups",
    "gateway-runtime",
    "audit",
  ]) {
    await navigate(route);
    const bounds = await page.locator(".workspace .native-grid").boundingBox();
    expect(
      bounds.height,
      route + " has a usable grid viewport",
    ).toBeGreaterThan(200);
    expect(
      bounds.y + bounds.height,
      route + " stays inside the viewport",
    ).toBeLessThanOrEqual(1000);
    if (await page.locator(".workspace .error").count())
      throw Error(
        route + ": " + (await page.locator(".workspace .error").innerText()),
      );
  }
  console.log("PASS all remaining data pages load without runtime errors");

  await navigate("dhcp-reservations");
  await page.emulateMedia({ colorScheme: "dark" });
  await page.evaluate(() => localStorage.setItem("infrapilot-theme", "dark"));
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await expect(page.locator(".app-header .x-selectfield")).toHaveCount(0);
  expect(await page.evaluate(() => localStorage.getItem("infrapilot-theme"))).toBeNull();
  await expect(page.locator(".workspace > .x-panelheader")).toContainText(
    "保留地址",
  );
  await page.setViewportSize({ width: 390, height: 844 });
  await page.waitForTimeout(250);
  const width = await page.evaluate(() => ({
    width: innerWidth,
    content: document.documentElement.scrollWidth,
  }));
  expect(width.content).toBeLessThanOrEqual(width.width + 1);
  await page.locator(".app-header .menu-trigger").click();
  await expect(page.locator(".mobile-sidebar")).toBeVisible();
  await page.screenshot({
    path: output + "/mobile-navigation-light.png",
    fullPage: true,
  });
  await page
    .locator(".x-mask:visible")
    .last()
    .click({ position: { x: 370, y: 100 } });
  await expect(page.locator(".mobile-sidebar")).toHaveCount(0);
  await page.screenshot({ path: output + "/mobile-light.png", fullPage: true });
  await row("printer").click();
  await button("更多操作").click();
  await expect(page.locator(".actions-menu")).toBeVisible();
  await page.screenshot({
    path: output + "/mobile-actions-light.png",
    fullPage: true,
  });
  await page
    .locator(".actions-menu .x-menuitem")
    .filter({ hasText: /^编辑$/ })
    .click();
  await expect(dialog()).toBeVisible();
  await expect(dialog()).toHaveCSS("opacity", "1");
  const mobileEditor = await dialog().boundingBox();
  expect(mobileEditor.x).toBe(0);
  expect(mobileEditor.y).toBe(0);
  expect(mobileEditor.width).toBe(390);
  expect(mobileEditor.height).toBe(844);
  await expect(field("主机名")).toHaveValue("printer");
  await page.screenshot({
    path: output + "/mobile-editor-light.png",
    fullPage: true,
  });
  await button("取消", dialog()).click();
  await expect(dialog()).toHaveCount(0);
  await page.locator(".menu-trigger").click();
  await page
    .locator(".mobile-sidebar .native-navigation .x-gridrow")
    .filter({ hasText: "条件转发" })
    .click();
  await expect(page.locator(".workspace > .x-panelheader")).toHaveText(
    "条件转发",
  );
  await expect(page.locator(".mobile-sidebar")).toHaveCount(0);
  console.log("PASS fixed light theme with old dark preference and mobile navigation");
  console.log("PASS native menu action and full-screen mobile editor");
  expect(errors).toEqual([]);
  console.log("PASS zero uncaught browser errors");
} catch (error) {
  await page.screenshot({ path: output + "/failure.png", fullPage: true });
  console.error("FAIL", error.message.slice(0, 2000));
  console.error("BROWSER ERRORS", errors);
  console.error(
    "ALERT",
    (await page.locator(".error").allTextContents()).join("\n").slice(0, 1000),
  );
  process.exitCode = 1;
} finally {
  await browser.close();
}
