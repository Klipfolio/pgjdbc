/*-------------------------------------------------------------------------
*
* Copyright (c) 2004-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql.ds.common;

import legacy.org.postgresql.ds.PGConnectionPoolDataSource;
import legacy.org.postgresql.ds.PGPoolingDataSource;
import legacy.org.postgresql.ds.PGSimpleDataSource;

import javax.naming.Context;
import javax.naming.Name;
import javax.naming.RefAddr;
import javax.naming.Reference;
import javax.naming.spi.ObjectFactory;
import java.util.Hashtable;

/**
 * Returns a DataSource-ish thing based on a JNDI reference.  In the case of a
 * SimpleDataSource or ConnectionPool, a new instance is created each time, as
 * there is no connection state to maintain. In the case of a PoolingDataSource,
 * the same DataSource will be returned for every invocation within the same
 * VM/ClassLoader, so that the state of the connections in the pool will be
 * consistent.
 *
 * @author Aaron Mulder (ammulder@chariotsolutions.com)
 */
public class PGObjectFactory implements ObjectFactory
{
    /**
     * Dereferences a PostgreSQL DataSource.  Other types of references are
     * ignored.
     */
    public Object getObjectInstance(Object obj, Name name, Context nameCtx,
                                    Hashtable environment) throws Exception
    {
        Reference ref = (Reference)obj;
        String className = ref.getClassName();
        if (className.equals("legacy.org.postgresql.ds.PGSimpleDataSource")
                || className.equals("legacy.org.postgresql.jdbc2.optional.SimpleDataSource")
                || className.equals("legacy.org.postgresql.jdbc3.Jdbc3SimpleDataSource"))
        {
            return loadSimpleDataSource(ref);
        }
        else if (className.equals("legacy.org.postgresql.ds.PGConnectionPoolDataSource")
                 || className.equals("legacy.org.postgresql.jdbc2.optional.ConnectionPool")
                 || className.equals("legacy.org.postgresql.jdbc3.Jdbc3ConnectionPool"))
        {
            return loadConnectionPool(ref);
        }
        else if (className.equals("legacy.org.postgresql.ds.PGPoolingDataSource")
                 || className.equals("legacy.org.postgresql.jdbc2.optional.PoolingDataSource")
                 || className.equals("legacy.org.postgresql.jdbc3.Jdbc3PoolingDataSource"))
        {
            return loadPoolingDataSource(ref);
        }
        else
        {
            return null;
        }
    }

    private Object loadPoolingDataSource(Reference ref)
    {
        // If DataSource exists, return it
        String name = getProperty(ref, "dataSourceName");
        PGPoolingDataSource pds = PGPoolingDataSource.getDataSource(name);
        if (pds != null)
        {
            return pds;
        }
        // Otherwise, create a new one
        pds = new PGPoolingDataSource();
        pds.setDataSourceName(name);
        loadBaseDataSource(pds, ref);
        String min = getProperty(ref, "initialConnections");
        if (min != null)
        {
            pds.setInitialConnections(Integer.parseInt(min));
        }
        String max = getProperty(ref, "maxConnections");
        if (max != null)
        {
            pds.setMaxConnections(Integer.parseInt(max));
        }
        return pds;
    }

    private Object loadSimpleDataSource(Reference ref)
    {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        return loadBaseDataSource(ds, ref);
    }

    private Object loadConnectionPool(Reference ref)
    {
        PGConnectionPoolDataSource cp = new PGConnectionPoolDataSource();
        return loadBaseDataSource(cp, ref);
    }

    protected Object loadBaseDataSource(BaseDataSource ds, Reference ref)
    {
        ds.setDatabaseName(getProperty(ref, "databaseName"));
        ds.setPassword(getProperty(ref, "password"));
        String port = getProperty(ref, "portNumber");
        if (port != null)
        {
            ds.setPortNumber(Integer.parseInt(port));
        }
        ds.setServerName(getProperty(ref, "serverName"));
        ds.setUser(getProperty(ref, "user"));

        String prepareThreshold = getProperty(ref, "prepareThreshold");
        if (prepareThreshold != null)
            ds.setPrepareThreshold(Integer.parseInt(prepareThreshold));

        return ds;
    }

    protected String getProperty(Reference ref, String s)
    {
        RefAddr addr = ref.get(s);
        if (addr == null)
        {
            return null;
        }
        return (String)addr.getContent();
    }

}
