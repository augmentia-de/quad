import { render, screen, waitFor } from '@testing-library/react'
import { Runs } from './Runs'
import type { RunView } from '../services/types'

const api = vi.hoisted(() => {
  const mockRuns: RunView[] = [
    {
      runId: 'run-1',
      kind: 'tool',
      status: 'completed',
      stepIndex: 2,
      startedAt: '2026-09-04T10:00:00Z',
      finishedAt: '2026-09-04T10:00:05Z',
      durationMs: 5000,
      steps: [
        { stepId: 'tool-1', kind: 'tool', status: 'completed', durationMs: 100, eventType: 'STEP', timestamp: '2026-09-04T10:00:01Z' },
        { stepId: 'model-1', kind: 'model', status: 'started', durationMs: 0, eventType: 'STEP', timestamp: '2026-09-04T10:00:00Z' },
      ],
    },
  ]
  return {
    mockRuns,
    fetchRuns: vi.fn().mockResolvedValue(mockRuns),
    execTask: vi.fn().mockResolvedValue({ sessionId: 'run-1', success: true, result: '', toolCount: 0, durationMs: 1, costLimit: 0, model: 'x' }),
  }
})

vi.mock('../services/api', () => ({
  fetchRuns: api.fetchRuns,
  execTask: api.execTask,
}))

describe('Runs', () => {
  it('renders the runs heading', () => {
    render(<Runs />)
    expect(screen.getByText('Runs')).toBeInTheDocument()
  })

  it('renders runs from the API with their steps', async () => {
    render(<Runs />)

    await waitFor(() => expect(screen.getByText('run-1')).toBeInTheDocument())
    // "tool" erscheint als Run-Kind-Badge und als Step-Kind
    expect(screen.getAllByText('tool').length).toBeGreaterThanOrEqual(1)
    // "completed" erscheint als Run-Status-Badge und als Step-Status
    expect(screen.getAllByText('completed').length).toBeGreaterThanOrEqual(1)
    // Steps sind aufklappbar (summary)
    expect(screen.getByText('Steps (2)')).toBeInTheDocument()
  })

  it('renders filter controls', async () => {
    render(<Runs />)
    expect(screen.getByLabelText('Period:')).toBeInTheDocument()
    expect(screen.getByLabelText('Status:')).toBeInTheDocument()
    expect(screen.getByLabelText('Run ID:')).toBeInTheDocument()
  })
})
