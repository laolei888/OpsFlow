import { defineStore } from 'pinia'

export const TOKEN_KEY = 'opsflow_token'
export const USER_KEY = 'opsflow_user'

function storedUser() {
  try {
    return JSON.parse(localStorage.getItem(USER_KEY) || 'null')
  } catch (_) {
    localStorage.removeItem(USER_KEY)
    return null
  }
}

export const useSessionStore = defineStore('session', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: storedUser()
  }),
  getters: {
    authenticated: state => Boolean(state.token),
    roles: state => state.user?.roles || [],
    displayName: state => state.user?.displayName || state.user?.username || '协作成员',
    isAdmin() {
      return this.roles.includes('ADMIN')
    },
    isStaff() {
      return this.roles.some(role => ['AGENT', 'ADMIN'].includes(role))
    }
  },
  actions: {
    establish(data) {
      this.token = data.token
      this.user = data
      localStorage.setItem(TOKEN_KEY, data.token)
      localStorage.setItem(USER_KEY, JSON.stringify(data))
    },
    clear() {
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
      localStorage.removeItem(USER_KEY)
    }
  }
})

