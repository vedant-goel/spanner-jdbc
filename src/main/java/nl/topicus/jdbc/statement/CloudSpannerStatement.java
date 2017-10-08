package nl.topicus.jdbc.statement;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.ReadContext;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.TokenMgrError;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import nl.topicus.jdbc.CloudSpannerConnection;
import nl.topicus.jdbc.resultset.CloudSpannerResultSet;

/**
 * 
 * @author loite
 *
 */
public class CloudSpannerStatement extends AbstractCloudSpannerStatement
{
	protected ResultSet lastResultSet = null;

	protected int lastUpdateCount = -1;

	public CloudSpannerStatement(CloudSpannerConnection connection, DatabaseClient dbClient)
	{
		super(connection, dbClient);
	}

	@Override
	public ResultSet executeQuery(String sql) throws SQLException
	{
		try (ReadContext context = getReadContext())
		{
			com.google.cloud.spanner.ResultSet rs = context.executeQuery(com.google.cloud.spanner.Statement.of(sql));
			return new CloudSpannerResultSet(this, rs);
		}
	}

	@Override
	public int executeUpdate(String sql) throws SQLException
	{
		PreparedStatement ps = getConnection().prepareStatement(sql);
		return ps.executeUpdate();
	}

	@Override
	public boolean execute(String sql) throws SQLException
	{
		Statement statement = null;
		boolean ddl = isDDLStatement(sql);
		if (!ddl)
		{
			try
			{
				statement = CCJSqlParserUtil.parse(sanitizeSQL(sql));
			}
			catch (JSQLParserException | TokenMgrError e)
			{
				throw new SQLException("Error while parsing sql statement " + sql + ": " + e.getLocalizedMessage(), e);
			}
		}
		if (!ddl && statement instanceof Select)
		{
			lastResultSet = executeQuery(sql);
			lastUpdateCount = -1;
			return true;
		}
		else
		{
			lastUpdateCount = executeUpdate(sql);
			lastResultSet = null;
			return false;
		}
	}

	private static final String[] DDL_STATEMENTS = { "CREATE", "ALTER", "DROP" };

	/**
	 * Do a quick check if this SQL statement is a DDL statement
	 * 
	 * @return true if the SQL statement is a DDL statement
	 */
	protected boolean isDDLStatement(String sql)
	{
		String ddl = sql.trim();
		ddl = ddl.substring(0, Math.min(8, ddl.length())).toUpperCase();
		for (String statement : DDL_STATEMENTS)
		{
			if (ddl.startsWith(statement))
				return true;
		}

		return false;
	}

	protected boolean isSelectStatement(String sql)
	{
		String select = sql.trim();
		select = select.substring(0, Math.min(6, select.length())).toUpperCase();
		if (select.startsWith("SELECT"))
			return true;

		return false;
	}

	@Override
	public ResultSet getResultSet() throws SQLException
	{
		return lastResultSet;
	}

	@Override
	public int getUpdateCount() throws SQLException
	{
		return lastUpdateCount;
	}

	@Override
	public boolean getMoreResults() throws SQLException
	{
		lastResultSet.close();
		lastResultSet = null;
		return false;
	}

	@Override
	public boolean getMoreResults(int current) throws SQLException
	{
		return getMoreResults();
	}

	@Override
	public int executeUpdate(String sql, int autoGeneratedKeys) throws SQLException
	{
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public int executeUpdate(String sql, int[] columnIndexes) throws SQLException
	{
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public int executeUpdate(String sql, String[] columnNames) throws SQLException
	{
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public boolean execute(String sql, int autoGeneratedKeys) throws SQLException
	{
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public boolean execute(String sql, int[] columnIndexes) throws SQLException
	{
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public boolean execute(String sql, String[] columnNames) throws SQLException
	{
		throw new SQLFeatureNotSupportedException();
	}

}
