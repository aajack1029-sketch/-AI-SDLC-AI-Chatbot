import { useState } from 'react'
import axios from 'axios'

type Role = 'DEVELOPER' | 'DEVOPS' | 'MANAGER'

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
  const [currentRole, setCurrentRole] = useState<Role>('DEVELOPER')

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

    setMessages((previous) => [
      ...previous,
      { sender: 'user', text: currentInput },
    ])
    setInput('')
    setLoading(true)

    try {
      const requestBody: {
        message: string
        role: Role
        filters?: GitHubEventFilters
      } = {
        message: currentInput,
        role: currentRole,
      }
      if (filters) requestBody.filters = filters

      const response = await axios.post<ChatApiResponse>(
        'http://localhost:8080/api/v1/chat/query',
        requestBody,
      )

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
      console.error('API 呼叫或 CORS 連線失敗:', error)
      setMessages((previous) => [
        ...previous,
        {
          sender: 'ai',
          text: '⚠️ 系統連線異常，請確認後端服務是否啟動，或檢查 CORS 設定。',
        },
      ])
    } finally {
      setLoading(false)
    }
  }

  const handleKeyDown = (event: React.KeyboardEvent<HTMLInputElement>) => {
    if (event.nativeEvent.isComposing || event.nativeEvent.keyCode === 229) {
      return
    }

    if (event.key === 'Enter') {
      event.preventDefault()
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
        <label htmlFor="role-select" style={{ fontWeight: 600 }}>
          🎭 檢索角色
        </label>
        <select
          id="role-select"
          value={currentRole}
          onChange={(event) => {
            const role = event.target.value
            if (role === 'DEVELOPER' || role === 'DEVOPS' || role === 'MANAGER') {
              setCurrentRole(role)
            }
          }}
          disabled={loading}
          style={{ padding: '7px 10px', borderRadius: '6px' }}
        >
          <option value="DEVELOPER">開發人員</option>
          <option value="DEVOPS">維運人員</option>
          <option value="MANAGER">管理者</option>
        </select>
        <span style={{ fontSize: '13px', color: '#666' }}>
          角色會隨提問傳送給助理
        </span>
      </div>

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
    </div>
  )
}

export default App