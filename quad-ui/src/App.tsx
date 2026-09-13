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
import { StatusBar } from './components/StatusBar'
import { HitlOverlay } from './components/HitlOverlay'
import { MemoryManager } from './components/MemoryManager'
import { SkillStoreManager } from './components/SkillStoreManager'
import { ProvenanceView } from './components/ProvenanceView'
import { Automation } from './components/Automation'
import { WorkspaceManager } from './components/WorkspaceManager'
import { DynamicWorkflow } from './components/DynamicWorkflow'
import './App.css'

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

function TabNavigation({ activeTab, onTabChange }: {
  activeTab: string
  onTabChange: (tab: string) => void
}) {
  return (
    <nav className="tabs" role="tablist">
      {NAVIGATION_ITEMS.map(item => (
        <button
          key={item.id}
          role="tab"
          aria-selected={activeTab === item.id}
          className={activeTab === item.id ? 'active' : ''}
          onClick={() => onTabChange(item.id)}
        >
          <span className="tab-icon">{item.icon}</span>
          <span className="tab-label">{item.label}</span>
        </button>
      ))}
    </nav>
  )
}

function AppContent({ activeTab, onNavigate }: { activeTab: string, onNavigate: (tab: string) => void }) {
  const renderContent = () => {
    switch (activeTab) {
      case 'dashboard': return <Dashboard onNavigate={onNavigate} />
      case 'agents': return <AgentManager />
      case 'workflows': return <WorkflowComposer onTabChange={onNavigate} />
      case 'dynamic': return <DynamicWorkflow />
      case 'runs': return <Runs />
      case 'messaging': return <Messaging />
      case 'memory': return <MemoryManager />
      case 'security': return <SecurityCenter />
      case 'permissions': return <WorkspaceManager />
      case 'automation': return <Automation />
      case 'skills': return <SkillStoreManager />
      case 'provenance': return <ProvenanceView />
      case 'sessions': return <Sessions />
      case 'monitor': return <Monitor />
      case 'audit': return <Audit />
      case 'debug': return <DebugView />
      default: return <Dashboard onNavigate={onNavigate} />
    }
  }

  return (
    <main className="app-main">
      {renderContent()}
    </main>
  )
}

function App() {
  const [activeTab, setActiveTab] = useState('dashboard')

  return (
    <WorkflowProvider>
      <div className="quad-app" role="main">
        <header className="app-header">
          <h1>QUAD Studio</h1>
        </header>
        <TabNavigation activeTab={activeTab} onTabChange={setActiveTab} />
        <div className="flex flex-1 overflow-hidden">
          <AppContent activeTab={activeTab} onNavigate={setActiveTab} />
        </div>
        <StatusBar />
        <HitlOverlay />
      </div>
    </WorkflowProvider>
  )
}

export default App