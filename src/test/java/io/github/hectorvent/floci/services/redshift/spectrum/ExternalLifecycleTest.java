package io.github.hectorvent.floci.services.redshift.spectrum;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.glue.GlueService;
import io.github.hectorvent.floci.services.glue.model.Column;
import io.github.hectorvent.floci.services.glue.model.Partition;
import io.github.hectorvent.floci.services.glue.model.StorageDescriptor;
import io.github.hectorvent.floci.services.glue.model.Table;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ExternalLifecycleTest {
    private static final SpectrumSession SESSION = new SpectrumSession("000000000000", "000000000000:c",
            "dev", List.of(), false);
    private static final ExternalSchemaBinding BINDING = new ExternalSchemaBinding(SESSION.accountId(),
            SESSION.clusterKey(), "dev", "lake", "analytics", "arn:aws:iam::000000000000:role/R");
    private static final String ADD = "ALTER TABLE lake.sales ADD PARTITION (dt='2026-10-17') LOCATION 's3://bucket/day/'";
    private final ExternalCatalogRegistry registry = mock(ExternalCatalogRegistry.class);
    private final ExternalTableMaterializer materializer = mock(ExternalTableMaterializer.class);
    private final GlueService glue = mock(GlueService.class);
    private final BackendSql backend = mock(BackendSql.class);
    private final ExternalMetadataWriter metadata = spy(new ExternalMetadataWriter(glue, new ObjectMapper()));
    private final SpectrumQueryPreparation preparation = new SpectrumQueryPreparation(registry, materializer,
            new ExternalStatementParser(), glue, metadata);
    private Table table;

    @BeforeEach
    void setUp() {
        when(registry.list(SESSION.accountId(), SESSION.clusterKey(), "dev")).thenReturn(List.of(BINDING));
        Column key = new Column();
        key.setName("dt");
        key.setType("string");
        table = new Table();
        table.setName("sales");
        table.setPartitionKeys(List.of(key));
        table.setStorageDescriptor(new StorageDescriptor());
        when(glue.getTable("analytics", "sales")).thenReturn(table);
        doNothing().when(metadata).refresh(any(), anyString(), any());
    }

    @Test
    void registersPartitionAndInvalidatesMaterializedRows() {
        assertTrue(preparation.handlesDdl(ADD, SESSION));
        assertTrue(preparation.prepare(ADD, SESSION, backend));
        ArgumentCaptor<Partition> captured = ArgumentCaptor.forClass(Partition.class);
        verify(glue).createPartition(eq("analytics"), eq("sales"), captured.capture());
        assertEquals(List.of("2026-10-17"), captured.getValue().getValues());
        assertEquals("s3://bucket/day/", captured.getValue().getStorageDescriptor().getLocation());
        verify(materializer).invalidateTable(SESSION.clusterKey(), "dev", "lake", "sales");
        verify(metadata).refresh(backend, SESSION.accountId(), BINDING);
    }

    @Test
    void duplicatePartitionIsAnErrorAndIfNotExistsPreservesItsLocation() {
        Partition existing = new Partition();
        existing.setValues(List.of("2026-10-17"));
        when(glue.getPartitions("analytics", "sales")).thenReturn(List.of(existing));
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(ADD, SESSION, backend));
        assertEquals("42710", error.sqlState());
        assertTrue(preparation.prepare(ADD.replace("ADD PARTITION", "ADD IF NOT EXISTS PARTITION"), SESSION, backend));
        verify(glue, never()).createPartition(anyString(), anyString(), any());
    }

    @Test
    void validatesEveryPartitionBeforeWritingAny() {
        String sql = ADD + " PARTITION (wrong='x') LOCATION 's3://bucket/invalid/'";
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(sql, SESSION, backend));
        assertEquals("22023", error.sqlState());
        verify(glue, never()).createPartition(anyString(), anyString(), any());
    }

    @Test
    void duplicateWithinTheSameRequestDoesNotPartiallyRegister() {
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare(ADD + " PARTITION (dt='2026-10-17') LOCATION 's3://bucket/other/'", SESSION, backend));
        assertEquals("42710", error.sqlState());
        verify(glue, never()).createPartition(anyString(), anyString(), any());
    }

    @Test
    void dropTableRemovesGlueDefinitionAndColdOrCachedBackendRelation() {
        assertTrue(preparation.handlesDdl("DROP TABLE lake.sales", SESSION));
        assertTrue(preparation.prepare("DROP TABLE lake.sales", SESSION, backend));
        verify(backend).execute("DROP TABLE IF EXISTS \"lake\".\"sales\" RESTRICT");
        verify(glue).deleteTable("analytics", "sales");
        verify(materializer).forget(SESSION.clusterKey(), "dev", "lake", "sales");
        verify(metadata).refresh(backend, SESSION.accountId(), BINDING);
        verify(materializer, never()).ensureCurrent(any(), any(), any(), anyString());
    }

    @Test
    void failedDependencyCheckKeepsGlueTable() {
        doThrow(new SpectrumReadException("2BP01", "dependent view")).when(backend).execute(anyString());
        assertThrows(SpectrumReadException.class, () -> preparation.prepare("DROP TABLE lake.sales", SESSION, backend));
        verify(glue, never()).deleteTable(anyString(), anyString());
        verifyNoInteractions(materializer);
    }

    @Test
    void dropMissingTableRequiresIfExistsAndCleansStaleCache() {
        when(glue.getTable("analytics", "sales"))
                .thenThrow(new AwsException("EntityNotFoundException", "gone", 400));
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare("DROP TABLE lake.sales", SESSION, backend));
        assertEquals("42P01", error.sqlState());
        verifyNoInteractions(backend);
        assertTrue(preparation.prepare("DROP TABLE IF EXISTS lake.sales CASCADE", SESSION, backend));
        verify(backend).execute("DROP TABLE IF EXISTS \"lake\".\"sales\" CASCADE");
        verify(materializer).forget(SESSION.clusterKey(), "dev", "lake", "sales");
        verify(glue, never()).deleteTable(anyString(), anyString());
    }

    @Test
    void restrictChecksGlueTablesThatHaveNeverBeenLoaded() {
        when(glue.getTables("analytics")).thenReturn(List.of(table));
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare("DROP SCHEMA lake", SESSION, backend));
        assertEquals("2BP01", error.sqlState());
        verifyNoInteractions(backend);
        verify(registry, never()).unbind(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void cascadeUnregistersSchemaWithoutDeletingGlueDatabaseOrTables() {
        assertTrue(preparation.handlesDdl("DROP SCHEMA lake CASCADE", SESSION));
        assertTrue(preparation.prepare("DROP SCHEMA lake CASCADE", SESSION, backend));
        verify(backend).execute("DROP SCHEMA \"lake\" CASCADE; SELECT floci_internal.purge_external_schema('lake')");
        verify(registry).unbind(SESSION.accountId(), SESSION.clusterKey(), "dev", "lake");
        verify(materializer).forgetSchema(SESSION.clusterKey(), "dev", "lake");
        verify(glue, never()).deleteDatabase(anyString());
        verify(glue, never()).deleteTable(anyString(), anyString());
    }

    @Test
    void failedSchemaDropRetainsBindingAndCache() {
        doThrow(new SpectrumReadException("42501", "not owner")).when(backend).execute(anyString());
        assertThrows(SpectrumReadException.class, () -> preparation.prepare("DROP SCHEMA lake CASCADE", SESSION, backend));
        verify(registry, never()).unbind(anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(materializer);
    }

    @Test
    void rejectsLifecycleInsideTransactionsBeforeMutatingStores() {
        SpectrumSession transaction = new SpectrumSession(SESSION.accountId(), SESSION.clusterKey(), "dev", List.of(), true);
        for (String sql : List.of(ADD, "DROP TABLE lake.sales", "DROP SCHEMA lake CASCADE")) {
            assertTrue(preparation.handlesDdl(sql, transaction));
            SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                    () -> preparation.prepare(sql, transaction, backend));
            assertEquals("25001", error.sqlState());
        }
        verifyNoInteractions(glue, backend, materializer);
    }

    @Test
    void leavesInternalLifecycleToPostgres() {
        for (String sql : List.of("DROP TABLE public.sales", "DROP SCHEMA public CASCADE",
                "ALTER TABLE public.sales ADD COLUMN total INTEGER")) {
            assertFalse(preparation.handlesDdl(sql, SESSION));
            assertFalse(preparation.prepare(sql, SESSION, backend));
        }
        verifyNoInteractions(glue, backend, materializer);
    }

    @Test
    void malformedOrRepeatedPartitionKeysNeverReachGlue() {
        for (String keys : List.of("dt='x', garbage", "dt='x', dt='y'", "dt='x' garbage")) {
            SpectrumSqlException error = assertThrows(SpectrumSqlException.class, () -> preparation.prepare(
                    "ALTER TABLE lake.sales ADD PARTITION (" + keys + ") LOCATION 's3://bucket/day/'", SESSION, backend));
            assertEquals("42601", error.sqlState());
        }
        verifyNoInteractions(glue, backend, materializer);
    }

    @Test
    void ifExistsDoesNotSuppressAccessDenied() {
        when(glue.getTable("analytics", "sales"))
                .thenThrow(new AwsException("AccessDeniedException", "denied", 403));
        SpectrumSqlException error = assertThrows(SpectrumSqlException.class,
                () -> preparation.prepare("DROP TABLE IF EXISTS lake.sales", SESSION, backend));
        assertEquals("42501", error.sqlState());
        verifyNoInteractions(backend, materializer);
    }

    @Test
    void quotesSchemaAndTableNamesBeforeSendingBackendDdl() {
        ExternalSchemaBinding quoted = new ExternalSchemaBinding(SESSION.accountId(), SESSION.clusterKey(),
                "dev", "a\"b", "analytics", BINDING.iamRoleArn());
        when(registry.list(SESSION.accountId(), SESSION.clusterKey(), "dev")).thenReturn(List.of(quoted));
        assertTrue(preparation.prepare("DROP TABLE IF EXISTS \"a\"\"b\".\"sales\"", SESSION, backend));
        verify(backend).execute("DROP TABLE IF EXISTS \"a\"\"b\".\"sales\" RESTRICT");
    }

    @Test
    void backendAuthorizationFailsBeforeAnyCatalogMutation() {
        doThrow(new SpectrumReadException("42501", "not owner")).when(backend).execute(anyString());
        for (String sql : List.of(ADD, "DROP TABLE lake.sales")) {
            SpectrumReadException error = assertThrows(SpectrumReadException.class,
                    () -> preparation.prepare(sql, SESSION, backend));
            assertEquals("42501", error.sqlState());
        }
        verify(glue, never()).createPartition(anyString(), anyString(), any());
        verify(glue, never()).deleteTable(anyString(), anyString());
    }
}
