import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import { installAccessGuard, router } from './router'
import './style.css'

const app = createApp(App)
const pinia = createPinia()

installAccessGuard(pinia)
app.use(pinia)
app.use(router)
app.mount('#app')
