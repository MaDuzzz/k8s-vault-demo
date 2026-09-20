const JSON_HEADERS = { 'Content-Type': 'application/json' }

async function request(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: options.body ? { ...JSON_HEADERS, ...options.headers } : options.headers,
  })

  if (!response.ok) {
    let message = `Yêu cầu thất bại (${response.status})`
    try {
      const payload = await response.json()
      message = payload.message || payload.detail || message
    } catch {
      // The response does not contain a JSON problem body.
    }
    throw new Error(message)
  }

  if (response.status === 204) return null
  return response.json()
}

export const todoApi = {
  list: () => request('/api/todos'),
  create: (title) => request('/api/todos', {
    method: 'POST',
    body: JSON.stringify({ title }),
  }),
  update: (id, todo) => request(`/api/todos/${id}`, {
    method: 'PUT',
    body: JSON.stringify({ title: todo.title, completed: todo.completed }),
  }),
  remove: (id) => request(`/api/todos/${id}`, { method: 'DELETE' }),
}

export const vaultApi = {
  status: () => request('/api/vault/status'),
}
