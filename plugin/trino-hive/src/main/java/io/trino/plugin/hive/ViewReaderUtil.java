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
package io.trino.plugin.hive;

import com.linkedin.coral.hive.hive2rel.HiveMetastoreClient;
import com.linkedin.coral.hive.hive2rel.HiveToRelConverter;
import com.linkedin.coral.presto.rel2presto.RelToPrestoConverter;
import io.airlift.json.JsonCodec;
import io.airlift.json.JsonCodecFactory;
import io.airlift.json.ObjectMapperProvider;
import io.trino.plugin.base.CatalogName;
import io.trino.plugin.hive.authentication.HiveIdentity;
import io.trino.plugin.hive.metastore.CoralSemiTransactionalHiveMSCAdapter;
import io.trino.plugin.hive.metastore.SemiTransactionalHiveMetastore;
import io.trino.plugin.hive.metastore.Table;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorMaterializedViewDefinition;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorViewDefinition;
import io.trino.spi.connector.ConnectorViewDefinition.ViewColumn;
import io.trino.spi.type.TypeManager;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.hadoop.hive.metastore.TableType;

import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_INVALID_VIEW_DATA;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_VIEW_TRANSLATION_ERROR;
import static io.trino.plugin.hive.HiveMetadata.TABLE_COMMENT;
import static io.trino.plugin.hive.HiveSessionProperties.isLegacyHiveViewTranslation;
import static io.trino.plugin.hive.util.HiveUtil.checkCondition;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static org.apache.hadoop.hive.metastore.TableType.VIRTUAL_VIEW;

public final class ViewReaderUtil
{
    private ViewReaderUtil()
    {}

    public interface ViewReader
    {
        ConnectorViewDefinition decodeViewData(String viewData, Table table, CatalogName catalogName);
    }

    public static ViewReader createViewReader(SemiTransactionalHiveMetastore metastore, ConnectorSession session, Table table, TypeManager typemanager)
    {
        if (isPrestoView(table)) {
            return new PrestoViewReader();
        }
        if (isLegacyHiveViewTranslation(session)) {
            return new LegacyHiveViewReader();
        }

        return new HiveViewReader(new CoralSemiTransactionalHiveMSCAdapter(metastore, new HiveIdentity(session)), typemanager);
    }

    public static final String PRESTO_VIEW_FLAG = "presto_view";
    static final String VIEW_PREFIX = "/* Presto View: ";
    static final String VIEW_SUFFIX = " */";
    private static final String MATERIALIZED_VIEW_PREFIX = "/* Presto Materialized View: ";
    private static final String MATERIALIZED_VIEW_SUFFIX = " */";
    private static final JsonCodec<ConnectorViewDefinition> VIEW_CODEC =
            new JsonCodecFactory(new ObjectMapperProvider()).jsonCodec(ConnectorViewDefinition.class);

    private static final JsonCodec<ConnectorMaterializedViewDefinition> MATERIALIZED_VIEW_CODEC =
            new JsonCodecFactory(new ObjectMapperProvider()).jsonCodec(ConnectorMaterializedViewDefinition.class);

    public static boolean isPrestoView(Table table)
    {
        return "true".equals(table.getParameters().get(PRESTO_VIEW_FLAG));
    }

    public static boolean isHiveOrPrestoView(Table table)
    {
        return table.getTableType().equals(TableType.VIRTUAL_VIEW.name());
    }

    public static boolean canDecodeView(Table table)
    {
        // we can decode Hive or Presto view
        return table.getTableType().equals(VIRTUAL_VIEW.name());
    }

    public static String encodeViewData(ConnectorViewDefinition definition)
    {
        byte[] bytes = VIEW_CODEC.toJsonBytes(definition);
        String data = Base64.getEncoder().encodeToString(bytes);
        return VIEW_PREFIX + data + VIEW_SUFFIX;
    }

    public static String encodeMaterializedViewData(ConnectorMaterializedViewDefinition definition)
    {
        byte[] bytes = MATERIALIZED_VIEW_CODEC.toJsonBytes(definition);
        String data = Base64.getEncoder().encodeToString(bytes);
        return MATERIALIZED_VIEW_PREFIX + data + MATERIALIZED_VIEW_SUFFIX;
    }

    /**
     * Supports decoding of Presto views
     */
    public static class PrestoViewReader
            implements ViewReader
    {
        @Override
        public ConnectorViewDefinition decodeViewData(String viewData, Table table, CatalogName catalogName)
        {
            checkCondition(viewData.startsWith(VIEW_PREFIX), HIVE_INVALID_VIEW_DATA, "View data missing prefix: %s", viewData);
            checkCondition(viewData.endsWith(VIEW_SUFFIX), HIVE_INVALID_VIEW_DATA, "View data missing suffix: %s", viewData);
            viewData = viewData.substring(VIEW_PREFIX.length());
            viewData = viewData.substring(0, viewData.length() - VIEW_SUFFIX.length());
            byte[] bytes = Base64.getDecoder().decode(viewData);
            return VIEW_CODEC.fromJson(bytes);
        }

        public static ConnectorMaterializedViewDefinition decodeMaterializedViewData(String data)
        {
            checkCondition(data.startsWith(MATERIALIZED_VIEW_PREFIX), HIVE_INVALID_VIEW_DATA, "Materialized View data missing prefix: %s", data);
            checkCondition(data.endsWith(MATERIALIZED_VIEW_SUFFIX), HIVE_INVALID_VIEW_DATA, "Materialized View data missing suffix: %s", data);
            data = data.substring(MATERIALIZED_VIEW_PREFIX.length());
            data = data.substring(0, data.length() - MATERIALIZED_VIEW_SUFFIX.length());
            byte[] bytes = Base64.getDecoder().decode(data);
            return MATERIALIZED_VIEW_CODEC.fromJson(bytes);
        }
    }

    /**
     * Class to decode Hive view definitions
     */
    public static class HiveViewReader
            implements ViewReader
    {
        private final HiveMetastoreClient metastoreClient;
        private final TypeManager typeManager;

        public HiveViewReader(HiveMetastoreClient hiveMetastoreClient, TypeManager typemanager)
        {
            this.metastoreClient = requireNonNull(hiveMetastoreClient, "hiveMetastoreClient is null");
            this.typeManager = requireNonNull(typemanager, "typeManager is null");
        }

        @Override
        public ConnectorViewDefinition decodeViewData(String viewSql, Table table, CatalogName catalogName)
        {
            try {
                HiveToRelConverter hiveToRelConverter = HiveToRelConverter.create(metastoreClient);
                RelNode rel = hiveToRelConverter.convertView(table.getDatabaseName(), table.getTableName());
                RelToPrestoConverter rel2Presto = new RelToPrestoConverter();
                String prestoSql = rel2Presto.convert(rel);
                RelDataType rowType = rel.getRowType();
                List<ViewColumn> columns = rowType.getFieldList().stream()
                        .map(field -> new ViewColumn(
                                field.getName(),
                                typeManager.fromSqlType(getTypeString(field.getType())).getTypeId()))
                        .collect(toImmutableList());
                return new ConnectorViewDefinition(prestoSql,
                        Optional.of(catalogName.toString()),
                        Optional.of(table.getDatabaseName()),
                        columns,
                        Optional.ofNullable(table.getParameters().get(TABLE_COMMENT)),
                        Optional.empty(),
                        false);
            }
            catch (RuntimeException e) {
                throw new TrinoException(HIVE_VIEW_TRANSLATION_ERROR,
                        format("Failed to translate Hive view '%s': %s",
                                table.getSchemaTableName(),
                                e.getMessage()),
                        e);
            }
        }

        // Calcite does not provide correct type strings for non-primitive types.
        // We add custom code here to make it work. Goal is for calcite/coral to handle this
        private String getTypeString(RelDataType type)
        {
            switch (type.getSqlTypeName()) {
                case ROW: {
                    verify(type.isStruct(), "expected ROW type to be a struct: %s", type);
                    return type.getFieldList().stream()
                            .map(field -> field.getName().toLowerCase(Locale.ENGLISH) + " " + getTypeString(field.getType()))
                            .collect(joining(",", "row(", ")"));
                }
                case CHAR:
                    return "varchar";
                case FLOAT:
                    return "real";
                case BINARY:
                case VARBINARY:
                    return "varbinary";
                case MAP: {
                    RelDataType keyType = type.getKeyType();
                    RelDataType valueType = type.getValueType();
                    return format("map(%s,%s)", getTypeString(keyType), getTypeString(valueType));
                }
                case ARRAY: {
                    return format("array(%s)", getTypeString(type.getComponentType()));
                }
                case DECIMAL: {
                    return format("decimal(%s,%s)", type.getPrecision(), type.getScale());
                }
                default:
                    return type.getSqlTypeName().toString();
            }
        }
    }
}
