/*
Switch every generated primary key from UUIDv4 to UUIDv7.

Why: the REST list endpoints page with a keyset cursor over the uuid column
(AbstractJooqDao.loadPage: orderBy(uuid) + seek(fromId)), so the uuid *is* the
default sort order. With random v4 keys a freshly created element lands at an
arbitrary position in the list instead of at the end. UUIDv7 carries a 48 bit
unix millisecond timestamp in its high bits, so byte order becomes insertion
order and the existing cursor keeps working untouched - no cursor rewrite, no
schema change, no new column.

Requires PostgreSQL 18, which is where uuidv7() became a built-in. The guard
below turns an older server into a readable failure instead of an
"undefined function" three statements later.

The uuid-ossp extension stays installed. Nothing new depends on it, but it is
what V1 created and what the pre-existing defaults referenced, so dropping it
here would only add a way for a partially migrated database to break.

Rows that already exist keep their v4 uuid. Their high bits are uniformly
random while every v7 uuid generated this century begins 0x01, so old rows sort
*after* all new ones rather than interleaving with them. That is deliberate:
rewriting primary keys in place would have to cascade through every foreign key
in the schema. See spec/features/db/DB_SCHEMA.md.
*/

DO $$
BEGIN
  IF current_setting('server_version_num')::int < 180000 THEN
    RAISE EXCEPTION 'Loom requires PostgreSQL 18 or newer for uuidv7() defaults, found %', version();
  END IF;
END $$;

/*
Driven off the catalog rather than a hand written list of 66 columns: the set of
tables carrying a uuid_generate_v4() default is exactly what the preceding
migrations produced, and reading it back is the only way to be sure none was
missed.
*/
DO $$
DECLARE
  col record;
  touched int := 0;
BEGIN
  FOR col IN
    SELECT c.relname AS table_name, a.attname AS column_name
      FROM pg_attrdef d
      JOIN pg_class c ON c.oid = d.adrelid
      JOIN pg_namespace n ON n.oid = c.relnamespace
      JOIN pg_attribute a ON a.attrelid = d.adrelid AND a.attnum = d.adnum
     WHERE n.nspname = current_schema()
       AND c.relkind = 'r'
       AND pg_get_expr(d.adbin, d.adrelid) LIKE '%uuid_generate_v4%'
     ORDER BY c.relname, a.attname
  LOOP
    EXECUTE format('ALTER TABLE %I ALTER COLUMN %I SET DEFAULT uuidv7()', col.table_name, col.column_name);
    touched := touched + 1;
  END LOOP;
  RAISE NOTICE 'Switched % column default(s) to uuidv7()', touched;
END $$;
