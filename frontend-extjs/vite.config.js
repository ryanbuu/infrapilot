import { defineConfig } from "vite";
export default defineConfig({
  base: process.env.PREVIEW_BASE || "/",
  server: {
    proxy: {
      "/api": {
        target: process.env.API_PROXY_TARGET || "http://127.0.0.1:18085",
      },
    },
  },
  build: { chunkSizeWarningLimit: 1600 },
});
