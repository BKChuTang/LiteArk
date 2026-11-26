import { defineStore } from 'pinia'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('liteark_token') || '',
    user: JSON.parse(localStorage.getItem('liteark_user') || 'null'),
  }),
  getters: {
    isLogin: (s) => !!s.token,
    isAdmin: (s) => s.user && s.user.role_id === 1,
  },
  actions: {
    setLogin(token, user) {
      this.token = token
      this.user = user
      localStorage.setItem('liteark_token', token)
      localStorage.setItem('liteark_user', JSON.stringify(user))
    },
    updateUser(user) {
      this.user = { ...this.user, ...user }
      localStorage.setItem('liteark_user', JSON.stringify(this.user))
    },
    logout() {
      this.token = ''
      this.user = null
      localStorage.removeItem('liteark_token')
      localStorage.removeItem('liteark_user')
    },
  },
})
