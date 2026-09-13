# Skill-based Tool Control in QUAD

## Overview

QUAD has a multi-layered system for controlling the tools available to each agent. There are **three separate filter layers** that serve different purposes and are technically fully isolated from one another.

```
┌─────────────────────────────────────────────────────┐
│           QUAD TOOL FILTER ARCHITECTURE             │
├──────────┬──────────────────┬───────────────────────┤
│ Layer    │                   │                     │
│ A        │   Static          │   AgentBuilder      │
│          │   Configuration   │   withTools(Set)    │
│ B        │   File-based      │   XML-Injection     │
│          │   Skills          │   in System-Prompt  │
│ C        │   DB Skills       │   Union-Whitelist   │
│          │   (Port 06)       │   + Registry Filter │
├──────────┴──────────────────┴───────────────────────┤
│                                                     │
│   SubAgents: Each Sub-Agent has its own registry    │
│   • Static: SubAgentTool with pre-built registry    │
│   • Dynamic: DynamicSubAgentTool creates reg. per call│
│                                                     │
└─────────────────────────────────────────────────────┘
```

---

## Layer A: Static Tool Selection (`withTools`)

**When:** Build-time when creating the agent

**Where:** `AgentBuilder.build()` line 224

**How it works:**

```java
// AgentBuilder.java - build()
if (toolNames != null && !toolNames.isEmpty()) {
    toolRegistry = toolRegistry.withOnly(toolNames);
}
```

The `withOnly()` method creates a **new** `QuadToolRegistry` containing only the desired tools:

```java
public QuadToolRegistry withOnly(Set<String> names) {
    QuadToolRegistry filtered = new QuadToolRegistry(argsMapper);
    for (String name : names) {
        ToolMethod tm = tools.get(name);
        if (tm != null) filtered.register(name, tm);
    }
    return filtered;
}
```

**Result:** Only the explicitly named tools end up in the registry. All others are unavailable.

---

## Layer B: File-based Skills (Inventory State)

**When:** Build-time, but also at runtime via `skill_search` / `tool_activator`

**Where:**
- `AgentSkillsPlugin.beforeModelCall()` — XML-injection
- `SkillActivationHook.afterToolCall()` — auto-activate on tool_name
- `SkillSearchTool` — search and list

**How it works:**

File-based skills are discovered via `SkillParser` in SKILL.md/skill.md files:

```java
// AgentBuilder.java - registerSkillPlugins()
var capabilityRegistry = CapabilityRegistry.builder()
    .skillDirs(skillDirs.toArray(new Path[0]))
    .build();
var allSkills = capabilityRegistry.discoverAllSkills();

var skillsPlugin = new AgentSkillsPlugin(allSkills, initialSkills, skillSearchEnabled);
agent.addHook(skillsPlugin);
```

The plugin injects an XML block into the system prompt before each LLM call:

```xml
<activated_skills>
<skill name="financial-analysis">
... instructions ...
<allowed_tools>readFile, webSearch</allowed_tools>
</skill>
</activated_skills>

<available_skills>
<skill><name>code-review</name><description>...</description></skill>
</available_skills>
```

**IMPORTANT: File-based skills do NOT filter the tool registry.**

`allowed_tools` is only **informational text** for the LLM ("these are the allowed tools"). The agent can still use all other tools because the registry remains unchanged. This also applies to:
- Initial skills via `AgentBuilder.withInitialSkills(...)`
- Runtime activation via `SkillActivationHook` (detects the `tool_activator` call and invokes `activateSkill(...)`)

### Runtime Search: `SkillSearchTool` + `ToolActivatorTool`

When `skillSearchEnabled=true`, a `SkillSearchTool` is registered in the registry. The LLM can use it to search for and activate skills at runtime:

```json
{ "query": "finance" }                    // search for skills
{ "skillName": "financial-analysis" }     // activate skill
```

A `ToolActivatorTool` enables alternative auto-activation after tool usage (`SkillActivationHook`).

---

## Layer C: DB Skills (Port 06) — Newly Added

**Purpose:** Persisted skills with a defined tool whitelist per skill that actually filters the registry.

### Package Structure

```
quad-core/src/main/java/de/augmentia/quad/core/capability/skill/
├── SkillEntry.java           (Persisted model: id, name, description, allowed_tools)
├── SkillStore.java           (Interface: listAll, findByName, upsert, deleteByName)

quad-quarkus/src/main/java/de/augmentia/quad/quarkus/skillstore/
├── JdbcSkillStore.java       (JDBC implementation: tables skills + metadata)
├── SkillsConfig.java         (@Inject DataSource, enabled flag + auto-load)
├── SkillResource.java        (@Path("/api/skills"))
```

**Migration:** `V9__skills.sql`:

```sql
create table if not exists skills (
    id varchar(64) primary key,
    name varchar(256) not null unique,
    description text,
    instructions text,
    allowed_tools text,              -- JSON array of tool names
    declared_tools text,
    metadata text,
    created_at timestamp default CURRENT_TIMESTAMP
);
```

### The Correct Filtering Approach

#### ❌ Wrong: Intersection of all skills

```java
// BAD: With disjoint skills the result is empty!
Set<String> remaining = new HashSet<>(allAvailable);
for (var skill : jdbcSkillStore.listAll()) {
    remaining.retainAll(skill.filterAllowed(remaining));  // INTERSECTION!
}
// Example: read-only {read} ∩ dev {write,bash} ∩ expert {read,write} = {}
```

#### ✅ Correct: Union (sum of all allowed tools)

```java
// Correct: Whitelists add up
Set<String> filtered = new HashSet<>(baseTools);
for (var skill : jdbcSkillStore.listAll()) {
    if (skill.hasToolRestrictions()) {
        Set<String> allowed = skill.filterAllowed(baseTools);
        filtered.addAll(allowed);  // UNION – whitelists sum up
    }
}
if (!filtered.equals(baseTools)) {
    agent.setToolRegistry(agent.getToolRegistry().withOnly(filtered));
}
```

**Why union is correct:** `allowed_tools` is a **whitelist** ("may use these tools"), not a constraint ("may ONLY use these"). Two different skills should **add up** their permissions, not exclude each other. If one skill allows `{read}` and another allows `{write,bash}`, the agent may use `{read, write, bash}`.

### Integration into `ChannelAgentFactory`

In `quad-quarkus/src/main/java/.../messaging/ChannelAgentFactory.java`:

```java
@Inject SkillsConfig skillStoreConfig;
@Inject JdbcSkillStore jdbcSkillStore;

private void applyDbSkills(Agent agent, Set<String> baseTools) {
    if (!skillStoreConfig.isEnabled()) return;
    
    Set<String> filtered = new HashSet<>(baseTools);
    for (var skill : jdbcSkillStore.listAll()) {
        if (skill.hasToolRestrictions()) {
            Set<String> allowed = skill.filterAllowed(baseTools);
            filtered.addAll(allowed);  // Union
        }
    }
    if (!filtered.equals(baseTools)) {
        var reg = agent.getToolRegistry();
        Set<String> currentNames = reg.getAll().stream()
            .map(t -> t.spec().name()).collect(Collectors.toSet());
        for (var name : currentNames) {
            if (!filtered.contains(name)) {
                reg.remove(name);
            }
        }
    }
}
```

### Activation

```properties
quad.skills.store.enabled=false     # default: off
quad.skills.store.auto-load=false   # seed from personas.yaml
```

---

## Interaction Between Layers

### Order of Application

```
1. AgentBuilder.build():                          withTools() → base registry
2. File skills:                                   XML-injection (no registry change!)
3. SubAgentTool/SubAgentTool:                     its own registry per sub-agent
4. DynamicSubAgentTool:                           new registry per call
5. DB Skills (applyDbSkills):                     union whitelist → registry filter
```

### What Happens When?

| Feature | When | Affects | Changes Registry? |
|---------|-----------|-----------|------------------|
| `withTools(Set)` | Build | Root agent | ✅ Yes |
| `withInitialSkills(List)` | Build | Root agent | ❌ No (XML/prompt only) |
| File skills (*.md) | Build + Runtime | Root agent | ❌ No (XML/prompt only) |
| `skill_search` / `tool_activator` | Runtime | Root agent | ❌ No (prompt only) |
| DB skills | Build (after Builder.build()) | Root agent | ✅ Yes (union whitelist) |
| `SubAgentTool` | Run | Sub-agent | ✅ Own registry |
| `DynamicSubAgentTool` | Run | Sub-agent | ✅ New registry per call |

### Cross-Interactions

- **A+B:** File-based skills completely ignore the static tool selection — they only act as prompt context. `initialSkills` activates them as an XML block but filters no tools.
- **A+C:** DB skills take the set filtered by `withTools` and enlarge it via union. They do not further intersect — that would be wrong (see above).
- **B+D:** `SkillActivationHook` checks after a `tool_activator` call whether a tool is mapped to a skill (`toolToSkill` map). If so → `activateSkill(...)` → re-inject the XML block. No registry change.
- **C+D:** `DynamicSubAgentTool.execute()` uses `capabilityRegistry.getSkill(name)` for prompt injection (`buildSkillBlock()`), but not for tool filtering. Union would need to be added here optionally.

---

## SubAgent Filtering

### Static: `SubAgentTool`

Creates its own `QuadToolRegistry` with the `@Tool` methods of the sub-agent (or with an explicitly assigned registry):

```java
// SubAgentTool.java
if (subAgent.getToolRegistry() == null) {
    QuadToolRegistry registry = new QuadToolRegistry(new ToolArgsMapper(...));
    registry.registerFromAgent(subAgent);
    subAgent.setToolRegistry(registry);
}
```

The sub-agent is registered as a tool in the parent via `AgentBuilder.withSubAgent(toolName, subAgent)`.

### Dynamic: `DynamicSubAgentTool`

Creates a fresh registry each time:

```java
// DynamicSubAgentTool.execute()
QuadToolRegistry agentRegistry = new QuadToolRegistry(new ToolArgsMapper(...));

if (toolNames.isEmpty()) {
    // All tools except FORBIDDEN_TOOLS
    for (ToolMethod tool : fullRegistry.getAll()) {
        if (!FORBIDDEN_TOOLS.contains(tool.spec().name())) {
            agentRegistry.register(tool.spec().name(), tool);
        }
    }
} else {
    // Only explicitly requested tools
    for (String name : toolNames) {
        ToolMethod tool = fullRegistry.get(name);
        if (tool != null) agentRegistry.register(name, tool);
    }
}
```

Parameter API: `{"prompt": "...", "tools": ["websearch", "read_file"], "skills": ["finance"]}`

---

## Migration / Database

### `skills` Table (V9__skills.sql)

```sql
create table if not exists skills (
    id varchar(64) primary key,
    name varchar(256) not null unique,
    description text,
    instructions text,
    allowed_tools text,
    declared_tools text,
    metadata text,
    created_at timestamp not null default CURRENT_TIMESTAMP
);
```

- `allowed_tools`: JSON array of tool names (e.g. `["readFile","webSearch"]`)
- `declared_tools`: JSON array — which tools a skill is expected to need
- `metadata`: JSON object (modelOverride, maxIterations etc.)

Upsert pattern: `DELETE FROM skills WHERE name=?` followed by `INSERT INTO skills ...`. SQLite-compatible (V1–V8 style).

---

## Summary: Design Principles

1. **Union instead of intersection:** DB skill whitelists add up. No exclusion through disjoint skills.
2. **File skills ≠ registry filter:** Files only serve prompt-context injection (`<activated_skills>` XML). No intervention in the tool registry.
3. **DB skills = real whitelist:** DB skills physically filter the registry via `QuadToolRegistry.withOnly(Set)`.
4. **SubAgents always isolated:** Each sub-agent has its own registry, independent of the parent.
5. **Layered design:** Static selection (A) → file prompt (B) → sub-agent registries (C/D) → DB whitelist (E) — each step works on its own level.