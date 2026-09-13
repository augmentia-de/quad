import { useState } from 'react'
import { isValidJsonSchema } from '../services/jsonSchema'

interface JsonSchemaPopupProps {
  /** Currently saved schema (as a string; empty = disabled). */
  initialSchema: string
  title?: string
  onSave: (schema: string) => void
  onClose: () => void
}

/**
 * Modal for editing the output JSON schema of an agent node.
 * Saving is only possible with a valid JSON schema (empty = disabled).
 */
export function JsonSchemaPopup({ initialSchema, title, onSave, onClose }: JsonSchemaPopupProps) {
  const [value, setValue] = useState(initialSchema)
  const validation = isValidJsonSchema(value)

  return (
    <div className="agent-editor-overlay" role="dialog" aria-label="JSON Output Schema">
      <div className="agent-editor modal">
        <h3>{title ?? 'JSON Output Schema'}</h3>
        <p className="schema-popup-hint">
          Define the structured output JSON schema. The model is forced via Structured Output
          (responseFormat) to return exactly this schema. Leave empty = disabled.
        </p>
        <div className="form-group">
          <label htmlFor="json-schema-textarea">Schema (JSON)</label>
          <textarea
            id="json-schema-textarea"
            value={value}
            onChange={e => setValue(e.target.value)}
            placeholder='{ "type": "object", "properties": { "summary": { "type": "string" } }, "required": ["summary"] }'
            rows={14}
            spellCheck={false}
          />
        </div>
        <div className={`schema-validation ${validation.valid ? 'ok' : 'bad'}`} role="status">
          {value.trim() === ''
            ? 'Schema disabled (empty).'
            : validation.valid
              ? 'Valid JSON Schema'
              : validation.error}
        </div>
        <div className="schema-popup-actions">
          <button
            type="button"
            disabled={!validation.valid}
            onClick={() => { onSave(value.trim()); onClose() }}
            title={validation.valid ? 'Save schema' : 'Invalid schema — saving blocked'}
          >
            Save Schema
          </button>
          <button type="button" className="danger" onClick={onClose}>Cancel</button>
        </div>
      </div>
    </div>
  )
}