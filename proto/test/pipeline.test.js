import assert from 'node:assert/strict'
import test from 'node:test'
import { substituteInputPlaceholders } from '../src/utils/pipeline.js'

const inputs = [
  { name: '兑换码', pipeline_type: 'string' },
  { name: '次数', pipeline_type: 'int' },
  { name: '启用', pipeline_type: 'bool' },
]

test('whole-token placeholders preserve declared input types', () => {
  const override = {
    codes: '{兑换码}',
    count: '{次数}',
    enabled: '{启用}',
  }

  assert.deepEqual(
    substituteInputPlaceholders(override, inputs, {
      兑换码: '占位',
      次数: '3',
      启用: 'true',
    }),
    {
      codes: '占位',
      count: 3,
      enabled: true,
    },
  )
})

test('embedded and array placeholders remain strings and recurse', () => {
  const override = {
    messages: ['count={次数}', { value: '{次数}' }],
    missing: '{未声明}',
  }

  assert.deepEqual(
    substituteInputPlaceholders(override, inputs, { 次数: '4' }),
    {
      messages: ['count=4', { value: 4 }],
      missing: '{未声明}',
    },
  )
})
