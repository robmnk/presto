/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.phoenix;


import com.facebook.presto.plugin.jdbc.*;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.SchemaTableName;
import com.google.common.collect.ImmutableSet;
import io.airlift.log.Logger;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.ColumnHandle;
import com.facebook.presto.spi.predicate.TupleDomain;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.compile.QueryPlan;
import org.apache.phoenix.compile.StatementContext;
import org.apache.phoenix.iterate.*;
import org.apache.phoenix.jdbc.*;
import org.apache.phoenix.jdbc.PhoenixEmbeddedDriver.ConnectionInfo;
import org.apache.phoenix.mapreduce.PhoenixInputSplit;
import org.apache.phoenix.mapreduce.util.PhoenixConfigurationUtil;
import org.apache.phoenix.monitoring.ScanMetricsHolder;
import org.apache.phoenix.query.ConnectionQueryServices;
import org.apache.phoenix.query.HBaseFactoryProvider;
import org.apache.phoenix.schema.PName;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.jdbc.PhoenixDriver;

import javax.inject.Inject;

import java.io.IOException;
import java.sql.*;
import java.util.*;
import java.util.function.BiFunction;

import static com.facebook.presto.phoenix.MetadataUtil.toPhoenixSchemaName;
import static com.facebook.presto.phoenix.PhoenixClientModule.getConnectionProperties;
import static com.facebook.presto.phoenix.PhoenixErrorCode.PHOENIX_METADATA_ERROR;
import static com.facebook.presto.phoenix.PhoenixErrorCode.PHOENIX_QUERY_ERROR;
import static com.facebook.presto.plugin.jdbc.DriverConnectionFactory.basicConnectionProperties;
import static com.facebook.presto.spi.type.VarcharType.createUnboundedVarcharType;
import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.lang.String.join;
import static java.sql.Types.*;
import static java.sql.Types.ARRAY;
import static java.sql.Types.FLOAT;
import static java.util.Collections.nCopies;
import static java.util.Objects.requireNonNull;
import static org.apache.phoenix.coprocessor.BaseScannerRegionObserver.SKIP_REGION_BOUNDARY_CHECK;
import static org.apache.phoenix.query.QueryServicesOptions.DEFAULT_SCHEMA;
import static org.apache.phoenix.util.SchemaUtil.ESCAPE_CHARACTER;

public class PhoenixClient
        extends BaseJdbcClient
{
    private final Configuration configuration;

    @Inject
    public PhoenixClient(JdbcConnectorId connectorId, BaseJdbcConfig config, PhoenixConfig phoenixConfig)
            throws SQLException
    {
        super(connectorId, config, ESCAPE_CHARACTER, connectionFactory(config, phoenixConfig));
        this.configuration = new Configuration(false);
        getConnectionProperties(phoenixConfig).forEach((k, v) -> configuration.set((String) k, (String) v));
    }

    private static ConnectionFactory connectionFactory(BaseJdbcConfig config, PhoenixConfig phoenixConfig)
            throws SQLException
    {
        Properties connectionProperties = basicConnectionProperties(config);
        connectionProperties.setProperty("useInformationSchema", "true");
        connectionProperties.setProperty("nullCatalogMeansCurrent", "false");
        connectionProperties.setProperty("useUnicode", "true");
        connectionProperties.setProperty("characterEncoding", "utf8");
        connectionProperties.setProperty("tinyInt1isBit", "false");
//        if (mySqlConfig.isAutoReconnect()) {
//            connectionProperties.setProperty("autoReconnect", String.valueOf(mySqlConfig.isAutoReconnect()));
//            connectionProperties.setProperty("maxReconnects", String.valueOf(mySqlConfig.getMaxReconnects()));
//        }
//        if (mySqlConfig.getConnectionTimeout() != null) {
//            connectionProperties.setProperty("connectTimeout", String.valueOf(mySqlConfig.getConnectionTimeout().toMillis()));
//        }

        return new DriverConnectionFactory(new PhoenixDriver(), config.getConnectionUrl(), connectionProperties);
    }

    public PhoenixConnection getConnection()
            throws SQLException
    {
        return connectionFactory.openConnection().unwrap(PhoenixConnection.class);
    }

    public org.apache.hadoop.hbase.client.Connection getHConnection()
            throws IOException
    {
        return HBaseFactoryProvider.getHConnectionFactory().createConnection(configuration);
    }

    public void execute(ConnectorSession session, String statement)
    {
        try (Connection connection = connectionFactory.openConnection()) {
            execute(connection, statement);
        }
        catch (SQLException e) {
            throw new PrestoException(PHOENIX_QUERY_ERROR, "Error while executing statement", e);
        }
    }

    @Override
    public PreparedStatement buildSql(Connection connection, JdbcSplit split, List<JdbcColumnHandle> columnHandles)
            throws SQLException
    {
        PhoenixSplit phoenixSplit = (PhoenixSplit) split;
        PreparedStatement query = new QueryBuilder(identifierQuote).buildSql(
                this,
                connection,
                split.getCatalogName(),
                split.getSchemaName(),
                split.getTableName(),
                columnHandles,
                split.getTupleDomain(),
                split.getAdditionalPredicate());
        QueryPlan queryPlan = getQueryPlan((PhoenixPreparedStatement) query);
        ResultSet resultSet = getResultSet(phoenixSplit.getPhoenixInputSplit(), queryPlan);
        return new DelegatePreparedStatement(query)
        {
            @Override
            public ResultSet executeQuery()
            {
                return resultSet;
            }
        };
    }

    @Override
    protected Optional<BiFunction<String, Long, String>> limitFunction()
    {
        return Optional.of((sql, limit) -> sql + " LIMIT " + limit);
    }

    @Override
    public String buildInsertSql(JdbcOutputTableHandle handle)
    {
        PhoenixOutputTableHandle outputHandle = (PhoenixOutputTableHandle) handle;
        String params = join(",", nCopies(handle.getColumnNames().size(), "?"));
        if (outputHandle.hasUUIDRowkey()) {
            String nextId = format(
                    "NEXT VALUE FOR %s, ",
                    quoted(null, handle.getSchemaName(), handle.getTableName() + "_sequence"));
            params = nextId + params;
        }
        return format(
                "UPSERT INTO %s VALUES (%s)",
                quoted(null, handle.getSchemaName(), handle.getTableName()),
                params);
    }

    @Override
    protected ResultSet getTables(Connection connection, String schemaName, String tableName)
            throws SQLException
    {
        Optional<String> optionalSchemaName = Optional.ofNullable(schemaName);
        Optional<String> phoenixSchemaName = toPhoenixSchemaName(optionalSchemaName);
        return super.getTables(connection, phoenixSchemaName.get(), tableName);
    }

    @Override
    protected SchemaTableName getSchemaTableName(ResultSet resultSet)
            throws SQLException
    {
        String schemaName = firstNonNull(resultSet.getString("TABLE_SCHEM"), DEFAULT_SCHEMA);
        return new SchemaTableName(schemaName, resultSet.getString("TABLE_NAME"));
    }

    @Override
    public Optional<ReadMapping> toPrestoType(ConnectorSession session, JdbcTypeHandle typeHandle)
    {
        switch (typeHandle.getJdbcType()) {
            case VARCHAR:
            case NVARCHAR:
            case LONGVARCHAR:
            case LONGNVARCHAR:
                if (typeHandle.getColumnSize() == 0) {
                    return Optional.of(varcharColumnMapping(createUnboundedVarcharType()));
                }
                return super.toPrestoType(session, connection, typeHandle);
            // TODO add support for TIMESTAMP after Phoenix adds support for LocalDateTime
            case TIMESTAMP:
            case TIME_WITH_TIMEZONE:
            case TIMESTAMP_WITH_TIMEZONE:
                return Optional.empty();
            case FLOAT:
                return Optional.of(realColumnMapping());
            case ARRAY:
                JdbcTypeHandle elementTypeHandle = getArrayElementTypeHandle(typeHandle);
                if (elementTypeHandle.getJdbcType() == Types.VARBINARY) {
                    return Optional.empty();
                }
                return toPrestoType(session, connection, elementTypeHandle)
                        .map(elementMapping -> {
                            ArrayType prestoArrayType = new ArrayType(elementMapping.getType());
                            String jdbcTypeName = elementTypeHandle.getJdbcTypeName()
                                    .orElseThrow(() -> new PrestoException(
                                            PHOENIX_METADATA_ERROR,
                                            "Type name is missing for jdbc type: " + JDBCType.valueOf(elementTypeHandle.getJdbcType())));
                            return arrayColumnMapping(session, prestoArrayType, jdbcTypeName);
                        });
        }
        return super.toPrestoType(session, connection, typeHandle);
    }

    @Override
    public WriteMapping toWriteMapping(ConnectorSession session, Type type)
    {
        if (DOUBLE.equals(type)) {
            return WriteMapping.doubleMapping("double", doubleWriteFunction());
        }
        if (REAL.equals(type)) {
            return WriteMapping.longMapping("float", realWriteFunction());
        }
        if (TIME.equals(type)) {
            return WriteMapping.longMapping("time", timeWriteFunction());
        }
        // Phoenix doesn't support _WITH_TIME_ZONE
        if (TIME_WITH_TIME_ZONE.equals(type) || TIMESTAMP_WITH_TIME_ZONE.equals(type)) {
            throw new PrestoException(NOT_SUPPORTED, "Unsupported column type: " + type.getDisplayName());
        }
        if (type instanceof ArrayType) {
            Type elementType = ((ArrayType) type).getElementType();
            String elementDataType = toWriteMapping(session, elementType).getDataType().toUpperCase();
            String elementWriteName = getArrayElementPhoenixTypeName(session, this, elementType);
            return WriteMapping.blockMapping(elementDataType + " ARRAY", arrayWriteFunction(session, elementType, elementWriteName));
        }
        return super.toWriteMapping(session, type);
    }

    private static ColumnMapping arrayColumnMapping(ConnectorSession session, ArrayType arrayType, String elementJdbcTypeName)
    {
        return ColumnMapping.blockMapping(
                arrayType,
                arrayReadFunction(session, arrayType.getElementType()),
                arrayWriteFunction(session, arrayType.getElementType(), elementJdbcTypeName));
    }

    private static BlockReadFunction arrayReadFunction(ConnectorSession session, Type elementType)
    {
        return (resultSet, columnIndex) -> {
            Object[] objectArray = toBoxedArray(resultSet.getArray(columnIndex).getArray());
            return jdbcObjectArrayToBlock(session, elementType, objectArray);
        };
    }

    private static BlockWriteFunction arrayWriteFunction(ConnectorSession session, Type elementType, String elementJdbcTypeName)
    {
        return (statement, index, block) -> {
            Array jdbcArray = statement.getConnection().createArrayOf(elementJdbcTypeName, getJdbcObjectArray(session, elementType, block));
            statement.setArray(index, jdbcArray);
        };
    }

    private JdbcTypeHandle getArrayElementTypeHandle(JdbcTypeHandle arrayTypeHandle)
    {
        String arrayTypeName = arrayTypeHandle.getJdbcTypeName()
                .orElseThrow(() -> new PrestoException(PHOENIX_METADATA_ERROR, "Type name is missing for jdbc type: " + JDBCType.valueOf(arrayTypeHandle.getJdbcType())));
        checkArgument(arrayTypeName.endsWith(" ARRAY"), "array type must end with ' ARRAY'");
        arrayTypeName = arrayTypeName.substring(0, arrayTypeName.length() - " ARRAY".length());
        return new JdbcTypeHandle(
                PDataType.fromSqlTypeName(arrayTypeName).getSqlType(),
                Optional.of(arrayTypeName),
                arrayTypeHandle.getColumnSize(),
                arrayTypeHandle.getDecimalDigits(),
                arrayTypeHandle.getArrayDimensions());
    }

    public QueryPlan getQueryPlan(PhoenixPreparedStatement inputQuery)
    {
        try {
            // Optimize the query plan so that we potentially use secondary indexes
            QueryPlan queryPlan = inputQuery.optimizeQuery();
            // Initialize the query plan so it sets up the parallel scans
            queryPlan.iterator(MapReduceParallelScanGrouper.getInstance());
            return queryPlan;
        }
        catch (SQLException e) {
            throw new PrestoException(PHOENIX_QUERY_ERROR, "Failed to get the Phoenix query plan", e);
        }
    }

    private static ResultSet getResultSet(PhoenixInputSplit split, QueryPlan queryPlan)
    {
        List<Scan> scans = split.getScans();
        try {
            List<PeekingResultIterator> iterators = new ArrayList<>(scans.size());
            StatementContext context = queryPlan.getContext();
            // Clear the table region boundary cache to make sure long running jobs stay up to date
            PName physicalTableName = queryPlan.getTableRef().getTable().getPhysicalName();
            PhoenixConnection phoenixConnection = context.getConnection();
            ConnectionQueryServices services = phoenixConnection.getQueryServices();
            services.clearTableRegionCache(physicalTableName.getBytes());

            for (Scan scan : scans) {
                scan = new Scan(scan);
                // For MR, skip the region boundary check exception if we encounter a split. ref: PHOENIX-2599
                scan.setAttribute(SKIP_REGION_BOUNDARY_CHECK, Bytes.toBytes(true));

                ScanMetricsHolder scanMetricsHolder = ScanMetricsHolder.getInstance(
                        context.getReadMetricsQueue(),
                        physicalTableName.getString(),
                        scan,
                        phoenixConnection.getLogLevel());

                TableResultIterator tableResultIterator = new TableResultIterator(
                        phoenixConnection.getMutationState(),
                        scan,
                        scanMetricsHolder,
                        services.getRenewLeaseThresholdMilliSeconds(),
                        queryPlan,
                        MapReduceParallelScanGrouper.getInstance());
                iterators.add(LookAheadResultIterator.wrap(tableResultIterator));
            }
            ResultIterator iterator = queryPlan.useRoundRobinIterator() ? RoundRobinResultIterator.newIterator(iterators, queryPlan) : ConcatResultIterator.newIterator(iterators);
            if (context.getSequenceManager().getSequenceCount() > 0) {
                iterator = new SequenceResultIterator(iterator, context.getSequenceManager());
            }
            // Clone the row projector as it's not thread safe and would be used simultaneously by
            // multiple threads otherwise.
            return new PhoenixResultSet(iterator, queryPlan.getProjector().cloneIfNecessary(), context);
        }
        catch (SQLException e) {
            throw new PrestoException(PHOENIX_QUERY_ERROR, "Error while setting up Phoenix ResultSet", e);
        }
        catch (IOException e) {
            throw new PrestoException(PhoenixErrorCode.PHOENIX_INTERNAL_ERROR, "Error while copying scan", e);
        }
    }
}
