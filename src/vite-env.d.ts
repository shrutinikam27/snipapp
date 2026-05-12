/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly VITE_GEMINI_API_KEY: string
  readonly APP_URL: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
