import { describe, expect, it } from 'vitest'
import { buildSchemaTree, isValidJsonSchema, toPointerPath } from './jsonSchema'

describe('isValidJsonSchema', () => {
  it('accepts an empty (disabled) schema', () => {
    expect(isValidJsonSchema('')).toEqual({ valid: true })
    expect(isValidJsonSchema('   ').valid).toBe(true)
  })

  it('accepts a basic object schema', () => {
    const result = isValidJsonSchema('{ "type": "object", "properties": { "name": { "type": "string" } }, "required": ["name"] }')
    expect(result).toEqual({ valid: true })
  })

  it('accepts arrays, enums and anyOf', () => {
    const result = isValidJsonSchema('{ "type": "array", "items": { "type": "string" }, "const": "x" }')
    expect(result.valid).toBe(true)
    expect(isValidJsonSchema('{ "anyOf": [{ "type": "string" }, { "type": "integer" }] }').valid).toBe(true)
  })

  it('rejects malformed JSON', () => {
    expect(isValidJsonSchema('{ type: object }').valid).toBe(false)
  })

  it('rejects non-object schemas', () => {
    expect(isValidJsonSchema('[]').valid).toBe(false)
  })

  it('rejects unknown types', () => {
    expect(isValidJsonSchema('{ "type": "banana" }').valid).toBe(false)
  })

  it('rejects missing schema roots', () => {
    expect(isValidJsonSchema('{ "foo": 1 }').valid).toBe(false)
  })

  it('rejects invalid required / enum / nested nodes', () => {
    expect(isValidJsonSchema('{ "type": "object", "required": "name" }').valid).toBe(false)
    expect(isValidJsonSchema('{ "enum": [] }').valid).toBe(false)
    expect(isValidJsonSchema('{ "properties": { "x": { "type": 42 } } }').valid).toBe(false)
  })
})

describe('buildSchemaTree', () => {
  it('builds a tree from a flat object schema', () => {
    const tree = buildSchemaTree('{ "type": "object", "properties": { "analysis": { "type": "object", "properties": { "complexity": { "type": "string" } } }, "score": { "type": "number" } } }')
    expect(tree.length).toBe(2)
    expect(tree[0].key).toBe('analysis')
    expect(tree[0].dotPath).toBe('analysis')
    expect(tree[0].path).toBe('/analysis')
    expect(tree[0].children[0]).toMatchObject({ key: 'complexity', dotPath: 'analysis.complexity', path: '/analysis/complexity' })
    expect(tree[1]).toMatchObject({ key: 'score', type: 'number', children: [] })
  })

  it('expands arrays into a [0] element with bracket notation', () => {
    const tree = buildSchemaTree('{ "type": "object", "properties": { "items": { "type": "array", "items": { "type": "object", "properties": { "name": { "type": "string" } } } } } }')
    const items = tree[0]
    expect(items.key).toBe('items')
    expect(items.type).toBe('array')
    const first = items.children[0]
    expect(first.key).toBe('[0]')
    expect(first.dotPath).toBe('items[0]')
    expect(first.children[0]).toMatchObject({ key: 'name', dotPath: 'items[0].name', path: '/items/0/name' })
  })

  it('treats a root array as its first element', () => {
    const tree = buildSchemaTree('{ "type": "array", "items": { "type": "object", "properties": { "id": { "type": "string" } } } }')
    expect(tree[0]).toMatchObject({ key: '[0]', dotPath: '[0]', path: '/0' })
    expect(tree[0].children[0]).toMatchObject({ key: 'id', dotPath: '[0].id', path: '/0/id' })
  })

  it('returns an empty list for empty or invalid input', () => {
    expect(buildSchemaTree('')).toEqual([])
    expect(buildSchemaTree('not json')).toEqual([])
    expect(buildSchemaTree('[]')).toEqual([])
  })
})

describe('toPointerPath', () => {
  it('converts bracket/dot notation to a JSON pointer', () => {
    expect(toPointerPath('items[0].name')).toBe('/items/0/name')
    expect(toPointerPath('analysis.complexity')).toBe('/analysis/complexity')
    expect(toPointerPath('[0].id')).toBe('/0/id')
    expect(toPointerPath('')).toBe('')
  })
})