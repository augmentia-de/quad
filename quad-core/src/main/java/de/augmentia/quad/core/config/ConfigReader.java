package de.augmentia.quad.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigReader {

    private static final Logger log = LoggerFactory.getLogger(ConfigReader.class);
    private static volatile ConfigReader defaultInstance = new ConfigReader();

    private final Logger instanceLog;

    public ConfigReader() {
        this.instanceLog = LoggerFactory.getLogger(ConfigReader.class);
    }

    public ConfigReader(Logger instanceLog) {
        this.instanceLog = instanceLog != null ? instanceLog : LoggerFactory.getLogger(ConfigReader.class);
    }

    public static ConfigReader defaultInstance() {
        return defaultInstance;
    }

    public static void setDefaultInstance(ConfigReader reader) {
        defaultInstance = reader;
    }

    // ── Instance methods ──

    public String getValue(String key) {
        var val = System.getProperty("vault." + key);
        if (val != null && !val.isBlank()) {
            instanceLog.debug("ConfigReader.get({}) → vault.{} = {}", key, key, mask(val));
            return val;
        }
        val = System.getenv(key);
        if (val != null && !val.isBlank()) {
            instanceLog.debug("ConfigReader.get({}) → env.{} = {}", key, key, mask(val));
            return val;
        }
        val = System.getProperty(key);
        if (val != null && !val.isBlank()) {
            instanceLog.debug("ConfigReader.get({}) → sysprop.{} = {}", key, key, mask(val));
            return val;
        }
        instanceLog.debug("ConfigReader.get({}) → null", key);
        return null;
    }

    public String getValue(String key, String fallback) {
        var val = getValue(key);
        return val != null ? val : fallback;
    }

    public boolean hasAnyValue(String prefix) {
        return getValue(prefix + "PROVIDER") != null
            || getValue(prefix + "API_KEY") != null
            || getValue(prefix + "BASE_URL") != null
            || getValue(prefix + "MODEL") != null;
    }

    public static String mask(String s) {
        if (s == null) return null;
        if (s.length() <= 8) return s;
        return s.substring(0, 8) + "...";
    }

    public static Double parseDouble(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return null; }
    }

    public static Integer parseInt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return null; }
    }

    public static Boolean parseBoolean(String s) {
        if (s == null || s.isBlank()) return null;
        return Boolean.parseBoolean(s);
    }

    // ── Static convenience methods (delegate to default instance) ──

    public static String get(String key) {
        return defaultInstance.getValue(key);
    }

    public static String get(String key, String fallback) {
        return defaultInstance.getValue(key, fallback);
    }

    public static boolean hasAny(String prefix) {
        return defaultInstance.hasAnyValue(prefix);
    }
}
