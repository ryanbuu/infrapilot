import { expect, test } from "@playwright/test";

test("conditional forwarding CRUD, conflict protection and backup restore", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByRole("button", { name: "填入演示账户" }).click();
  await page.getByRole("button", { name: "登录工作空间" }).click();
  await expect(page.getByRole("heading", { name: "网络概览" })).toBeVisible();
  await page.getByRole("menuitem", { name: "条件转发", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "条件转发", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByText("example.net", { exact: true }).first(),
  ).toBeVisible();
  await expect(page.getByText("双节点一致", { exact: true })).toBeVisible();
  const name = `browser-${Date.now()}.example.org`;
  await page.getByRole("button", { name: "新增条件转发", exact: true }).click();
  await page.getByRole("textbox", { name: "转发域名", exact: true }).fill(name);
  await page
    .getByRole("textbox", { name: "上游 IP", exact: true })
    .fill("192.0.2.70\n2001:db8::53");
  await page
    .getByRole("textbox", { name: "TLS 校验域名", exact: true })
    .fill("resolver.example.org");
  await page.getByRole("button", { name: "校验并预览", exact: true }).click();
  await expect(
    page.getByText("两台节点配置校验通过", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "确认保存", exact: true }).click();
  const row = page
    .locator(".forwarding-desktop tr[data-row-key]")
    .filter({ hasText: name });
  await expect(row).toContainText("192.0.2.70:853");
  await row.getByRole("button", { name: "编辑", exact: true }).click();
  await page
    .getByRole("textbox", { name: "转发备注", exact: true })
    .fill("修改后备注");
  await page.getByRole("button", { name: "校验并预览", exact: true }).click();
  await page.getByRole("button", { name: "确认保存", exact: true }).click();
  await expect(row).toContainText("修改后备注");
  await page.getByRole("button", { name: "变更备份", exact: true }).click();
  const backup = page
    .locator(".forwarding-backup")
    .filter({ hasText: `修改 · ${name}` });
  await backup
    .getByRole("button", { name: "恢复变更前配置", exact: true })
    .click();
  await page.getByRole("button", { name: "校验并预览", exact: true }).click();
  await page.getByRole("button", { name: "确认恢复", exact: true }).click();
  await expect(row).not.toContainText("修改后备注");
  await row.getByRole("button", { name: "删除条件转发", exact: true }).click();
  await page.getByRole("button", { name: "校验并预览", exact: true }).click();
  await page.getByRole("button", { name: "确认删除", exact: true }).click();
  await expect(row).toHaveCount(0);
});

test("conditional forwarding remains usable in dark mobile layouts", async ({
  page,
}) => {
  const errors: string[] = [];
  const resize: string[] = [];
  page.on("pageerror", (e) => {
    if (
      e.message ===
      "ResizeObserver loop completed with undelivered notifications."
    )
      resize.push(e.message);
    else errors.push(e.message);
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/#/forwarding");
  await page.getByRole("button", { name: "填入演示账户" }).click();
  await page.getByRole("button", { name: "登录工作空间" }).click();
  await expect(
    page.getByRole("heading", { name: "条件转发", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".forwarding-mobile-card").first()).toBeVisible();
  await expect(page.locator(".forwarding-desktop")).toBeHidden();
  await page.getByRole("button", { name: "切换主题", exact: true }).click();
  await page.getByRole("menuitem", { name: /深色模式/ }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
  await page.getByRole("button", { name: "新增条件转发", exact: true }).click();
  await expect(
    page.getByRole("textbox", { name: "TLS 校验域名", exact: true }),
  ).toBeVisible();
  await page
    .locator('.forwarding-editor .ant-select[aria-label="转发协议"]')
    .click();
  await page.getByTitle("DNS (UDP / TCP)", { exact: true }).click();
  await expect(
    page.getByRole("textbox", { name: "TLS 校验域名", exact: true }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("spinbutton", { name: "上游端口", exact: true }),
  ).toHaveValue("53");
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
  expect(resize.length).toBeLessThanOrEqual(2);
});
