// 交接摘要（Step 2b）：型別、API 呼叫與圖表計算。
// 數字全部來自後端 /api/v1/handover/summary（SQL 計算），前端只負責呈現。
import axios from 'axios'

export type TaskStatus = 'DONE' | 'IN_PROGRESS' | 'BLOCKED' | 'NOT_STARTED'
export type TaskPriority = 'HIGH' | 'MEDIUM' | 'LOW'
export type HandoverSort = 'priority' | 'progress'

export interface HandoverTask {
  taskName: string
  progressPercent: number
  priority: TaskPriority
  status: TaskStatus
  blockerNote: string | null
  lastReportDate: string
  reportId: string
  occurrences: number
}

export interface HandoverSummary {
  author: string
  from: string
  to: string
  rawTaskCount: number
  duplicatesRemoved: number
  totalTasks: number
  statusCounts: Partial<Record<TaskStatus, number>>
  tasks: HandoverTask[]
}

export const STATUS_ORDER: TaskStatus[] = [
  'DONE',
  'IN_PROGRESS',
  'BLOCKED',
  'NOT_STARTED',
]

export const STATUS_META: Record<TaskStatus, { label: string; color: string }> = {
  DONE: { label: '已完成', color: '#2e9e5b' },
  IN_PROGRESS: { label: '進行中', color: '#2f7de1' },
  BLOCKED: { label: '卡關', color: '#d9480f' },
  NOT_STARTED: { label: '未開始', color: '#9aa3af' },
}

export const PRIORITY_LABEL: Record<TaskPriority, string> = {
  HIGH: '高',
  MEDIUM: '中',
  LOW: '低',
}

export interface DonutSegment {
  status: TaskStatus
  count: number
  fraction: number
  /** stroke-dasharray 用：弧長、其餘長度 */
  dash: [number, number]
  /** stroke-dashoffset：讓每一段接在前一段後面 */
  offset: number
}

/** 把各狀態數量換成甜甜圈的每一段（圓周長 circumference）。數量為 0 的狀態不畫。 */
export function donutSegments(
  counts: Partial<Record<TaskStatus, number>>,
  circumference: number,
): DonutSegment[] {
  const total = STATUS_ORDER.reduce((sum, status) => sum + (counts[status] ?? 0), 0)
  if (total <= 0) return []

  let consumed = 0
  const segments: DonutSegment[] = []
  for (const status of STATUS_ORDER) {
    const count = counts[status] ?? 0
    if (count <= 0) continue
    const fraction = count / total
    const length = fraction * circumference
    segments.push({
      status,
      count,
      fraction,
      dash: [length, circumference - length],
      offset: -consumed,
    })
    consumed += length
  }
  return segments
}

export interface HandoverQuery {
  author?: string
  sort: HandoverSort
}

export async function fetchHandoverSummary(
  apiUrl: string,
  idToken: string,
  query: HandoverQuery,
): Promise<HandoverSummary> {
  const params: Record<string, string> = { sort: query.sort }
  if (query.author?.trim()) params.author = query.author.trim()
  const response = await axios.get<HandoverSummary>(apiUrl, {
    params,
    headers: { Authorization: `Bearer ${idToken}` },
  })
  return response.data
}
