import { test, expect } from "@playwright/test";

test("a reserved lease edits its existing record and static server DNS uses the main zone", async ({
  page,
}) => {
  await page.goto("/");
  await page.getByRole("button", { name: "填入演示账户" }).click();
  await page.getByRole("button", { name: "登录工作空间" }).click();
  await expect(page.getByRole("heading", { name: "网络概览" })).toBeVisible();
  await page.goto("/#/dhcp-leases");
  const lease = page
    .locator("tr[data-row-key]")
    .filter({ hasText: "198.51.100.101" });
  await lease.getByRole("button", { name: "将租约保留", exact: true }).click();
  await page
    .getByRole("textbox", { name: "保留主机名", exact: true })
    .fill("server-browser");
  await page.getByRole("radio", { name: "静态维护", exact: true }).check();
  await page
    .getByRole("textbox", { name: "保留地址DNS 服务器", exact: true })
    .fill("192.0.2.44");
  await page.getByRole("button", { name: "校验并预览", exact: true }).click();
  await page.getByRole("button", { name: "确认同步", exact: true }).click();
  await expect(page.getByRole("region", { name: "DHCP 配置编辑" })).toHaveCount(
    0,
  );
  await expect(lease).toContainText("server-browser.intra.example.com");
  await expect(
    lease.getByRole("button", { name: "将租约保留", exact: true }),
  ).toHaveCount(0);
  await expect(
    lease.getByRole("button", { name: "重发 DNS", exact: true }),
  ).toBeDisabled();
  await lease
    .getByRole("button", { name: "编辑已有保留地址", exact: true })
    .click();
  await expect(
    page.getByRole("textbox", { name: "MAC 地址", exact: true }),
  ).toBeDisabled();
  await expect(
    page.getByRole("textbox", { name: "保留地址DNS 服务器", exact: true }),
  ).toHaveValue("192.0.2.44");
  await expect(
    page.getByRole("textbox", { name: "保留主机名", exact: true }),
  ).toHaveValue("server-browser");
  await page
    .getByRole("button", { name: "关闭 DHCP 编辑", exact: true })
    .click();
  const state = await (await page.request.get("/api/dhcp")).json();
  const csrf = await (await page.request.get("/api/csrf")).json();
  const response = await page.request.post(
    "/api/dhcp/preview/reservation-create",
    {
      headers: { [csrf.headerName]: csrf.token },
      data: {
        revision: state.revision,
        subnetId: 1,
        values: {
          mac: "02:00:00:00:00:01",
          address: "198.51.100.60",
          hostname: "duplicate",
        },
      },
    },
  );
  expect(response.status()).toBe(409);
  const after = await (await page.request.get("/api/dhcp")).json();
  expect(after.revision).toBe(state.revision);
  expect(
    after.subnets[0].reservations.filter(
      (r: { mac: string }) => r.mac === "02:00:00:00:00:01",
    ),
  ).toHaveLength(1);
  await page.setViewportSize({ width: 390, height: 844 });
  const mobileLease = page
    .locator(".dhcp-mobile-cards .mobile-record")
    .filter({ hasText: "198.51.100.101" });
  await expect(mobileLease).toContainText("server-browser.intra.example.com");
  await expect(
    mobileLease.getByRole("button", { name: "编辑已有保留地址", exact: true }),
  ).toBeEnabled();
  await expect(
    mobileLease.getByRole("button", { name: "重发 DNS", exact: true }),
  ).toBeDisabled();
});
