import { useState } from 'react'
import { AxiosError } from 'axios'
import { getIdToken, type User } from 'firebase/auth'
import {
  PRIORITY_LABEL,
  STATUS_META,
  STATUS_ORDER,
  donutSegments,
  fetchHandoverSummary,
  type HandoverSort,
  type HandoverSummary,
} from './handover'
import './HandoverPanel.css'

interface HandoverPanelProps {
  user: User
  isManager: boolean
  apiUrl: string
}

const RADIUS = 52
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

function describeError(error: unknown): string {
  if (error instanceof AxiosError) {
    const body = error.response?.data as { error?: string } | undefined
    if (typeof body?.error === 'string') return body.error
    if (error.response?.status === 403) return '沒有權限查看這份交接摘要。'
    if (error.response?.status === 401) return '登入已過期，請重新登入。'
    return '無法連線到交接摘要 API，請確認後端服務已啟動。'
  }
  return error instanceof Error ? error.message : '交接摘要載入失敗。'
}

export default function HandoverPanel({ user, isManager, apiUrl }: HandoverPanelProps) {
  const [author, setAuthor] = useState('')
  const [sort, setSort] = useState<HandoverSort>('priority')
  const [summary, setSummary] = useState<HandoverSummary | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const load = async (nextSort: HandoverSort = sort) => {
    setLoading(true)
    setError('')
    try {
      const query = { author: isManager ? author : undefined, sort: nextSort }
      let data: HandoverSummary
      try {
        data = await fetchHandoverSummary(apiUrl, await getIdToken(user), query)
      } catch (caught) {
        if (!(caught instanceof AxiosError) || caught.response?.status !== 401) throw caught
        data = await fetchHandoverSummary(apiUrl, await getIdToken(user, true), query)
      }
      setSummary(data)
    } catch (caught) {
      setSummary(null)
      setError(describeError(caught))
    } finally {
      setLoading(false)
    }
  }

  const segments = summary ? donutSegments(summary.statusCounts, CIRCUMFERENCE) : []
  const doneCount = summary?.statusCounts.DONE ?? 0
  const openTasks = summary?.tasks.filter((task) => task.status !== 'DONE') ?? []
  const doneTasks = summary?.tasks.filter((task) => task.status === 'DONE') ?? []

  return (
    <section className="handover-panel" aria-label="交接摘要">
      <div className="handover-toolbar">
        <h3>📋 交接摘要</h3>
        {isManager && (
          <input
            type="text"
            value={author}
            placeholder="作者姓名（留空＝自己）"
            onChange={(event) => setAuthor(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === 'Enter' && !event.nativeEvent.isComposing) void load()
            }}
            disabled={loading}
            aria-label="要查詢的作者"
          />
        )}
        <label className="handover-sort">
          排序
          <select
            value={sort}
            onChange={(event) => {
              const next = event.target.value === 'progress' ? 'progress' : 'priority'
              setSort(next)
              if (summary) void load(next)
            }}
            disabled={loading}
          >
            <option value="priority">依優先度</option>
            <option value="progress">依進度</option>
          </select>
        </label>
        <button type="button" onClick={() => void load()} disabled={loading}>
          {loading ? '載入中...' : summary ? '重新整理' : '產生交接摘要'}
        </button>
      </div>

      {error && <p role="alert" className="handover-error">{error}</p>}

      {summary && summary.totalTasks === 0 && (
        <p className="handover-empty">
          {summary.author} 在 {summary.from} ～ {summary.to} 沒有可顯示的任務。
        </p>
      )}

      {summary && summary.totalTasks > 0 && (
        <>
          <div className="handover-overview">
            <figure className="handover-donut">
              <svg
                viewBox="0 0 140 140"
                role="img"
                aria-label={`任務狀態分布：${STATUS_ORDER.map(
                  (status) => `${STATUS_META[status].label} ${summary.statusCounts[status] ?? 0}`,
                ).join('、')}`}
              >
                <circle cx="70" cy="70" r={RADIUS} className="donut-track" />
                {segments.map((segment) => (
                  <circle
                    key={segment.status}
                    cx="70"
                    cy="70"
                    r={RADIUS}
                    className="donut-segment"
                    stroke={STATUS_META[segment.status].color}
                    strokeDasharray={`${segment.dash[0]} ${segment.dash[1]}`}
                    strokeDashoffset={segment.offset}
                    transform="rotate(-90 70 70)"
                  />
                ))}
                <text x="70" y="66" textAnchor="middle" className="donut-total">
                  {summary.totalTasks}
                </text>
                <text x="70" y="84" textAnchor="middle" className="donut-caption">
                  項任務
                </text>
              </svg>
            </figure>

            <div className="handover-meta">
              <p className="handover-title">
                {summary.author}・{summary.from} ～ {summary.to}
              </p>
              <ul className="handover-legend">
                {STATUS_ORDER.map((status) => (
                  <li key={status}>
                    <span
                      className="legend-dot"
                      style={{ background: STATUS_META[status].color }}
                      aria-hidden="true"
                    />
                    {STATUS_META[status].label}
                    <strong>{summary.statusCounts[status] ?? 0}</strong>
                  </li>
                ))}
              </ul>
              <p className="handover-note">
                已完成 {doneCount} / {summary.totalTasks} 項。
                {summary.duplicatesRemoved > 0 &&
                  `（原始 ${summary.rawTaskCount} 筆，已合併 ${summary.duplicatesRemoved} 筆重複）`}
              </p>
            </div>
          </div>

          <h4>待交接任務（{openTasks.length}）</h4>
          {openTasks.length === 0 ? (
            <p className="handover-empty">沒有未完成的任務 🎉</p>
          ) : (
            <ul className="handover-tasks">
              {openTasks.map((task) => (
                <li key={`${task.reportId}-${task.taskName}`} className={`task-${task.status.toLowerCase()}`}>
                  <div className="task-head">
                    <span className={`priority-chip priority-${task.priority.toLowerCase()}`}>
                      {PRIORITY_LABEL[task.priority]}
                    </span>
                    <span className="task-name">{task.taskName}</span>
                    <span className="task-status" style={{ color: STATUS_META[task.status].color }}>
                      {STATUS_META[task.status].label}
                    </span>
                  </div>
                  <div
                    className="task-bar"
                    role="progressbar"
                    aria-valuemin={0}
                    aria-valuemax={100}
                    aria-valuenow={task.progressPercent}
                    aria-label={`${task.taskName} 進度`}
                  >
                    <span
                      style={{
                        width: `${task.progressPercent}%`,
                        background: STATUS_META[task.status].color,
                      }}
                    />
                  </div>
                  <div className="task-foot">
                    <span>{task.progressPercent}%</span>
                    <span>最後更新 {task.lastReportDate}</span>
                    {task.occurrences > 1 && <span>出現 {task.occurrences} 次</span>}
                  </div>
                  {task.blockerNote && (
                    <p className="task-blocker">⚠️ 卡關原因：{task.blockerNote}</p>
                  )}
                </li>
              ))}
            </ul>
          )}

          {doneTasks.length > 0 && (
            <details className="handover-done">
              <summary>已完成任務（{doneTasks.length}）</summary>
              <ul>
                {doneTasks.map((task) => (
                  <li key={`${task.reportId}-${task.taskName}`}>
                    ✅ {task.taskName}
                    <span>（{task.lastReportDate}）</span>
                  </li>
                ))}
              </ul>
            </details>
          )}
        </>
      )}
    </section>
  )
}
