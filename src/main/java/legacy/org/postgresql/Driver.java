/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2011, PostgreSQL Global Development Group
*
*
*-------------------------------------------------------------------------
*/
package legacy.org.postgresql;

import legacy.org.postgresql.core.Logger;
import legacy.org.postgresql.jdbc4.Jdbc4Connection;
import legacy.org.postgresql.util.GT;
import legacy.org.postgresql.util.PSQLDriverVersion;
import legacy.org.postgresql.util.PSQLException;
import legacy.org.postgresql.util.PSQLState;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Properties;
import java.util.StringTokenizer;

/**
 * The Java SQL framework allows for multiple database drivers.  Each
 * driver should supply a class that implements the Driver interface
 *
 * <p>The DriverManager will try to load as many drivers as it can find and
 * then for any given connection request, it will ask each driver in turn
 * to try to connect to the target URL.
 *
 * <p>It is strongly recommended that each Driver class should be small and
 * standalone so that the Driver class can be loaded and queried without
 * bringing in vast quantities of supporting code.
 *
 * <p>When a Driver class is loaded, it should create an instance of itself
 * and register it with the DriverManager. This means that a user can load
 * and register a driver by doing Class.forName("foo.bah.Driver")
 *
 * @see PGConnection
 * @see java.sql.Driver
 */
public class Driver implements java.sql.Driver
{

    // make these public so they can be used in setLogLevel below

    public static final int DEBUG = 2;
    public static final int INFO = 1;

    private static final Logger logger = new Logger();
    private static boolean logLevelSet = false;

    static
    {
        try
        {
            // moved the registerDriver from the constructor to here
            // because some clients call the driver themselves (I know, as
            // my early jdbc work did - and that was based on other examples).
            // Placing it here, means that the driver is registered once only.
            java.sql.DriverManager.registerDriver(new Driver());
        }
        catch (SQLException e)
        {
            e.printStackTrace();
        }
    }

    // Helper to retrieve default properties from classloader resource
    // properties files.
    private Properties defaultProperties;
    private synchronized Properties getDefaultProperties() throws IOException {
        if (defaultProperties != null)
            return defaultProperties;

        // Make sure we load properties with the maximum possible
        // privileges.
        try
        {
            defaultProperties = (Properties)
                AccessController.doPrivileged(new PrivilegedExceptionAction() {
                        public Object run() throws IOException {
                            return loadDefaultProperties();
                        }
                    });
        }
        catch (PrivilegedActionException e)
        {
            throw (IOException)e.getException();
        }

        // Use the loglevel from the default properties (if any)
        // as the driver-wide default unless someone explicitly called
        // setLogLevel() already.
        synchronized (Driver.class) {
            if (!logLevelSet) {
                String driverLogLevel = defaultProperties.getProperty("loglevel");
                if (driverLogLevel != null) {
                    try {
                        setLogLevel(Integer.parseInt(driverLogLevel));
                    } catch (Exception l_e) {
                        // XXX revisit
                        // invalid value for loglevel; ignore it
                    }
                }
            }
        }

        return defaultProperties;
    }

    private Properties loadDefaultProperties() throws IOException {
        Properties merged = new Properties();

        try {
            merged.setProperty("user", System.getProperty("user.name"));
        } catch (java.lang.SecurityException se) {
            // We're just trying to set a default, so if we can't
            // it's not a big deal.
        }

        // If we are loaded by the bootstrap classloader, getClassLoader()
        // may return null. In that case, try to fall back to the system
        // classloader.
        //
        // We should not need to catch SecurityException here as we are
        // accessing either our own classloader, or the system classloader
        // when our classloader is null. The ClassLoader javadoc claims
        // neither case can throw SecurityException.
        ClassLoader cl = getClass().getClassLoader();
        if (cl == null)
            cl = ClassLoader.getSystemClassLoader();

        if (cl == null) {
            logger.debug("Can't find a classloader for the Driver; not loading driver configuration");
            return merged; // Give up on finding defaults.
        }

        logger.debug("Loading driver configuration via classloader " + cl);

        // When loading the driver config files we don't want settings found
        // in later files in the classpath to override settings specified in
        // earlier files.  To do this we've got to read the returned
        // Enumeration into temporary storage.
        ArrayList urls = new ArrayList();
        Enumeration urlEnum = cl.getResources("org/postgresql/driverconfig.properties");
        while (urlEnum.hasMoreElements())
        {
            urls.add(urlEnum.nextElement());
        }

        for (int i=urls.size()-1; i>=0; i--) {
            URL url = (URL)urls.get(i);
            logger.debug("Loading driver configuration from: " + url);
            InputStream is = url.openStream();
            merged.load(is);
            is.close();
        }

        return merged;
    }

    /**
     * Try to make a database connection to the given URL. The driver
     * should return "null" if it realizes it is the wrong kind of
     * driver to connect to the given URL. This will be common, as
     * when the JDBC driverManager is asked to connect to a given URL,
     * it passes the URL to each loaded driver in turn.
     *
     * <p>The driver should raise an SQLException if it is the right driver
     * to connect to the given URL, but has trouble connecting to the
     * database.
     *
     * <p>The java.util.Properties argument can be used to pass arbitrary
     * string tag/value pairs as connection arguments.
     *
     * user - (required) The user to connect as
     * password - (optional) The password for the user
     * ssl - (optional) Use SSL when connecting to the server
     * charSet - (optional) The character set to be used for converting
     *  to/from the database to unicode.  If multibyte is enabled on the
     *  server then the character set of the database is used as the default,
     *  otherwise the jvm character encoding is used as the default.
     *   This value is only used when connecting to a 7.2 or older server.
     * loglevel - (optional) Enable logging of messages from the driver.
     *  The value is an integer from 1 to 2 where:
     *    INFO = 1, DEBUG = 2
     *  The output is sent to DriverManager.getPrintWriter() if set,
     *  otherwise it is sent to System.out.
     * compatible - (optional) This is used to toggle
     *  between different functionality as it changes across different releases
     *  of the jdbc driver code.  The values here are versions of the jdbc
     *  client and not server versions.  For example in 7.1 get/setBytes
     *  worked on LargeObject values, in 7.2 these methods were changed
     *  to work on bytea values.  This change in functionality could
     *  be disabled by setting the compatible level to be "7.1", in
     *  which case the driver will revert to the 7.1 functionality.
     *
     * <p>Normally, at least
     * "user" and "password" properties should be included in the
     * properties. For a list of supported
     * character encoding , see
     * http://java.sun.com/products/jdk/1.2/docs/guide/internat/encoding.doc.html
     * Note that you will probably want to have set up the Postgres database
     * itself to use the same encoding, with the "-E <encoding>" argument
     * to createdb.
     *
     * Our protocol takes the forms:
     * <PRE>
     * jdbc:postgresqllegacy://host:port/database?param1=val1&...
     * </PRE>
     *
     * @param url the URL of the database to connect to
     * @param info a list of arbitrary tag/value pairs as connection
     * arguments
     * @return a connection to the URL or null if it isnt us
     * @exception SQLException if a database access error occurs
     * @see java.sql.Driver#connect
     */
    public java.sql.Connection connect(String url, Properties info) throws SQLException
    {
        // get defaults
        Properties defaults;
        try
        {
            defaults = getDefaultProperties();
        }
        catch (IOException ioe)
        {
            throw new PSQLException(GT.tr("Error loading default settings from driverconfig.properties"),
                                    PSQLState.UNEXPECTED_ERROR, ioe);
        }

        // override defaults with provided properties
        Properties props = new Properties(defaults);
        for (Enumeration e = info.propertyNames(); e.hasMoreElements(); )
        {
            String propName = (String)e.nextElement();
            props.setProperty(propName, info.getProperty(propName));
        }

        // parse URL and add more properties
        if ((props = parseURL(url, props)) == null)
        {
            logger.debug("Error in url: " + url);
            return null;
        }
        try
        {
            logger.debug("Connecting with URL: " + url);

            // Enforce login timeout, if specified, by running the connection
            // attempt in a separate thread. If we hit the timeout without the
            // connection completing, we abandon the connection attempt in
            // the calling thread, but the separate thread will keep trying.
            // Eventually, the separate thread will either fail or complete
            // the connection; at that point we clean up the connection if
            // we managed to establish one after all. See ConnectThread for
            // more details.
            long timeout = timeout(props);
            if (timeout <= 0)
                return makeConnection(url, props);

            ConnectThread ct = new ConnectThread(url, props);
            new Thread(ct, "PostgreSQL JDBC driver connection thread").start();
            return ct.getResult(timeout);
        }
        catch (PSQLException ex1)
        {
            logger.debug("Connection error:", ex1);
            // re-throw the exception, otherwise it will be caught next, and a
            // org.postgresql.unusual error will be returned instead.
            throw ex1;
        }
        catch (java.security.AccessControlException ace)
        {
            throw new PSQLException(GT.tr("Your security policy has prevented the connection from being attempted.  You probably need to grant the connect java.net.SocketPermission to the database server host and port that you wish to connect to."), PSQLState.UNEXPECTED_ERROR, ace);
        }
        catch (Exception ex2)
        {
            logger.debug("Unexpected connection error:", ex2);
            throw new PSQLException(GT.tr("Something unusual has occured to cause the driver to fail. Please report this exception."),
                                    PSQLState.UNEXPECTED_ERROR, ex2);
        }
    }

    /**
     * Perform a connect in a separate thread; supports
     * getting the results from the original thread while enforcing
     * a login timout.
     */
    private static class ConnectThread implements Runnable {
        ConnectThread(String url, Properties props) {
            this.url = url;
            this.props = props;
        }

        public void run() {
            Connection conn;
            Throwable error;

            try {
                conn = makeConnection(url, props);
                error = null;
            } catch (Throwable t) {
                conn = null;
                error = t;
            }

            synchronized (this) {
                if (abandoned) {
                    if (conn != null) {
                        try {
                            conn.close();
                        } catch (SQLException e) {}
                    }
                } else {
                    result = conn;
                    resultException = error;
                    notify();
                }
            }
        }

        /**
         * Get the connection result from this (assumed running) thread.
         * If the timeout is reached without a result being available,
         * a SQLException is thrown.
         *
         * @param timeout timeout in milliseconds
         * @return the new connection, if successful
         * @throws SQLException if a connection error occurs or the timeout is reached
         */
        public Connection getResult(long timeout) throws SQLException {
            long expiry = System.currentTimeMillis() + timeout;
            synchronized (this) {
                while (true) {
                    if (result != null)
                        return result;
                    
                    if (resultException != null) {
                        if (resultException instanceof SQLException) {
                            resultException.fillInStackTrace();
                            throw (SQLException)resultException;
                        } else {
                            throw new PSQLException(GT.tr("Something unusual has occured to cause the driver to fail. Please report this exception."),
                                                    PSQLState.UNEXPECTED_ERROR, resultException);
                        }
                    }
                    
                    long delay = expiry - System.currentTimeMillis();
                    if (delay <= 0) {
                        abandoned = true;
                        throw new PSQLException(GT.tr("Connection attempt timed out."),
                                                PSQLState.CONNECTION_UNABLE_TO_CONNECT);
                    }
                    
                    try {
                        wait(delay);
                    } catch (InterruptedException ie) {
                        abandoned = true;
                        throw new PSQLException(GT.tr("Interrupted while attempting to connect."),
                                                PSQLState.CONNECTION_UNABLE_TO_CONNECT);
                    }                                            
                }
            }
        }

        private final String url;
        private final Properties props;
        private Connection result;
        private Throwable resultException;
        private boolean abandoned;
    }

    /**
     * Create a connection from URL and properties. Always
     * does the connection work in the current thread without
     * enforcing a timeout, regardless of any timeout specified
     * in the properties.
     *
     * @param url the original URL
     * @param props the parsed/defaulted connection properties
     * @return a new connection
     * @throws SQLException if the connection could not be made
     */
    private static Connection makeConnection(String url, Properties props) throws SQLException {
        return new Jdbc4Connection(host(props), port(props),
                                      user(props), database(props),
                                      props, url);
    }

    /**
     * Returns true if the driver thinks it can open a connection to the
     * given URL.  Typically, drivers will return true if they understand
     * the subprotocol specified in the URL and false if they don't.  Our
     * protocols start with jdbc:postgresqllegacy:
     *
     * @see java.sql.Driver#acceptsURL
     * @param url the URL of the driver
     * @return true if this driver accepts the given URL
     * @exception SQLException if a database-access error occurs
     * (Dont know why it would *shrug*)
     */
    public boolean acceptsURL(String url) throws SQLException
    {
        if (parseURL(url, null) == null)
            return false;
        return true;
    }

    private static final Object[][] knownProperties = {
                { "PGDBNAME", Boolean.TRUE,
                  "Database name to connect to; may be specified directly in the JDBC URL." },
                { "user", Boolean.TRUE,
                  "Username to connect to the database as.", null },
                { "PGHOST", Boolean.FALSE,
                  "Hostname of the PostgreSQL server; may be specified directly in the JDBC URL." },
                { "PGPORT", Boolean.FALSE,
                  "Port number to connect to the PostgreSQL server on; may be specified directly in the JDBC URL.", },
                { "password", Boolean.FALSE,
                  "Password to use when authenticating.", },
                { "protocolVersion", Boolean.FALSE,
                  "Force use of a particular protocol version when connecting; if set, disables protocol version fallback.", },
                { "ssl", Boolean.FALSE,
                  "Control use of SSL; any nonnull value causes SSL to be required." },
                { "sslfactory", Boolean.FALSE,
                  "Provide a SSLSocketFactory class when using SSL." },
                { "sslfactoryarg", Boolean.FALSE,
                  "Argument forwarded to constructor of SSLSocketFactory class." },
                { "loglevel", Boolean.FALSE,
                  "Control the driver's log verbosity: 0 is off, 1 is INFO, 2 is DEBUG.",
                  new String[] { "0", "1", "2" } },
                { "allowEncodingChanges", Boolean.FALSE,
                  "Allow the user to change the client_encoding variable." },
                { "logUnclosedConnections", Boolean.FALSE,
                  "When connections that are not explicitly closed are garbage collected, log the stacktrace from the opening of the connection to trace the leak source."},
                { "prepareThreshold", Boolean.FALSE,
                  "Default statement prepare threshold (numeric)." },
                { "charSet", Boolean.FALSE,
                  "When connecting to a pre-7.3 server, the database encoding to assume is in use." },
                { "compatible", Boolean.FALSE,
                  "Force compatibility of some features with an older version of the driver.",
                  new String[] { "7.1", "7.2", "7.3", "7.4", "8.0", "8.1", "8.2" } },
                { "loginTimeout", Boolean.FALSE,
                  "The login timeout, in seconds; 0 means no timeout beyond the normal TCP connection timout." },
                { "socketTimeout", Boolean.FALSE,
                  "The timeout value for socket read operations, in seconds; 0 means no timeout." },
                { "tcpKeepAlive", Boolean.FALSE,
                  "Enable or disable TCP keep-alive probe." },
                { "stringtype", Boolean.FALSE,
                  "The type to bind String parameters as (usually 'varchar'; 'unspecified' allows implicit casting to other types)",
                  new String[] { "varchar", "unspecified" } },
                { "kerberosServerName", Boolean.FALSE,
                  "The Kerberos service name to use when authenticating with GSSAPI.  This is equivalent to libpq's PGKRBSRVNAME environment variable." },
                { "jaasApplicationName", Boolean.FALSE,
                  "Specifies the name of the JAAS system or application login configuration." }
            };

    /**
     * The getPropertyInfo method is intended to allow a generic GUI
     * tool to discover what properties it should prompt a human for
     * in order to get enough information to connect to a database.
     *
     * <p>Note that depending on the values the human has supplied so
     * far, additional values may become necessary, so it may be necessary
     * to iterate through several calls to getPropertyInfo
     *
     * @param url the Url of the database to connect to
     * @param info a proposed list of tag/value pairs that will be sent on
     * connect open.
     * @return An array of DriverPropertyInfo objects describing
     * possible properties.  This array may be an empty array if
     * no properties are required
     * @exception SQLException if a database-access error occurs
     * @see java.sql.Driver#getPropertyInfo
     */
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) throws SQLException
    {
        Properties copy = new Properties(info);
        parseURL(url, copy);

        DriverPropertyInfo[] props = new DriverPropertyInfo[knownProperties.length];
        for (int i = 0; i < knownProperties.length; ++i)
        {
            String name = (String) knownProperties[i][0];
            props[i] = new DriverPropertyInfo(name, copy.getProperty(name));
            props[i].required = ((Boolean) knownProperties[i][1]).booleanValue();
            props[i].description = (String) knownProperties[i][2];
            if (knownProperties[i].length > 3)
                props[i].choices = (String[]) knownProperties[i][3];
        }

        return props;
    }

    public static final int MAJORVERSION = 9;
    /**
     * Gets the drivers major version number
     *
     * @return the drivers major version number
     */
    public int getMajorVersion()
    {
        return MAJORVERSION;
    }


    public static final int MINORVERSION = 1;
    /**
     * Get the drivers minor version number
     *
     * @return the drivers minor version number
     */
    public int getMinorVersion()
    {
        return MINORVERSION;
    }

    /**
     * Returns the server version series of this driver and the
     * specific build number.
     */
    public static String getVersion()
    {
        return "@VERSION@ (build " + PSQLDriverVersion.buildNumber + ")";
    }

    /**
     * Report whether the driver is a genuine JDBC compliant driver.  A
     * driver may only report "true" here if it passes the JDBC compliance
     * tests, otherwise it is required to return false.  JDBC compliance
     * requires full support for the JDBC API and full support for SQL 92
     * Entry Level.
     *
     * <p>For PostgreSQL, this is not yet possible, as we are not SQL92
     * compliant (yet).
     */
    public boolean jdbcCompliant()
    {
        return false;
    }

    static private String[] protocols = { "jdbc", "postgresqllegacy" };

    /**
     * Constructs a new DriverURL, splitting the specified URL into its
     * component parts
     * @param url JDBC URL to parse
     * @param defaults Default properties
     * @return Properties with elements added from the url
     * @exception SQLException
     */
    Properties parseURL(String url, Properties defaults) throws SQLException
    {
        int state = -1;
        Properties urlProps = new Properties(defaults);

        String l_urlServer = url;
        String l_urlArgs = "";

        int l_qPos = url.indexOf('?');
        if (l_qPos != -1)
        {
            l_urlServer = url.substring(0, l_qPos);
            l_urlArgs = url.substring(l_qPos + 1);
        }

        // look for an IPv6 address that is enclosed by []
        // the upcoming parsing that uses colons as identifiers can't handle
        // the colons in an IPv6 address.
        int ipv6start = l_urlServer.indexOf("[");
        int ipv6end = l_urlServer.indexOf("]");
        String ipv6address = null;
        if (ipv6start != -1 && ipv6end > ipv6start)
        {
            ipv6address = l_urlServer.substring(ipv6start + 1, ipv6end);
            l_urlServer = l_urlServer.substring(0, ipv6start) + "ipv6host" + l_urlServer.substring(ipv6end + 1);
        }

        //parse the server part of the url
        StringTokenizer st = new StringTokenizer(l_urlServer, ":/", true);
        int count;
        for (count = 0; (st.hasMoreTokens()); count++)
        {
            String token = st.nextToken();

            // PM Aug 2 1997 - Modified to allow multiple backends
            if (count <= 3)
            {
                if ((count % 2) == 1 && token.equals(":"))
                    ;
                else if ((count % 2) == 0)
                {
                    boolean found = (count == 0) ? true : false;
                    for (int tmp = 0;tmp < protocols.length;tmp++)
                    {
                        if (token.equals(protocols[tmp]))
                        {
                            // PM June 29 1997 Added this property to enable the driver
                            // to handle multiple backend protocols.
                            if (count == 2 && tmp > 0)
                            {
                                urlProps.setProperty("Protocol", token);
                                found = true;
                            }
                        }
                    }

                    if (found == false)
                        return null;
                }
                else
                    return null;
            }
            else if (count > 3)
            {
                if (count == 4 && token.equals("/"))
                    state = 0;
                else if (count == 4)
                {
                    urlProps.setProperty("PGDBNAME", token);
                    state = -2;
                }
                else if (count == 5 && state == 0 && token.equals("/"))
                    state = 1;
                else if (count == 5 && state == 0)
                    return null;
                else if (count == 6 && state == 1)
                    urlProps.setProperty("PGHOST", token);
                else if (count == 7 && token.equals(":"))
                    state = 2;
                else if (count == 8 && state == 2)
                {
                    try
                    {
                        Integer portNumber = Integer.decode(token);
                        urlProps.setProperty("PGPORT", portNumber.toString());
                    }
                    catch (Exception e)
                    {
                        return null;
                    }
                }
                else if ((count == 7 || count == 9) &&
                         (state == 1 || state == 2) && token.equals("/"))
                    state = -1;
                else if (state == -1)
                {
                    urlProps.setProperty("PGDBNAME", token);
                    state = -2;
                }
            }
        }
        if (count <= 1)
        {
            return null;
        }

        // if we extracted an IPv6 address out earlier put it back
        if (ipv6address != null)
            urlProps.setProperty("PGHOST", ipv6address);

        //parse the args part of the url
        StringTokenizer qst = new StringTokenizer(l_urlArgs, "&");
        for (count = 0; (qst.hasMoreTokens()); count++)
        {
            String token = qst.nextToken();
            int l_pos = token.indexOf('=');
            if (l_pos == -1)
            {
                urlProps.setProperty(token, "");
            }
            else
            {
                urlProps.setProperty(token.substring(0, l_pos), token.substring(l_pos + 1));
            }
        }

        return urlProps;
    }

    /**
     * @return the hostname portion of the URL
     */
    private static String host(Properties props)
    {
        return props.getProperty("PGHOST", "localhost");
    }

    /**
     * @return the port number portion of the URL or the default if no port was specified
     */
    private static int port(Properties props)
    {
        return Integer.parseInt(props.getProperty("PGPORT", "@DEF_PGPORT@"));
    }

    /**
     * @return the username of the URL
     */
    private static String user(Properties props)
    {
        return props.getProperty("user", "");
    }

    /**
     * @return the database name of the URL
     */
    private static String database(Properties props)
    {
        return props.getProperty("PGDBNAME", "");
    }

    /**
     * @return the timeout from the URL, in milliseconds
     */
    private static long timeout(Properties props)
    {
        String timeout = props.getProperty("loginTimeout");
        if (timeout != null) {
            try {
                return (long) (Float.parseFloat(timeout) * 1000);
            } catch (NumberFormatException e) {
                // Log level isn't set yet, so this doesn't actually 
                // get printed.
                logger.debug("Couldn't parse loginTimeout value: " + timeout);
            }
        }
        return DriverManager.getLoginTimeout() * 1000;
    }

    /*
     * This method was added in v6.5, and simply throws an SQLException
     * for an unimplemented method. I decided to do it this way while
     * implementing the JDBC2 extensions to JDBC, as it should help keep the
     * overall driver size down.
     * It now requires the call Class and the function name to help when the
     * driver is used with closed software that don't report the stack strace
     * @param callClass the call Class
     * @param functionName the name of the unimplemented function with the type
     *  of its arguments
     * @return PSQLException with a localized message giving the complete 
     *  description of the unimplemeted function
     */
    public static java.sql.SQLFeatureNotSupportedException notImplemented(Class callClass, String functionName)
    {
        return new java.sql.SQLFeatureNotSupportedException(GT.tr("Method {0} is not yet implemented.", callClass.getName() + "." + functionName),
                                 PSQLState.NOT_IMPLEMENTED.getState());
    }

    /**
    * used to turn logging on to a certain level, can be called
    * by specifying fully qualified class ie org.postgresql.Driver.setLogLevel()
    * @param logLevel sets the level which logging will respond to
    * INFO being almost no messages
    * DEBUG most verbose
    */
    public static void setLogLevel(int logLevel)
    {
        synchronized (Driver.class) {
            logger.setLogLevel(logLevel);
            logLevelSet = true;
        }
    }

    public static int getLogLevel()
    {
        synchronized (Driver.class) {
            return logger.getLogLevel();
        }
    }

    public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException
    {
        throw notImplemented(this.getClass(), "getParentLogger()");
    }

}
