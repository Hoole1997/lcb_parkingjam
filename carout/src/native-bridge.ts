import type { Progress } from './storage'

/** Android 仅注入到可信本地页面的最小桥接接口。 */
interface AndroidCaroutBridge {
  loadProgress(): string
  saveProgress(progressJson: string): void
  loadSoundEnabled(): boolean
  saveSoundEnabled(enabled: boolean): void
  firstFrameRendered(sessionId: number): void
  exitToGameHome(): void
  levelCompleted(levelNumber: number): void
  showToast(message: string, duration: NativeToastDuration): void
  levelStarted(levelNumber: number, entry: GameLevelEntry): void
  gameActionClicked(levelNumber: number, action: GameActionType): void
  levelResult(levelNumber: number, result: GameResultType): void
  resultActionClicked(
    levelNumber: number,
    result: GameResultType,
    action: GameResultActionType,
  ): void
  requestRewardedAd(placement: RewardedAdPlacement, requestId: number): void
}

export type GameLevelEntry =
  | 'home'
  | 'level_select'
  | 'next_level'
  | 'retry'
  | 'restart'
  | 'refresh'

export type GameActionType = 'back' | 'restart' | 'refresh' | 'sound_on' | 'sound_off'
export type GameResultType = 'win' | 'fail'
export type GameResultActionType = 'next_level' | 'retry' | 'home'
export type NativeToastDuration = 'short' | 'long'

export type RewardedAdPlacement =
  | 'tool_refresh'
  | 'tool_remove'
  | 'tool_sort'
  | 'slot_6'
  | 'slot_7'

declare global {
  interface Window {
    CaroutNative?: AndroidCaroutBridge
    CaroutHost?: {
      setPaused(paused: boolean): void
      completeRewardedAd(requestId: number, rewardEarned: boolean): void
    }
  }
}

export const hostMode = new URLSearchParams(location.search).get('host') === '1'
let nextRewardRequestId = 1
const pendingRewardRequests = new Map<number, (rewardEarned: boolean) => void>()

export function loadNativeProgress(): Progress | null {
  if (!hostMode || !window.CaroutNative) return null
  try {
    return JSON.parse(window.CaroutNative.loadProgress()) as Progress
  } catch {
    return null
  }
}

export function saveNativeProgress(progress: Progress) {
  if (!hostMode || !window.CaroutNative) return
  try {
    window.CaroutNative.saveProgress(JSON.stringify(progress))
  } catch {
    // 原生宿主异常不阻塞游戏帧，localStorage 仍保留降级存档。
  }
}

/** 同步读取原生偏好，null 表示当前不是原生宿主或桥接不可用。 */
export function loadNativeSoundEnabled(): boolean | null {
  if (!hostMode || !window.CaroutNative) return null
  try {
    return window.CaroutNative.loadSoundEnabled()
  } catch {
    return null
  }
}

export function saveNativeSoundEnabled(enabled: boolean) {
  if (!hostMode || !window.CaroutNative) return
  try {
    window.CaroutNative.saveSoundEnabled(enabled)
  } catch {
    // 偏好桥接异常不影响当前页面内的声音切换。
  }
}

export function exitToNativeGameHome(): boolean {
  if (!hostMode || !window.CaroutNative) return false
  try {
    window.CaroutNative.exitToGameHome()
    return true
  } catch {
    return false
  }
}

export function notifyNativeLevelCompleted(levelNumber: number) {
  if (!hostMode || !window.CaroutNative) return
  try {
    window.CaroutNative.levelCompleted(levelNumber)
  } catch {
    // 广告或埋点桥不可用不影响结算。
  }
}

/** 所有局内轻提示交由 Android Toast 展示，Canvas 不再维护提示层和计时状态。 */
export function showNativeToast(
  message: string,
  duration: NativeToastDuration = 'short',
): boolean {
  if (!hostMode || !window.CaroutNative || !message.trim()) return false
  try {
    window.CaroutNative.showToast(message, duration)
    return true
  } catch {
    return false
  }
}

export function notifyNativeLevelStarted(levelNumber: number, entry: GameLevelEntry) {
  if (!hostMode || !window.CaroutNative) return
  try {
    window.CaroutNative.levelStarted(levelNumber, entry)
  } catch {
    // 埋点桥异常不影响关卡初始化。
  }
}

export function notifyNativeGameAction(levelNumber: number, action: GameActionType) {
  if (!hostMode || !window.CaroutNative) return
  try {
    window.CaroutNative.gameActionClicked(levelNumber, action)
  } catch {
    // 埋点桥异常不影响用户操作。
  }
}

export function notifyNativeLevelResult(levelNumber: number, result: GameResultType) {
  if (!hostMode || !window.CaroutNative) return
  try {
    window.CaroutNative.levelResult(levelNumber, result)
  } catch {
    // 埋点桥异常不影响胜负结算。
  }
}

export function notifyNativeResultAction(
  levelNumber: number,
  result: GameResultType,
  action: GameResultActionType,
) {
  if (!hostMode || !window.CaroutNative) return
  try {
    window.CaroutNative.resultActionClicked(levelNumber, result, action)
  } catch {
    // 埋点桥异常不影响结果页导航。
  }
}

/** Canvas 完成首帧后通知 Android 交接窗口，避免原生窗口先显示空背景。 */
export function notifyNativeFirstFrameRendered() {
  if (!hostMode || !window.CaroutNative) return
  const sessionId = Number(new URLSearchParams(location.search).get('session'))
  if (!Number.isSafeInteger(sessionId) || sessionId <= 0) return
  try {
    window.CaroutNative.firstFrameRendered(sessionId)
  } catch {
    // 首帧通知只控制原生窗口交接，不参与游戏规则。
  }
}

/**
 * 向 Android 应用层申请一次激励广告。返回 false 表示当前不是原生宿主环境；每个请求
 * 都由 requestId 与结果一一对应，防止异步回调串单或重复发奖。
 */
export function requestNativeRewardedAd(
  placement: RewardedAdPlacement,
  onResult: (rewardEarned: boolean) => void,
): boolean {
  if (!hostMode || !window.CaroutNative) return false
  const requestId = nextRewardRequestId++
  pendingRewardRequests.set(requestId, onResult)
  try {
    window.CaroutNative.requestRewardedAd(placement, requestId)
    return true
  } catch {
    pendingRewardRequests.delete(requestId)
    return false
  }
}

/** 仅供 CaroutHost 的原生回调入口调用；消费后立即删除，保证最多结算一次。 */
export function completeNativeRewardedAd(requestId: number, rewardEarned: boolean) {
  const callback = pendingRewardRequests.get(requestId)
  if (!callback) return
  pendingRewardRequests.delete(requestId)
  callback(rewardEarned === true)
}
