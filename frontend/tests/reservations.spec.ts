import { test, expect, type Page } from "@playwright/test";

async function reservations(page: Page) {
  await page.goto("/");
  await page.getByRole("button", { name: "填入演示账户" }).click();
  await page.getByRole("button", { name: "登录工作空间" }).click();
  await expect(page.getByRole("heading", { name: "网络概览" })).toBeVisible();
  await page.goto("/#/dhcp-reservations");
  await expect(
    page.getByRole("heading", { name: "保留地址", exact: true }),
  ).toBeVisible();
}
async function commit(page: Page) {
  await page.getByRole("button", { name: "校验并预览", exact: true }).click();
  await expect(
    page.getByText("主备节点配置校验通过", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "确认同步", exact: true }).click();
  await expect(page.getByRole("region", { name: "DHCP 配置编辑" })).toHaveCount(
    0,
  );
}

test("reservation options survive save and re-edit, and cleared fields inherit", async ({
  page,
}, info) => {
  const errors: string[] = [];
  page.on("pageerror", (e) => errors.push(e.message));
  await page.setViewportSize({ width: 1920, height: 1080 });
  await reservations(page);
  await page.getByRole("button", { name: "添加保留地址", exact: true }).click();
  await page
    .getByRole("textbox", { name: "MAC 地址", exact: true })
    .fill("02:00:00:00:00:f0");
  await page
    .getByRole("textbox", { name: "固定 IP", exact: true })
    .fill("198.51.100.70");
  await page
    .getByRole("textbox", { name: "保留主机名", exact: true })
    .fill("options-browser");
  await expect(
    page.getByRole("textbox", { name: "保留地址网关", exact: true }),
  ).toHaveAttribute("placeholder", "继承：198.51.100.1");
  await page
    .getByRole("textbox", { name: "保留地址网关", exact: true })
    .fill("198.51.100.2");
  await page
    .getByRole("textbox", { name: "保留地址DNS 服务器", exact: true })
    .fill("192.0.2.20, 192.0.2.21");
  await page
    .getByRole("textbox", { name: "保留地址域名", exact: true })
    .fill("devices.example.com");
  await page
    .getByRole("textbox", { name: "保留地址搜索域", exact: true })
    .fill("devices.example.com, example.com");
  await page
    .getByRole("textbox", { name: "保留地址NTP 服务器", exact: true })
    .fill("192.0.2.42");
  await page
    .getByRole("spinbutton", { name: "保留地址 MTU", exact: true })
    .fill("1450");
  await page
    .getByRole("textbox", { name: "保留地址备注", exact: true })
    .fill("专用网络设置");
  await page.locator(".reservation-advanced .ant-collapse-header").click();
  await page
    .getByRole("textbox", { name: "启动服务器 IP", exact: true })
    .fill("192.0.2.40");
  await page
    .getByRole("textbox", { name: "启动服务器名称", exact: true })
    .fill("boot.example.com");
  await page
    .getByRole("textbox", { name: "启动文件", exact: true })
    .fill("ipxe.efi");
  await page
    .getByRole("button", { name: "添加 DHCP 选项", exact: true })
    .click();
  await page
    .getByRole("spinbutton", { name: "选项代码 1", exact: true })
    .fill("66");
  await page
    .getByRole("textbox", { name: "选项值 1", exact: true })
    .fill("boot.example.com");
  await page.getByRole("button", { name: "校验并预览", exact: true }).click();
  await expect(page.locator(".reservation-review")).toContainText("DNS 服务器");
  await expect(page.locator(".reservation-review")).toContainText(
    "192.0.2.20, 192.0.2.21",
  );
  await page.getByRole("button", { name: "确认同步", exact: true }).click();
  const row = page
    .locator(".reservation-table tr[data-row-key]")
    .filter({ hasText: "02:00:00:00:00:f0" });
  await expect(row).toContainText("198.51.100.2");
  await expect(
    row.locator(".data-value").filter({ hasText: /^192\.0\.2\.20$/ }),
  ).toBeVisible();
  await expect(
    row.locator(".data-value").filter({ hasText: /^192\.0\.2\.21$/ }),
  ).toBeVisible();
  await expect(
    page
      .locator(".reservation-table .ant-table-thead")
      .getByText("主机名", { exact: true }),
  ).toBeVisible();
  const widths = await page
    .locator(".reservation-table .ant-table-thead .ant-table-cell")
    .evaluateAll((cells) => cells.map((c) => c.getBoundingClientRect().width));
  expect(widths[2] / widths.reduce((a, b) => a + b, 0)).toBeLessThan(0.25);
  expect(widths[5]).toBeGreaterThan(widths[0]);
  await page.screenshot({
    path: info.outputPath("reservations-desktop.png"),
    fullPage: true,
  });
  await row.getByRole("button", { name: "编辑保留地址", exact: true }).click();
  await expect(
    page.getByRole("textbox", { name: "保留地址DNS 服务器", exact: true }),
  ).toHaveValue("192.0.2.20, 192.0.2.21");
  await expect(
    page.getByRole("textbox", { name: "启动文件", exact: true }),
  ).toHaveValue("ipxe.efi");
  await expect(
    page.getByRole("textbox", { name: "选项值 1", exact: true }),
  ).toHaveValue("boot.example.com");
  await page
    .getByRole("textbox", { name: "保留地址网关", exact: true })
    .fill("");
  await page
    .getByRole("textbox", { name: "保留地址DNS 服务器", exact: true })
    .fill("");
  await commit(page);
  await expect(row).toContainText("198.51.100.1");
  await expect(
    row.locator(".data-value").filter({ hasText: /^192\.0\.2\.10$/ }),
  ).toBeVisible();
  await expect(
    row.locator(".data-value").filter({ hasText: /^192\.0\.2\.11$/ }),
  ).toBeVisible();
  await row.getByRole("button", { name: "编辑保留地址", exact: true }).click();
  await expect(
    page.getByRole("textbox", { name: "保留地址DNS 服务器", exact: true }),
  ).toHaveValue("");
  await expect(
    page.getByRole("textbox", { name: "选项值 1", exact: true }),
  ).toHaveValue("boot.example.com");
  await expect(
    page.getByRole("spinbutton", { name: "保留地址 MTU", exact: true }),
  ).toHaveValue("1450");
  await page.screenshot({
    path: info.outputPath("reservations-editor-desktop.png"),
    fullPage: true,
  });
  expect(errors).toEqual([]);
});

test("invalid reservation DNS is rejected without offering publish", async ({
  page,
}) => {
  await reservations(page);
  await page.getByRole("button", { name: "添加保留地址", exact: true }).click();
  await page
    .getByRole("textbox", { name: "MAC 地址", exact: true })
    .fill("02:00:00:00:00:f1");
  await page
    .getByRole("textbox", { name: "固定 IP", exact: true })
    .fill("198.51.100.71");
  await page
    .getByRole("textbox", { name: "保留地址DNS 服务器", exact: true })
    .fill("999.0.0.1");
  await page.getByRole("button", { name: "校验并预览", exact: true }).click();
  await expect(page.locator(".dhcp-editor .ant-alert-error")).toContainText(
    "IPv4",
  );
  await expect(
    page.getByRole("button", { name: "确认同步", exact: true }),
  ).toHaveCount(0);
});

for (const scheme of ["light", "dark"] as const) {
  test(`reservation editor and additional options fit narrow touch screens (${scheme})`, async ({
    page,
  }, info) => {
    await page.emulateMedia({ colorScheme: scheme });
    await reservations(page);
    await page.setViewportSize({ width: 390, height: 844 });
    await page
      .getByRole("button", { name: "添加保留地址", exact: true })
      .click();
    await expect(
      page.getByRole("textbox", { name: "保留地址DNS 服务器", exact: true }),
    ).toBeVisible();
    await page.locator(".reservation-advanced .ant-collapse-header").click();
    await page
      .getByRole("button", { name: "添加 DHCP 选项", exact: true })
      .click();
    for (const width of [390, 320, 800]) {
      await page.setViewportSize({ width, height: 844 });
      const size = await page.evaluate(() => ({
        scroll: document.documentElement.scrollWidth,
        viewport: document.documentElement.clientWidth,
      }));
      expect(size.scroll, `overflow at ${width}`).toBeLessThanOrEqual(
        size.viewport,
      );
      await expect(
        page.getByRole("textbox", { name: "选项值 1", exact: true }),
      ).toBeVisible();
      expect(
        await page
          .locator(".reservation-advanced .ant-collapse-content")
          .evaluate((element) => element.scrollTop),
      ).toBe(0);
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({
      path: info.outputPath(`reservations-editor-mobile-${scheme}.png`),
      fullPage: true,
    });
  });
}
