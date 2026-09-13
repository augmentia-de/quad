package de.augmentia.quad.core.capability;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PgVectorCapabilityIndexTest {

    private DataSource dataSource;
    private ObjectMapper objectMapper;
    private PgVectorCapabilityIndex index;
    private Connection connection;
    private PreparedStatement statement;

    @BeforeEach
    void setUp() throws SQLException {
        dataSource = mock(DataSource.class);
        objectMapper = new ObjectMapper();
        index = new PgVectorCapabilityIndex(dataSource, objectMapper, "capability_index");

        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);
    }

    @Test
    void shouldIndexCapability() throws Exception {
        Capability capability = new Capability(
            "test-capability",
            "A test capability",
            "com.example.MyClass::myMethod",
            "local",
            "builtin",
            Set.of("tenant-1"),
            0.9
        );

        assertDoesNotThrow(() -> index.index(capability));
        verify(statement).setString(eq(1), eq("test-capability"));
        verify(statement).setString(eq(2), eq("A test capability"));
        verify(statement).executeUpdate();
    }

    @Test
    void shouldRemoveCapability() throws Exception {
        when(statement.executeQuery()).thenReturn(mock(ResultSet.class));
        when(statement.getUpdateCount()).thenReturn(1);

        assertDoesNotThrow(() -> index.remove("test-capability"));
        verify(statement).setString(1, "test-capability");
        verify(statement).executeUpdate();
    }

    @Test
    void shouldSearchCapabilities() throws Exception {
        Capability cap1 = new Capability("cap-1", "description 1", "ref1", "src", "type", Set.of(), 0.8);
        Capability cap2 = new Capability("cap-2", "description 2", "ref2", "src", "type", Set.of(), 0.7);

        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("name")).thenReturn("cap-1", "cap-2");
        when(resultSet.getString("description")).thenReturn("description 1", "description 2");
        when(resultSet.getString("method_ref")).thenReturn("ref1", "ref2");
        when(resultSet.getString("source")).thenReturn("src", "src");
        when(resultSet.getString("type")).thenReturn("type", "type");
        when(resultSet.getString("allowed_tenants")).thenReturn(null, null);
        when(resultSet.getDouble("score")).thenReturn(0.8, 0.7);

        when(statement.executeQuery()).thenReturn(resultSet);

        List<Capability> results = index.search("test query", 10);

        assertEquals(2, results.size());
        assertEquals("cap-1", results.get(0).name());
        assertEquals("cap-2", results.get(1).name());
    }

    @Test
    void shouldSearchWithTenantFilter() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.next()).thenReturn(false);

        when(statement.executeQuery()).thenReturn(resultSet);

        List<Capability> results = index.search("test query", 10, "tenant-123");

        assertTrue(results.isEmpty());
        verify(statement, atLeastOnce()).setString(anyInt(), anyString());
    }

    @Test
    void shouldClearAllCapabilities() throws Exception {
        assertDoesNotThrow(() -> index.clear());
        verify(statement).executeUpdate();
    }

    @Test
    void shouldHandleJsonSerialization() throws Exception {
        Capability capability = new Capability(
            "cap-with-tenants",
            "Test",
            "ref",
            "src",
            "type",
            Set.of("tenant-1", "tenant-2"),
            0.5
        );

        String tenantsJson = objectMapper.writeValueAsString(capability.allowedTenants());
        assertTrue(tenantsJson.contains("tenant-1"));
        assertTrue(tenantsJson.contains("tenant-2"));
    }
}