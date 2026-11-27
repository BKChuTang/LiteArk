import { defineStore } from 'pinia'

/** 二次验证中间态只放内存，不写 localStorage；刷新页面需重新登录。 */
export const useTotpPendingStore = defineStore('totpPending', {
  state: () => ({ token: '', username: '', redirect: '', ttlDays: 30 }),
  getters: {
    ready: (s) => !!s.token,
  },
  actions: {
    start(token, username, redirect, ttlDays) {
      this.token = token
      this.username = username || ''
      this.redirect = redirect || ''
      this.ttlDays = Number(ttlDays || 30)
    },
    clear() {
      this.token = ''
      this.username = ''
      this.redirect = ''
      this.ttlDays = 30
    },
  },
})
