package com.xqbase.h2pg;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import org.h2.tools.Server;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class TestPgClients {
	private Server server;
	private Connection h2Conn, conn;
	private Statement stat;

	static {
		try {
			Class.forName("org.postgresql.Driver");
		} catch (ClassNotFoundException e) {
			throw new RuntimeException(e);
		}
	}

	@BeforeEach
	public void before() throws SQLException {
		h2Conn = DriverManager.getConnection("jdbc:h2:mem:pgserver;" +
				"mode=postgresql;database_to_lower=true", "sa", "sa");
		server = new Server(new PgServerCompat(), "-pgPort", "5535",
				"-key", "pgserver", "mem:pgserver");
		server.start();
		conn = DriverManager.getConnection("jdbc:postgresql://localhost:5535/pgserver", "sa", "sa");
		stat = conn.createStatement();
		stat.execute("CREATE TABLE test (id SERIAL PRIMARY KEY, x1 INTEGER)");
	}

	@Test
	public void testPgAdmin() throws SQLException {
		stat.execute("SET client_min_messages=notice");
		try (ResultSet rs = stat.executeQuery("SELECT set_config('bytea_output','escape',false) " +
				"FROM pg_settings WHERE name = 'bytea_output'")) {
			assertFalse(rs.next());
		}
		stat.execute("SET client_encoding='UNICODE'");
		try (ResultSet rs = stat.executeQuery("SELECT version()")) {
			assertTrue(rs.next());
			assertNotNull(rs.getString("version"));
		}
		try (ResultSet rs = stat.executeQuery("SELECT " +
				"db.oid as did, db.datname, db.datallowconn, " +
				"pg_encoding_to_char(db.encoding) AS serverencoding, " +
				"has_database_privilege(db.oid, 'CREATE') as cancreate, datlastsysoid " +
				"FROM pg_database db WHERE db.datname = current_database()")) {
			assertTrue(rs.next());
			assertEquals("pgserver", rs.getString("datname"));
			assertFalse(rs.next());
		}
		try (ResultSet rs = stat.executeQuery("SELECT " +
				"oid as id, rolname as name, rolsuper as is_superuser, " +
				"CASE WHEN rolsuper THEN true ELSE rolcreaterole END as can_create_role, " +
				"CASE WHEN rolsuper THEN true ELSE rolcreatedb END as can_create_db " +
				"FROM pg_catalog.pg_roles WHERE rolname = current_user")) {
			assertTrue(rs.next());
			assertEquals("sa", rs.getString("name"));
			assertFalse(rs.next());
		}
		try (ResultSet rs = stat.executeQuery("SELECT " +
				"db.oid as did, db.datname as name, ta.spcname as spcname, db.datallowconn, " +
				"has_database_privilege(db.oid, 'CREATE') as cancreate, datdba as owner " +
				"FROM pg_database db LEFT OUTER JOIN pg_tablespace ta ON db.dattablespace = ta.oid " +
				"WHERE db.oid > 100000::OID")) {
			assertTrue(rs.next());
			assertEquals("pgserver", rs.getString("name"));
			assertFalse(rs.next());
		}
		try (ResultSet rs = stat.executeQuery("SELECT nsp.oid, nsp.nspname as name, " +
				"has_schema_privilege(nsp.oid, 'CREATE') as can_create, " +
				"has_schema_privilege(nsp.oid, 'USAGE') as has_usage " +
				"FROM pg_namespace nsp WHERE nspname NOT LIKE 'pg\\_%' AND NOT (" +
				"(nsp.nspname = 'pg_catalog' AND EXISTS (SELECT 1 FROM pg_class " +
				"WHERE relname = 'pg_class' AND relnamespace = nsp.oid LIMIT 1)) OR " +
				"(nsp.nspname = 'pgagent' AND EXISTS (SELECT 1 FROM pg_class " +
				"WHERE relname = 'pga_job' AND relnamespace = nsp.oid LIMIT 1)) OR " +
				"(nsp.nspname = 'information_schema' AND EXISTS (SELECT 1 FROM pg_class " +
				"WHERE relname = 'tables' AND relnamespace = nsp.oid LIMIT 1))" +
				") ORDER BY nspname")) {
			assertTrue(rs.next());
			assertEquals("public", rs.getString("name"));
			assertFalse(rs.next());
		}
		try (ResultSet rs = stat.executeQuery("SELECT format_type(23, NULL)")) {
			assertTrue(rs.next());
			assertEquals("INTEGER", rs.getString(1));
			assertFalse(rs.next());
		}
		// pgAdmin sends `SET LOCAL join_collapse_limit=8`, but `LOCAL` is not supported yet
		stat.execute("SET join_collapse_limit=8");
	}

	@Test
	public void testHeidiSQL() throws SQLException {
		try (ResultSet rs = stat.executeQuery("SHOW ssl")) {
			assertTrue(rs.next());
			assertEquals("off", rs.getString(1));
		}
		stat.execute("SET search_path TO 'public', '$user'");
		try (ResultSet rs = stat.executeQuery("SELECT *, NULL AS data_length, " +
				"pg_relation_size(QUOTE_IDENT(t.TABLE_SCHEMA) || '.' || QUOTE_IDENT(t.TABLE_NAME))::bigint " +
				"AS index_length, " +
				"c.reltuples, obj_description(c.oid) AS comment " +
				"FROM \"information_schema\".\"tables\" AS t " +
				"LEFT JOIN \"pg_namespace\" n ON t.table_schema = n.nspname " +
				"LEFT JOIN \"pg_class\" c ON n.oid = c.relnamespace AND c.relname=t.table_name " +
				"WHERE t.\"table_schema\"='public'")) {
			assertTrue(rs.next());
			assertEquals("test", rs.getString("table_name"));
			assertTrue(rs.getLong("index_length") >= 0L); // test pg_relation_size()
			assertNull(rs.getString("comment")); // test obj_description()
		}
		try (ResultSet rs = stat.executeQuery("SELECT \"p\".\"proname\", \"p\".\"proargtypes\" " +
				"FROM \"pg_catalog\".\"pg_namespace\" AS \"n\" " +
				"JOIN \"pg_catalog\".\"pg_proc\" AS \"p\" ON \"p\".\"pronamespace\" = \"n\".\"oid\" " +
				"WHERE \"n\".\"nspname\"='public'")) {
			assertFalse(rs.next()); // "pg_proc" always empty
		}
		try (ResultSet rs = stat.executeQuery("SELECT DISTINCT a.attname AS column_name, " +
				"a.attnum, a.atttypid, FORMAT_TYPE(a.atttypid, a.atttypmod) AS data_type, " +
				"CASE a.attnotnull WHEN false THEN 'YES' ELSE 'NO' END AS IS_NULLABLE, " +
				"com.description AS column_comment, pg_get_expr(def.adbin, def.adrelid) AS column_default, " +
				"NULL AS character_maximum_length FROM pg_attribute AS a " +
				"JOIN pg_class AS pgc ON pgc.oid = a.attrelid " +
				"LEFT JOIN pg_description AS com ON (pgc.oid = com.objoid AND a.attnum = com.objsubid) " +
				"LEFT JOIN pg_attrdef AS def ON (a.attrelid = def.adrelid AND a.attnum = def.adnum) " +
				"WHERE a.attnum > 0 AND pgc.oid = a.attrelid AND pg_table_is_visible(pgc.oid) " +
				"AND NOT a.attisdropped AND pgc.relname = 'test' ORDER BY a.attnum")) {
			assertTrue(rs.next());
			assertEquals("id", rs.getString("column_name"));
			assertTrue(rs.next());
			assertEquals("x1", rs.getString("column_name"));
			assertFalse(rs.next());
		}
		try (ResultSet rs = stat.executeQuery("SHOW ALL")) {
			ResultSetMetaData rsMeta = rs.getMetaData();
			assertEquals("name", rsMeta.getColumnName(1));
			assertEquals("setting", rsMeta.getColumnName(2));
		}
		try (PreparedStatement ps = conn.prepareStatement(
				"SELECT \"c\".\"conname\" AS \"CONSTRAINT_NAME\", " +
				"CASE \"c\".\"contype\" WHEN 'c' THEN 'CHECK' WHEN 'f' THEN 'FOREIGN KEY' " +
				"WHEN 'p' THEN 'PRIMARY KEY' WHEN 'u' THEN 'UNIQUE' END AS \"CONSTRAINT_TYPE\", " +
				"\"a\".\"attname\" AS \"COLUMN_NAME\" FROM \"pg_constraint\" AS \"c\" " +
				"LEFT JOIN \"pg_class\" \"t\" ON \"c\".\"conrelid\"=\"t\".\"oid\" " +
				"LEFT JOIN \"pg_attribute\" \"a\" ON \"t\".\"oid\"=\"a\".\"attrelid\" " +
				"LEFT JOIN \"pg_namespace\" \"n\" ON \"t\".\"relnamespace\"=\"n\".\"oid\" " +
				"WHERE c.contype IN ('p', 'u') AND \"a\".\"attnum\"=ANY(\"c\".\"conkey\") " +
				"AND \"n\".\"nspname\"='public' AND \"t\".\"relname\"=? " +
				"ORDER BY \"a\".\"attnum\"")) {
			ps.setString(1, "test");
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				assertEquals("PRIMARY KEY", rs.getString("constraint_type"));
				assertEquals("id", rs.getString("column_name"));
				assertFalse(rs.next());
			}

			stat.execute("create table test2 (x1 int primary key, x2 int, unique (x2))");
			ps.setString(1, "test2");
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				assertEquals("PRIMARY KEY", rs.getString("constraint_type"));
				assertEquals("x1", rs.getString("column_name"));
				assertTrue(rs.next());
				assertEquals("UNIQUE", rs.getString("constraint_type"));
				assertEquals("x2", rs.getString("column_name"));
				assertFalse(rs.next());
			}

			stat.execute("create table test3 (x1 int, x2 int, primary key (x1, x2))");
			ps.setString(1, "test3");
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				assertEquals("PRIMARY KEY", rs.getString("constraint_type"));
				assertEquals("x1", rs.getString("column_name"));
				assertTrue(rs.next());
				assertEquals("PRIMARY KEY", rs.getString("constraint_type"));
				assertEquals("x2", rs.getString("column_name"));
				assertFalse(rs.next());
			}
		}
	}

	@Test
	public void testDBeaver() throws SQLException {
		try (ResultSet rs = stat.executeQuery("SELECT t.oid,t.*,c.relkind FROM pg_catalog.pg_type t " +
				"LEFT OUTER JOIN pg_class c ON c.oid=t.typrelid WHERE typnamespace=-1000")) {
			// just no exception
		}
		stat.execute("SET search_path TO 'ab', 'c\"d', 'e''f'");
		try (ResultSet rs = stat.executeQuery("SHOW search_path")) {
			assertTrue(rs.next());
			assertEquals("pg_catalog, ab, \"c\"\"d\", \"e'f\"", rs.getString("search_path"));
		}
		stat.execute("SET search_path TO ab, \"c\"\"d\", \"e'f\"");
		try (ResultSet rs = stat.executeQuery("SHOW search_path")) {
			assertTrue(rs.next());
			assertEquals("pg_catalog, ab, \"c\"\"d\", \"e'f\"", rs.getString("search_path"));
		}
		int oid;
		try (ResultSet rs = stat.executeQuery("SELECT oid FROM pg_class WHERE relname = 'test'")) {
			rs.next();
			oid = rs.getInt("oid");
		}
		try (ResultSet rs = stat.executeQuery("SELECT i.*,i.indkey as keys," +
				"c.relname,c.relnamespace,c.relam,c.reltablespace," +
				"tc.relname as tabrelname,dsc.description," +
				"pg_catalog.pg_get_expr(i.indpred, i.indrelid) as pred_expr," +
				"pg_catalog.pg_get_expr(i.indexprs, i.indrelid, true) as expr," +
				"pg_catalog.pg_relation_size(i.indexrelid) as index_rel_size," +
				"pg_catalog.pg_stat_get_numscans(i.indexrelid) as index_num_scans " +
				"FROM pg_catalog.pg_index i " +
				"INNER JOIN pg_catalog.pg_class c ON c.oid=i.indexrelid " +
				"INNER JOIN pg_catalog.pg_class tc ON tc.oid=i.indrelid " +
				"LEFT OUTER JOIN pg_catalog.pg_description dsc ON i.indexrelid=dsc.objoid " +
				"WHERE i.indrelid=" + oid + " ORDER BY c.relname")) {
			// pg_index is empty
			assertFalse(rs.next());
		}
		try (ResultSet rs = stat.executeQuery("SELECT c.oid,c.*," +
				"t.relname as tabrelname,rt.relnamespace as refnamespace,d.description " +
				"FROM pg_catalog.pg_constraint c " +
				"INNER JOIN pg_catalog.pg_class t ON t.oid=c.conrelid " +
				"LEFT OUTER JOIN pg_catalog.pg_class rt ON rt.oid=c.confrelid " +
				"LEFT OUTER JOIN pg_catalog.pg_description d ON d.objoid=c.oid " +
				"AND d.objsubid=0 AND d.classoid='pg_constraint'::regclass WHERE c.conrelid=" + oid)) {
			assertTrue(rs.next());
			assertEquals("test", rs.getString("tabrelname"));
			assertEquals("p", rs.getString("contype"));
			assertEquals(Short.valueOf((short) 1), ((Object[]) rs.getArray("conkey").getArray())[0]);
		}
	}

	@AfterEach
	public void after() throws SQLException {
		stat.close();
		conn.close();
		server.stop();
		h2Conn.close();
	}
}