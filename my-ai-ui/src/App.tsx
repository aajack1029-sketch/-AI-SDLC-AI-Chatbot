import { useEffect, useRef, useState, type FormEvent } from 'react'
import axios, { AxiosError } from 'axios'
import { FirebaseError } from 'firebase/app'
import {
  getIdToken,
  getIdTokenResult,
  onAuthStateChanged,
  signInWithEmailAndPassword,
  signOut,
  type User,
} from 'firebase/auth'
import { auth } from './firebase'

type Role = 'DEVELOPER' | 'MANAGER' | 'ADMIN'

interface ChatSource {
  label: string
  reference: string
  url?: string
}

interface Message {
  sender: 'user' | 'ai'
  text: string
  intent?: string | string[]
  references?: ChatSource[]
}

interface GitHubEventFilters {
  eventType?: string
}

interface QuickScenario {
  label: string
  question: string
  filters?: GitHubEventFilters
}

interface ChatApiResponse {
  answer?: string
  reply?: string
  intent?: string | null
  intents?: string[]
  references?: (Partial<ChatSource> | string)[] | Partial<ChatSource> | string | null
  sourceUrls?: string[]
  source?: string | string[] | null
}

function App() {
  const [input, setInput] = useState('')
  const [messages, setMessages] = useState<Message[]>([])
  const [loading, setLoading] = useState(false)
  const [user, setUser] = useState<User | null>(null)
  const [allowedRoles, setAllowedRoles] = useState<Role[]>([])
  const [authReady, setAuthReady] = useState(false)
  const [authLoading, setAuthLoading] = useState(false)
  const [authError, setAuthError] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [currentRole, setCurrentRole] = useState<Role>('DEVELOPER')
  const isComposing = useRef(false)
  const compositionEndedAt = useRef(0)
  const selectedRole = allowedRoles.includes(currentRole)
    ? currentRole
    : allowedRoles[0]

  useEffect(() => onAuthStateChanged(auth, async (currentUser) => {
    setUser(currentUser)
    setAllowedRoles([])
    setAuthError('')

    if (!currentUser) {
      setAuthReady(true)
      return
    }

    setAuthReady(false)
    try {
      const tokenResult = await getIdTokenResult(currentUser, true)
      const roleClaim = tokenResult.claims.roles
      const roles = Array.isArray(roleClaim)
        ? roleClaim.filter(
            (role): role is Role =>
              role === 'DEVELOPER' || role === 'MANAGER' || role === 'ADMIN',
          )
        : []
      setAllowedRoles([...new Set(roles)])
    } catch (error) {
      console.error('無法讀取 Firebase 使用者角色:', error)
      setAuthError('無法驗證使用者權限，請重新登入或聯絡管理員。')
    } finally {
      setAuthReady(true)
    }
  }), [])

  const handleSignIn = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setAuthLoading(true)
    setAuthError('')
    try {
      await signInWithEmailAndPassword(auth, email.trim(), password)
      setPassword('')
    } catch (error) {
      console.error('Firebase 登入失敗:', error)
      setAuthError(error instanceof FirebaseError && error.code === 'auth/invalid-credential'
        ? '電子郵件或密碼錯誤。'
        : '登入失敗，請確認帳號設定後再試。')
    } finally {
      setAuthLoading(false)
    }
  }

  const handleSignOut = async () => {
    setAuthError('')
    try {
      await signOut(auth)
      setMessages([])
    } catch (error) {
      console.error('Firebase 登出失敗:', error)
      setAuthError('登出失敗，請稍後再試。')
    }
  }

  const quickScenarios: QuickScenario[] = [
    {
      label: '📄 ENVIRONMENT_DOC・環境文件',
      question: '目前測試機的 IP 是多少？',
    },
    {
      label: '📅 DAILY_STATUS・工作日報',
      question: '最近有哪些工作日報提到測試？',
    },
    {
      label: '🐙 GITHUB_SDLC・GitHub Issue',
      question: '請說明測試 Issue 的內容',
      filters: { eventType: 'issue' },
    },
  ]

  const handleSend = async (
    textToSend?: string,
    filters?: GitHubEventFilters,
  ) => {
    const currentInput = (textToSend ?? input).trim()
    if (!currentInput || loading) return
    if (!user || !selectedRole || selectedRole === 'ADMIN') {
      setAuthError('此帳號沒有聊天 API 所需的角色權限，請聯絡管理員。')
      return
    }

    setMessages((previous) => [
      ...previous,
      { sender: 'user', text: currentInput },
    ])
    setInput('')
    setLoading(true)

    try {
      const requestBody: { message: string; filters?: GitHubEventFilters } = {
        message: currentInput,
      }
      if (filters) requestBody.filters = filters
      const apiUrl =
        import.meta.env.VITE_CHAT_API_URL ||
        'http://localhost:8080/api/v1/chat/query'
      const sendRequest = (idToken: string) =>
        axios.post<ChatApiResponse>(
          apiUrl,
          requestBody,
          { headers: { Authorization: `Bearer ${idToken}` } },
        )

      let response
      try {
        response = await sendRequest(await getIdToken(user))
      } catch (error) {
        if (!(error instanceof AxiosError) || error.response?.status !== 401) {
          throw error
        }
        response = await sendRequest(await getIdToken(user, true))
      }

      const rawReferences =
        response.data.references ?? response.data.sourceUrls ?? response.data.source
      const references = (Array.isArray(rawReferences)
        ? rawReferences
        : rawReferences
          ? [rawReferences]
          : []
      ).map((reference): ChatSource => {
        if (typeof reference === 'string') {
          const isUrl = /^https?:\/\//i.test(reference)
          return {
            label: '資料來源',
            reference: isUrl ? '查看原始資料' : reference,
            url: isUrl ? reference : undefined,
          }
        }

        return {
          label: reference.label || '資料來源',
          reference: reference.reference || reference.url || '來源文件',
          url: reference.url,
        }
      })

      const aiMessage: Message = {
        sender: 'ai',
        text: response.data.answer || response.data.reply || '未收到有效回覆',
        intent: response.data.intents?.length
          ? response.data.intents
          : response.data.intent ?? undefined,
        references,
      }

      setMessages((previous) => [...previous, aiMessage])
    } catch (error) {
      const apiError = error instanceof AxiosError ? error : undefined
      const responseStatus = apiError?.response?.status
      console.error('聊天 API 呼叫失敗:', {
        status: responseStatus,
        authChallenge: apiError?.response?.headers['www-authenticate'],
      })
      const errorMessage = responseStatus === 401
        ? '後端拒絕 Firebase ID Token。請確認後端的 Firebase 專案 ID、JWT issuer 與 JWKS 設定。'
        : responseStatus === 403
          ? '此帳號沒有聊天 API 權限，請確認 Firebase 使用者已設定 DEVELOPER 或 MANAGER 角色。'
          : '⚠️ 系統連線異常，請確認後端服務與 CORS 設定。'
      setMessages((previous) => [
        ...previous,
        {
          sender: 'ai',
          text: errorMessage,
        },
      ])
    } finally {
      setLoading(false)
    }
  }

  const handleKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (
      isComposing.current ||
      event.nativeEvent.isComposing ||
      event.nativeEvent.keyCode === 229
    ) {
      return
    }

    if (event.key === 'Enter') {
      event.preventDefault()
      if (Date.now() - compositionEndedAt.current < 100) return
      void handleSend()
    }
  }

  return (
    <div
      style={{
        maxWidth: '850px',
        margin: '0 auto',
        padding: '20px',
        fontFamily: 'sans-serif',
      }}
    >
      <h2>🤖 SDLC AI Chatbot - 工作日報與權威文件檢索系統</h2>

      {!authReady ? (
        <p>正在確認 Firebase 登入狀態...</p>
      ) : !user ? (
        <form
          onSubmit={(event) => void handleSignIn(event)}
          style={{
            maxWidth: '420px',
            margin: '32px auto',
            padding: '24px',
            border: '1px solid #ddd',
            borderRadius: '8px',
            display: 'grid',
            gap: '12px',
            textAlign: 'left',
          }}
        >
          <h3 style={{ margin: 0 }}>使用 Firebase 帳號登入</h3>
          <label htmlFor="email">電子郵件</label>
          <input
            id="email"
            type="email"
            value={email}
            autoComplete="username"
            onChange={(event) => setEmail(event.target.value)}
            required
            disabled={authLoading}
          />
          <label htmlFor="password">密碼</label>
          <input
            id="password"
            type="password"
            value={password}
            autoComplete="current-password"
            onChange={(event) => setPassword(event.target.value)}
            required
            disabled={authLoading}
          />
          {authError && <p role="alert" style={{ color: '#b42318' }}>{authError}</p>}
          <button type="submit" disabled={authLoading}>
            {authLoading ? '登入中...' : '登入'}
          </button>
          <p style={{ fontSize: '13px', color: '#666' }}>
            帳號由管理員在 Firebase Authentication 建立；此介面不提供註冊。
          </p>
        </form>
      ) : !selectedRole ? (
        <div style={{ margin: '32px auto', maxWidth: '520px' }}>
          <p>已登入 {user.email || user.uid}，但帳號未獲授權使用聊天服務。</p>
          <p role="alert" style={{ color: '#b42318' }}>
            {authError || '請聯絡管理員確認 Firebase ID Token 的 roles claim。'}
          </p>
          <button type="button" onClick={() => void handleSignOut()}>登出</button>
        </div>
      ) : selectedRole === 'ADMIN' ? (
        <div style={{ margin: '32px auto', maxWidth: '520px' }}>
          <p>已登入 {user.email || user.uid}，角色：後台管理者。</p>
          {allowedRoles.length > 1 && (
            <label>
              切換角色{' '}
              <select
                value={selectedRole}
                onChange={(event) => {
                  const role = allowedRoles.find(
                    (allowedRole) => allowedRole === event.target.value,
                  )
                  if (role) setCurrentRole(role)
                }}
              >
                {allowedRoles.map((role) => (
                  <option key={role} value={role}>
                    {role === 'DEVELOPER'
                      ? '員工'
                      : role === 'MANAGER'
                        ? '部門主管'
                        : '後台管理者'}
                  </option>
                ))}
              </select>
            </label>
          )}
          <p role="status">
            後台管理功能尚未開放。此角色目前不能使用聊天或日報檢索 API。
          </p>
          <button type="button" onClick={() => void handleSignOut()}>登出</button>
        </div>
      ) : (
      <>
      <div
        style={{
          marginBottom: '14px',
          padding: '12px 14px',
          borderRadius: '8px',
          background: '#eef2f7',
          display: 'flex',
          alignItems: 'center',
          gap: '12px',
          flexWrap: 'wrap',
        }}
      >
        <span style={{ fontWeight: 600 }}>👤 {user.email || user.uid}</span>
        <label htmlFor="role-select" style={{ fontWeight: 600 }}>授權角色</label>
        <select
          id="role-select"
          value={selectedRole}
          onChange={(event) => {
            const role = allowedRoles.find(
              (allowedRole) => allowedRole === event.target.value,
            )
            if (role) setCurrentRole(role)
          }}
          disabled={loading || allowedRoles.length < 2}
          style={{ padding: '7px 10px', borderRadius: '6px' }}
        >
          {allowedRoles.map((role) => (
            <option key={role} value={role}>
              {role === 'DEVELOPER'
                ? '員工'
                : role === 'MANAGER'
                  ? '部門主管'
                  : '後台管理者'}
            </option>
          ))}
        </select>
        <span style={{ fontSize: '13px', color: '#666' }}>
          角色由 Firebase 驗證的 ID Token 決定
        </span>
        <button type="button" onClick={() => void handleSignOut()} disabled={loading}>
          登出
        </button>
      </div>
      {authError && <p role="alert" style={{ color: '#b42318' }}>{authError}</p>}

      <div style={{ marginBottom: '14px' }}>
        <div style={{ marginBottom: '8px', fontSize: '14px', color: '#666' }}>
          快速體驗三種資料情境：
        </div>
        <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
          {quickScenarios.map((scenario) => (
            <button
              key={scenario.label}
              type="button"
              onClick={() => void handleSend(scenario.question, scenario.filters)}
              disabled={loading}
              style={{
                padding: '8px 12px',
                border: '1px solid #007bff',
                borderRadius: '18px',
                background: '#fff',
                color: '#007bff',
                cursor: loading ? 'not-allowed' : 'pointer',
              }}
            >
              {scenario.label}
            </button>
          ))}
        </div>
      </div>

      <div
        style={{
          border: '1px solid #ccc',
          height: '450px',
          overflowY: 'auto',
          padding: '15px',
          borderRadius: '8px',
          background: '#f9f9f9',
        }}
      >
        {messages.map((message, index) => (
          <div
            key={index}
            style={{
              textAlign: message.sender === 'user' ? 'right' : 'left',
              margin: '15px 0',
            }}
          >
            <div
              style={{
                display: 'inline-block',
                padding: '12px 16px',
                borderRadius: '8px',
                maxWidth: '75%',
                textAlign: 'left',
                background: message.sender === 'user' ? '#007bff' : '#ffffff',
                color: message.sender === 'user' ? '#fff' : '#333',
                boxShadow: '0 1px 3px rgba(0,0,0,0.1)',
                whiteSpace: message.sender === 'ai' ? 'pre-wrap' : 'normal',
              }}
            >
              {message.text}
              {message.sender === 'ai' && message.references?.length ? (
                <div
                  style={{
                    marginTop: '9px',
                    paddingTop: '8px',
                    borderTop: '1px dashed #ddd',
                    fontSize: '12px',
                    color: '#287a48',
                  }}
                >
                  <strong>📍 資料來源</strong>
                  <div
                    style={{
                      display: 'flex',
                      flexWrap: 'wrap',
                      gap: '6px',
                      marginTop: '5px',
                    }}
                  >
                    {message.references.map((reference, referenceIndex) => {
                      const content = `${reference.label}：${reference.reference}`
                      const safeUrl = /^https?:\/\//i.test(reference.url || '')

                      return safeUrl ? (
                        <a
                          key={`${reference.url}-${referenceIndex}`}
                          href={reference.url}
                          target="_blank"
                          rel="noopener noreferrer"
                          style={{
                            color: '#176b3a',
                            background: '#e8f5ec',
                            padding: '3px 8px',
                            borderRadius: '12px',
                          }}
                        >
                          {content}
                        </a>
                      ) : (
                        <span
                          key={`${content}-${referenceIndex}`}
                          style={{
                            background: '#e8f5ec',
                            padding: '3px 8px',
                            borderRadius: '12px',
                          }}
                        >
                          {content}
                        </span>
                      )
                    })}
                  </div>
                </div>
              ) : null}
            </div>
            {message.intent && (
              <div style={{ fontSize: '12px', color: '#666', marginTop: '4px' }}>
                🏷️ 辨識意圖:{' '}
                {typeof message.intent === 'object'
                  ? JSON.stringify(message.intent)
                  : message.intent}
              </div>
            )}
          </div>
        ))}
        {loading && (
          <div style={{ color: '#888', fontStyle: 'italic' }}>AI 思考中...</div>
        )}
      </div>

      <div style={{ marginTop: '10px', display: 'flex', gap: '10px' }}>
        <input
          type="text"
          value={input}
          onChange={(event) => setInput(event.target.value)}
          onCompositionStart={() => {
            isComposing.current = true
            compositionEndedAt.current = 0
          }}
          onCompositionEnd={() => {
            isComposing.current = false
            compositionEndedAt.current = Date.now()
          }}
          onKeyDown={handleKeyDown}
          placeholder="請輸入問題（例如：測試機 IP 是多少？今日部署進度？）"
          style={{
            flex: 1,
            padding: '10px',
            borderRadius: '4px',
            border: '1px solid #ccc',
          }}
          disabled={loading}
        />
        <button
          type="button"
          onClick={() => void handleSend()}
          style={{
            padding: '10px 20px',
            background: '#007bff',
            color: '#fff',
            border: 'none',
            borderRadius: '4px',
            cursor: loading ? 'not-allowed' : 'pointer',
          }}
          disabled={loading || !input.trim()}
        >
          送出
        </button>
      </div>
      </>
      )}
    </div>
  )
}

export default App