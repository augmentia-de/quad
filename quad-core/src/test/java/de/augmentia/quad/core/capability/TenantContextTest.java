package de.augmentia.quad.core.capability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TenantContextTest {

    @BeforeEach
    void setUp() {
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void shouldSetAndGetTenantId() {
        TenantContext.current().setTenantId("tenant-123");

        assertEquals("tenant-123", TenantContext.current().tenantId());
    }

    @Test
    void shouldReturnDefaultTenantId() {
        assertEquals("default", TenantContext.current().tenantId());
    }

    @Test
    void shouldClearThreadLocal() {
        TenantContext.current().setTenantId("tenant-456");
        assertEquals("tenant-456", TenantContext.current().tenantId());

        TenantContext.clear();

        assertEquals("default", TenantContext.current().tenantId());
    }

    @Test
    void shouldBeAutoCloseableScope() throws Exception {
        TenantContext.current().setTenantId("tenant-scoped");

        try (TenantContext.Scope scope = TenantContext.withTenant("tenant-auto-close")) {
            assertEquals("tenant-auto-close", TenantContext.current().tenantId());
        }

        assertEquals("default", TenantContext.current().tenantId());
    }

    @Test
    void shouldSupportScopedTenant() throws Exception {
        String tenantName = "tenant-" + System.nanoTime();

        try (TenantContext.Scope scope = TenantContext.withTenant(tenantName)) {
            assertEquals(tenantName, TenantContext.current().tenantId());
            TenantContext.current().setTenantId("nested-" + tenantName);
            assertEquals("nested-" + tenantName, TenantContext.current().tenantId());
        }

        assertEquals("default", TenantContext.current().tenantId());
    }

    @Test
    void shouldMaintainIsolationBetweenDifferentScopes() throws Exception {
        try (TenantContext.Scope scope1 = TenantContext.withTenant("tenant-a")) {
            assertEquals("tenant-a", TenantContext.current().tenantId());
        }

        try (TenantContext.Scope scope2 = TenantContext.withTenant("tenant-b")) {
            assertEquals("tenant-b", TenantContext.current().tenantId());
        }

        assertEquals("default", TenantContext.current().tenantId());
    }

    @Test
    void shouldAllowReusingContextAfterClear() {
        TenantContext.current().setTenantId("first-tenant");
        TenantContext.clear();

        TenantContext.current().setTenantId("second-tenant");
        assertEquals("second-tenant", TenantContext.current().tenantId());
    }
}