/*
 * Copyright Debezium Authors.
 *
 * Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */

package io.debezium.connector.mysql.antlr;

import java.sql.Types;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.misc.Interval;
import org.antlr.v4.runtime.tree.ParseTree;

import io.debezium.annotation.VisibleForTesting;
import io.debezium.antlr.AntlrDdlParser;
import io.debezium.antlr.AntlrDdlParserListener;
import io.debezium.antlr.DataTypeResolver;
import io.debezium.antlr.DataTypeResolver.DataTypeEntry;
import io.debezium.connector.binlog.charset.BinlogCharsetRegistry;
import io.debezium.connector.binlog.jdbc.BinlogSystemVariables;
import io.debezium.connector.mysql.antlr.listener.MySqlAntlrDdlParserListener;
import io.debezium.ddl.parser.mysql.generated.MySqlLexer;
import io.debezium.ddl.parser.mysql.generated.MySqlParser;
import io.debezium.ddl.parser.mysql.generated.MySqlParser.CharsetNameContext;
import io.debezium.ddl.parser.mysql.generated.MySqlParser.CollationNameContext;
import io.debezium.ddl.parser.mysql.generated.MySqlParser.RenameTableClauseContext;
import io.debezium.ddl.parser.mysql.generated.MySqlParser.RenameTableContext;
import io.debezium.relational.Column;
import io.debezium.relational.ColumnEditor;
import io.debezium.relational.SystemVariables;
import io.debezium.relational.TableEditor;
import io.debezium.relational.TableId;
import io.debezium.relational.Tables.TableFilter;

/**
 * An ANTLR based parser for MySQL DDL statements.
 *
 * @author Roman Kuchár <kucharrom@gmail.com>.
 */
public class MySqlAntlrDdlParser extends AntlrDdlParser<MySqlLexer, MySqlParser> {

    private final ConcurrentMap<String, String> charsetNameForDatabase = new ConcurrentHashMap<>();
    private final TableFilter tableFilter;
    private final BinlogCharsetRegistry charsetRegistry;
    private final DataTypeResolver dataTypeResolver = initializeDataTypeResolver();

    @VisibleForTesting
    public MySqlAntlrDdlParser() {
        this(TableFilter.includeAll());
    }

    @VisibleForTesting
    public MySqlAntlrDdlParser(TableFilter tableFilter) {
        this(true, false, false, tableFilter, null);
    }

    public MySqlAntlrDdlParser(boolean throwErrorsFromTreeWalk, boolean includeViews, boolean includeComments,
                               TableFilter tableFilter, BinlogCharsetRegistry charsetRegistry) {
        super(throwErrorsFromTreeWalk, includeViews, includeComments);
        systemVariables = new BinlogSystemVariables();
        this.tableFilter = tableFilter;
        this.charsetRegistry = charsetRegistry;
    }

    @Override
    protected ParseTree parseTree(MySqlParser parser) {
        return parser.root();
    }

    @Override
    protected AntlrDdlParserListener createParseTreeWalkerListener() {
        return new MySqlAntlrDdlParserListener(this);
    }

    @Override
    protected MySqlLexer createNewLexerInstance(CharStream charStreams) {
        return new MySqlLexer(charStreams);
    }

    @Override
    protected MySqlParser createNewParserInstance(CommonTokenStream commonTokenStream) {
        return new MySqlParser(commonTokenStream);
    }

    @Override
    protected SystemVariables createNewSystemVariablesInstance() {
        return new BinlogSystemVariables();
    }

    @Override
    protected boolean isGrammarInUpperCase() {
        return true;
    }

    @Override
    public DataTypeResolver dataTypeResolver() {
        return dataTypeResolver;
    }

    private DataTypeResolver initializeDataTypeResolver() {
        DataTypeResolver.Builder dataTypeResolverBuilder = new DataTypeResolver.Builder();

        dataTypeResolverBuilder.registerDataTypes(MySqlParser.StringDataTypeContext.class.getCanonicalName(), Arrays.asList(
                new DataTypeEntry(Types.CHAR, MySqlParser.CHAR),
                new DataTypeEntry(Types.VARCHAR, MySqlParser.CHAR, MySqlParser.VARYING),
                new DataTypeEntry(Types.VARCHAR, MySqlParser.VARCHAR),
                new DataTypeEntry(Types.VARCHAR, MySqlParser.TINYTEXT),
                new DataTypeEntry(Types.VARCHAR, MySqlParser.TEXT),
                new DataTypeEntry(Types.VARCHAR, MySqlParser.MEDIUMTEXT),
                new DataTypeEntry(Types.VARCHAR, MySqlParser.LONGTEXT),
                new DataTypeEntry(Types.VARCHAR, MySqlParser.LONG),
                new DataTypeEntry(Types.NCHAR, MySqlParser.NCHAR),
                new DataTypeEntry(Types.NVARCHAR, MySqlParser.NCHAR, MySqlParser.VARYING),
                new DataTypeEntry(Types.NVARCHAR, MySqlParser.NVARCHAR),
                new DataTypeEntry(Types.CHAR, MySqlParser.CHAR, MySqlParser.BINARY),
                new DataTypeEntry(Types.VARCHAR, MySqlParser.VARCHAR, MySqlParser.BINARY),
                new DataTypeEntry(Types.VARCHAR, MySqlParser.TINYTEXT, MySqlParser.BINARY),
                new DataTypeEntry(Types.VARCHAR, MySqlParser.TEXT, MySqlParser.BINARY),
                new DataTypeEntry(Types.VARCHAR, MySqlParser.MEDIUMTEXT, MySqlParser.BINARY),
                new DataTypeEntry(Types.VARCHAR, MySqlParser.LONGTEXT, MySqlParser.BINARY),
                new DataTypeEntry(Types.NCHAR, MySqlParser.NCHAR, MySqlParser.BINARY),
                new DataTypeEntry(Types.NVARCHAR, MySqlParser.NVARCHAR, MySqlParser.BINARY),
                new DataTypeEntry(Types.CHAR, MySqlParser.CHARACTER),
                new DataTypeEntry(Types.VARCHAR, MySqlParser.CHARACTER, MySqlParser.VARYING)));
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.NationalStringDataTypeContext.class.getCanonicalName(), Arrays.asList(
                new DataTypeEntry(Types.NVARCHAR, MySqlParser.NATIONAL, MySqlParser.VARCHAR).setSuffixTokens(MySqlParser.BINARY),
                new DataTypeEntry(Types.NCHAR, MySqlParser.NATIONAL, MySqlParser.CHARACTER).setSuffixTokens(MySqlParser.BINARY),
                new DataTypeEntry(Types.NCHAR, MySqlParser.NATIONAL, MySqlParser.CHAR).setSuffixTokens(MySqlParser.BINARY),
                new DataTypeEntry(Types.NVARCHAR, MySqlParser.NCHAR, MySqlParser.VARCHAR).setSuffixTokens(MySqlParser.BINARY)));
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.NationalVaryingStringDataTypeContext.class.getCanonicalName(), Arrays.asList(
                new DataTypeEntry(Types.NVARCHAR, MySqlParser.NATIONAL, MySqlParser.CHAR, MySqlParser.VARYING),
                new DataTypeEntry(Types.NVARCHAR, MySqlParser.NATIONAL, MySqlParser.CHARACTER, MySqlParser.VARYING)));
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.DimensionDataTypeContext.class.getCanonicalName(), Arrays.asList(
                new DataTypeEntry(Types.SMALLINT, MySqlParser.TINYINT)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.SMALLINT, MySqlParser.INT1)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.SMALLINT, MySqlParser.SMALLINT)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.SMALLINT, MySqlParser.INT2)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.INTEGER, MySqlParser.MEDIUMINT)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.INTEGER, MySqlParser.INT3)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.INTEGER, MySqlParser.MIDDLEINT)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.INTEGER, MySqlParser.INT)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.INTEGER, MySqlParser.INTEGER)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.INTEGER, MySqlParser.INT4)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.BIGINT, MySqlParser.BIGINT)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.BIGINT, MySqlParser.INT8)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.REAL, MySqlParser.REAL)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.DOUBLE, MySqlParser.DOUBLE)
                        .setSuffixTokens(MySqlParser.PRECISION, MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.DOUBLE, MySqlParser.FLOAT8)
                        .setSuffixTokens(MySqlParser.PRECISION, MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.FLOAT, MySqlParser.FLOAT)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.FLOAT, MySqlParser.FLOAT4)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL),
                new DataTypeEntry(Types.DECIMAL, MySqlParser.DECIMAL)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL)
                        .setDefaultLengthScaleDimension(10, 0),
                new DataTypeEntry(Types.DECIMAL, MySqlParser.DEC)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL)
                        .setDefaultLengthScaleDimension(10, 0),
                new DataTypeEntry(Types.DECIMAL, MySqlParser.FIXED)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL)
                        .setDefaultLengthScaleDimension(10, 0),
                new DataTypeEntry(Types.NUMERIC, MySqlParser.NUMERIC)
                        .setSuffixTokens(MySqlParser.SIGNED, MySqlParser.UNSIGNED, MySqlParser.ZEROFILL)
                        .setDefaultLengthScaleDimension(10, 0),
                new DataTypeEntry(Types.BIT, MySqlParser.BIT)
                        .setDefaultLengthDimension(1),
                new DataTypeEntry(Types.OTHER, MySqlParser.VECTOR)
                        .setDefaultLengthDimension(2048),
                new DataTypeEntry(Types.TIME, MySqlParser.TIME),
                new DataTypeEntry(Types.TIMESTAMP_WITH_TIMEZONE, MySqlParser.TIMESTAMP),
                new DataTypeEntry(Types.TIMESTAMP, MySqlParser.DATETIME),
                new DataTypeEntry(Types.BINARY, MySqlParser.BINARY),
                new DataTypeEntry(Types.VARBINARY, MySqlParser.VARBINARY),
                new DataTypeEntry(Types.BLOB, MySqlParser.BLOB),
                new DataTypeEntry(Types.INTEGER, MySqlParser.YEAR)));
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.SimpleDataTypeContext.class.getCanonicalName(), Arrays.asList(
                new DataTypeEntry(Types.DATE, MySqlParser.DATE),
                new DataTypeEntry(Types.BLOB, MySqlParser.TINYBLOB),
                new DataTypeEntry(Types.BLOB, MySqlParser.MEDIUMBLOB),
                new DataTypeEntry(Types.BLOB, MySqlParser.LONGBLOB),
                new DataTypeEntry(Types.BOOLEAN, MySqlParser.BOOL),
                new DataTypeEntry(Types.BOOLEAN, MySqlParser.BOOLEAN),
                new DataTypeEntry(Types.BIGINT, MySqlParser.SERIAL)));
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.CollectionDataTypeContext.class.getCanonicalName(), Arrays.asList(
                new DataTypeEntry(Types.CHAR, MySqlParser.ENUM).setSuffixTokens(MySqlParser.BINARY),
                new DataTypeEntry(Types.CHAR, MySqlParser.SET).setSuffixTokens(MySqlParser.BINARY)));
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.SpatialDataTypeContext.class.getCanonicalName(), Arrays.asList(
                new DataTypeEntry(Types.OTHER, MySqlParser.GEOMETRYCOLLECTION),
                new DataTypeEntry(Types.OTHER, MySqlParser.GEOMCOLLECTION),
                new DataTypeEntry(Types.OTHER, MySqlParser.LINESTRING),
                new DataTypeEntry(Types.OTHER, MySqlParser.MULTILINESTRING),
                new DataTypeEntry(Types.OTHER, MySqlParser.MULTIPOINT),
                new DataTypeEntry(Types.OTHER, MySqlParser.MULTIPOLYGON),
                new DataTypeEntry(Types.OTHER, MySqlParser.POINT),
                new DataTypeEntry(Types.OTHER, MySqlParser.POLYGON),
                new DataTypeEntry(Types.OTHER, MySqlParser.JSON),
                new DataTypeEntry(Types.OTHER, MySqlParser.GEOMETRY)));
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.LongVarbinaryDataTypeContext.class.getCanonicalName(), Arrays.asList(
                new DataTypeEntry(Types.BLOB, MySqlParser.LONG)
                        .setSuffixTokens(MySqlParser.VARBINARY)));
        dataTypeResolverBuilder.registerDataTypes(MySqlParser.LongVarcharDataTypeContext.class.getCanonicalName(), Arrays.asList(
                new DataTypeEntry(Types.VARCHAR, MySqlParser.LONG)
                        .setSuffixTokens(MySqlParser.VARCHAR)));

        return dataTypeResolverBuilder.build();
    }

    /**
     * Provides a map of default character sets by database/schema name.
     *
     * @return map of default character sets.
     */
    public ConcurrentMap<String, String> charsetNameForDatabase() {
        return charsetNameForDatabase;
    }

    /**
     * Parse a name from {@link MySqlParser.UidContext}.
     *
     * @param uidContext uid context
     * @return name without quotes.
     */
    public String parseName(MySqlParser.UidContext uidContext) {
        return withoutQuotes(uidContext);
    }

    /**
     * Parse qualified table identification from {@link MySqlParser.FullIdContext}.
     * {@link MySqlAntlrDdlParser#currentSchema()} will be used if definition of schema name is not part of the context.
     *
     * @param fullIdContext full id context.
     * @return qualified {@link TableId}.
     */
    public TableId parseQualifiedTableId(MySqlParser.FullIdContext fullIdContext) {
        final char[] fullTableName = fullIdContext.getText().toCharArray();
        StringBuilder component = new StringBuilder();
        String dbName = null;
        String tableName = null;
        final char EMPTY = '\0';
        char lastQuote = EMPTY;
        for (int i = 0; i < fullTableName.length; i++) {
            char c = fullTableName[i];
            if (isQuote(c)) {
                // Opening quote
                if (lastQuote == EMPTY) {
                    lastQuote = c;
                }
                // Closing quote
                else if (lastQuote == c) {
                    // escape of quote by doubling
                    if (i < fullTableName.length - 1 && fullTableName[i + 1] == c) {
                        component.append(c);
                        i++;
                    }
                    else {
                        lastQuote = EMPTY;
                    }
                }
                // Quote that is part of name
                else {
                    component.append(c);
                }
            }
            // dot that is not in quotes, so name separator
            else if (c == '.' && lastQuote == EMPTY) {
                dbName = component.toString();
                component = new StringBuilder();
            }
            // Any char is part of name including quoted dot
            else {
                component.append(c);
            }
        }
        tableName = component.toString();
        return resolveTableId(dbName != null ? dbName : currentSchema(), tableName);
    }

    /**
     * Parse column names for primary index from {@link MySqlParser.IndexColumnNamesContext}. This method will update
     * column to be not optional and set primary key column names to table.
     *
     * @param indexColumnNamesContext primary key index column names context.
     * @param tableEditor editor for table where primary key index is parsed.
     */
    public void parsePrimaryIndexColumnNames(MySqlParser.IndexColumnNamesContext indexColumnNamesContext, TableEditor tableEditor) {
        List<String> pkColumnNames = indexColumnNamesContext.indexColumnName().stream()
                .map(indexColumnNameContext -> {
                    // MySQL does not allow a primary key to have nullable columns, so let's make sure we model that correctly ...
                    String columnName;
                    if (indexColumnNameContext.uid() != null) {
                        columnName = parseName(indexColumnNameContext.uid());
                    }
                    else if (indexColumnNameContext.STRING_LITERAL() != null) {
                        columnName = withoutQuotes(indexColumnNameContext.STRING_LITERAL().getText());
                    }
                    else {
                        columnName = indexColumnNameContext.expression().getText();
                    }
                    Column column = tableEditor.columnWithName(columnName);
                    if (column != null && column.isOptional()) {
                        final ColumnEditor ce = column.edit().optional(false);
                        if (ce.hasDefaultValue() && !ce.defaultValueExpression().isPresent()) {
                            ce.unsetDefaultValueExpression();
                        }
                        tableEditor.addColumn(ce.create());
                    }
                    return column != null ? column.name() : columnName;
                })
                .collect(Collectors.toList());

        tableEditor.setPrimaryKeyNames(pkColumnNames);
    }

    /**
     * Parse column names for unique index from {@link MySqlParser.IndexColumnNamesContext}. This method will set
     * unique key column names to table if there are no optional.
     *
     * @param indexColumnNamesContext unique key index column names context.
     * @param tableEditor editor for table where primary key index is parsed.
     */
    public void parseUniqueIndexColumnNames(MySqlParser.IndexColumnNamesContext indexColumnNamesContext, TableEditor tableEditor) {
        List<Column> indexColumns = getIndexColumns(indexColumnNamesContext, tableEditor);
        if (indexColumns.stream().filter(col -> Objects.isNull(col) || col.isOptional()).count() > 0) {
            logger.warn("Skip to set unique index columns {} to primary key which including optional columns", indexColumns);
        }
        else {
            tableEditor.setPrimaryKeyNames(indexColumns.stream().map(Column::name).collect(Collectors.toList()));
        }
    }

    /**
     * Determine if a table's unique index should be included when parsing relative unique index statement.
     *
     * @param indexColumnNamesContext unique index column names context.
     * @param tableEditor editor for table where unique index is parsed.
     * @return true if the index is to be included; false otherwise.
     */
    public boolean isTableUniqueIndexIncluded(MySqlParser.IndexColumnNamesContext indexColumnNamesContext, TableEditor tableEditor) {
        return getIndexColumns(indexColumnNamesContext, tableEditor).stream().filter(Objects::isNull).count() == 0;
    }

    private List<Column> getIndexColumns(MySqlParser.IndexColumnNamesContext indexColumnNamesContext, TableEditor tableEditor) {
        return indexColumnNamesContext.indexColumnName().stream()
                .map(indexColumnNameContext -> {
                    String columnName;
                    if (indexColumnNameContext.uid() != null) {
                        columnName = parseName(indexColumnNameContext.uid());
                    }
                    else if (indexColumnNameContext.STRING_LITERAL() != null) {
                        columnName = withoutQuotes(indexColumnNameContext.STRING_LITERAL().getText());
                    }
                    else {
                        columnName = indexColumnNameContext.expression().getText();
                    }
                    return tableEditor.columnWithName(columnName);
                })
                .collect(Collectors.toList());
    }

    /**
     * Get the name of the character set for the current database, via the "character_set_database" system property.
     *
     * @return the name of the character set for the current database, or null if not known ...
     */
    public String currentDatabaseCharset() {
        String charsetName = systemVariables.getVariable(BinlogSystemVariables.CHARSET_NAME_DATABASE);
        if (charsetName == null || "DEFAULT".equalsIgnoreCase(charsetName)) {
            charsetName = systemVariables.getVariable(BinlogSystemVariables.CHARSET_NAME_SERVER);
        }
        return charsetName;
    }

    /**
     * Get the name of the character set for the give table name.
     *
     * @return the name of the character set for the given table, or null if not known ...
     */
    public String charsetForTable(TableId tableId) {
        final String defaultDatabaseCharset = tableId.catalog() != null ? charsetNameForDatabase().get(tableId.catalog()) : null;
        return defaultDatabaseCharset != null ? defaultDatabaseCharset : currentDatabaseCharset();
    }

    /**
     * Runs a function if all given object are not null.
     *
     * @param function function to run; may not be null
     * @param nullableObjects object to be tested, if they are null.
     */
    public void runIfNotNull(Runnable function, Object... nullableObjects) {
        for (Object nullableObject : nullableObjects) {
            if (nullableObject == null) {
                return;
            }
        }
        function.run();
    }

    /**
     * Extracts the enumeration values properly parsed and escaped.
     *
     * @param enumValues the raw enumeration values from the parsed column definition
     * @return the list of options allowed for the {@code ENUM} or {@code SET}; never null.
     */
    public static List<String> extractEnumAndSetOptions(List<String> enumValues) {
        return enumValues.stream()
                .map(MySqlAntlrDdlParser::withoutQuotes)
                .map(MySqlAntlrDdlParser::escapeOption)
                .collect(Collectors.toList());
    }

    public static String escapeOption(String option) {
        // Replace comma to backslash followed by comma (this escape sequence implies comma is part of the option)
        // Replace backlash+single-quote to a single-quote.
        // Replace double single-quote to a single-quote.
        return option.replaceAll(",", "\\\\,").replaceAll("\\\\'", "'").replace("''", "'");
    }

    public TableFilter getTableFilter() {
        return tableFilter;
    }

    /**
     * Obtains the charset name either form charset if present or from collation.
     *
     * @param charsetNode
     * @param collationNode
     * @return character set
     */
    public String extractCharset(CharsetNameContext charsetNode, CollationNameContext collationNode) {
        String charsetName = null;
        if (charsetNode != null && charsetNode.getText() != null) {
            charsetName = withoutQuotes(charsetNode.getText());
        }
        else if (collationNode != null && collationNode.getText() != null) {
            final String collationName = withoutQuotes(collationNode.getText()).toLowerCase();
            for (int index = 0; index < charsetRegistry.getCharsetMapSize(); index++) {
                if (collationName.equals(charsetRegistry.getCollationNameForCollationIndex(index))) {
                    charsetName = charsetRegistry.getCharsetNameForCollationIndex(index);
                    break;
                }
            }
        }
        return charsetName;
    }

    /**
     * Signal an alter table event to ddl changes listener.
     *
     * @param id         the table identifier; may not be null
     * @param previousId the previous name of the view if it was renamed, or null if it was not renamed
     * @param ctx        the start of the statement; may not be null
     */
    public void signalAlterTable(TableId id, TableId previousId, RenameTableClauseContext ctx) {
        final RenameTableContext parent = (RenameTableContext) ctx.getParent();
        Interval interval = new Interval(ctx.getParent().start.getStartIndex(),
                parent.renameTableClause().get(0).start.getStartIndex() - 1);
        String prefix = ctx.getParent().start.getInputStream().getText(interval);
        signalAlterTable(id, previousId, prefix + getText(ctx));
    }
}
