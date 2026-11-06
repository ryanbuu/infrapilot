import { expect, test } from "@playwright/test";

async function login(page: import("@playwright/test").Page, path: string) {
  await page.goto("/#/" + path);
  await page.getByRole("button", { name: "填入演示账户" }).click();
  await page.getByRole("button", { name: "登录工作空间" }).click();
}
test("BIND configuration publish, coordinated backup restore and zone catalog", async ({
  page,
}) => {
  await login(page, "bind-access");
  await expect(
    page.getByRole("heading", { name: "访问控制与 Views", exact: true }),
  ).toBeVisible();
  const name = "browser_acl_" + Date.now();
  await page.getByRole("button", { name: "新增配置", exact: true }).click();
  await page
    .getByRole("textbox", { name: /原生配置/ })
    .fill(`acl "${name}" { 198.51.100.0/24; };`);
  await page.getByRole("checkbox", { name: "同时应用到两个节点" }).check();
  await page.getByRole("button", { name: "校验并预览", exact: true }).click();
  await expect(
    page.getByText("2 个节点已通过原生配置校验", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "确认发布", exact: true }).click();
  const row = page
    .locator(".bind-table tr[data-row-key]")
    .filter({ hasText: name });
  await expect(row).toHaveCount(1);
  await page.locator(".bind-node").filter({ hasText: "BIND 2" }).click();
  await expect(row).toHaveCount(1);
  await page.getByRole("button", { name: "变更备份", exact: true }).click();
  await page
    .locator(".bind-backup")
    .first()
    .getByRole("button", { name: "恢复此变更前配置" })
    .click();
  await page.getByRole("button", { name: "校验并预览", exact: true }).click();
  await page.getByRole("button", { name: "确认发布", exact: true }).click();
  await expect(row).toHaveCount(0);
  await page.getByRole("menuitem", { name: "权威区域", exact: true }).click();
  await page.getByRole("button", { name: "新增主从区域", exact: true }).click();
  const zone = `lab-${Date.now()}.example.net`;
  await page.getByRole("textbox", { name: "区域名称", exact: true }).fill(zone);
  await page.getByRole("button", { name: "校验并预览", exact: true }).click();
  await page.getByRole("button", { name: "确认发布", exact: true }).click();
  await expect(
    page.locator(".bind-table tr[data-row-key]").filter({ hasText: zone }),
  ).toHaveCount(1);
  await page
    .locator(".bind-table tr[data-row-key]")
    .filter({ hasText: zone })
    .getByRole("button", { name: "删除区域" })
    .click();
  await page.getByRole("button", { name: "校验并预览", exact: true }).click();
  await page.getByRole("button", { name: "确认发布", exact: true }).click();
  await expect(
    page.locator(".bind-table tr[data-row-key]").filter({ hasText: zone }),
  ).toHaveCount(0);
});

test("BIND mobile dark UI, telemetry and native editor fit the viewport", async ({
  page,
}) => {
  const errors: string[] = [],
    resize: string[] = [];
  page.on("pageerror", (e) => {
    if (
      e.message ===
      "ResizeObserver loop completed with undelivered notifications."
    )
      resize.push(e.message);
    else errors.push(e.message);
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await login(page, "bind-logging");
  await expect(
    page.getByRole("heading", { name: "日志与统计", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("nsstats.Requestv4", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "切换主题", exact: true }).click();
  await page.getByRole("menuitem", { name: /深色模式/ }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.getByRole("button", { name: "打开菜单" }).click();
  await page.getByRole("menuitem", { name: "原生配置", exact: true }).click();
  await page.getByRole("button", { name: "编辑", exact: true }).first().click();
  await expect(page.locator(".ant-drawer textarea")).toBeVisible();
  await page.keyboard.press("Escape");
  for (const width of [320, 390, 760, 800, 1024, 1440]) {
    await page.setViewportSize({ width, height: 844 });
    await expect
      .poll(() =>
        page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
      )
      .toBeTruthy();
  }
  expect(errors).toEqual([]);
  expect(resize.length).toBeLessThanOrEqual(3);
});
