import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { JsonSchemaPopup } from './JsonSchemaPopup'

describe('JsonSchemaPopup', () => {
  it('saves a valid schema', () => {
    const onSave = vi.fn()
    const onClose = vi.fn()
    render(<JsonSchemaPopup initialSchema="" onSave={onSave} onClose={onClose} />)

    fireEvent.change(screen.getByLabelText('Schema (JSON)'), {
      target: { value: '{ "type": "object", "properties": { "s": { "type": "string" } } }' },
    })
    expect(screen.getByText('Valid JSON Schema')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Save Schema'))

    expect(onSave).toHaveBeenCalledWith('{ "type": "object", "properties": { "s": { "type": "string" } } }')
    expect(onClose).toHaveBeenCalled()
  })

  it('blocks saving for an invalid schema', () => {
    const onSave = vi.fn()
    render(<JsonSchemaPopup initialSchema="" onSave={onSave} onClose={() => {}} />)

    fireEvent.change(screen.getByLabelText('Schema (JSON)'), {
      target: { value: '{ type: object }' },
    })
    expect(screen.getByText(/Invalid JSON/)).toBeInTheDocument()
    const save = screen.getByText('Save Schema') as HTMLButtonElement
    expect(save.disabled).toBe(true)
    fireEvent.click(save)
    expect(onSave).not.toHaveBeenCalled()
  })

  it('treats an empty schema as deactivated and savable', () => {
    const onSave = vi.fn()
    render(<JsonSchemaPopup initialSchema="" onSave={onSave} onClose={() => {}} />)

    expect(screen.getByText('Schema deaktiviert (leer).')).toBeInTheDocument()
    const save = screen.getByText('Save Schema') as HTMLButtonElement
    expect(save.disabled).toBe(false)
    fireEvent.click(save)
    expect(onSave).toHaveBeenCalledWith('')
  })
})