package de.augmentia.quad.core.capability.skill;

import java.util.List;
import java.util.Optional;

/**
 * SPI for persisted skill storage. Lives in the framework-agnostic core next to
 * {@link SkillRegistry}/{@link TextSkill}; concrete backends (JDBC, in-memory)
 * implement it so the DB skill system and the file-based system can interoperate.
 */
public interface SkillStore {

    /** Creates or overwrites a skill (upsert by name). */
    void upsert(String id, String name, String description, String instructions,
                List<String> allowedTools, List<String> declaredTools, java.util.Map<String, String> meta);

    /** Loads a skill by name. */
    Optional<SkillEntry> findByName(String name);

    /** Lists all skills. */
    List<SkillEntry> listAll();

    /** Whether any skills exist. */
    boolean hasSkills();

    /** Deletes a skill by name. */
    void deleteByName(String name);
}
