package me.ryanhamshire.GriefPrevention;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ClaimSaveFailureTest
{
    private static Claim claim(long id)
    {
        Claim claim = new Claim();
        claim.id = id;
        return claim;
    }

    @Test
    void flatFileSaveReportsFailureInsteadOfAcknowledgingTrustRestoration() throws Exception
    {
        FlatFileDataStore store = mock(FlatFileDataStore.class, CALLS_REAL_METHODS);
        Claim claim = claim(987654321L);
        doReturn("Owner: test\n").when(store).getYamlForClaim(claim);
        Path path = Path.of("plugins/GriefPreventionData/ClaimData/987654321.yml");
        Files.createDirectories(path);
        Files.writeString(path.resolve("blocker"), "cannot replace a nonempty directory");
        try (MockedStatic<GriefPrevention> ignored = mockStatic(GriefPrevention.class))
        {
            assertThrows(RuntimeException.class, () -> store.saveClaim(claim));
            assertEquals("cannot replace a nonempty directory", Files.readString(path.resolve("blocker")));
        }
        finally
        {
            Files.delete(path.resolve("blocker"));
            Files.delete(path);
        }
    }

    @Test
    void databaseInsertFailureIsReportedAndRolledBack() throws Exception
    {
        DatabaseDataStore store = mock(DatabaseDataStore.class, CALLS_REAL_METHODS);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        var field = DatabaseDataStore.class.getDeclaredField("databaseConnection");
        field.setAccessible(true);
        field.set(store, connection);
        when(connection.isValid(3)).thenReturn(true);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(1).thenThrow(new SQLException("disk full"));
        Claim claim = claim(42L);
        doReturn("world;0;0;0").when(store).locationToString(any());
        try (MockedStatic<GriefPrevention> ignored = mockStatic(GriefPrevention.class))
        {
            assertThrows(RuntimeException.class, () -> store.saveClaim(claim));
            verify(connection).rollback();
            verify(connection, never()).commit();
        }
    }
}
