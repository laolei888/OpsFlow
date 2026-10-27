import assert from 'node:assert/strict'
import test from 'node:test'
import { knowledgeQueueCount, reviewDueLabel, reviewWaitLabel } from '../src/app/knowledgeGovernance.js'

const NOW = new Date('2026-10-27T08:00:00.000Z').getTime()

test('knowledge queue counts prefer server analytics over the loaded page', () => {
  assert.equal(knowledgeQueueCount({ pendingReviewArticles: 12 }, 'pendingReviewArticles', [{}, {}]), 12)
  assert.equal(knowledgeQueueCount(null, 'pendingReviewArticles', [{}, {}]), 2)
})

test('review wait labels communicate queue age', () => {
  assert.equal(reviewWaitLabel('2026-10-27T07:45:00.000Z', NOW), '刚刚提交')
  assert.equal(reviewWaitLabel('2026-10-27T03:00:00.000Z', NOW), '已等待 5 小时')
  assert.equal(reviewWaitLabel('2026-10-24T08:00:00.000Z', NOW), '已等待 3 天')
})

test('review due labels show overdue and remaining days', () => {
  assert.equal(reviewDueLabel('2026-10-25T08:00:00.000Z', NOW), '已逾期 2 天')
  assert.equal(reviewDueLabel('2026-10-30T08:00:00.000Z', NOW), '剩余 3 天')
  assert.equal(reviewDueLabel(null, NOW), '复核日期未知')
})
