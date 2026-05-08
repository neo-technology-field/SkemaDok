/**
 * Thin wrapper around the SkemaDok REST API.
 * All functions are async and throw on non-2xx responses.
 */

async function request(method, path, body) {
  const options = {
    method,
    headers: {'Content-Type': 'application/json'}
  }
  if (body !== undefined) {
    options.body = JSON.stringify(body)
  }
  const res = await fetch('/api' + path, options)
  if (!res.ok) {
    const text = await res.text()
    throw new Error(`${method} ${path} → ${res.status}: ${text}`)
  }
  return res.status === 204 ? null : res.json()
}

export const schemaApi = {
  getSchema:   ()         => request('GET', '/schema'),
  saveSchema:  (document) => request('PUT', '/schema', document),
  getFilename: ()         => fetch('/api/schema/filename').then(r => r.ok ? r.text() : Promise.reject(new Error(`GET /schema/filename → ${r.status}`))),
}
