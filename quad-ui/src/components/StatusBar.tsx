import { useEffect, useState } from 'react'
import { fetchAgentStatus, fetchMetrics } from '../services/api'

interface StatusBarProps {
  className?: string
}

export function StatusBar({ className }: StatusBarProps) {
  const [metrics, setMetrics] = useState({
    session: 'default',
    tenant: 'default',
    model: 'gpt-4o-mini',
    tokens: 1200,
  })

  useEffect(() => {
    const fetchData = async () => {
      try {
        const status = await fetchAgentStatus()
        setMetrics(prev => ({
          ...prev,
          model: status.model || prev.model,
        }))
      } catch {
        // Use defaults on error
      }
      try {
        const tokenMetrics = await fetchMetrics()
        setMetrics(prev => ({
          ...prev,
          tokens: tokenMetrics.total || prev.tokens,
        }))
      } catch {
        // Use defaults on error
      }
    }
    fetchData()
    const interval = setInterval(fetchData, 30000)
    return () => clearInterval(interval)
  }, [])

  return (
    <footer className={`status-bar ${className || ''}`} role="status">
      <div className="status-item">
        <span className="status-label">Session:</span>
        <span className="status-value">{metrics.session}</span>
      </div>
      <div className="status-item">
        <span className="status-label">Tenant:</span>
        <span className="status-value">{metrics.tenant}</span>
      </div>
      <div className="status-item">
        <span className="status-label">LLM:</span>
        <span className="status-value">{metrics.model}</span>
      </div>
      <div className="status-item">
        <span className="status-label">Tokens:</span>
        <span className="status-value">{metrics.tokens.toLocaleString()}</span>
      </div>
    </footer>
  )
}