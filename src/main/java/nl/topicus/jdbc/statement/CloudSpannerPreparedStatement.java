package nl.topicus.jdbc.statement;

import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.ItemsList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectBody;
import net.sf.jsqlparser.statement.select.SelectVisitor;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.SubSelect;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import nl.topicus.jdbc.CloudSpannerConnection;
import nl.topicus.jdbc.resultset.CloudSpannerResultSet;

import com.google.cloud.spanner.DatabaseClient;
import com.google.cloud.spanner.Key;
import com.google.cloud.spanner.KeySet;
import com.google.cloud.spanner.Mutation;
import com.google.cloud.spanner.Mutation.WriteBuilder;

/**
 * 
 * @author loite
 *
 */
public class CloudSpannerPreparedStatement extends AbstractCloudSpannerPreparedStatement
{
	private String sql;

	private List<Mutation> batchMutations = new ArrayList<>();

	public CloudSpannerPreparedStatement(String sql, CloudSpannerConnection connection, DatabaseClient dbClient)
	{
		super(connection, dbClient);
		this.sql = sql;
	}

	@Override
	public ResultSet executeQuery() throws SQLException
	{
		Statement statement;
		try
		{
			statement = CCJSqlParserUtil.parse(sql);
		}
		catch (JSQLParserException e)
		{
			throw new SQLException("Error while parsing sql statement", e);
		}
		if (statement instanceof Select)
		{
			String namedSql = convertPositionalParametersToNamedParameters(sql);
			com.google.cloud.spanner.Statement.Builder builder = com.google.cloud.spanner.Statement
					.newBuilder(namedSql);
			setSelectParameters(((Select) statement).getSelectBody(), builder);
			com.google.cloud.spanner.ResultSet rs = getReadContext().executeQuery(builder.build());

			return new CloudSpannerResultSet(rs);
		}
		throw new SQLException("SQL statement not suitable for executeQuery");
	}

	private String convertPositionalParametersToNamedParameters(String sql)
	{
		boolean inString = false;
		StringBuilder res = new StringBuilder(sql);
		int i = 0;
		int parIndex = 1;
		while (i < res.length())
		{
			char c = res.charAt(i);
			if (c == '\'')
			{
				inString = !inString;
			}
			else if (c == '?' && !inString)
			{
				res.replace(i, i + 1, "@p" + parIndex);
				parIndex++;
			}
			i++;
		}

		return res.toString();
	}

	private void setSelectParameters(SelectBody body, com.google.cloud.spanner.Statement.Builder builder)
	{
		body.accept(new SelectVisitor()
		{

			@Override
			public void visit(WithItem withItem)
			{
			}

			@Override
			public void visit(SetOperationList setOpList)
			{
			}

			@Override
			public void visit(PlainSelect plainSelect)
			{
				setWhereParameters(plainSelect.getWhere(), builder);
				if (plainSelect.getLimit() != null)
				{
					setWhereParameters(plainSelect.getLimit().getRowCount(), builder);
				}
				if (plainSelect.getOffset() != null && plainSelect.getOffset().isOffsetJdbcParameter())
				{
					ValueBinderExpressionVisitorAdapter<com.google.cloud.spanner.Statement.Builder> binder = new ValueBinderExpressionVisitorAdapter<com.google.cloud.spanner.Statement.Builder>(
							getParameteStore(), builder.bind("p" + getParameteStore().getHighestIndex()));
					binder.setValue(getParameteStore().getParameter(getParameteStore().getHighestIndex()));
				}
			}
		});
	}

	private void setWhereParameters(Expression where, com.google.cloud.spanner.Statement.Builder builder)
	{
		if (where != null)
		{
			where.accept(new ExpressionVisitorAdapter()
			{

				@Override
				public void visit(JdbcParameter parameter)
				{
					parameter
							.accept(new ValueBinderExpressionVisitorAdapter<com.google.cloud.spanner.Statement.Builder>(
									getParameteStore(), builder.bind("p" + parameter.getIndex())));
				}

				@Override
				public void visit(SubSelect subSelect)
				{
					setSelectParameters(subSelect.getSelectBody(), builder);
				}

			});
		}
	}

	@Override
	public void addBatch() throws SQLException
	{
		if (getConnection().getAutoCommit())
		{
			throw new SQLFeatureNotSupportedException(
					"Batching of statements is only allowed when not running in autocommit mode");
		}
		if (isDDLStatement())
		{
			throw new SQLFeatureNotSupportedException("DDL statements may not be batched");
		}
		Mutation mutation = createMutation();
		batchMutations.add(mutation);
		getParameteStore().clearParameters();
	}

	@Override
	public void clearBatch() throws SQLException
	{
		batchMutations.clear();
		getParameteStore().clearParameters();
	}

	@Override
	public int[] executeBatch() throws SQLException
	{
		int[] res = new int[batchMutations.size()];
		int index = 0;
		for (Mutation mutation : batchMutations)
		{
			res[index] = writeMutation(mutation);
			index++;
		}
		batchMutations.clear();
		getParameteStore().clearParameters();
		return res;
	}

	@Override
	public int executeUpdate() throws SQLException
	{
		if (isDDLStatement())
		{
			String ddl = formatDDLStatement(sql);
			return executeDDL(ddl);
		}
		return writeMutation(createMutation());
	}

	private Mutation createMutation() throws SQLException
	{
		try
		{
			if (isDDLStatement())
			{
				throw new SQLException("Cannot create mutation for DDL statement");
			}
			Statement statement = CCJSqlParserUtil.parse(sql);
			if (statement instanceof Insert)
			{
				return performInsert((Insert) statement);
			}
			else if (statement instanceof Update)
			{
				return performUpdate((Update) statement);
			}
			else if (statement instanceof Delete)
			{
				return performDelete((Delete) statement);
			}
			else
			{
				throw new SQLFeatureNotSupportedException(
						"Unrecognized or unsupported SQL-statment: Expected one of INSERT, UPDATE or DELETE. Please note that batching of prepared statements is not supported for SELECT-statements.");
			}
		}
		catch (JSQLParserException e)
		{
			throw new SQLException("Error while parsing sql statement " + sql, e);
		}
	}

	private static final String[] DDL_STATEMENTS = { "CREATE", "ALTER", "DROP" };

	/**
	 * Do a quick check if this SQL statement is a DDL statement
	 * 
	 * @return true if the SQL statement is a DDL statement
	 */
	private boolean isDDLStatement()
	{
		String ddl = this.sql.trim();
		ddl = ddl.substring(0, Math.min(8, ddl.length())).toUpperCase();
		for (String statement : DDL_STATEMENTS)
		{
			if (ddl.startsWith(statement))
				return true;
		}

		return false;
	}

	/**
	 * Does some formatting to DDL statements that might have been generated by
	 * standard SQL generators to make it compatible with Google Cloud Spanner.
	 * 
	 * @param sql
	 *            The sql to format
	 * @return The formatted DDL statement.
	 * @throws SQLException
	 */
	private String formatDDLStatement(String sql) throws SQLException
	{
		String res = sql.trim().toUpperCase();
		String[] parts = res.split("\\s+");
		if (parts.length >= 2)
		{
			String sqlWithSingleSpaces = String.join(" ", parts);
			if (sqlWithSingleSpaces.startsWith("CREATE TABLE"))
			{
				int primaryKeyIndex = res.indexOf(", PRIMARY KEY (");
				if (primaryKeyIndex > -1)
				{
					int endPrimaryKeyIndex = res.indexOf(")", primaryKeyIndex);
					String primaryKeySpec = res.substring(primaryKeyIndex + 2, endPrimaryKeyIndex + 1);
					res = res.replace(", " + primaryKeySpec, "");
					res = res + " " + primaryKeySpec;
				}
			}
		}

		return res;
	}

	private Mutation performInsert(Insert insert) throws SQLException
	{
		ItemsList items = insert.getItemsList();
		if (!(items instanceof ExpressionList))
		{
			throw new SQLException("Insert statement must contain a list of values");
		}
		List<Expression> expressions = ((ExpressionList) items).getExpressions();
		WriteBuilder builder = Mutation.newInsertBuilder(insert.getTable().getFullyQualifiedName());
		int index = 0;
		for (Column col : insert.getColumns())
		{
			expressions.get(index).accept(
					new ValueBinderExpressionVisitorAdapter<WriteBuilder>(getParameteStore(), builder.set(col
							.getFullyQualifiedName())));
			index++;
		}
		return builder.build();
	}

	private Mutation performUpdate(Update update) throws SQLException
	{
		if (update.getTables().isEmpty())
			throw new SQLException("No table found in update statement");
		if (update.getTables().size() > 1)
			throw new SQLException("Update statements for multiple tables at once are not supported");
		String table = update.getTables().get(0).getFullyQualifiedName();
		List<Expression> expressions = update.getExpressions();
		WriteBuilder builder = Mutation.newUpdateBuilder(table);
		int index = 0;
		for (Column col : update.getColumns())
		{
			expressions.get(index).accept(
					new ValueBinderExpressionVisitorAdapter<WriteBuilder>(getParameteStore(), builder.set(col
							.getFullyQualifiedName())));
			index++;
		}
		visitInsertWhereClause(update.getWhere(), builder);

		return builder.build();
	}

	private Mutation performDelete(Delete delete) throws SQLException
	{
		String table = delete.getTable().getFullyQualifiedName();
		Expression where = delete.getWhere();
		if (where == null)
		{
			// Delete all
			return Mutation.delete(table, KeySet.all());
		}
		else
		{
			// Delete one
			Key.Builder keyBuilder = Key.newBuilder();
			visitDeleteWhereClause(where, keyBuilder);
			return Mutation.delete(table, keyBuilder.build());
		}
	}

	private void visitDeleteWhereClause(Expression where, Key.Builder keyBuilder)
	{
		if (where != null)
		{
			where.accept(new DMLWhereClauseVisitor(getParameteStore())
			{

				@Override
				protected void visitExpression(Column col, Expression expression)
				{
					expression.accept(new KeyBuilderExpressionVisitorAdapter<>(getParameteStore(), keyBuilder));
				}

			});
		}
	}

	private void visitInsertWhereClause(Expression where, WriteBuilder builder)
	{
		if (where != null)
		{
			where.accept(new DMLWhereClauseVisitor(getParameteStore())
			{

				@Override
				protected void visitExpression(Column col, Expression expression)
				{
					expression.accept(new ValueBinderExpressionVisitorAdapter<WriteBuilder>(getParameteStore(), builder
							.set(col.getFullyQualifiedName())));
				}

			});
		}
	}

	private int executeDDL(String ddl) throws SQLException
	{
		getConnection().executeDDL(ddl);
		return 0;
	}

	@Override
	public boolean execute() throws SQLException
	{
		throw new SQLFeatureNotSupportedException();
	}

	@Override
	public ParameterMetaData getParameterMetaData() throws SQLException
	{
		return new CloudSpannerParameterMetaData(sql, getParameteStore());
	}

}
