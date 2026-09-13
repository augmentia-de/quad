import { useEffect, useState } from 'react'
import { fetchMessagingStatus, fetchMessagingChannels } from '../services/api'
import type { MessagingChannel, MessagingStatus } from '../services/types'

export function Messaging() {
  const [status, setStatus] = useState<MessagingStatus | null>(null)
  const [channels, setChannels] = useState<MessagingChannel[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    setError(null)
    try {
      const [s, c] = await Promise.all([fetchMessagingStatus(), fetchMessagingChannels()])
      setStatus(s)
      setChannels(c)
    } catch (err) {
      setError(String(err))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [])

  return (
    <div className="messaging-view">
      <h2>Messaging</h2>
      <p className="hint">Kafka, AMQP and Email channels: status and configuration</p>

      {loading && <p>Loading...</p>}
      {error && <p className="error-state">Error: {error}</p>}

      {status && (
        <section className="panel">
          <h3>Channel Status</h3>
          <div className="channel-grid">
            <div className="channel-group">
              <h4>Inbound</h4>
              {Object.keys(status.inbound).length === 0 ? (
                <p className="empty-state">No inbound channels active</p>
              ) : (
                <table>
                  <thead><tr><th>Channel</th><th>Status</th></tr></thead>
                  <tbody>
                    {Object.entries(status.inbound).map(([name, state]) => (
                      <tr key={name}>
                        <td>{name}</td>
                        <td>
                          <span className={`badge ${state === 'running' ? 'success' : 'warning'}`}>
                            {state}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
            <div className="channel-group">
              <h4>Outbound</h4>
              {Object.keys(status.outbound).length === 0 ? (
                <p className="empty-state">No outbound channels configured</p>
              ) : (
                <table>
                  <thead><tr><th>Channel</th><th>Status</th></tr></thead>
                  <tbody>
                    {Object.entries(status.outbound).map(([name, state]) => (
                      <tr key={name}>
                        <td>{name}</td>
                        <td>
                          <span className={`badge ${state === 'available' ? 'success' : 'warning'}`}>
                            {state}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          </div>
        </section>
      )}

      <section className="panel" style={{ marginTop: '1rem' }}>
        <h3>Configured Channels (Topic-Agent Mapping)</h3>
        {channels.length === 0 ? (
          <p className="empty-state">No channels configured. Set quad.messaging.kafka.topics / amqp.queues / email.agents in application.properties.</p>
        ) : (
          <table>
            <thead>
              <tr><th>Name</th><th>Transport</th><th>Topic/Queue</th><th>Agent</th><th>Tenant</th></tr>
            </thead>
            <tbody>
              {channels.map((ch, i) => (
                <tr key={i}>
                  <td>{ch.name}</td>
                  <td><span className={`badge ${ch.transport}`}>{ch.transport}</span></td>
                  <td>{ch.topic}</td>
                  <td>{ch.agentId}</td>
                  <td>{ch.tenantId}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <button type="button" onClick={load} style={{ marginTop: '0.75rem' }}>Refresh</button>
    </div>
  )
}
