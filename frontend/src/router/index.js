import { createRouter, createWebHashHistory } from 'vue-router'
import { VIEW_META } from '../app/navigation'
import { useSessionStore } from '../stores/session'

const RouteAnchor = { name: 'RouteAnchor', render: () => null }

const routes = [
  { path: '/', redirect: VIEW_META.overview.path },
  ...Object.entries(VIEW_META).map(([view, meta]) => ({
    path: meta.path,
    name: view,
    component: RouteAnchor,
    meta: { view, access: meta.access || 'authenticated' }
  })),
  {
    path: '/tickets/:ticketId(\\d+)',
    name: 'ticketDetail',
    component: RouteAnchor,
    meta: { view: 'tickets', access: 'authenticated' }
  },
  { path: '/:pathMatch(.*)*', redirect: VIEW_META.overview.path }
]

export const router = createRouter({
  history: createWebHashHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

export function installAccessGuard(pinia) {
  router.beforeEach(to => {
    const session = useSessionStore(pinia)
    if (!session.authenticated) return true
    if (to.meta.access === 'admin' && !session.isAdmin) return VIEW_META.overview.path
    if (to.meta.access === 'staff' && !session.isStaff) return VIEW_META.overview.path
    return true
  })
}

