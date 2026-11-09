import { test, expect } from "@playwright/test";

test.use({ hasTouch: true, isMobile: true });

for (const scheme of ["light", "dark"] as const) {
  test(`mobile navigation has an opaque ${scheme} surface and isolates the page`, async ({
    page,
    browserName,
  }) => {
    await page.emulateMedia({ colorScheme: scheme });
    await page.goto("/");
    await page.getByRole("button", { name: "填入演示账户" }).click();
    await page.getByRole("button", { name: "登录工作空间" }).click();
    await page.getByRole("menuitem", { name: "解析记录", exact: true }).click();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.locator(".mobile-record").first().waitFor();
    await page.evaluate(() => window.scrollTo(0, 0));
    const trigger = page.getByRole("button", { name: "打开菜单", exact: true });
    await trigger.click();
    // The old translucent sidebar let the page and its backdrop show through.
    await expect(
      page.locator(
        ".sidebar:visible, .mobile-navigation.ant-drawer-open [role=dialog]",
      ),
    ).toHaveCSS(
      "background-color",
      scheme === "dark" ? "rgb(24, 42, 34)" : "rgb(255, 255, 255)",
    );
    const dialog = page.getByRole("dialog", { name: "导航菜单", exact: true });
    await expect(dialog).toBeVisible();
    await expect(page.locator(".main-content")).toHaveAttribute("inert");
    await expect(page.locator("body")).toHaveCSS("overflow-y", "hidden");
    await expect(
      page.getByRole("button", { name: "关闭菜单", exact: true }),
    ).toBeFocused();
    await expect(
      dialog.getByRole("textbox", { name: "搜索导航", exact: true }),
    ).toHaveCSS("font-size", "16px");
    expect(
      await page.evaluate(() =>
        document
          .elementFromPoint(24, 200)
          ?.closest('[role="dialog"]')
          ?.getAttribute("aria-label"),
      ),
    ).toBe("导航菜单");
    await page.screenshot({
      path: `../docs/screenshots/mobile-navigation-${scheme}.png`,
      animations: "disabled",
    });
    await dialog.getByRole("button", { name: "退出登录", exact: true }).focus();
    await page.keyboard.press("Tab");
    await expect(dialog.locator(".brand")).toBeFocused();
    await page.keyboard.press("Shift+Tab");
    await expect(
      dialog.getByRole("button", { name: "退出登录", exact: true }),
    ).toBeFocused();
    const bodyScroll = await page.evaluate(() => window.scrollY);
    // Playwright's mobile WebKit driver does not implement mouse-wheel input.
    if (browserName === "chromium") {
      await page.mouse.move(378, 400);
      await page.mouse.wheel(0, 350);
    }
    expect(await page.evaluate(() => window.scrollY)).toBe(bodyScroll);
    await page.locator(".mobile-navigation .ant-drawer-mask").click({
      position: { x: 378, y: 200 },
    });
    await expect(dialog).not.toBeVisible();
    await expect(trigger).toBeFocused();
    await expect(page.locator("body")).not.toHaveCSS("overflow-y", "hidden");

    await trigger.click();
    await expect(dialog).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(dialog).not.toBeVisible();
    await expect(trigger).toBeFocused();
    await trigger.click();
    await dialog.getByRole("button", { name: "关闭菜单", exact: true }).click();
    await expect(dialog).not.toBeVisible();
    await expect(trigger).toBeFocused();

    await trigger.click();
    await dialog.getByRole("menuitem", { name: "租约", exact: true }).click();
    await expect(
      page.getByRole("heading", { name: "租约", exact: true }),
    ).toBeVisible();
    await expect(dialog).not.toBeVisible();
    await expect(page.locator(".main-content")).not.toHaveAttribute("inert");
    await trigger.click();
    await dialog
      .getByRole("textbox", { name: "搜索导航", exact: true })
      .fill("解析");
    await expect(
      dialog.getByRole("menuitem", { name: "解析记录", exact: true }),
    ).toBeVisible();
    await dialog
      .getByRole("textbox", { name: "搜索导航", exact: true })
      .fill("");
    await page.setViewportSize({ width: 667, height: 375 });
    await expect(
      dialog.getByRole("button", { name: "退出登录", exact: true }),
    ).toBeInViewport();
    await expect(dialog.locator(".ant-menu-root")).toHaveCSS(
      "overflow-y",
      "auto",
    );
    await page.setViewportSize({ width: 1440, height: 1000 });
    await expect(dialog).toHaveCount(0);
    await expect(page.locator("body")).not.toHaveCSS("overflow-y", "hidden");
    await expect(page.locator(".sidebar")).toHaveCSS(
      "background-color",
      scheme === "dark" ? "rgb(24, 42, 34)" : "rgb(255, 255, 255)",
    );
    await expect(
      page.getByRole("menuitem", { name: "解析记录", exact: true }),
    ).toBeVisible();
    const dimensions = await page.evaluate(() => [
      document.documentElement.scrollWidth,
      innerWidth,
    ]);
    expect(dimensions[0]).toBeLessThanOrEqual(dimensions[1]!);
  });
}
