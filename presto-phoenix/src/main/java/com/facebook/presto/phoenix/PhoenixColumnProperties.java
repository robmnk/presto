package com.facebook.presto.phoenix;

import com.facebook.presto.spi.ColumnMetadata;
import com.facebook.presto.spi.session.PropertyMetadata;
import com.facebook.presto.spi.type.TypeManager;
import com.google.common.collect.ImmutableList;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.phoenix.PhoenixTableProperties.getRowkeys;
import static com.facebook.presto.spi.session.PropertyMetadata.booleanProperty;

public class PhoenixColumnProperties
{
    public static final String PRIMARY_KEY = "primary_key";

    private final List<PropertyMetadata<?>> columnProperties;

    @Inject
    public PhoenixColumnProperties(TypeManager typeManager)
    {
        columnProperties = ImmutableList.of(
                booleanProperty(
                        PRIMARY_KEY,
                        "True if the column is part of the primary key",
                        false,
                        false));
    }

    public List<PropertyMetadata<?>> getColumnProperties()
    {
        return columnProperties;
    }

    public static boolean isPrimaryKey(ColumnMetadata col, Map<String, Object> tableProperties)
    {
        Optional<List<String>> rowkeysTableProp = getRowkeys(tableProperties);
        if (rowkeysTableProp.isPresent()) {
            return rowkeysTableProp.get().stream().anyMatch(col.getName()::equalsIgnoreCase);
        }
        Boolean isPk = (Boolean) col.getProperties().get(PRIMARY_KEY);
        return isPk != null && isPk;
    }
}
