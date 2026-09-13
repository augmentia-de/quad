// Pragmatic JSON-schema validator (no Ajv dependency).
// Accepts a JSON-schema structure with the keywords relevant for Structured Output.

const SCHEMA_TYPES = ['object', 'array', 'string', 'number', 'integer', 'boolean', 'null']

/** Checks whether `raw` is a syntactically valid JSON schema (JSON object). */
export function isValidJsonSchema(raw: string): { valid: boolean; error?: string } {
  const trimmed = raw.trim()
  if (trimmed === '') return { valid: true }
  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch (e) {
    return { valid: false, error: `Invalid JSON: ${(e as Error).message}` }
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    return { valid: false, error: 'Schema must be a JSON object' }
  }
  const schema = parsed as Record<string, unknown>
  if (!('type' in schema) && !('properties' in schema) && !('anyOf' in schema)
    && !('$ref' in schema) && !('items' in schema) && !('const' in schema) && !('enum' in schema)) {
    return { valid: false, error: 'Schema must declare "type", "properties", "anyOf", "$ref", "items", "const" or "enum"' }
  }
  return validateNode(schema, '$')
}

function validateNode(node: unknown, at: string): { valid: boolean; error?: string } {
  if (typeof node !== 'object' || node === null || Array.isArray(node)) {
    return { valid: false, error: `Schema at ${at} must be an object` }
  }
  const schema = node as Record<string, unknown>
  if ('type' in schema) {
    const t = schema.type
    const types = Array.isArray(t) ? t : [t]
    for (const one of types) {
      if (typeof one !== 'string' || !SCHEMA_TYPES.includes(one)) {
        return { valid: false, error: `Unknown type "${one}" at ${at}` }
      }
    }
  }
  if ('format' in schema && typeof schema.format !== 'string') {
    return { valid: false, error: `"format" at ${at} must be a string` }
  }
  if ('required' in schema) {
    if (!Array.isArray(schema.required) || !schema.required.every(x => typeof x === 'string')) {
      return { valid: false, error: `"required" at ${at} must be an array of strings` }
    }
  }
  if ('properties' in schema) {
    if (typeof schema.properties !== 'object' || schema.properties === null || Array.isArray(schema.properties)) {
      return { valid: false, error: `"properties" at ${at} must be an object` }
    }
    const props = schema.properties as Record<string, unknown>
    for (const [name, sub] of Object.entries(props)) {
      const check = validateNode(sub, `${at}.properties.${name}`)
      if (!check.valid) return check
    }
  }
  if ('items' in schema) {
    if (typeof schema.items !== 'object' || schema.items === null) {
      return { valid: false, error: `"items" at ${at} must be an object or array` }
    }
    if (Array.isArray(schema.items)) {
      for (let i = 0; i < schema.items.length; i++) {
        const check = validateNode(schema.items[i], `${at}.items[${i}]`)
        if (!check.valid) return check
      }
    } else {
      const check = validateNode(schema.items as Record<string, unknown>, `${at}.items`)
      if (!check.valid) return check
    }
  }
  if ('enum' in schema && (!Array.isArray(schema.enum) || schema.enum.length === 0)) {
    return { valid: false, error: `"enum" at ${at} must be a non-empty array` }
  }
  if ('anyOf' in schema || 'oneOf' in schema || 'allOf' in schema) {
    for (const key of ['anyOf', 'oneOf', 'allOf'] as const) {
      if (!(key in schema)) continue
      const list = schema[key]
      if (!Array.isArray(list) || list.length === 0) {
        return { valid: false, error: `"${key}" at ${at} must be a non-empty array` }
      }
      for (let i = 0; i < list.length; i++) {
        const check = validateNode(list[i], `${at}.${key}[${i}]`)
        if (!check.valid) return check
      }
    }
  }
  return { valid: true }
}

// ─── Schema tree ────────────────────────────────────────────────
// Builds a navigable tree structure from a JSON schema, from which
// the subtree path (e.g. "/analysis/complexity" or "items[0].name")
// can be selected visually.

export interface SchemaTreeNode {
  /** Property name or "[0]" for array elements */
  key: string
  /** Dot/bracket notation (robust for JsonPathResolver), e.g. "analysis.complexity" or "items[0].name" */
  dotPath: string
  /** JSON-Pointer-Variante, abgeleitet aus dotPath, z. B. "/analysis/complexity" */
  path: string
  /** Schema-Typ (object/array/string/…), sofern angegeben */
  type: string
  children: SchemaTreeNode[]
}

/** Leitet aus einer Dot-Pfad-Notation ("items[0].name") den JSON-Pointer ("/items/0/name") ab. */
export function toPointerPath(dotPath: string): string {
  const segments = dotPath.replace(/\[(\d+)\]/g, '.$1').split('.').filter(s => s !== '')
  return segments.length ? '/' + segments.join('/') : ''
}

/** Builds the tree of root properties of a JSON schema. Empty for invalid/empty schema. */
export function buildSchemaTree(schemaRaw: string): SchemaTreeNode[] {
  const trimmed = (schemaRaw ?? '').trim()
  if (!trimmed) return []
  let parsed: unknown
  try {
    parsed = JSON.parse(trimmed)
  } catch {
    return []
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) return []
  const root = parsed as Record<string, unknown>
  if (schemaTypeOf(root) === 'array' && root['items'] && typeof root['items'] === 'object' && root['items'] !== null) {
    return [buildNode('[0]', root['items'] as Record<string, unknown>, '[0]')]
  }
  return nodeProperties(root, '')
}

function schemaTypeOf(node: Record<string, unknown>): string {
  const t = node['type']
  if (typeof t === 'string') return t
  if (Array.isArray(t)) return t.filter(x => typeof x === 'string').join('|')
  return 'object'
}

/** Liefert die Property-Kinder eines Objekt-Schemas (bzw. leere Liste) unterhalb von {@code parentDot}. */
function nodeProperties(node: Record<string, unknown>, parentDot: string): SchemaTreeNode[] {
  if (!node['properties'] || typeof node['properties'] !== 'object') return []
  const props = node['properties'] as Record<string, unknown>
  const result: SchemaTreeNode[] = []
  for (const [name, sub] of Object.entries(props)) {
    if (typeof sub !== 'object' || sub === null) continue
    const dotPath = parentDot ? parentDot + '.' + name : name
    result.push(buildNode(name, sub as Record<string, unknown>, dotPath))
  }
  return result
}

function buildNode(key: string, node: Record<string, unknown>, dotPath: string): SchemaTreeNode {
  const type = schemaTypeOf(node)
  let children: SchemaTreeNode[]
  if (type === 'array' && node['items'] && typeof node['items'] === 'object' && node['items'] !== null) {
    const item = node['items'] as Record<string, unknown>
    children = [buildNode('[0]', item, dotPath + '[0]')]
  } else {
    children = nodeProperties(node, dotPath)
  }
  return { key, dotPath, path: toPointerPath(dotPath), type, children }
}