package com.facebook.presto.phoenix;

import org.apache.phoenix.util.SchemaUtil;

import java.util.Optional;

import static org.apache.phoenix.query.QueryConstants.NULL_SCHEMA_NAME;
import static org.apache.phoenix.query.QueryServicesOptions.DEFAULT_SCHEMA;

public class MetadataUtil
{
    private MetadataUtil()
    {
    }

    public static String getEscapedTableName(Optional<String> schema, String table)
    {
        return SchemaUtil.getEscapedTableName(toPhoenixSchemaName(schema).orElse(null), table);
    }

    public static Optional<String> toPhoenixSchemaName(Optional<String> prestoSchemaName)
    {
        return prestoSchemaName.map(schemaName -> DEFAULT_SCHEMA.equalsIgnoreCase(schemaName) ? NULL_SCHEMA_NAME : schemaName);
    }

    public static Optional<String> toPrestoSchemaName(Optional<String> phoenixSchemaName)
    {
        return phoenixSchemaName.map(schemaName -> schemaName.isEmpty() ? DEFAULT_SCHEMA : schemaName);
    }
}
