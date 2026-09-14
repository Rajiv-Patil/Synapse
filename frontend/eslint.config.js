import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'
import { defineConfig, globalIgnores } from 'eslint/config'
import baseui from 'eslint-plugin-baseui'
export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      js.configs.recommended,
      tseslint.configs.recommended,
      reactHooks.configs.flat.recommended,
      reactRefresh.configs.vite,
    ],
    languageOptions: {
      globals: globals.browser,
    },

    plugins: {
      baseui: baseui,
    },
    rules: {
      'baseui/deprecated-theme-api': "warn",
      'baseui/deprecated-component-api': "warn",
      'baseui/no-deep-imports': "warn",
    }

  },
])
