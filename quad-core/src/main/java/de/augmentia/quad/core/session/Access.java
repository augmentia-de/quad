package de.augmentia.quad.core.session;

/**
 * Access level granted to a directory.
 * <p>
 * {@link #READ} allows read-only access (list, read file, search),
 * {@link #READ_WRITE} additionally allows creating/modifying/deleting files.
 */
public enum Access {
    READ,
    READ_WRITE
}
