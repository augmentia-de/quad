import { fireEvent, render, screen, within } from '@testing-library/react'
import { WorkflowProvider } from '../contexts/WorkflowContext'
import { WorkflowComposer } from './WorkflowComposer'

describe('WorkflowComposer', () => {
  it('renders the workflow composer title', () => {
    render(
      <WorkflowProvider>
        <WorkflowComposer />
      </WorkflowProvider>
    )
    expect(screen.getByText('Workflow Composer')).toBeInTheDocument()
  })

  it('has toolbar buttons', () => {
    render(
      <WorkflowProvider>
        <WorkflowComposer />
      </WorkflowProvider>
    )
    expect(screen.getByText('+ New')).toBeInTheDocument()
    expect(screen.getByText('+ Insert')).toBeInTheDocument()
    expect(screen.getByText('📥 Messaging In')).toBeInTheDocument()
    expect(screen.getByText('📤 Messaging Out')).toBeInTheDocument()
    expect(screen.getByText('⟳ Loop')).toBeInTheDocument()
    expect(screen.getByText('⤳ Conditional')).toBeInTheDocument()
    expect(screen.getByText('⧉ Nested Workflow')).toBeInTheDocument()
    expect(screen.getByText('Reset')).toBeInTheDocument()
    expect(screen.getByText('Save')).toBeInTheDocument()
    expect(screen.getByText('Execute')).toBeInTheDocument()
  })

  it('shows empty state initially', () => {
    render(
      <WorkflowProvider>
        <WorkflowComposer />
      </WorkflowProvider>
    )
    expect(screen.getByText(/Add agents using the toolbar/)).toBeInTheDocument()
  })

  it('shows the start-task input when no messaging-in node exists', () => {
    render(
      <WorkflowProvider>
        <WorkflowComposer />
      </WorkflowProvider>
    )
    expect(screen.getByLabelText('Start / Initial Task')).toBeInTheDocument()
  })

  it('lets the start task be typed', () => {
    render(
      <WorkflowProvider>
        <WorkflowComposer />
      </WorkflowProvider>
    )
    const input = screen.getByLabelText('Start / Initial Task') as HTMLTextAreaElement
    fireEvent.change(input, { target: { value: 'Analyse die Beschwerden' } })
    expect(input.value).toBe('Analyse die Beschwerden')
  })

  it('replaces the start-task input with a hint when a messaging-in node is added', () => {
    render(
      <WorkflowProvider>
        <WorkflowComposer />
      </WorkflowProvider>
    )
    expect(screen.getByLabelText('Start / Initial Task')).toBeInTheDocument()
    fireEvent.click(screen.getByText('📥 Messaging In'))
    expect(screen.queryByLabelText('Start / Initial Task')).not.toBeInTheDocument()
    expect(screen.getByText(/Workflow starts from a messaging-in node/)).toBeInTheDocument()
  })

  it('adds loop, conditional and nested-workflow nodes from the toolbar', () => {
    render(
      <WorkflowProvider>
        <WorkflowComposer />
      </WorkflowProvider>
    )
    const canvas = () => screen.getByRole('region', { name: 'Workflow Editor' })
    fireEvent.click(screen.getByText('⟳ Loop'))
    expect(within(canvas()).getByText('Loop Node')).toBeInTheDocument()

    fireEvent.click(screen.getByText('⤳ Conditional'))
    expect(within(canvas()).getByText('Conditional Node')).toBeInTheDocument()

    fireEvent.change(screen.getByLabelText('Add more nodes'), {
      target: { value: 'nested-workflow' },
    })
    expect(within(canvas()).getByText('Nested Workflow Node')).toBeInTheDocument()
  })

  it('renders the loop and conditional node editors', () => {
    render(
      <WorkflowProvider>
        <WorkflowComposer />
      </WorkflowProvider>
    )
    const canvas = () => screen.getByRole('region', { name: 'Workflow Editor' })
    fireEvent.click(screen.getByText('⟳ Loop'))
    fireEvent.click(within(canvas()).getByText('Loop Node'))
    expect(screen.getByText('Max Iterations')).toBeInTheDocument()
    expect(screen.getByText('Exit Condition')).toBeInTheDocument()

    fireEvent.click(screen.getByText('⤳ Conditional'))
    fireEvent.click(within(canvas()).getByText('Conditional Node'))
    expect(screen.getByPlaceholderText('e.g. contains:error')).toBeInTheDocument()
  })

  it('renders a generic timeout field (0 = off) for nodes', () => {
    render(
      <WorkflowProvider>
        <WorkflowComposer />
      </WorkflowProvider>
    )
    const canvas = () => screen.getByRole('region', { name: 'Workflow Editor' })
    fireEvent.click(screen.getByText('⟳ Loop'))
    fireEvent.click(within(canvas()).getByText('Loop Node'))
    expect(screen.getByText('Timeout (ms, 0 = kein Limit)')).toBeInTheDocument()
    expect(screen.getByText(/deaktiviert/)).toBeInTheDocument()
  })

  it('inserts a new step in the middle of a workflow (a-b -> a-c-b)', () => {
    render(
      <WorkflowProvider>
        <WorkflowComposer />
      </WorkflowProvider>
    )
    const canvas = () => screen.getByRole('region', { name: 'Workflow Editor' })
    const more = () => screen.getByLabelText('Add more nodes')

    // Build a -> b (nested-workflow, then async appended after it)
    fireEvent.change(more(), { target: { value: 'nested-workflow' } })
    fireEvent.change(more(), { target: { value: 'async' } })

    // Select a and insert c in the middle: expects a -> c and c -> b
    fireEvent.click(within(canvas()).getByText('Nested Workflow Node'))
    fireEvent.change(more(), { target: { value: 'fork' } })

    const section = screen.getByText('Data Flow Between Steps').closest('section') as HTMLElement
    const pairs = within(section)
      .getAllByRole('row')
      .slice(1)
      .map(r => {
        const cells = within(r).getAllByRole('cell')
        return { from: cells[0].textContent, to: cells[1].textContent }
      })

    expect(pairs).toContainEqual({ from: 'Nested Workflow Node', to: 'Fork Node' })
    expect(pairs).toContainEqual({ from: 'Fork Node', to: 'Async Node' })
    expect(pairs).not.toContainEqual({ from: 'Nested Workflow Node', to: 'Async Node' })
  })
})