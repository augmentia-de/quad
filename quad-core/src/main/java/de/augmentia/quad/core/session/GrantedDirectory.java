package de.augmentia.quad.core.session;

import java.nio.file.Path;

/**
 * A user-granted directory that expands the session sandbox beyond the session
 * workspace root.
 * <p>
 * The path is always stored normalized and absolute. {@link #access()} controls
 * whether the agent may only read it ({@link Access#READ}) or also write
 * ({@link Access#READ_WRITE}).
 */
public record GrantedDirectory(Path path, Access access) {

    public GrantedDirectory {
        if (path == null) {
            throw new IllegalArgumentException("path must not be null");
        }
        path = path.toAbsolutePath().normalize();
        if (access == null) {
            access = Access.READ;
        }
    }

    public boolean isWritable() {
        return access == Access.READ_WRITE;
    }

    public GrantedDirectory withWritable(boolean writable) {
        return new GrantedDirectory(path, writable ? Access.READ_WRITE : Access.READ);
    }
}
