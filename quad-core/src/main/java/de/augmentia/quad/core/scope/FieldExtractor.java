package de.augmentia.quad.core.scope;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Extracts values from Records, Maps and POJOs via path expressions.
 * <p>
 * Supported syntax:
 * <ul>
 *   <li>{@code field} – direct component/key</li>
 *   <li>{@code nested.field} – nested Records/Maps</li>
 *   <li>{@code list[0]} / {@code nested.list[2]} – element of a {@link List}</li>
 * </ul>
 */
public final class FieldExtractor {

    private FieldExtractor() {}

    /**
     * Walks the given path on the root object.
     *
     * @return the resolved value or {@code null} if any segment cannot be resolved
     */
    @SuppressWarnings("unchecked")
    public static <T> T extract(Object root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        Object current = root;
        for (String segment : path.split("\\.")) {
            if (current == null) return null;
            current = resolveSegment(current, segment);
        }
        return (T) current;
    }

    /**
     * Resolves a path and renders the result as prompt-ready text.
     * Records/POJOs are rendered via {@code toString()}, strings as-is.
     */
    public static String extractText(Object root, String path) {
        Object value = extract(root, path);
        if (value == null) return "";
        return value instanceof String s ? s : String.valueOf(value);
    }

    private static Object resolveSegment(Object current, String segment) {
        int bracket = segment.indexOf('[');
        String name = bracket > 0 ? segment.substring(0, bracket) : segment;

        Object value = name.isEmpty() ? current : readField(current, name);

        while (bracket > 0) {
            int close = segment.indexOf(']', bracket);
            if (close < 0 || value == null) return null;
            int index;
            try {
                index = Integer.parseInt(segment.substring(bracket + 1, close).trim());
            } catch (NumberFormatException e) {
                return null;
            }
            value = listElement(value, index);
            bracket = segment.indexOf('[', close);
        }
        return value;
    }

    private static Object readField(Object obj, String name) {
        if (obj instanceof Map<?, ?> map) {
            return map.get(name);
        }
        if (obj instanceof Record record) {
            return Arrays.stream(record.getClass().getRecordComponents())
                .filter(c -> c.getName().equals(name))
                .findFirst()
                .map(c -> invoke(c, record))
                .orElse(null);
        }
        try {
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            try {
                return obj.getClass().getMethod(getter).invoke(obj);
            } catch (NoSuchMethodException nsme) {
                var method = obj.getClass().getDeclaredMethod(getter);
                method.setAccessible(true);
                return method.invoke(obj);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Object invoke(RecordComponent c, Record record) {
        try {
            var accessor = c.getAccessor();
            if (!accessor.canAccess(record)) {
                accessor.setAccessible(true);
            }
            return accessor.invoke(record);
        } catch (Exception e) {
            return null;
        }
    }

    private static Object listElement(Object value, int index) {
        if (value instanceof List<?> list) {
            return index >= 0 && index < list.size() ? list.get(index) : null;
        }
        if (value.getClass().isArray()) {
            Object[] arr = (Object[]) value;
            return index >= 0 && index < arr.length ? arr[index] : null;
        }
        return null;
    }
}