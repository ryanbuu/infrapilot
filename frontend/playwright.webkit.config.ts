import { defineConfig } from "@playwright/test";
import config from "./playwright.config";

export default defineConfig({
  ...config,
  testMatch: [
    "navigation.spec.ts",
    "gateway.spec.ts",
    "forwarding.spec.ts",
    "bind.spec.ts",
    "reservations.spec.ts",
  ],
  outputDir: "test-results/webkit",
  use: {
    ...config.use,
    browserName: "webkit",
    channel: undefined,
    hasTouch: true,
  },
});
