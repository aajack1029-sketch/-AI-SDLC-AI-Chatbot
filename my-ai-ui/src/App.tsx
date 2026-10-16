import { useState } from 'react'
import axios from 'axios'

interface Message {
  sender: 'user' | 'ai'
  text: string
  intent?: string | string[]
}

interface ChatApiResponse {
  answer?: string
  reply?: string
  intent?: string | null
  intents?: string[]
}

function App() {
  const [input, setInput] = useState('')
  const [messages, setMessages] = useState<Message[]>([])
  const [loading, setLoading] = useState(false)

  const handleSend = async () => {
    const currentInput = input.trim()
    if (!currentInput || loading) return

    setMessages((previous) => [
      ...previous,
      { sender: 'user', text: currentInput },
    ])
    setInput('')
    setLoading(true)

    try {
      const response = await axios.post<ChatApiResponse>(
        'http://localhost:8080/api/v1/chat/query',
        {
          message: currentInput,
          role: 'DEVELOPER',
        },
      )

      const aiMessage: Message = {
        sender: 'ai',
        text: response.data.answer || response.data.reply || '未收到有效回覆',
        intent: response.data.intents?.length
          ? response.data.intents
          : response.data.intent ?? undefined,
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

  return (
    <div
      style={{
        maxWidth: '800px',
        margin: '0 auto',
        padding: '20px',
        fontFamily: 'sans-serif',
      }}
    >
      <h2>🤖 SDLC AI Chatbot - 工作日報與權威文件檢索系統</h2>

      <div
        style={{
          border: '1px solid #ccc',
          height: '400px',
          overflowY: 'auto',
          padding: '10px',
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
                padding: '10px 14px',
                borderRadius: '8px',
                maxWidth: '70%',
                textAlign: 'left',
                background: message.sender === 'user' ? '#007bff' : '#ffffff',
                color: message.sender === 'user' ? '#fff' : '#333',
                boxShadow: '0 1px 2px rgba(0,0,0,0.1)',
                whiteSpace: message.sender === 'ai' ? 'pre-wrap' : 'normal',
              }}
            >
              {message.text}
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
          onKeyDown={(event) => {
            if (event.key === 'Enter') void handleSend()
          }}
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