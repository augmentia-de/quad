import { render, screen } from '@testing-library/react'
import { WorkflowProvider } from '../contexts/WorkflowContext'
import { Dashboard } from './Dashboard'

describe('Dashboard', () => {
  it('renders the dashboard heading', () => {
    render(
      <WorkflowProvider>
        <Dashboard />
      </WorkflowProvider>
    )
    expect(screen.getByText('QUAD Studio Dashboard')).toBeInTheDocument()
  })

  it('shows loading state initially', () => {
    render(
      <WorkflowProvider>
        <Dashboard />
      </WorkflowProvider>
    )
    expect(screen.getByText('Loading...')).toBeInTheDocument()
  })
})