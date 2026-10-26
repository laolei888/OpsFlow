import assert from 'node:assert/strict'
import test from 'node:test'
import { intakeStepErrors, recommendPriority, TICKET_CATEGORIES } from '../src/app/ticketIntake.js'

test('ticket intake categories use stable unique values', () => {
  assert.equal(new Set(TICKET_CATEGORIES.map(item => item.value)).size, TICKET_CATEGORIES.length)
  assert.ok(TICKET_CATEGORIES.every(item => item.label && item.description && item.placeholder))
})

test('priority recommendation escalates broad outage without workaround', () => {
  assert.equal(recommendPriority({ serviceUnavailable: true, affectedUsers: 'MANY', workaround: 'NO' }).value, 'URGENT')
  assert.equal(recommendPriority({ serviceUnavailable: true, affectedUsers: 'SINGLE', workaround: 'YES' }).value, 'HIGH')
  assert.equal(recommendPriority({ serviceUnavailable: false, affectedUsers: 'TEAM', workaround: 'YES' }).value, 'MEDIUM')
  assert.equal(recommendPriority({ serviceUnavailable: false, affectedUsers: 'SINGLE', workaround: 'YES' }).value, 'LOW')
})

test('each intake step validates only its own required context', () => {
  assert.deepEqual(intakeStepErrors(1, { category: '', title: '' }), ['请选择问题分类', '请填写问题标题'])
  assert.deepEqual(intakeStepErrors(2, { description: '报错', impactScope: '', expectedOutcome: '' }), ['请说明影响范围', '请说明期望结果'])
  assert.deepEqual(intakeStepErrors(3, { serviceUnavailable: false, affectedUsers: 'SINGLE', workaround: 'YES', priority: 'LOW' }), [])
})
