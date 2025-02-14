/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.jdbc2;

import legacy.org.postgresql.PGResultSetMetaData;
import legacy.org.postgresql.core.BaseConnection;
import legacy.org.postgresql.core.Field;
import legacy.org.postgresql.util.GT;
import legacy.org.postgresql.util.PSQLException;
import legacy.org.postgresql.util.PSQLState;

import java.sql.*;

public abstract class AbstractJdbc2ResultSetMetaData implements PGResultSetMetaData
{
    protected final BaseConnection connection;
    protected final Field[] fields;

    private boolean fieldInfoFetched;

    /*
     * Initialise for a result with a tuple set and
     * a field descriptor set
     *
     * @param fields the array of field descriptors
     */
    public AbstractJdbc2ResultSetMetaData(BaseConnection connection, Field[] fields)
    {
        this.connection = connection;
        this.fields = fields;
        fieldInfoFetched = false;
    }

    /*
     * Whats the number of columns in the ResultSet?
     *
     * @return the number
     * @exception SQLException if a database access error occurs
     */
    public int getColumnCount() throws SQLException
    {
        return fields.length;
    }

    /*
     * Is the column automatically numbered (and thus read-only)
     * I believe that PostgreSQL does not support this feature.
     *
     * @param column the first column is 1, the second is 2...
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean isAutoIncrement(int column) throws SQLException
    {
        fetchFieldMetaData();
        Field field = getField(column);
        return field.getAutoIncrement();
    }

    /*
     * Does a column's case matter? ASSUMPTION: Any field that is
     * not obviously case insensitive is assumed to be case sensitive
     *
     * @param column the first column is 1, the second is 2...
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean isCaseSensitive(int column) throws SQLException
    {
        Field field = getField(column);
        return connection.getTypeInfo().isCaseSensitive(field.getOID());
    }

    /*
     * Can the column be used in a WHERE clause?  Basically for
     * this, I split the functions into two types: recognised
     * types (which are always useable), and OTHER types (which
     * may or may not be useable). The OTHER types, for now, I
     * will assume they are useable.  We should really query the
     * catalog to see if they are useable.
     *
     * @param column the first column is 1, the second is 2...
     * @return true if they can be used in a WHERE clause
     * @exception SQLException if a database access error occurs
     */
    public boolean isSearchable(int column) throws SQLException
    {
        return true;
    }

    /*
     * Is the column a cash value? 6.1 introduced the cash/money
     * type, which haven't been incorporated as of 970414, so I
     * just check the type name for both 'cash' and 'money'
     *
     * @param column the first column is 1, the second is 2...
     * @return true if its a cash column
     * @exception SQLException if a database access error occurs
     */
    public boolean isCurrency(int column) throws SQLException
    {
        String type_name = getPGType(column);

        return type_name.equals("cash") || type_name.equals("money");
    }

    /*
     * Indicates the nullability of values in the designated column.
     *
     * @param column the first column is 1, the second is 2...
     * @return one of the columnNullable values
     * @exception SQLException if a database access error occurs
     */
    public int isNullable(int column) throws SQLException
    {
        fetchFieldMetaData();
        Field field = getField(column);
        return field.getNullable();
    }

    /*
     * Is the column a signed number? In PostgreSQL, all numbers
     * are signed, so this is trivial. However, strings are not
     * signed (duh!)
     *
     * @param column the first column is 1, the second is 2...
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean isSigned(int column) throws SQLException
    {
        Field field = getField(column);
        return connection.getTypeInfo().isSigned(field.getOID());
    }

    /*
     * What is the column's normal maximum width in characters?
     *
     * @param column the first column is 1, the second is 2, etc.
     * @return the maximum width
     * @exception SQLException if a database access error occurs
     */
    public int getColumnDisplaySize(int column) throws SQLException
    {
        Field field = getField(column);
        return connection.getTypeInfo().getDisplaySize(field.getOID(), field.getMod());
    }

    /*
     * @param column the first column is 1, the second is 2, etc.
     * @return the column label
     * @exception SQLException if a database access error occurs
     */
    public String getColumnLabel(int column) throws SQLException
    {
        Field field = getField(column);
        return field.getColumnLabel();
    }

    /*
     * What's a column's name?
     *
     * @param column the first column is 1, the second is 2, etc.
     * @return the column name
     * @exception SQLException if a database access error occurs
     */
    public String getColumnName(int column) throws SQLException
    {
        return getColumnLabel(column);
    }

    public String getBaseColumnName(int column) throws SQLException {
        fetchFieldMetaData();
        Field field = getField(column);
        return field.getColumnName();
    }

    /*
     * @param column the first column is 1, the second is 2...
     * @return the Schema Name
     * @exception SQLException if a database access error occurs
     */
    public String getSchemaName(int column) throws SQLException
    {
        return "";
    }

    private void fetchFieldMetaData() throws SQLException {
        if (fieldInfoFetched)
            return;

        fieldInfoFetched = true;

        StringBuffer sql = new StringBuffer();
        sql.append("SELECT c.oid, a.attnum, a.attname, c.relname, n.nspname, ");
        sql.append("a.attnotnull OR (t.typtype = 'd' AND t.typnotnull), ");
        sql.append("pg_catalog.pg_get_expr(d.adbin, d.adrelid) LIKE '%nextval(%' ");
        sql.append("FROM pg_catalog.pg_class c ");
        sql.append("JOIN pg_catalog.pg_namespace n ON (c.relnamespace = n.oid) ");
        sql.append("JOIN pg_catalog.pg_attribute a ON (c.oid = a.attrelid) ");
        sql.append("JOIN pg_catalog.pg_type t ON (a.atttypid = t.oid) ");
        sql.append("LEFT JOIN pg_catalog.pg_attrdef d ON (d.adrelid = a.attrelid AND d.adnum = a.attnum) ");
        sql.append("JOIN (");

        // 7.4 servers don't support row IN operations (a,b) IN ((c,d),(e,f))
        // so we've got to fake that with a JOIN here.
        //
        boolean hasSourceInfo = false;
        for (int i=0; i<fields.length; i++) {
            if (fields[i].getTableOid() == 0)
                continue;

            if (hasSourceInfo)
                sql.append(" UNION ALL ");

            sql.append("SELECT ");
            sql.append(fields[i].getTableOid());
            if (!hasSourceInfo)
                sql.append(" AS oid ");
            sql.append(", ");
            sql.append(fields[i].getPositionInTable());
            if (!hasSourceInfo)
                sql.append(" AS attnum");

            if (!hasSourceInfo)
                hasSourceInfo = true;
        }
        sql.append(") vals ON (c.oid = vals.oid AND a.attnum = vals.attnum) ");

        if (!hasSourceInfo)
            return;

        Statement stmt = connection.createStatement();
        ResultSet rs = stmt.executeQuery(sql.toString());
        while (rs.next()) {
            int table = (int)rs.getLong(1);
            int column = (int)rs.getLong(2);
            String columnName = rs.getString(3);
            String tableName = rs.getString(4);
            String schemaName = rs.getString(5);
            int nullable = rs.getBoolean(6) ? ResultSetMetaData.columnNoNulls : ResultSetMetaData.columnNullable;
            boolean autoIncrement = rs.getBoolean(7);
            for (int i=0; i<fields.length; i++) {
                if (fields[i].getTableOid() == table && fields[i].getPositionInTable() == column) {
                    fields[i].setColumnName(columnName);
                    fields[i].setTableName(tableName);
                    fields[i].setSchemaName(schemaName);
                    fields[i].setNullable(nullable);
                    fields[i].setAutoIncrement(autoIncrement);
                }
            }
        }
    }

    public String getBaseSchemaName(int column) throws SQLException
    {
        fetchFieldMetaData();
        Field field = getField(column);
        return field.getSchemaName();
    }

    /*
     * What is a column's number of decimal digits.
     *
     * @param column the first column is 1, the second is 2...
     * @return the precision
     * @exception SQLException if a database access error occurs
     */
    public int getPrecision(int column) throws SQLException
    {
        Field field = getField(column);
        return connection.getTypeInfo().getPrecision(field.getOID(), field.getMod());
    }

    /*
     * What is a column's number of digits to the right of the
     * decimal point?
     *
     * @param column the first column is 1, the second is 2...
     * @return the scale
     * @exception SQLException if a database access error occurs
     */
    public int getScale(int column) throws SQLException
    {
        Field field = getField(column);
        return connection.getTypeInfo().getScale(field.getOID(), field.getMod());
    }

    /*
     * @param column the first column is 1, the second is 2...
     * @return column name, or "" if not applicable
     * @exception SQLException if a database access error occurs
     */
    public String getTableName(int column) throws SQLException
    {
        return "";
    }

    public String getBaseTableName(int column) throws SQLException
    {
        fetchFieldMetaData();
        Field field = getField(column);
        return field.getTableName();
    }

    /*
     * What's a column's table's catalog name?  As with getSchemaName(),
     * we can say that if getTableName() returns n/a, then we can too -
     * otherwise, we need to work on it.
     *
     * @param column the first column is 1, the second is 2...
     * @return catalog name, or "" if not applicable
     * @exception SQLException if a database access error occurs
     */
    public String getCatalogName(int column) throws SQLException
    {
        return "";
    }

    /*
     * What is a column's SQL Type? (java.sql.Type int)
     *
     * @param column the first column is 1, the second is 2, etc.
     * @return the java.sql.Type value
     * @exception SQLException if a database access error occurs
     * @see org.postgresql.Field#getSQLType
     * @see java.sql.Types
     */
    public int getColumnType(int column) throws SQLException
    {
        return getSQLType(column);
    }

    /*
     * Whats is the column's data source specific type name?
     *
     * @param column the first column is 1, the second is 2, etc.
     * @return the type name
     * @exception SQLException if a database access error occurs
     */
    public String getColumnTypeName(int column) throws SQLException
    {
        String type = getPGType(column);
        if (isAutoIncrement(column)) {
            if ("int4".equals(type)) {
                return "serial";
            } else if ("int8".equals(type)) {
                return "bigserial";
            }
        }

        return type;
    }

    /*
     * Is the column definitely not writable?  In reality, we would
     * have to check the GRANT/REVOKE stuff for this to be effective,
     * and I haven't really looked into that yet, so this will get
     * re-visited.
     *
     * @param column the first column is 1, the second is 2, etc.
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean isReadOnly(int column) throws SQLException
    {
        return false;
    }

    /*
     * Is it possible for a write on the column to succeed?  Again, we
     * would in reality have to check the GRANT/REVOKE stuff, which
     * I haven't worked with as yet.  However, if it isn't ReadOnly, then
     * it is obviously writable.
     *
     * @param column the first column is 1, the second is 2, etc.
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean isWritable(int column) throws SQLException
    {
        return !isReadOnly(column);
    }

    /*
     * Will a write on this column definately succeed? Hmmm...this
     * is a bad one, since the two preceding functions have not been
     * really defined. I cannot tell is the short answer. I thus
     * return isWritable() just to give us an idea.
     *
     * @param column the first column is 1, the second is 2, etc..
     * @return true if so
     * @exception SQLException if a database access error occurs
     */
    public boolean isDefinitelyWritable(int column) throws SQLException
    {
        return false;
    }

    // ********************************************************
    // END OF PUBLIC INTERFACE
    // ********************************************************

    /*
     * For several routines in this package, we need to convert
     * a columnIndex into a Field[] descriptor.  Rather than do
     * the same code several times, here it is.
     *
     * @param columnIndex the first column is 1, the second is 2...
     * @return the Field description
     * @exception SQLException if a database access error occurs
     */
    protected Field getField(int columnIndex) throws SQLException
    {
        if (columnIndex < 1 || columnIndex > fields.length)
            throw new PSQLException(GT.tr("The column index is out of range: {0}, number of columns: {1}.", new Object[]{new Integer(columnIndex), new Integer(fields.length)}), PSQLState.INVALID_PARAMETER_VALUE );
        return fields[columnIndex - 1];
    }

    protected String getPGType(int columnIndex) throws SQLException
    {
        return connection.getTypeInfo().getPGType(getField(columnIndex).getOID());
    }

    protected int getSQLType(int columnIndex) throws SQLException
    {
        return connection.getTypeInfo().getSQLType(getField(columnIndex).getOID());
    }


    // ** JDBC 2 Extensions **

    // This can hook into our PG_Object mechanism
    /**
     * Returns the fully-qualified name of the Java class whose instances
     * are manufactured if the method <code>ResultSet.getObject</code>
     * is called to retrieve a value from the column.
     *
     * <code>ResultSet.getObject</code> may return a subclass of the class
     * returned by this method.
     *
     * @param column the first column is 1, the second is 2, ...
     * @return the fully-qualified name of the class in the Java programming
     *     language that would be used by the method
     *     <code>ResultSet.getObject</code> to retrieve the value in the specified
     *     column. This is the class name used for custom mapping.
     * @exception SQLException if a database access error occurs
     */
    public String getColumnClassName(int column) throws SQLException
    {
        Field field = getField(column);
        String result = connection.getTypeInfo().getJavaClass(field.getOID());

        if (result != null)
            return result;

        int sqlType = getSQLType(column);
        switch(sqlType) {
            case Types.ARRAY:
                return ("java.sql.Array");
            default:
                String type = getPGType(column);
                if ("unknown".equals(type)) {
                    return ("java.lang.String");
                }
                return ("java.lang.Object");
        }
    }
}

