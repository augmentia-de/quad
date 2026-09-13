import { useEffect, useState, useId } from 'react'

// Dynamically import mermaid (client-side only)
let mermaidInstance: typeof import('mermaid') | null = null

interface Props {
  definition: string
  title?: string
  onError?: (err: string) => void
}

export function MermaidDiagram({ definition, title, onError }: Props) {
  const reactId = useId()
  const [svg, setSvg] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (!definition) return
    let cancelled = false

    async function render() {
      setLoading(true)
      setError(null)
      setSvg(null)

      try {
        if (!mermaidInstance) {
          mermaidInstance = await import('mermaid')
          mermaidInstance.default.initialize({
            theme: 'dark',
            securityLevel: 'loose',
            startOnLoad: false,
            themeVariables: { fontSize: '14px', primaryColor: '#1f2937' }
          })
        }
        if (cancelled) return
        const { svg: rendered } = await mermaidInstance.default.render(`mermaid-${reactId}`, definition)
        if (!cancelled) {
          setSvg(rendered)
          setLoading(false)
        }
      } catch (e: unknown) {
        if (!cancelled) {
          const msg = e instanceof Error ? e.message : String(e)
          setError(msg)
          onError?.(msg)
          setLoading(false)
        }
      }
    }

    render()
    return () => { cancelled = true }
  }, [definition, reactId, onError])

  return (
    <div className="mermaid-diagram">
      {title && <h3>{title}</h3>}
      <div className="mermaid-canvas">
        {loading && <div className="loading">Loading diagram…</div>}
        {error && (
          <div className="error-state">{error}</div>
        )}
        {svg && !loading && (
          <div dangerouslySetInnerHTML={{ __html: svg }} />
        )}
      </div>
    </div>
  )
}