import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.js"
  },
  server: {
    port: 5173,
    proxy: {
      "/governance/advisories/": {
        target: "http://localhost:8085",
        changeOrigin: true
      },
      "/api": {
        target: "http://localhost:8085",
        changeOrigin: true
      },
      "/governance/advisories": {
        target: "http://localhost:8090",
        changeOrigin: true
      }
    }
  },
  preview: {
    port: 4173
  }
});
