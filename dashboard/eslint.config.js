import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist", "public", "node_modules"] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ["**/*.{ts,tsx}"],
    languageOptions: { ecmaVersion: 2022, globals: globals.browser },
    plugins: { "react-hooks": reactHooks },
    rules: {
      ...reactHooks.configs.recommended.rules,
      "no-magic-numbers": ["warn", { ignore: [-1, 0, 1, 2, 100], ignoreArrayIndexes: true, enforceConst: true, detectObjects: false }],
    },
  },
  { files: ["src/shared/theme/**", "**/*.test.ts", "vite.config.ts"], rules: { "no-magic-numbers": "off" } },
);
