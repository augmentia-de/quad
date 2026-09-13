import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AgentEditor } from './AgentEditor'
import { fetchHooks } from '../services/api'
import type { AgentDefinition } from '../services/types'

vi.mock('../services/api', () => ({
  fetchHooks: vi.fn(),
}))

const baseAgent: AgentDefinition = {
  id: 'a1',
  name: 'HookAgent',
  description: '',
  category: 'general',
  model: 'gpt-4o-mini',
  temperature: 0.7,
  maxTokens: 4096,
  topP: 1,
  tools: ['webSearch'],
  guardrailsInput: [],
  guardrailsOutput: [],
  hooks: ['hitl'],
  agentType: 'ua',
  systemPrompt: 'Be concise.',
}

function renderEditor(onSave: (a: AgentDefinition) => void = () => {}) {
  return render(
    <AgentEditor
      agent={baseAgent}
      onSave={onSave}
      onCancel={() => {}}
      modelOptions={['gpt-4o-mini']}
      toolOptions={['webSearch']}
    />
  )
}

describe('AgentEditor hooks', () => {
  beforeEach(() => {
    vi.mocked(fetchHooks).mockResolvedValue([
      { name: 'hitl', type: 'unknown' as const, active: true },
      { name: 'audit-log', type: 'unknown' as const, active: true },
    ])
  })

  it('renders hook checkboxes from the backend catalog', async () => {
    renderEditor()
    expect(await screen.findByLabelText('HITL (Tool-Freigabe)')).toBeInTheDocument()
    expect(screen.getByLabelText('Audit-log')).toBeInTheDocument()
  })

  it('pre-selects hooks configured on the agent', async () => {
    renderEditor()
    const hitl = (await screen.findByLabelText('HITL (Tool-Freigabe)')) as HTMLInputElement
    expect(hitl).toBeChecked()
    expect(screen.getByLabelText('Audit-log')).not.toBeChecked()
  })

  it('persists toggled hooks on save', async () => {
    const onSave = vi.fn()
    renderEditor(onSave)
    fireEvent.click(await screen.findByLabelText('Audit-log'))
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ hooks: ['hitl', 'audit-log'] }))
  })
})

describe('AgentEditor timeout', () => {
  beforeEach(() => {
    vi.mocked(fetchHooks).mockResolvedValue([])
  })

  it('renders the timeout field with default value when unset', () => {
    renderEditor()
    const input = screen.getByLabelText(/Timeout/i) as HTMLInputElement
    expect(input).toBeInTheDocument()
    expect(input.value).toBe('')
  })

  it('persists the entered timeout on save', () => {
    const onSave = vi.fn()
    renderEditor(onSave)
    const input = screen.getByLabelText(/Timeout/i)
    fireEvent.change(input, { target: { value: '120' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(onSave).toHaveBeenCalledWith(expect.objectContaining({ timeoutSeconds: 120 }))
  })

  it('shows the existing timeout value when editing an agent', () => {
    render(
      <AgentEditor
        agent={{ ...baseAgent, timeoutSeconds: 45 }}
        onSave={() => {}}
        onCancel={() => {}}
        modelOptions={['gpt-4o-mini']}
        toolOptions={['webSearch']}
      />
    )
    expect((screen.getByLabelText(/Timeout/i) as HTMLInputElement).value).toBe('45')
  })
})