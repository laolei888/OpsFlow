import { computed, unref } from 'vue'

export function usePermissions(user) {
  const roles = computed(() => unref(user)?.roles || [])
  const isAdmin = computed(() => roles.value.includes('ADMIN'))
  const isStaff = computed(() => roles.value.some(role => ['AGENT', 'ADMIN'].includes(role)))
  const isRequester = computed(() => roles.value.includes('REQUESTER') && !isStaff.value)

  function hasAnyRole(...allowedRoles) {
    return allowedRoles.some(role => roles.value.includes(role))
  }

  function canAccess(access = 'authenticated') {
    if (access === 'admin') return isAdmin.value
    if (access === 'staff') return isStaff.value
    return Boolean(unref(user))
  }

  return { roles, isAdmin, isStaff, isRequester, hasAnyRole, canAccess }
}

