import assert from 'node:assert/strict'
import test from 'node:test'
import {
  formatSlaRemaining,
  slaState,
  ticketActions,
  ticketStepState
} from '../src/app/ticketWorkflow.js'

test('staff actions mirror backend ticket transitions', () => {
  assert.deepEqual(ticketActions('OPEN', true).map(action => action.target), ['IN_PROGRESS', 'REJECTED'])
  assert.deepEqual(ticketActions('IN_PROGRESS', true).map(action => action.target), ['WAITING_CONFIRMATION', 'REJECTED'])
  assert.deepEqual(ticketActions('WAITING_CONFIRMATION', true).map(action => action.target), ['IN_PROGRESS'])
  assert.deepEqual(ticketActions('RESOLVED', true).map(action => action.target), ['IN_PROGRESS', 'CLOSED'])
  assert.deepEqual(ticketActions('CLOSED', true), [])
})

test('requesters only receive confirmation actions', () => {
  assert.deepEqual(ticketActions('OPEN', false), [])
  assert.deepEqual(ticketActions('WAITING_CONFIRMATION', false).map(action => action.target), ['IN_PROGRESS', 'RESOLVED'])
  assert.equal(ticketActions('WAITING_CONFIRMATION', false)[0].decision, 'REOPEN')
  assert.deepEqual(ticketActions('RESOLVED', false).map(action => action.target), ['CLOSED'])
})

test('workflow stage marks completed, current and upcoming steps', () => {
  assert.equal(ticketStepState('WAITING_CONFIRMATION', 'OPEN'), 'complete')
  assert.equal(ticketStepState('WAITING_CONFIRMATION', 'WAITING_CONFIRMATION'), 'current')
  assert.equal(ticketStepState('WAITING_CONFIRMATION', 'RESOLVED'), 'upcoming')
  assert.equal(ticketStepState('CLOSED', 'CLOSED'), 'complete')
  assert.equal(ticketStepState('REJECTED', 'OPEN'), 'blocked')
})

test('SLA state distinguishes active risk and terminal tickets', () => {
  const now = Date.parse('2026-10-26T08:00:00Z')
  assert.equal(slaState({ status: 'OPEN', dueAt: '2026-10-26T07:00:00Z' }, now).key, 'overdue')
  assert.equal(slaState({ status: 'OPEN', dueAt: '2026-10-26T18:00:00Z' }, now).key, 'due-soon')
  assert.equal(slaState({ status: 'OPEN', dueAt: '2026-10-28T08:00:00Z' }, now).key, 'on-track')
  assert.equal(slaState({ status: 'RESOLVED', dueAt: '2026-10-25T08:00:00Z' }, now).key, 'completed')
  assert.equal(slaState({ status: 'OPEN' }, now).key, 'unknown')
})

test('SLA remaining text is concise for deadlines and breaches', () => {
  assert.equal(formatSlaRemaining(26 * 60 * 60 * 1000), '剩余 1 天 2 小时')
  assert.equal(formatSlaRemaining(-90 * 60 * 1000), '已超时 1 小时 30 分钟')
  assert.equal(formatSlaRemaining(null), '未配置 SLA 截止时间')
})
