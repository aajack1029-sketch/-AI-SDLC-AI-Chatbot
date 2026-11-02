# React + TypeScript + Vite

This template provides a minimal setup to get React working in Vite with HMR and some ESLint rules.

## Firebase Authentication

1. In Firebase Console, add a Web App to project `sdlc-ai-chatbot-e017d` and enable **Authentication → Sign-in method → Email/Password**.
2. Create user accounts in Firebase Authentication. The chat UI intentionally has no public sign-up flow.
3. Copy `.env.example` to `.env` and fill in the Web App's `apiKey`, `authDomain`, `projectId`, and `appId`. Set `VITE_CHAT_API_URL` if the backend is not at the local default.
4. Assign `roles` custom claims (`["DEVELOPER"]` or `["MANAGER"]`) to each authorized Firebase user using a trusted Firebase Admin SDK environment. Never set authorization claims from the browser or expose service-account credentials in Vite.
   Authentication uses browser-session persistence, so closing the browser tab ends the local sign-in session.
5. Start Vite with `npm run dev`, then sign in. The frontend refreshes the Firebase ID token to read claims and sends the ID token as `Authorization: Bearer ...` for each chat request. The API role is derived from the verified token, not a client-selected request field.

Vite `VITE_*` values are included in the browser bundle. Firebase Web App configuration is client configuration, not a place for service-account keys or other server secrets.

Currently, two official plugins are available:

- [@vitejs/plugin-react](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react) uses [Oxc](https://oxc.rs)
- [@vitejs/plugin-react-swc](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react-swc) uses [SWC](https://swc.rs/)

## React Compiler

The React Compiler is not enabled on this template because of its impact on dev & build performances. To add it, see [this documentation](https://react.dev/learn/react-compiler/installation).

## Expanding the ESLint configuration

If you are developing a production application, we recommend updating the configuration to enable type-aware lint rules:

```js
export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      // Other configs...

      // Remove tseslint.configs.recommended and replace with this
      tseslint.configs.recommendedTypeChecked,
      // Alternatively, use this for stricter rules
      tseslint.configs.strictTypeChecked,
      // Optionally, add this for stylistic rules
      tseslint.configs.stylisticTypeChecked,

      // Other configs...
    ],
    languageOptions: {
      parserOptions: {
        project: ['./tsconfig.node.json', './tsconfig.app.json'],
        tsconfigRootDir: import.meta.dirname,
      },
      // other options...
    },
  },
])

```

You can also install [eslint-plugin-react-x](https://npmx.dev/package/eslint-plugin-react-x) and [eslint-plugin-react-dom](https://npmx.dev/package/eslint-plugin-react-dom) for React-specific lint rules:

```js
// eslint.config.js
import reactX from 'eslint-plugin-react-x'
import reactDom from 'eslint-plugin-react-dom'

export default defineConfig([
  globalIgnores(['dist']),
  {
    files: ['**/*.{ts,tsx}'],
    extends: [
      // Other configs...
      // Enable lint rules for React
      reactX.configs['recommended-typescript'],
      // Enable lint rules for React DOM
      reactDom.configs.recommended,
    ],
    languageOptions: {
      parserOptions: {
        project: ['./tsconfig.node.json', './tsconfig.app.json'],
        tsconfigRootDir: import.meta.dirname,
      },
      // other options...
    },
  },
])

```
