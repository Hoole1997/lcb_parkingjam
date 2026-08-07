import { loadNativeSoundEnabled, saveNativeSoundEnabled } from './native-bridge'

const SOUND_ENABLED_KEY = 'parking-jam-sound-enabled'

/** 原生宿主为主；浏览器独立运行时使用 localStorage，便于调试同一套交互。 */
export function loadSoundEnabled(): boolean {
  const nativeValue = loadNativeSoundEnabled()
  if (nativeValue !== null) return nativeValue

  try {
    const storedValue = localStorage.getItem(SOUND_ENABLED_KEY)
    return storedValue === null ? true : storedValue === 'true'
  } catch {
    return true
  }
}

export function saveSoundEnabled(enabled: boolean) {
  try {
    localStorage.setItem(SOUND_ENABLED_KEY, String(enabled))
  } catch {
    // 无痕模式或存储空间不可用时，当前页面仍可正常切换声音。
  }
  saveNativeSoundEnabled(enabled)
}
