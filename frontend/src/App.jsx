import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { todoApi, vaultApi } from './api.js'

const STATUS_POLL_MS = 2_000

function formatTime(value, fallback = '—') {
  if (!value) return fallback
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return fallback
  return new Intl.DateTimeFormat('vi-VN', {
    hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false,
  }).format(date)
}

function shortLeaseId(value) {
  if (!value) return '—'
  const tail = value.split('/').filter(Boolean).at(-1) || value
  return `…/${tail.slice(-8)}`
}

function secondsUntil(value, now) {
  if (!value) return null
  const seconds = Math.ceil((new Date(value).getTime() - now) / 1_000)
  return Number.isFinite(seconds) ? Math.max(0, seconds) : null
}

function formatDuration(totalSeconds) {
  if (totalSeconds === null || totalSeconds === undefined || !Number.isFinite(totalSeconds)) return '—'
  const hours = Math.floor(totalSeconds / 3_600)
  const minutes = Math.floor((totalSeconds % 3_600) / 60)
  const seconds = totalSeconds % 60
  return hours > 0
    ? `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
    : `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`
}

function eventFromStatus(previous, current) {
  if (!current) return null
  if (!previous && current.currentDbUsername) {
    return { type: 'issued', text: `Credential được cấp cho ${current.currentDbUsername}` }
  }
  const generationChanged = previous?.credentialGeneration !== undefined
    && current.credentialGeneration !== undefined
    && previous.credentialGeneration !== current.credentialGeneration
  const credentialChanged = previous?.currentDbUsername
    && current.currentDbUsername
    && (previous.currentDbUsername !== current.currentDbUsername
      || previous.passwordFingerprint !== current.passwordFingerprint)
  if (generationChanged || credentialChanged || (previous?.leaseId && current.leaseId && previous.leaseId !== current.leaseId)) {
    return { type: 'rotated', text: `Credential đã rotate sang ${current.currentDbUsername}` }
  }
  if ((current.renewalCount || 0) > (previous?.renewalCount || 0)) {
    return { type: 'renewed', text: `Mốc renew VSO ước tính #${current.renewalCount}` }
  }
  if (previous?.connectionState !== current.connectionState) {
    return { type: 'state', text: current.message || `Trạng thái: ${current.connectionState}` }
  }
  return null
}

function App() {
  const [todos, setTodos] = useState([])
  const [vaultStatus, setVaultStatus] = useState(null)
  const [events, setEvents] = useState([])
  const [newTitle, setNewTitle] = useState('')
  const [editing, setEditing] = useState(null)
  const [pendingDelete, setPendingDelete] = useState(null)
  const [loadingTodos, setLoadingTodos] = useState(true)
  const [todoError, setTodoError] = useState('')
  const [vaultError, setVaultError] = useState('')
  const [busyId, setBusyId] = useState(null)
  const [now, setNow] = useState(Date.now())
  const previousStatus = useRef(null)

  const loadTodos = useCallback(async () => {
    try {
      setTodoError('')
      const data = await todoApi.list()
      setTodos(Array.isArray(data) ? data : data.content || [])
    } catch (error) {
      setTodoError(error.message)
    } finally {
      setLoadingTodos(false)
    }
  }, [])

  const loadVaultStatus = useCallback(async () => {
    try {
      const data = await vaultApi.status()
      const event = eventFromStatus(previousStatus.current, data)
      if (event) {
        const eventAt = event.type === 'renewed' ? data.lastRenewedAt : data.acquiredAt
        setEvents((current) => [
          { ...event, at: eventAt || new Date().toISOString() },
          ...current,
        ].slice(0, 4))
      }
      previousStatus.current = data
      setVaultStatus(data)
      setVaultError('')
    } catch (error) {
      setVaultError(error.message)
    }
  }, [])

  useEffect(() => {
    loadTodos()
    loadVaultStatus()
    const poll = window.setInterval(loadVaultStatus, STATUS_POLL_MS)
    const clock = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => {
      window.clearInterval(poll)
      window.clearInterval(clock)
    }
  }, [loadTodos, loadVaultStatus])

  const completedCount = todos.filter((todo) => todo.completed).length
  const connected = ['CONNECTED', 'RENEWED', 'READY', 'ACTIVE'].includes(vaultStatus?.connectionState)
  const managedByVso = vaultStatus?.renewable === true
  const nextActionAt = vaultStatus?.nextRenewalAt || vaultStatus?.estimatedRotationAt || vaultStatus?.expiresAt
  const remainingSeconds = secondsUntil(nextActionAt, now)
  const leaseProgress = useMemo(() => {
    const start = new Date(vaultStatus?.acquiredAt).getTime()
    const end = new Date(vaultStatus?.expiresAt).getTime()
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) return 0
    return Math.min(100, Math.max(0, ((now - start) / (end - start)) * 100))
  }, [now, vaultStatus?.acquiredAt, vaultStatus?.expiresAt])

  async function createTodo(event) {
    event.preventDefault()
    const title = newTitle.trim()
    if (!title) return
    try {
      setBusyId('new')
      setTodoError('')
      const created = await todoApi.create(title)
      setTodos((current) => [...current, created])
      setNewTitle('')
    } catch (error) {
      setTodoError(error.message)
    } finally {
      setBusyId(null)
    }
  }

  async function updateTodo(todo, patch) {
    try {
      setBusyId(todo.id)
      setTodoError('')
      const updated = await todoApi.update(todo.id, { ...todo, ...patch })
      setTodos((current) => current.map((item) => item.id === todo.id ? updated : item))
      setEditing(null)
    } catch (error) {
      setTodoError(error.message)
    } finally {
      setBusyId(null)
    }
  }

  async function deleteTodo(id) {
    try {
      setBusyId(id)
      setTodoError('')
      await todoApi.remove(id)
      setTodos((current) => current.filter((todo) => todo.id !== id))
      setPendingDelete(null)
    } catch (error) {
      setTodoError(error.message)
    } finally {
      setBusyId(null)
    }
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <div className="brand-mark" aria-hidden="true">VL</div>
        <div>
          <p className="eyebrow">Kubernetes · PostgreSQL · Vault VSO</p>
          <h1>Database Credential Lab</h1>
        </div>
        <div className={`connection-pill ${connected ? 'connected' : 'disconnected'}`}>
          <span className="status-dot" />
          {connected ? 'DB credential đang hoạt động' : 'Đang chờ credential'}
        </div>
      </header>

      <section className="workspace">
        <article className="todo-panel">
          <div className="section-heading">
            <div>
              <p className="eyebrow">Ứng dụng kiểm chứng</p>
              <h2>Todo hôm nay</h2>
            </div>
            <span className="counter">{completedCount} / {todos.length} hoàn tất</span>
          </div>

          <form className="composer" onSubmit={createTodo}>
            <label className="sr-only" htmlFor="new-todo">Nội dung todo mới</label>
            <input
              id="new-todo"
              value={newTitle}
              onChange={(event) => setNewTitle(event.target.value)}
              placeholder="Thêm một việc cần làm…"
              maxLength={200}
              disabled={busyId === 'new'}
            />
            <button type="submit" disabled={!newTitle.trim() || busyId === 'new'}>
              {busyId === 'new' ? 'Đang lưu…' : 'Thêm todo'}
            </button>
          </form>

          {todoError && (
            <div className="error-banner" role="alert">
              <span>{todoError}</span>
              <button onClick={loadTodos}>Thử lại</button>
            </div>
          )}

          {loadingTodos ? (
            <div className="loading-list" aria-label="Đang tải todo"><span /><span /><span /></div>
          ) : todos.length === 0 ? (
            <div className="empty-state">
              <strong>PostgreSQL đã sẵn sàng.</strong>
              <p>Thêm todo đầu tiên để kiểm tra thao tác ghi bằng credential hiện tại.</p>
            </div>
          ) : (
            <ul className="todo-list">
              {todos.map((todo) => {
                const isEditing = editing?.id === todo.id
                const isDeleting = pendingDelete === todo.id
                return (
                  <li key={todo.id} className={todo.completed ? 'is-complete' : ''}>
                    <button
                      className="check"
                      aria-label={`${todo.completed ? 'Bỏ hoàn tất' : 'Đánh dấu hoàn tất'} ${todo.title}`}
                      onClick={() => updateTodo(todo, { completed: !todo.completed })}
                      disabled={busyId === todo.id}
                    >
                      {todo.completed ? '✓' : ''}
                    </button>

                    {isEditing ? (
                      <form
                        className="edit-form"
                        onSubmit={(event) => {
                          event.preventDefault()
                          const title = editing.title.trim()
                          if (title) updateTodo(todo, { title })
                        }}
                      >
                        <label className="sr-only" htmlFor={`edit-${todo.id}`}>Sửa todo</label>
                        <input
                          id={`edit-${todo.id}`}
                          value={editing.title}
                          onChange={(event) => setEditing({ ...editing, title: event.target.value })}
                          maxLength={200}
                          autoFocus
                        />
                        <button type="submit" disabled={!editing.title.trim()}>Lưu</button>
                        <button type="button" onClick={() => setEditing(null)}>Huỷ</button>
                      </form>
                    ) : (
                      <span className="todo-title">{todo.title}</span>
                    )}

                    {!isEditing && (
                      isDeleting ? (
                        <div className="delete-confirm">
                          <button onClick={() => deleteTodo(todo.id)} disabled={busyId === todo.id}>Xác nhận</button>
                          <button onClick={() => setPendingDelete(null)}>Huỷ</button>
                        </div>
                      ) : (
                        <div className="row-actions">
                          <button className="text-action" onClick={() => setEditing({ id: todo.id, title: todo.title })}>Sửa</button>
                          <button className="text-action danger" onClick={() => setPendingDelete(todo.id)}>Xoá</button>
                        </div>
                      )
                    )}
                  </li>
                )
              })}
            </ul>
          )}

          <div className="database-proof">
            <span className="proof-icon">DB</span>
            <div>
              <strong>Dữ liệu được lưu trên PostgreSQL thật</strong>
              <p>Mỗi thao tác thêm, sửa, xoá đi qua pool kết nối dùng credential đang hiển thị.</p>
            </div>
          </div>
        </article>

        <aside className="vault-panel">
          <div className="vault-heading">
            <div>
              <p className="eyebrow">Projected Kubernetes Secret</p>
              <h2>{managedByVso ? 'Dynamic lease đang hoạt động' : 'Bootstrap credential'}</h2>
            </div>
            <span className={`live-badge ${connected ? '' : 'waiting'}`}>{connected ? 'LIVE' : 'WAIT'}</span>
          </div>

          {vaultError && <div className="vault-error" role="alert">{vaultError}</div>}

          <div className="credential-card">
            <p className="field-label">PostgreSQL username</p>
            <code>{vaultStatus?.currentDbUsername || 'Đang chờ Secret…'}</code>
            <p className="field-label">Password fingerprint</p>
            <code>{vaultStatus?.passwordFingerprint || '—'}</code>
            <p className="safe-note">Fingerprint giúp nhận biết password đã đổi; secret gốc không rời khỏi backend.</p>
          </div>

          <div className="lease-clock">
            <div className="clock-value">{managedByVso ? formatDuration(remainingSeconds) : 'STATIC'}</div>
            <div>
              <p className="field-label">{managedByVso ? `Còn lại trước lần ${vaultStatus?.nextRenewalAt ? 'renew dự kiến' : 'rotate dự kiến'}` : 'Giai đoạn 1'}</p>
              <strong>{managedByVso && nextActionAt ? `Tự động lúc ${formatTime(nextActionAt)}` : 'Chưa được VSO quản lý'}</strong>
            </div>
          </div>

          <div className="timeline" aria-label="Tiến trình lease">
            <div className="timeline-track"><span style={{ width: `${leaseProgress}%` }} /></div>
            <div className="timeline-labels">
              <span>Cấp lúc {formatTime(vaultStatus?.acquiredAt)}</span>
              <span>TTL {vaultStatus?.leaseDurationSeconds ? formatDuration(vaultStatus.leaseDurationSeconds) : '—'}</span>
            </div>
          </div>

          <dl className="lease-details">
            <div><dt>Trạng thái</dt><dd className={vaultStatus?.renewable ? 'ok' : ''}>{vaultStatus?.renewable ? 'Renewable' : vaultStatus?.connectionState || '—'}</dd></div>
            <div><dt>Renew ước tính</dt><dd>{managedByVso ? (vaultStatus?.renewalCount ?? '—') : '—'}</dd></div>
            <div><dt>Số lần rotate</dt><dd>{vaultStatus?.rotationCount ?? Math.max(0, (vaultStatus?.credentialGeneration ?? 1) - 1)}</dd></div>
            <div><dt>Dự kiến rotate</dt><dd>{formatTime(vaultStatus?.nextRotationAt || vaultStatus?.estimatedRotationAt || vaultStatus?.maxLeaseExpiresAt)}</dd></div>
            <div><dt>Nguồn credential</dt><dd>{vaultStatus?.credentialSource || 'Kubernetes Secret'}</dd></div>
            <div><dt>Ngưỡng VSO renew</dt><dd>{vaultStatus?.renewalPercent != null ? `${vaultStatus.renewalPercent}% TTL` : '—'}</dd></div>
            <div><dt>Lease ID</dt><dd><code>{shortLeaseId(vaultStatus?.leaseId)}</code></dd></div>
            <div><dt>Lần renew cuối</dt><dd>{formatTime(vaultStatus?.lastRenewedAt)}</dd></div>
          </dl>

          <section className="event-log" aria-labelledby="event-title">
            <div className="event-title-row">
              <h3 id="event-title">Sự kiện trong phiên</h3>
              <span>Tự cập nhật</span>
            </div>
            {events.length ? (
              <ol>
                {events.map((event, index) => (
                  <li key={`${event.type}-${event.at}-${index}`}>
                    <span className={`event-dot ${event.type}`} />
                    <div><strong>{event.text}</strong><time>{formatTime(event.at)}</time></div>
                  </li>
                ))}
              </ol>
            ) : (
              <p className="event-empty">Sự kiện nạp Secret, VSO sync và rotate sẽ xuất hiện ở đây.</p>
            )}
          </section>

          {vaultStatus?.message && <p className="status-message">{vaultStatus.message}</p>}
        </aside>
      </section>
    </main>
  )
}

export { eventFromStatus, formatDuration, shortLeaseId }
export default App
