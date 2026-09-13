package de.augmentia.quad.core.capability;

import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class TenantContext {

    private String tenantId = "default";

    public String tenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    private static final ThreadLocal<TenantContext> holder = ThreadLocal.withInitial(TenantContext::new);

    public static TenantContext current() {
        return holder.get();
    }

    public static void clear() {
        holder.remove();
    }

    public static Scope withTenant(String tenantId) {
        TenantContext ctx = current();
        ctx.setTenantId(tenantId);
        return new Scope();
    }

    public static class Scope implements AutoCloseable {
        @Override
        public void close() {
            clear();
        }
    }
}