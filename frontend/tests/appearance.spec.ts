import { test, expect } from "@playwright/test";
import type { Page } from "@playwright/test";

async function chooseTheme(page: Page, name: string) {
  await page.getByRole("button", { name: "切换主题", exact: true }).click();
  await page.getByRole("menuitem", { name: new RegExp(name) }).click();
}
async function signIn(page: Page) {
  await page.goto("/");
  await page.getByRole("button", { name: "填入演示账户" }).click();
  await page.getByRole("button", { name: "登录工作空间" }).click();
  await page.getByRole("heading", { name: "网络概览", exact: true }).waitFor();
}

test("appearance follows the OS, preserves manual choice and themes overlays", async ({
  page,
}) => {
  await page.emulateMedia({ colorScheme: "dark" });
  await page.goto("/");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await expect(page.locator(".login-panel")).toHaveCSS(
    "background-color",
    "rgb(24, 42, 34)",
  );
  await chooseTheme(page, "浅色模式");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await expect(page.locator(".login-panel")).toHaveCSS(
    "background-color",
    "rgb(255, 255, 255)",
  );
  await chooseTheme(page, "跟随系统");
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.emulateMedia({ colorScheme: "light" });
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await chooseTheme(page, "深色模式");
  await signIn(page);
  await expect(page.locator(".resource-list-row").first()).toHaveCSS(
    "background-color",
    "rgb(24, 42, 34)",
  );
  await page.getByRole("menuitem", { name: "解析记录", exact: true }).click();
  await page.locator("tr[data-row-key]").first().waitFor();
  await expect(page.locator(".records-table-panel")).toHaveCSS(
    "background-color",
    "rgb(24, 42, 34)",
  );
  await page.screenshot({
    path: "../docs/screenshots/dark.png",
    fullPage: true,
    animations: "disabled",
  });
  const row = page
    .getByRole("row")
    .filter({ hasText: "apphost.intra.example.com." });
  await row.getByRole("button", { name: "删除记录", exact: true }).click();
  await expect(page.locator(".ant-modal-content")).toHaveCSS(
    "background-color",
    "rgb(33, 57, 46)",
  );
  await page.getByRole("button", { name: /取\s*消/ }).click();
  await page.getByRole("button", { name: /添加记录/ }).click();
  await page.getByRole("button", { name: "预览变更", exact: true }).click();
  await expect(page.locator(".ant-message-notice-content")).toHaveCSS(
    "background-color",
    "rgb(33, 57, 46)",
  );
  await page.getByRole("button", { name: "关闭记录编辑", exact: true }).click();
  await page.getByRole("button", { name: /主从对比/ }).click();
  await expect(page.locator(".ant-drawer-content")).toHaveCSS(
    "background-color",
    "rgb(33, 57, 46)",
  );
});

test("help stays hidden until hover, keyboard focus or touch", async ({
  page,
  browser,
}) => {
  await signIn(page);
  await page.getByRole("menuitem", { name: "解析记录", exact: true }).click();
  await page.locator("tr[data-row-key]").first().waitFor();
  await expect(page.locator(".page-heading p, .panel-footnote")).toHaveCount(0);
  await expect(page.getByRole("tooltip")).toHaveCount(0);
  const help = page.getByRole("button", { name: "DNS 记录说明", exact: true });
  await help.hover();
  await expect(page.getByRole("tooltip")).toContainText("带锁记录");
  await page.getByRole("heading", { name: "DNS 记录", exact: true }).click();
  await page.mouse.move(0, 0);
  await expect(page.getByRole("tooltip")).not.toBeVisible();
  const zoneHelp = page.getByRole("button", {
    name: "区域维护说明",
    exact: true,
  });
  await zoneHelp.focus();
  await expect(page.getByRole("tooltip")).toContainText("变更自动同步");
  await page.keyboard.press("Escape");
  await expect(page.getByRole("tooltip")).not.toBeVisible();

  const context = await browser.newContext({
    viewport: { width: 390, height: 844 },
    isMobile: true,
    hasTouch: true,
  });
  try {
    const touch = await context.newPage();
    await signIn(touch);
    await touch.getByRole("button", { name: "打开菜单", exact: true }).tap();
    await touch.getByRole("menuitem", { name: "解析记录", exact: true }).tap();
    await touch.locator(".mobile-record").first().waitFor();
    await touch
      .getByRole("button", { name: "DNS 记录说明", exact: true })
      .tap();
    await expect(touch.getByRole("tooltip")).toContainText("带锁记录");
    await touch.getByRole("textbox", { name: "搜索记录", exact: true }).tap();
    await expect(touch.getByRole("tooltip")).not.toBeVisible();
    const width = await touch.evaluate(() => [
      document.documentElement.scrollWidth,
      innerWidth,
    ]);
    expect(width[0]).toBeLessThanOrEqual(width[1]!);
  } finally {
    await context.close();
  }
});
