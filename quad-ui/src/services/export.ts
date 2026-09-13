import type { AuditLogEntry } from './types'

function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

function convertToCSV(logs: AuditLogEntry[]): string {
  const header = 'id,timestamp,agent,event,details\n'
  const rows = logs.map(l =>
    `${l.id},${l.timestamp},"${l.agent.replaceAll('"', '""')}","${l.event.replaceAll('"', '""')}","${l.details.replaceAll('"', '""')}"`
  ).join('\n')
  return header + rows
}

export function exportAuditLogs(
  logs: AuditLogEntry[],
  format: 'json' | 'csv',
  filename: string = 'audit-export',
) {
  if (format === 'json') {
    const blob = new Blob([JSON.stringify(logs, null, 2)], { type: 'application/json' })
    downloadBlob(blob, `${filename}.json`)
  } else {
    const csv = convertToCSV(logs)
    const blob = new Blob([csv], { type: 'text/csv' })
    downloadBlob(blob, `${filename}.csv`)
  }
}