import { test, expect } from "@playwright/test";
async function signIn(page: import("@playwright/test").Page) {
  await page.goto("/");
  await page.getByRole("button", { name: "填入演示账户" }).click();
  await page.getByRole("button", { name: "登录工作空间" }).click();
  await expect(page.getByRole("heading", { name: "网络概览" })).toBeVisible();
}
test("record lifecycle, protected zones, comparison and audit", async ({
  page,
}) => {
  await signIn(page);
  await page.getByRole("menuitem", { name: /解析记录/ }).click();
  await expect(
    page.getByRole("heading", { name: "intra.example.com" }),
  ).toBeVisible();
  const addButton = page.getByRole("button", { name: "添加记录" });
  await expect(addButton).toHaveCSS("background-color", "rgb(35, 130, 87)");
  await expect(addButton).toHaveCSS("border-radius", "6px");
  await expect(addButton).toHaveCSS("font-size", "14px");
  await addButton.click();
  await page
    .getByRole("textbox", { name: "记录名称", exact: true })
    .fill("browser-test");
  await page
    .getByRole("textbox", { name: "记录值", exact: true })
    .fill("192.0.2.90");
  await page.getByRole("button", { name: "预览变更" }).click();
  await expect(page.getByText("确认这次变更")).toBeVisible();
  await page.getByRole("button", { name: "确认保存" }).click();
  const row = page
    .locator("tr[data-row-key]")
    .filter({ hasText: "browser-test.intra.example.com." });
  await expect(row).toBeVisible();
  await row.getByRole("button", { name: "编辑记录" }).click();
  await expect(
    page.getByRole("region", { name: "编辑解析记录" }),
  ).toBeVisible();
  await expect(page.locator(".ant-message-notice")).toHaveCount(0);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({
    path: "../docs/screenshots/editor.png",
    fullPage: true,
    animations: "disabled",
  });
  await page
    .getByRole("textbox", { name: "记录值", exact: true })
    .fill("192.0.2.91");
  await page.getByRole("button", { name: "预览变更" }).click();
  await page.getByRole("button", { name: "确认保存" }).click();
  await expect(row).toContainText("192.0.2.91");
  await page.getByRole("button", { name: "主从对比" }).click();
  await expect(
    page.locator(".probe-result strong").filter({ hasText: "bind1" }),
  ).toBeVisible();
  await expect(
    page.locator(".probe-result strong").filter({ hasText: "bind2" }),
  ).toBeVisible();
  await page.keyboard.press("Escape");
  await row.getByRole("button", { name: "删除记录" }).click();
  await page.getByRole("button", { name: "确认删除" }).click();
  await expect(row).toHaveCount(0);
  await page.locator(".zone-switcher").click();
  await page
    .locator('.ant-select-item-option[title="dhcp.intra.example.com"]')
    .click();
  await expect(page.getByRole("button", { name: "添加记录" })).toBeDisabled();
  await expect(page.locator(".reference-content .ant-tag")).toHaveText(
    "动态注册",
  );
  await page.getByRole("button", { name: "区域维护说明", exact: true }).hover();
  await expect(page.getByRole("tooltip")).toContainText("仅供查询与核验");
  await page.getByRole("menuitem", { name: /操作审计/ }).click();
  await expect(
    page
      .getByRole("row")
      .filter({ hasText: "browser-test.intra.example.com." }),
  ).toHaveCount(3);
  await page.getByRole("button", { name: "退出登录" }).click();
  await expect(page.getByRole("heading", { name: "欢迎回来" })).toBeVisible();
});
test("desktop and mobile views remain usable", async ({ page }) => {
  await signIn(page);
  await page.screenshot({
    path: "../docs/screenshots/overview.png",
    fullPage: true,
    animations: "disabled",
  });
  await page.getByRole("menuitem", { name: /解析记录/ }).click();
  await expect(
    page.getByRole("heading", { name: "intra.example.com" }),
  ).toBeVisible();
  await page.screenshot({
    path: "../docs/screenshots/records.png",
    fullPage: true,
    animations: "disabled",
  });
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole("button", { name: "打开菜单" })).toBeVisible();
  await page.getByRole("button", { name: "打开菜单" }).click();
  await page.getByRole("menuitem", { name: /概览/ }).click();
  await expect(page.getByRole("heading", { name: "网络概览" })).toBeVisible();
  await page.getByRole("button", { name: "打开菜单" }).click();
  await page.getByRole("menuitem", { name: /解析记录/ }).click();
  await expect(page.locator(".mobile-record").first()).toBeVisible();
  await expect(page.getByRole("table")).toHaveCount(0);
  await page.screenshot({
    path: "../docs/screenshots/mobile.png",
    fullPage: true,
    animations: "disabled",
  });
  const dimensions = await page.evaluate(() => ({
    body: document.documentElement.scrollWidth,
    viewport: innerWidth,
  }));
  expect(dimensions.body).toBeLessThanOrEqual(dimensions.viewport);
  await page
    .locator(".mobile-record")
    .first()
    .getByRole("button", { name: "编辑记录" })
    .click();
  await expect(
    page.getByRole("region", { name: "编辑解析记录" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "关闭记录编辑" }).click();
  await expect(page.getByRole("region", { name: "编辑解析记录" })).toHaveCount(
    0,
  );
});

test("filter records, configure columns and export current results", async ({
  page,
}) => {
  await signIn(page);
  await page.getByRole("menuitem", { name: /解析记录/ }).click();
  await page.getByRole("textbox", { name: "搜索记录" }).fill("apphost");
  await expect(
    page.getByRole("row").filter({ hasText: "apphost.intra.example.com." }),
  ).toHaveCount(1);
  await page.getByRole("button", { name: "显示选项", exact: true }).click();
  await page.getByRole("checkbox", { name: "TTL", exact: true }).uncheck();
  await expect(
    page.locator(".ant-table-thead th").filter({ hasText: /^TTL$/ }),
  ).toHaveCount(0);
  await page.getByRole("checkbox", { name: "TTL", exact: true }).check();
  await page.getByRole("heading", { name: "DNS 记录", exact: true }).click();
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "导出", exact: true }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe("intra.example.com-records.zone");
  const stream = await download.createReadStream();
  let text = "";
  for await (const chunk of stream!) text += chunk.toString();
  expect(text).toContain("apphost.intra.example.com. 300 IN A 192.0.2.30");
  expect(text).not.toContain("database.intra.example.com.");
});

test("DHCP reservations, subnet lifecycle and HA views", async ({ page }) => {
  await signIn(page);
  await page.getByRole("menuitem", { name: "保留地址", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "保留地址", exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "添加保留地址", exact: true }).click();
  await page
    .getByRole("textbox", { name: "MAC 地址", exact: true })
    .fill("02:00:00:00:00:ee");
  await page
    .getByRole("textbox", { name: "固定 IP", exact: true })
    .fill("198.51.100.60");
  await page
    .getByRole("textbox", { name: "保留主机名", exact: true })
    .fill("browser-reservation");
  await page.getByRole("button", { name: "校验并预览", exact: true }).click();
  await expect(
    page.getByText("主备节点配置校验通过", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "确认同步", exact: true }).click();
  const reservation = page
    .locator(".reservation-table tr[data-row-key]")
    .filter({ hasText: "02:00:00:00:00:ee" });
  await expect(reservation).toContainText("198.51.100.60");
  await reservation.getByRole("button", { name: "编辑保留地址" }).click();
  await page
    .getByRole("textbox", { name: "固定 IP", exact: true })
    .fill("198.51.100.61");
  await page.getByRole("button", { name: "校验并预览" }).click();
  await page.getByRole("button", { name: "确认同步" }).click();
  await expect(reservation).toContainText("198.51.100.61");
  await reservation.getByRole("button", { name: "删除保留地址" }).click();
  await page.getByRole("button", { name: "确认删除" }).click();
  await expect(reservation).toHaveCount(0);
  await page
    .getByRole("menuitem", { name: "子网与地址池", exact: true })
    .click();
  await page.getByRole("button", { name: "添加子网", exact: true }).click();
  await page
    .getByRole("textbox", { name: "子网前缀", exact: true })
    .fill("192.0.2.0/24");
  await page
    .getByRole("textbox", { name: "地址池", exact: true })
    .fill("192.0.2.100 - 192.0.2.150");
  await page.getByRole("button", { name: "校验并预览" }).click();
  await page.getByRole("button", { name: "确认同步" }).click();
  const subnet = page.getByRole("row").filter({ hasText: "192.0.2.0/24" });
  await expect(subnet).toBeVisible();
  await subnet.getByRole("button", { name: "删除子网" }).click();
  await page.getByRole("button", { name: "确认删除" }).click();
  await expect(subnet).toHaveCount(0);
  await page.getByRole("menuitem", { name: "租约", exact: true }).click();
  await expect(
    page.getByRole("row").filter({ hasText: "198.51.100.101" }),
  ).toBeVisible();
  await page.getByRole("menuitem", { name: "高可用状态", exact: true }).click();
  await expect(
    page.getByText("两台节点的子网配置一致", { exact: true }),
  ).toBeVisible();
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "高可用状态", exact: true }),
  ).toBeVisible();
});

test("DHCP and audit screenshots", async ({ page }) => {
  await page.goto("/");
  await expect(
    page.getByRole("heading", { name: "欢迎回来", exact: true }),
  ).toBeVisible();
  await page.screenshot({
    path: "../docs/screenshots/login.png",
    fullPage: true,
    animations: "disabled",
  });
  await signIn(page);
  for (const [name, file] of [
    ["子网与地址池", "dhcp-subnets"],
    ["租约", "dhcp-leases"],
    ["保留地址", "dhcp-reservations"],
    ["高可用状态", "dhcp-ha"],
    ["操作审计", "audit"],
  ]) {
    await page.getByRole("menuitem", { name: new RegExp(name) }).click();
    await expect(
      page.getByRole("heading", { name, exact: true }).first(),
    ).toBeVisible();
    await expect(page.locator(".ant-skeleton")).toHaveCount(0);
    await page.screenshot({
      path: `../docs/screenshots/${file}.png`,
      fullPage: true,
      animations: "disabled",
    });
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole("button", { name: "打开菜单" }).click();
  await page.getByRole("menuitem", { name: "租约", exact: true }).click();
  await expect(
    page.locator(".dhcp-mobile-cards .mobile-record").first(),
  ).toBeVisible();
  const size = await page.evaluate(() => ({
    page: document.documentElement.scrollWidth,
    viewport: innerWidth,
  }));
  expect(size.page).toBeLessThanOrEqual(size.viewport);
  await page.screenshot({
    path: "../docs/screenshots/dhcp-mobile.png",
    fullPage: true,
    animations: "disabled",
  });
});
