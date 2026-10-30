import { test, expect } from "@playwright/test";

for (const scheme of ["light", "dark"] as const) {
  test(`readable address lists and responsive resource pages (${scheme})`, async ({
    page,
  }, info) => {
    test.setTimeout(120000);
    const errors: string[] = [];
    const layoutNotices: string[] = [];
    page.on("pageerror", (error) => {
      // The unmodified Ant Design baseline emits this WebKit initial-layout notice too.
      // Keep it visible in test reports; do not suppress errors in the application.
      if (
        error.message ===
        "ResizeObserver loop completed with undelivered notifications."
      ) {
        layoutNotices.push(page.url());
        info.annotations.push({
          type: "browser-layout-notice",
          description: `${page.url()}: ${error.message}`,
        });
      } else errors.push(error.message);
    });
    await page.emulateMedia({ colorScheme: scheme });
    await page.goto("/");
    await page.getByRole("button", { name: "填入演示账户" }).click();
    await page.getByRole("button", { name: "登录工作空间" }).click();
    await expect(page.getByRole("heading", { name: "网络概览" })).toBeVisible();
    // Reproduce the original delimiter bug with no whitespace between addresses.
    await page.route("**/api/dhcp", async (route) => {
      const response = await route.fetch();
      const state = await response.json();
      for (const subnet of state.subnets) {
        subnet.dnsServers = "192.0.2.10,192.0.2.11";
        for (const reservation of subnet.reservations) {
          reservation.dnsServers = "";
          reservation.effective.dnsServers = subnet.dnsServers;
        }
      }
      await route.fulfill({ response, json: state });
    });
    await page.setViewportSize({ width: 1440, height: 1000 });
    await page.goto("/#/dhcp-reservations");
    const row = page.locator(".reservation-table tr[data-row-key]").first();
    const addresses = row
      .locator(".data-value")
      .filter({ hasText: /^192\.0\.2\.(10|11)$/ });
    await expect(addresses).toHaveCount(2);
    const boxes = await addresses.evaluateAll((elements) =>
      elements.map((element) => {
        const rect = element.getBoundingClientRect();
        return { top: rect.top, bottom: rect.bottom, width: rect.width };
      }),
    );
    expect(boxes[1].top).toBeGreaterThanOrEqual(boxes[0].bottom);
    const origin = row.getByRole("button", {
      name: "DNS 服务器：继承所属网络的设置",
      exact: true,
    });
    await origin.focus();
    await expect(
      page.getByRole("tooltip", { name: "继承所属网络的设置", exact: true }),
    ).toBeVisible();
    await page.getByRole("heading", { name: "保留地址", exact: true }).click();
    await page.mouse.move(0, 0);
    await expect(page.getByRole("tooltip")).toHaveCount(0);
    await page.screenshot({
      path: info.outputPath(`addresses-${scheme}.png`),
      fullPage: true,
    });

    const routes = [
      ["overview", "网络概览"],
      ["records", "DNS 记录"],
      ["dhcp-subnets", "子网与地址池"],
      ["dhcp-reservations", "保留地址"],
      ["dhcp-leases", "租约"],
      ["dhcp-ha", "高可用状态"],
      ["forwarding", "条件转发"],
      ["bind-zones", "权威区域"],
      ["gateway-routes", "路由"],
      ["audit", "操作审计"],
    ];
    for (const width of [1280, 390, 320]) {
      await page.setViewportSize({ width, height: 900 });
      for (const [route, title] of routes) {
        await page.goto(`/#/${route}`);
        await expect(
          page.getByRole("heading", { name: title, exact: true }),
        ).toBeVisible();
        await expect(page.locator(".ant-skeleton")).toHaveCount(0);
        await expect(
          page.locator(".ant-spin-spinning, .ant-spin-blur"),
        ).toHaveCount(0);
        for (const container of await page
          .locator(".ant-spin-container:visible")
          .all())
          await expect(container).toHaveCSS("opacity", "1");
        const dimensions = await page.evaluate(() => ({
          content: document.documentElement.scrollWidth,
          viewport: document.documentElement.clientWidth,
        }));
        expect(
          dimensions.content,
          `${route} at ${width}px`,
        ).toBeLessThanOrEqual(dimensions.viewport);
        // Address / identifier cells must fit their own allocated width, including IPv6 and long names.
        const clipped = await page
          .locator(".data-value:visible")
          .evaluateAll((elements) =>
            elements
              .filter(
                (element) => element.scrollWidth > element.clientWidth + 1,
              )
              .map((element) => element.textContent),
          );
        expect(clipped, `${route} clips a data value at ${width}px`).toEqual(
          [],
        );
        if (width === 390)
          await page.screenshot({
            path: info.outputPath(`${route}-${scheme}.png`),
            fullPage: true,
            animations: "disabled",
          });
      }
    }
    expect(errors).toEqual([]);
    expect(
      layoutNotices.length,
      "Initial layout notifications must not recur continuously",
    ).toBeLessThanOrEqual(4);
  });
}
