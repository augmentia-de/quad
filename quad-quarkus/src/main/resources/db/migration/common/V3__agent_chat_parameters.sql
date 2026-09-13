-- Agent-spezifische Chat-Parameter (JSON) und Message-Config

ALTER TABLE agents ADD COLUMN system_prompt text;
ALTER TABLE agents ADD COLUMN user_message_template text;
ALTER TABLE agents ADD COLUMN json_input boolean not null default 0;
ALTER TABLE agents ADD COLUMN chat_parameters text;
