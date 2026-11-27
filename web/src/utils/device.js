const KEY = 'liteark_device_id'

function randomId() {
  if (globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function') {
    return globalThis.crypto.randomUUID()
  }
  const bytes = new Uint8Array(16)
  if (globalThis.crypto && globalThis.crypto.getRandomValues) {
    globalThis.crypto.getRandomValues(bytes)
  } else {
    for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256)
  }
  return Array.from(bytes).map((b) => b.toString(16).padStart(2, '0')).join('')
}

/** 浏览器本地设备标识；用于信任设备判定，不随退出登录清除。 */
export function deviceId() {
  let id = localStorage.getItem(KEY)
  if (!id) {
    id = randomId()
    localStorage.setItem(KEY, id)
  }
  return id
}
