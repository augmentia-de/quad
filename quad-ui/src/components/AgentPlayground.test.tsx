import { render, screen } from '@testing-library/react'
import { AgentPlayground } from './AgentPlayground'

describe('AgentPlayground', () => {
  const agents = [
    {
      id: 'a1',
      name: 'ResearchAgent',
      description: 'Research',
      category: 'research',
      model: 'gpt-4o-mini',
      temperature: 0.7,
      maxTokens: 4096,
      topP: 1,
      tools: ['webSearch'],
      guardrailsInput: [],
      guardrailsOutput: [],
    },
  ]

  it('renders the playground with agent options', () => {
    render(<AgentPlayground agents={agents} />)

    expect(screen.getByText('Agent Playground')).toBeInTheDocument()
expect(screen.getByPlaceholderText(/Enter task/)).toBeInTheDocument()
expect(screen.getByLabelText(/Agent for test/)).toBeInTheDocument()
    expect(screen.getByText('ResearchAgent (gpt-4o-mini)')).toBeInTheDocument()
    expect(screen.getByText('▶ Testen')).toBeDisabled()
  })

  it('offers a chat history checkbox that is off by default', () => {
    render(<AgentPlayground agents={agents} />)

    const checkbox = screen.getByLabelText(/Carry chat history/)
    expect(checkbox).toBeInTheDocument()
    expect(checkbox).not.toBeChecked()
  })

  it('disables the run button without a task', () => {
    render(<AgentPlayground agents={[]} />)

    expect(screen.getByText('▶ Testen')).toBeDisabled()
  })
})