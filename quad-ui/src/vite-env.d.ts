/// <reference types="vite/client" />
/// <reference types="vitest/globals" />

declare global {
  namespace NodeJS {
    interface Process {
      env: {
        VITE_API_BASE_URL?: string
        VITE_MODE?: string
        NODE_ENV?: 'development' | 'production' | 'test'
      }
    }
  }
}

declare module '*.css' {
  const content: Record<string, string>
  export default content
}

declare module '*.svg' {
  import ReactComponent from '../node_modules/react-icons'
  const content: string
  export default content
  export const React: ReactComponent
}