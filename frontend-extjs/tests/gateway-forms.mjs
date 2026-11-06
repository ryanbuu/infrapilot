import { chromium, webkit, expect } from "@playwright/test";
import { mkdir, readFile } from "node:fs/promises";
const browser = await (
  process.env.BROWSER === "webkit" ? webkit : chromium
).launch(
  process.env.BROWSER === "webkit" || process.env.CI
    ? {}
    : { channel: "chrome" },
);
const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
const output = process.env.SCREENSHOT_DIR || "test-results";
await mkdir(output, { recursive: true });
const errors = [];
page.on("pageerror", (e) => errors.push(e.message));
const button = (text, scope = page) =>
  scope.locator(".x-button").filter({
    has: page.locator(".x-text-el", {
      hasText: new RegExp("^" + text + "$"),
    }),
  });
const field = (name) =>
  page.locator(
    '.native-dialog input[name="' +
      name +
      '"],.native-dialog textarea[name="' +
      name +
      '"]',
  );
async function tab(text) {
  await page.locator(".native-dialog .x-tab").filter({ hasText: text }).click();
}
async function ready() {
  await expect(page.locator(".native-dialog")).toHaveCSS("opacity", "1");
  await page.waitForTimeout(300);
}
try {
  await page.goto(process.env.PREVIEW_URL || "http://127.0.0.1:5181");
  if (
    !(await page.evaluate(
      async () => (await (await fetch("/api/meta")).json()).demo,
    ))
  )
    throw Error("Demo only");
  await button("填入演示账户").click();
  await button("登录工作空间").click();
  await expect(page.locator(".workspace")).toBeVisible();
  if (process.env.LIVE_FIXTURE) {
    const fixture = JSON.parse(
      await readFile(process.env.LIVE_FIXTURE, "utf8"),
    );
    const count = await page.evaluate(async (data) => {
      const { createGatewayForm } = await import("/src/gateway-form.js");
      const canonical = (v) =>
        JSON.stringify(v, (_, val) =>
          val && typeof val === "object" && !Array.isArray(val)
            ? Object.fromEntries(
                Object.entries(val).sort(([a], [b]) => a.localeCompare(b)),
              )
            : val,
        );
      let count = 0;
      for (const [kind, listing] of Object.entries(data.resources || data))
        for (const item of listing.items) {
          const form = createGatewayForm({
            kind,
            item,
            pluginSchemas: data.pluginSchemas || {},
            loadPluginSchema: async () => ({}),
          });
          try {
            if (canonical(form.read().value) !== canonical(item.value))
              throw Error(kind + " " + item.id + ": configuration changed");
            count++;
          } finally {
            form.component.destroy();
          }
        }
      return count;
    }, fixture);
    console.log("PASS " + count + " redacted live resources preserved locally");
  }
  const results = await page.evaluate(async () => {
    const { createGatewayForm } = await import("/src/gateway-form.js");
    const MASK = "__INFRAPILOT_REDACTED__";
    const upstream = {
      name: "backend",
      type: "roundrobin",
      nodes: { "192.0.2.8:8080": 0 },
      timeout: { connect: 1, send: 2, read: 3 },
      retries: 0,
      tls: { client_key: MASK },
      custom: { enabled: false, port: 0 },
    };
    const fixtures = {
      upstreams: upstream,
      routes: {
        uri: "/test",
        status: 0,
        upstream,
        plugins: { "key-auth": { header: "apikey", hide_credentials: false } },
        vars: [["arg_id", "==", "1"]],
      },
      services: { name: "svc", upstream_id: "1" },
      consumers: {
        username: "alice",
        plugins: { "key-auth": { key: MASK } },
        labels: { team: "infra" },
      },
      credentials: { plugins: { "key-auth": { key: MASK } } },
      ssls: {
        cert: "x".repeat(128),
        key: MASK,
        keys: MASK,
        snis: ["example.org"],
        status: 1,
      },
      consumer_groups: { desc: "group", plugins: {} },
      global_rules: { plugins: { cors: { allow_origins: "*" } } },
      plugin_configs: { name: "plugins", plugins: {} },
      plugin_metadata: { log_format: { host: "$host", status: "$status" } },
      stream_routes: { server_port: 9000, upstream },
      protos: { content: 'syntax = "proto3";' },
      secrets: { uri: "https://vault.example.org", token: MASK, prefix: "kv" },
    };
    const canonical = (v) =>
      JSON.stringify(v, (k, val) =>
        val && typeof val === "object" && !Array.isArray(val)
          ? Object.fromEntries(
              Object.entries(val).sort(([a], [b]) => a.localeCompare(b)),
            )
          : val,
      );
    const results = [];
    for (const [kind, value] of Object.entries(fixtures)) {
      const f = createGatewayForm({
        kind,
        item: { id: "test", value },
        loadPluginSchema: async () => ({}),
      });
      try {
        const actual = f.read().value;
        if (canonical(actual) !== canonical(value))
          throw Error("round-trip changed configuration");
        results.push(kind + ": preserved");
      } finally {
        f.component.destroy();
      }
    }
    const f = createGatewayForm({
      kind: "upstreams",
      item: {
        id: "test",
        value: {
          ...upstream,
          nodes: [
            {
              host: "192.0.2.1",
              port: 8080,
              weight: 0,
              priority: 0,
              metadata: { zone: "a" },
            },
          ],
        },
      },
    });
    f.component
      .query("field")
      .find((x) => x.getName() === "name")
      .setValue("renamed");
    const actual = f.read().value;
    if (
      actual.name !== "renamed" ||
      actual.nodes[0].weight !== 0 ||
      actual.nodes[0].metadata.zone !== "a" ||
      actual.tls.client_key !== MASK
    )
      throw Error("edit corrupted nested values");
    f.component.destroy();
    results.push("array nodes, zero, name edit, masked key: preserved");
    return results;
  });
  console.log(results.join("\n"));
  await page.evaluate(() => (location.hash = "#/gateway-upstreams"));
  await page.waitForTimeout(500);
  await button("添加配置").click();
  await ready();
  await field("resourceId").fill("form-ui-test");
  await field("name").fill("Native form upstream");
  await tab("转发目标");
  await button("添加节点").click();
  await field("nodes.0.host").fill("192.0.2.79");
  await field("nodes.0.port").fill("8080");
  await field("nodes.0.weight").fill("1");
  await page.waitForTimeout(600);
  await page.screenshot({ path: output + "/upstream-nodes.png" });
  await tab("连接与健康检查");
  // Optional groups use native checkboxes. Enable timeout and fill numeric fields.
  await page.evaluate(() => {
    Ext.ComponentQuery.query("field")
      .find((f) => f.getName() === "timeout.enabled")
      .setChecked(true);
  });
  await field("timeout.connect").fill("1");
  await field("timeout.send").fill("2");
  await field("timeout.read").fill("3");
  const preview = page.waitForRequest((r) =>
    r.url().endsWith("/gateway/resources/upstreams/preview"),
  );
  await button("校验并预览").click();
  const req = await preview;
  const payload = req.postDataJSON();
  expect(payload.value.nodes).toEqual([
    { host: "192.0.2.79", port: 8080, weight: 1 },
  ]);
  expect(payload.value.timeout).toEqual({ connect: 1, send: 2, read: 3 });
  await expect(button("确认保存")).toBeVisible();
  await button("确认保存").click();
  await expect(page.locator(".native-dialog")).toHaveCount(0);
  const row = page
    .locator(".workspace .x-gridrow")
    .filter({ hasText: "form-ui-test" });
  await row.click();
  await button("编辑", page.locator(".tools-actions")).click();
  await ready();
  await field("name").fill("Updated upstream");
  await button("校验并预览").click();
  await button("确认保存").click();
  await expect(page.locator(".native-dialog")).toHaveCount(0);
  await row.click();
  await button("删除", page.locator(".tools-actions")).click();
  await ready();
  await button("校验并预览").click();
  await button("确认保存").click();
  await expect(page.locator(".native-dialog")).toHaveCount(0);
  console.log("PASS native upstream create/edit/delete and typed preview");
  await page.evaluate(() => {
    location.hash = "#/gateway-consumers";
  });
  await page.waitForTimeout(500);
  await button("添加配置").click();
  await ready();
  await field("resourceId").fill("native-consumer");
  await tab("插件");
  await page.evaluate(() => {
    Ext.ComponentQuery.query("selectfield")
      .find((f) => f.getLabel() === "添加插件")
      .setValue("key-auth");
  });
  await button("添加插件").click();
  await expect(field("plugins.key-auth.key")).toBeVisible();
  await expect(field("plugins.key-auth.header")).toHaveCount(0);
  await page.locator(".native-dialog .x-tool-type-close").click();
  await page.evaluate(() => {
    location.hash = "#/gateway-plugin_metadata";
  });
  await page.waitForTimeout(500);
  await button("添加配置").click();
  await ready();
  await field("resourceId").fill("http-logger");
  await button("加载插件字段").click();
  await expect(page.locator(".native-dialog .x-tab")).toHaveCount(1);
  await expect(field("log_format.enabled")).toHaveCount(1);
  await page.locator(".native-dialog .x-tool-type-close").click();
  console.log("PASS consumer plugin context and metadata schema form");
  await page.evaluate(() => {
    location.hash = "#/gateway-upstreams";
  });
  await page.waitForTimeout(500);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.reload();
  await expect(page.locator(".workspace")).toBeVisible();
  await button("添加配置").click();
  await ready();
  const bounds = await page.locator(".native-dialog").boundingBox();
  expect(bounds.x).toBeGreaterThanOrEqual(0);
  expect(bounds.width).toBeLessThanOrEqual(390);
  await page.screenshot({ path: output + "/gateway-form-mobile.png" });
  await page.locator(".native-dialog .x-tool-type-close").click();
  expect(errors).toEqual([]);
  console.log("PASS mobile dialog and no browser errors");
} finally {
  await browser.close();
}
