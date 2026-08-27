import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import reactHooks from "eslint-plugin-react-hooks";

const eslintConfig = defineConfig([
  ...nextVitals,
  ...nextTs,
  reactHooks.configs.flat.recommended,
  {
    rules: {
      "react-hooks/set-state-in-effect": "off",
    },
  },
  // Override default ignores of eslint-config-next.
  globalIgnores([
    // Default ignores of eslint-config-next:
    ".next/**",
    "out/**",
    "build/**",
    "next-env.d.ts",
    // Standalone scripts using CommonJS:
    "server.cjs",
    "*.cjs",
    "test-*.js",
    "test_*.js",
    "browser-smoke-test.js",
  ]),
  {
    // Playwright fixtures expose helpers named `use` that intentionally
    // shadow React's `use()` hook. Disable the React rules-of-hooks
    // check for those files so they pass lint.
    files: ["tests/e2e/fixtures/**/*.{ts,tsx}"],
    rules: {
      "react-hooks/rules-of-hooks": "off",
    },
  },
]);

export default eslintConfig;
