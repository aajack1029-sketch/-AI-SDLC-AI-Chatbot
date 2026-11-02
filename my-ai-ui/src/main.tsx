import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { browserSessionPersistence, setPersistence } from 'firebase/auth'
import './index.css'
import App from './App.tsx'
import { auth } from './firebase'

const rootElement = document.getElementById('root')

if (!rootElement) {
  throw new Error('Required root element #root was not found.')
}

const root = createRoot(rootElement)

void setPersistence(auth, browserSessionPersistence)
  .then(() => {
    root.render(
      <StrictMode>
        <App />
      </StrictMode>,
    )
  })
  .catch((error: unknown) => {
    console.error('Firebase session persistence setup failed:', error)
    root.render(
      <p role="alert">
        無法初始化登入工作階段，請重新載入頁面或聯絡管理員。
      </p>,
    )
  })
