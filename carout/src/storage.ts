import { loadNativeProgress, saveNativeProgress } from './native-bridge'

// 进度存档：Android 宿主为主、localStorage 为浏览器调试降级。
export interface Progress {
  unlocked: number // 已解锁到第几关（1-based）
  done: Record<number, boolean> // levelIndex -> 已通关
}

const KEY = 'parking-jam-progress'

export function loadProgress(): Progress {
  const nativeProgress = loadNativeProgress()
  if (nativeProgress) return normalizeProgress(nativeProgress)
  try {
    const raw = localStorage.getItem(KEY)
    if (raw) {
      const p = JSON.parse(raw)
      // 兼容旧版 stars 存档
      const done: Record<number, boolean> = p.done ?? {}
      if (p.stars) for (const k of Object.keys(p.stars)) if (p.stars[k] > 0) done[+k] = true
      // 旧存档中的 coins 字段会在规范化时自然丢弃，完成无损迁移。
      return normalizeProgress({ unlocked: p.unlocked ?? 1, done })
    }
  } catch {
    /* 忽略损坏数据 */
  }
  return { unlocked: 1, done: {} }
}

export function saveProgress(p: Progress) {
  const stableProgress = normalizeProgress(p)
  try {
    localStorage.setItem(KEY, JSON.stringify(stableProgress))
  } catch {
    /* 存储不可用时静默失败 */
  }
  saveNativeProgress(stableProgress)
}

function normalizeProgress(progress: Progress): Progress {
  const done: Record<number, boolean> = {}
  for (const [key, value] of Object.entries(progress.done ?? {})) {
    const index = Number(key)
    if (Number.isInteger(index) && index >= 0 && index < 30 && value) done[index] = true
  }
  return {
    unlocked: Math.min(30, Math.max(1, Number(progress.unlocked) || 1)),
    done,
  }
}
