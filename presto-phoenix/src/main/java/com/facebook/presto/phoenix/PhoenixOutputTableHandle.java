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

import com.facebook.presto.plugin.jdbc.JdbcOutputTableHandle;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.facebook.presto.spi.ConnectorInsertTableHandle;
import com.facebook.presto.spi.ConnectorOutputTableHandle;
import com.google.common.collect.ImmutableList;
import com.facebook.presto.spi.type.Type;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class PhoenixOutputTableHandle
        extends JdbcOutputTableHandle
{
    private final boolean hasUuidRowKey;

    @JsonCreator
    public PhoenixOutputTableHandle(
            @JsonProperty("connectorId") String connectorId,
            @JsonProperty("schemaName") Optional<String> schemaName,
            @JsonProperty("tableName") String tableName,
            @JsonProperty("columnNames") List<String> columnNames,
            @JsonProperty("columnTypes") List<Type> columnTypes,
            @JsonProperty("hadUUIDRowkey") boolean hasUUIDRowkey)
    {
        super(connectorId, "", schemaName.orElse(null), tableName, columnNames, columnTypes, "");
        this.hasUuidRowKey = hasUUIDRowkey;
    }

    @JsonProperty
    public boolean hasUUIDRowkey()
    {
        return hasUuidRowKey;
    }
}
