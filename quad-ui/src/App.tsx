import { useState } from 'react'
import { WorkflowProvider } from './contexts/WorkflowContext'
import { Dashboard } from './components/Dashboard'
import { AgentManager } from './components/AgentManager'
import { WorkflowComposer } from './components/WorkflowComposer'
import { Runs } from './components/Runs'
import { SecurityCenter } from './components/SecurityCenter'
import { Monitor } from './components/Monitor'
import { Audit } from './components/Audit'
import { Sessions } from './components/Sessions'
import { DebugView } from './components/DebugView'
import { Messaging } from './components/Messaging'
import { HitlOverlay } from './components/HitlOverlay'
import { MemoryManager } from './components/MemoryManager'
import { SkillStoreManager } from './components/SkillStoreManager'
import { ProvenanceView } from './components/ProvenanceView'
import { Automation } from './components/Automation'
import { WorkspaceManager } from './components/WorkspaceManager'
import { DynamicWorkflow } from './components/DynamicWorkflow'

const NAVIGATION_ITEMS = [
  { id: 'dashboard', label: 'Dashboard', icon: '📊' },
  { id: 'agents', label: 'Agents', icon: '🤖' },
  { id: 'workflows', label: 'Workflows', icon: '🔄' },
  { id: 'dynamic', label: 'Dynamic', icon: '✨' },
  { id: 'runs', label: 'Runs', icon: '🚀' },
  { id: 'messaging', label: 'Messaging', icon: '📨' },
  { id: 'memory', label: 'Memory', icon: '🧠' },
  { id: 'security', label: 'Security', icon: '🔒' },
  { id: 'permissions', label: 'Permissions', icon: '🛡️' },
  { id: 'automation', label: 'Automation', icon: '⏰' },
  { id: 'skills', label: 'Skills', icon: '🛠️' },
  { id: 'provenance', label: 'Provenance', icon: '🔗' },
  { id: 'sessions', label: 'Sessions', icon: '💾' },
  { id: 'monitor', label: 'Monitor', icon: '📈' },
  { id: 'audit', label: 'Audit', icon: '📝' },
  { id: 'debug', label: 'Debug', icon: '🐛' },
]

/* ── Desktop / Shutdown Screen ─────────────────────────────────── */

function DesktopScreen({ onRestore }: { onRestore: () => void }) {
  return (
    <div className="desktop-screen">
      {/* Quad Studio icon in the top-left corner */}
      <div className="desktop-icon-group" onClick={onRestore}>
        <img src="/ai-pc-icon.png" alt="" className="desktop-icon" />
        <span className="desktop-label">QUAD Studio</span>
      </div>
    </div>
  )
}

/* ── Side Navigation ────────────────────────────────────────────── */

function SideNavigation({ activeTab, onSelect }: {
  activeTab: string
  onSelect: (id: string) => void
}) {
  return (
    <nav className="side-nav" role="navigation" aria-label="Main navigation">
      <ul>
        {NAVIGATION_ITEMS.map(item => (
          <li key={item.id}>
            <button
              className={`side-btn${activeTab === item.id ? ' active' : ''}`}
              onClick={() => onSelect(item.id)}
              title={item.label}
            >
              {item.icon} {item.label}
            </button>
          </li>
        ))}
      </ul>
    </nav>
  )
}

/* ── App ─────────────────────────────────────────────────────────── */

function App() {
  const [shutDown, setShutdown] = useState(false)
  const [activeTab, setActiveTab] = useState('dashboard')

  // ── Both minimize and close → exit to desktop ──
  const handleMinimize = () => setShutdown(true)

  const handleClose = () => {
    if (window.confirm('QUAD Studio schließen?')) {
      setShutdown(true)
    }
  }

  const handleRestore = () => {
    setShutdown(false)
  }

  // ── Desktop phase (both minimized and closed lead here) ──
  if (shutDown) {
    return <DesktopScreen onRestore={handleRestore} />
  }

  return (
    <WorkflowProvider>
      <div className="window" style={{ width: '100%', minHeight: '100vh', margin: 0 }}>

        {/* ── Title Bar ────────────────────────────────────────── */}
        <div className="title-bar">
          <img src="/ai-pc-icon-small.png" alt="QUAD" className="app-icon" />
          <div className="title-bar-text">QUAD Studio - Legacy software in the AI era</div>
          <div className="title-bar-controls">
            <button aria-label="Minimize" onClick={handleMinimize} />
            <button aria-label="Maximize" onClick={() => document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen()} />
            <button aria-label="Close" onClick={handleClose} />
          </div>
        </div>

        {/* ── Side-nav + Main content ──────────────────────────── */}
        <div className="app-layout">
          <SideNavigation activeTab={activeTab} onSelect={setActiveTab} />

          <div className="main-content">
            {activeTab === 'dashboard' && <Dashboard onNavigate={setActiveTab} />}
            {activeTab === 'agents' && <AgentManager />}
            {activeTab === 'workflows' && <WorkflowComposer onTabChange={setActiveTab} />}
            {activeTab === 'dynamic' && <DynamicWorkflow />}
            {activeTab === 'runs' && <Runs />}
            {activeTab === 'messaging' && <Messaging />}
            {activeTab === 'memory' && <MemoryManager />}
            {activeTab === 'security' && <SecurityCenter />}
            {activeTab === 'permissions' && <WorkspaceManager />}
            {activeTab === 'automation' && <Automation />}
            {activeTab === 'skills' && <SkillStoreManager />}
            {activeTab === 'provenance' && <ProvenanceView />}
            {activeTab === 'sessions' && <Sessions />}
            {activeTab === 'monitor' && <Monitor />}
            {activeTab === 'audit' && <Audit />}
            {activeTab === 'debug' && <DebugView />}
          </div>
        </div>

        {/* ── Status Bar ───────────────────────────────────────── */}
        <div className="status-bar">
          <p className="status-bar-field">Quad Studio — Win98 Edition</p>
          <p className="status-bar-field">{activeTab.charAt(0).toUpperCase() + activeTab.slice(1)}</p>
        </div>

        {/* Overlays always on top */}
        <HitlOverlay />
      </div>
    </WorkflowProvider>
  )
}

export default App
