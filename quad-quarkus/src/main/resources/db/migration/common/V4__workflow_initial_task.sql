-- Workflows: Speichert den optionalen Start-Task (verwendet, wenn kein messaging-in Node existiert)
alter table workflows add column initial_task text;