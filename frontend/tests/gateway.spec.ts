import { expect, test } from "@playwright/test";
import type { Page } from "@playwright/test";

async function login(page: Page) {
  await page.goto("/");
  await page.getByRole("button", { name: "填入演示账户" }).click();
  await page.getByRole("button", { name: "登录工作空间" }).click();
  await expect(page.getByRole("heading", { name: "网络概览" })).toBeVisible();
}
async function go(page: Page, section: string) {
  await page.evaluate((s) => {
    location.hash = `#/gateway-${s}`;
  }, section);
}
test("Application Gateway edits preserve JSON fields and restore from a private backup", async ({
  page,
}) => {
  await login(page);
  await go(page, "upstreams");
  await expect(
    page.getByRole("heading", { name: "上游", exact: true }),
  ).toBeVisible();
  const id = `browser-${Date.now()}`;
  await page.getByRole("button", { name: "新增上游", exact: true }).click();
  await page.getByRole("textbox", { name: "资源 ID", exact: true }).fill(id);
  await page
    .getByRole("textbox", { name: "资源名称", exact: true })
    .fill("浏览器网关测试");
  await page.getByRole("button", { name: "添加节点", exact: true }).click();
  await page
    .getByRole("textbox", { name: "节点 1 地址", exact: true })
    .fill("192.0.2.90");
  await page.getByRole("tab", { name: "JSON 配置", exact: true }).click();
  const editor = page.getByRole("textbox", {
    name: "完整 JSON 配置",
    exact: true,
  });
  const original = JSON.parse(await editor.inputValue());
  await editor.fill(
    JSON.stringify(
      {
        ...original,
        labels: { browser: "preserved" },
        timeout: { connect: 2, read: 15, send: 15 },
      },
      null,
      2,
    ),
  );
  await page.getByRole("button", { name: "校验并预览", exact: true }).click();
  await expect(
    page.getByText("Schema 校验通过", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "确认保存", exact: true }).click();
  const row = page
    .locator(".gateway-desktop-table tr[data-row-key]")
    .filter({ hasText: id });
  await expect(row).toContainText("192.0.2.90:80");
  await row.getByRole("button", { name: "编辑", exact: true }).click();
  await page
    .getByRole("textbox", { name: "资源名称", exact: true })
    .fill("浏览器网关修改");
  await page.getByRole("tab", { name: "JSON 配置", exact: true }).click();
  await expect(editor).toHaveValue(/"browser": "preserved"/);
  await page.getByRole("button", { name: "校验并预览", exact: true }).click();
  await page.getByRole("button", { name: "确认保存", exact: true }).click();
  await expect(row).toContainText("浏览器网关修改");
  await go(page, "backups");
  const backup = page
    .getByRole("row")
    .filter({ hasText: id })
    .filter({ hasText: "UPDATE" });
  await backup.getByRole("button", { name: "恢复前值", exact: true }).click();
  await expect(page.getByText("确认恢复配置", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "确认恢复", exact: true }).click();
  await go(page, "upstreams");
  await expect(row).toContainText("浏览器网关测试");
  await row.getByRole("button", { name: "删除网关资源", exact: true }).click();
  await page.getByRole("button", { name: "确认删除", exact: true }).click();
  await expect(row).toHaveCount(0);
});

test("gateway discovery, plugins, redacted export and responsive themes", async ({
  page,
}) => {
  const errors: string[] = [];
  const resizeNotifications: string[] = [];
  page.on("pageerror", (error) => {
    // WebKit may defer one Ant Design ResizeObserver delivery while resizing a table/drawer.
    // Keep this bounded and verify the final layout below; all application errors still fail.
    if (
      error.message ===
      "ResizeObserver loop completed with undelivered notifications."
    )
      resizeNotifications.push(error.message);
    else errors.push(error.message);
  });
  await login(page);
  await go(page, "overview");
  await expect(
    page.getByRole("heading", { name: "Application Gateway", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".gateway-resource-card")).toHaveCount(12);
  await page.getByRole("button", { name: "导出配置", exact: true }).click();
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: /^导\s*出$/ }).click();
  const download = await downloadPromise;
  const stream = await download.createReadStream();
  let text = "";
  for await (const chunk of stream!) text += chunk.toString();
  const exported = JSON.parse(text);
  expect(exported.format).toBe("infrapilot-gateway/v1");
  expect(exported.containsSecrets).toBe(false);
  expect(text).not.toContain("demo-secret-do-not-display");
  await go(page, "plugins");
  await page
    .getByRole("button", { name: "proxy-rewrite", exact: true })
    .click();
  await expect(page.locator(".ant-drawer-content .gateway-code")).toContainText(
    "properties",
  );
  await page.keyboard.press("Escape");
  await page.setViewportSize({ width: 390, height: 844 });
  await go(page, "routes");
  await expect(page.locator(".gateway-mobile-card").first()).toBeVisible();
  await expect(page.locator(".gateway-desktop-table")).toBeHidden();
  await page
    .locator(".gateway-mobile-card")
    .first()
    .getByRole("button", { name: "编辑", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "校验并预览", exact: true }),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(
    page.locator(".gateway-editor .ant-drawer-content-wrapper"),
  ).not.toBeVisible();
  await page.getByRole("button", { name: "切换主题", exact: true }).click();
  await page.getByRole("menuitem", { name: /深色模式/ }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.getByRole("button", { name: "打开菜单", exact: true }).click();
  await expect(
    page.getByText("Application Gateway", { exact: true }),
  ).toBeVisible();
  await page.getByRole("menuitem", { name: "网关概览", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Application Gateway", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".ant-message-notice")).toHaveCount(0);
  await page.screenshot({
    path: "../docs/screenshots/gateway-mobile-dark.png",
    fullPage: true,
    animations: "disabled",
  });
  const dimensions = await page.evaluate(() => ({
    viewport: innerWidth,
    width: document.documentElement.scrollWidth,
    overflow: [...document.querySelectorAll<HTMLElement>(".main-content *")]
      .filter((e) => e.getBoundingClientRect().right > innerWidth + 1)
      .map((e) => ({
        tag: e.tagName,
        class: e.className,
        text: e.innerText?.slice(0, 50),
        right: e.getBoundingClientRect().right,
      })),
  }));
  expect(
    dimensions.width,
    JSON.stringify(dimensions.overflow),
  ).toBeLessThanOrEqual(dimensions.viewport);
  expect(errors).toEqual([]);
  expect(resizeNotifications.length).toBeLessThanOrEqual(2);
});
