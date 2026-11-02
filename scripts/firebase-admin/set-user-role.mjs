import { applicationDefault, initializeApp } from 'firebase-admin/app'
import { getAuth } from 'firebase-admin/auth'
import { createInterface } from 'node:readline/promises'
import { stdin, stdout } from 'node:process'

const prompt = createInterface({ input: stdin, output: stdout })

try {
  console.log('Firebase 使用者角色設定（僅限可信任管理端執行）')
  console.log('認證使用本機 Google Application Default Credentials，不需要輸入或貼上私密金鑰。')
  console.log('先在此電腦執行：gcloud auth application-default login')
  console.log('')

  const projectId = (await prompt.question(
    '1/4 Firebase Project ID [sdlc-ai-chatbot-e017d]: ',
  )).trim() || 'sdlc-ai-chatbot-e017d'
  const email = (await prompt.question('2/4 使用者登入 Email: ')).trim()
  const role = (await prompt.question('3/4 指派角色 (DEVELOPER/MANAGER/ADMIN): '))
    .trim()
    .toUpperCase()

  if (!/^[a-z0-9][a-z0-9-]{4,28}[a-z0-9]$/.test(projectId)) {
    throw new Error('Firebase Project ID 格式不正確。')
  }
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    throw new Error('請輸入有效的使用者 Email。')
  }
  if (!['DEVELOPER', 'MANAGER', 'ADMIN'].includes(role)) {
    throw new Error('角色只能是 DEVELOPER、MANAGER 或 ADMIN。')
  }

  console.log(`\n目標 Firebase Project ID：${projectId}`)
  const app = initializeApp({
    credential: applicationDefault(),
    projectId,
  })
  const auth = getAuth(app)
  const user = await auth.getUserByEmail(email)
  const existingClaims = user.customClaims ?? {}

  console.log('')
  console.log(`專案：${projectId}`)
  console.log(`帳號：${user.email}`)
  console.log(`角色：${role}`)
  console.log(`既有 roles：${Array.isArray(existingClaims.roles) ? existingClaims.roles.join(', ') : '無'}`)
  const confirmation = (await prompt.question('4/4 輸入 APPLY 才會套用，其他輸入皆取消: ')).trim()
  if (confirmation !== 'APPLY') {
    console.log('已取消，沒有修改使用者權限。')
    process.exitCode = 0
  } else {
    await auth.setCustomUserClaims(user.uid, {
      ...existingClaims,
      roles: [role],
    })
    console.log(`已為 ${user.email} 設定 ${role}。請使用者重新登入以取得更新後的 ID Token。`)
  }
} catch (error) {
  console.error('設定 Firebase 角色失敗:', error instanceof Error ? error.message : error)
  process.exitCode = 1
} finally {
  prompt.close()
}
