const assert = require('node:assert/strict')
const path = require('node:path')
const { test } = require('node:test')
const { loadTypeScript } = require('./helpers/load-typescript.cjs')

test('API envelope and HTTP rejection retain precise business reasons for inline recovery', async () => {
  for (const rejectedHttp of [false, true]) {
    const envelope = { code: 4204, reason: 'source_unregistered', message: 'Source binding unavailable', data: null }
    const axios = {
      create: () => ({ get: async () => {
        if (rejectedHttp) throw { isAxiosError: true, response: { data: envelope } }
        return { data: envelope }
      } }),
      isAxiosError: error => error?.isAxiosError === true
    }
    const client = loadTypeScript(path.join(__dirname, '../src/api/client.ts'), { dependencies: { axios: { default: axios } } })
    const reported = []; client.setApiErrorReporter(message => reported.push(message))
    await assert.rejects(client.api.get('/advice/project-A/index/status', { reportError: false }), error =>
      error instanceof client.ApiError && error.code === 4204 && error.reason === 'source_unregistered' && error.message === envelope.message)
    assert.deepEqual(reported, [], 'the view owns its actionable inline error without a raw duplicate toast')
  }
})
