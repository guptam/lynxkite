package com.lynxanalytics.biggraph.controllers

import com.lynxanalytics.biggraph.graph_api.Scripting._
import com.lynxanalytics.biggraph.graph_util

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class SQLControllerTest extends BigGraphControllerTestBase {
  val sqlController = new SQLController(this)
  val resourceDir = getClass.getResource("/graph_operations/ImportGraphTest").toString
  graph_util.PrefixRepository.registerPrefix("IMPORTGRAPHTEST$", resourceDir)

  def await[T](f: concurrent.Future[T]): T =
    concurrent.Await.result(f, concurrent.duration.Duration.Inf)

  /*
  test("global sql on vertices") {
    val globalProjectframe = DirectoryEntry.fromName("Test_Dir/Test_Project").asNewProjectFrame()
    run("Example Graph", on = "Test_Dir/Test_Project")
    val result = await(sqlController.runSQLQuery(user, SQLQueryRequest(
      DataFrameSpec.global(directory = "Test_Dir",
        sql = "select name from `Test_Project|vertices` where age < 40"),
      maxRows = 10)))
    assert(result.header == List("name"))
    assert(result.data == List(List("Adam"), List("Eve"), List("Isolated Joe")))
  }

  test("global sql on vertices with attribute name quoted with backticks") {
    val globalProjectframe = DirectoryEntry.fromName("Test_Dir/Test_Project").asNewProjectFrame()
    run("Example Graph", on = "Test_Dir/Test_Project")
    val result = await(sqlController.runSQLQuery(user, SQLQueryRequest(
      DataFrameSpec.global(directory = "Test_Dir",
        sql = "select `name` from `Test_Project|vertices` where age < 40"),
      maxRows = 10)))
    assert(result.header == List("name"))
    assert(result.data == List(List("Adam"), List("Eve"), List("Isolated Joe")))
  }

  test("sql on vertices") {
    run("Example Graph")
    val result = await(sqlController.runSQLQuery(user, SQLQueryRequest(
      DataFrameSpec.local(project = projectName, sql = "select name from vertices where age < 40"),
      maxRows = 10)))
    assert(result.header == List("name"))
    assert(result.data == List(List("Adam"), List("Eve"), List("Isolated Joe")))
  }

  test("sql with empty results") {
    run("Example Graph")
    val result = await(sqlController.runSQLQuery(user, SQLQueryRequest(
      DataFrameSpec.local(project = projectName, sql = "select id from vertices where id = 11"),
      maxRows = 10)))
    assert(result.header == List("id"))
    assert(result.data == List())
  }

  test("sql file reading is disabled") {
    val file = getClass.getResource("/controllers/noread.csv").toString
    intercept[Throwable] {
      await(sqlController.runSQLQuery(user, SQLQueryRequest(
        DataFrameSpec.local(
          project = projectName,
          sql = s"select * from csv.`$file`"),
        maxRows = 10)))
    }
  }

  test("sql export to csv") {
    run("Example Graph")
    val result = await(sqlController.exportSQLQueryToCSV(user, SQLExportToCSVRequest(
      DataFrameSpec.local(
        project = projectName,
        sql = "select name, age from vertices where age < 40"),
      path = "<download>",
      delimiter = ";",
      quote = "\"",
      header = true)))
    val output = graph_util.HadoopFile(result.download.get.path).loadTextFile(sparkContext)
    val header = "name;age"
    assert(output.collect.filter(_ != header).sorted.mkString(", ") ==
      "Adam;20.3, Eve;18.2, Isolated Joe;2.0")
  }

  test("sql export to database") {
    val url = s"jdbc:sqlite:${dataManager.repositoryPath.resolvedNameWithNoCredentials}/test-db"
    run("Example Graph")
    val result = await(sqlController.exportSQLQueryToJdbc(user, SQLExportToJdbcRequest(
      DataFrameSpec.local(
        project = projectName,
        sql = "select name, age from vertices where age < 40 order by name"),
      jdbcUrl = url,
      table = "export_test",
      mode = "error")))
    val connection = java.sql.DriverManager.getConnection(url)
    val statement = connection.createStatement()
    val results = {
      val rs = statement.executeQuery("select * from export_test;")
      new Iterator[String] {
        def hasNext = rs.next
        def next = s"${rs.getString(1)};${rs.getDouble(2)}"
      }.toIndexedSeq
    }
    connection.close()
    assert(results.sorted == Seq("Adam;20.3", "Eve;18.2", "Isolated Joe;2.0"))
  }

  def importCSV(file: String, columns: List[String], infer: Boolean, limit: Option[Int] = None): Unit = {
    val csvFiles = "IMPORTGRAPHTEST$/" + file + "/part*"
    val response = sqlController.importCSV(
      user,
      CSVImportRequest(
        table = "csv-import-test",
        privacy = "public-read",
        files = csvFiles,
        columnNames = columns,
        delimiter = ",",
        mode = "FAILFAST",
        infer = infer,
        overwrite = true,
        columnsToImport = List(),
        limit = limit))
    val tablePath = response.id
    run(
      "Import vertices",
      Map(
        "table" -> tablePath,
        "id-attr" -> "new_id"))
  }

  def createViewCSV(file: String, columns: List[String], limit: Option[Int] = None): Unit = {
    val csvFiles = "IMPORTGRAPHTEST$/" + file + "/part*"
    sqlController.createViewCSV(
      user,
      CSVImportRequest(
        table = "csv-view-test",
        privacy = "public-read",
        files = csvFiles,
        columnNames = columns,
        delimiter = ",",
        mode = "FAILFAST",
        infer = false,
        overwrite = true,
        columnsToImport = List(),
        limit = limit))
  }

  test("import from CSV without header") {
    importCSV("testgraph/vertex-data", List("vertexId", "name", "age"), infer = false)
    assert(vattr[String]("vertexId") == Seq("0", "1", "2"))
    assert(vattr[String]("name") == Seq("Adam", "Bob", "Eve"))
    assert(vattr[String]("age") == Seq("18.2", "20.3", "50.3"))
  }

  test("import from CSV without header with limit") {
    importCSV("testgraph/vertex-data", List("vertexId", "name", "age"), infer = false, limit = Some(0))
    assert(vattr[String]("vertexId").isEmpty)
    assert(vattr[String]("name").isEmpty)
    assert(vattr[String]("age").isEmpty)

    importCSV("testgraph/vertex-data", List("vertexId", "name", "age"), infer = false, limit = Some(1))
    assert(vattr[String]("vertexId").length == 1)
    assert(vattr[String]("name").length == 1)
    assert(vattr[String]("age").length == 1)

    importCSV("testgraph/vertex-data", List("vertexId", "name", "age"), infer = false, limit = Some(2))
    assert(vattr[String]("vertexId").length == 2)
    assert(vattr[String]("name").length == 2)
    assert(vattr[String]("age").length == 2)

    importCSV("testgraph/vertex-data", List("vertexId", "name", "age"), infer = false, limit = Some(3))
    assert(vattr[String]("vertexId").length == 3)
    assert(vattr[String]("name").length == 3)
    assert(vattr[String]("age").length == 3)
  }

  test("import from CSV with header") {
    importCSV("with-header", List(), infer = false)
    assert(vattr[String]("vertexId") == Seq("0", "1", "2"))
    assert(vattr[String]("name") == Seq("Adam", "Bob", "Eve"))
    assert(vattr[String]("age") == Seq("18.2", "20.3", "50.3"))
  }

  test("import from CSV with type inference") {
    importCSV("with-header", List(), infer = true)
    assert(vattr[Int]("vertexId") == Seq(0, 1, 2))
    assert(vattr[String]("name") == Seq("Adam", "Bob", "Eve"))
    assert(vattr[Double]("age") == Seq(18.2, 20.3, 50.3))
  }

  val sqliteURL = s"jdbc:sqlite:${dataManager.repositoryPath.resolvedNameWithNoCredentials}/test-db"

  def createSqliteSubscribers() = {
    val connection = java.sql.DriverManager.getConnection(sqliteURL)
    val statement = connection.createStatement()
    statement.executeUpdate("""
    DROP TABLE IF EXISTS subscribers;
    CREATE TABLE subscribers
      (n TEXT, id INTEGER, name TEXT, gender TEXT, "race condition" TEXT, level DOUBLE PRECISION);
    INSERT INTO subscribers VALUES
      ('A', 1, 'Daniel', 'Male', 'Halfling', 10.0),
      ('B', 2, 'Beata', 'Female', 'Dwarf', 20.0),
      ('C', 3, 'Felix', 'Male', 'Gnome', NULL),
      ('D', 4, 'Oliver', 'Male', 'Troll', NULL),
      (NULL, 5, NULL, NULL, NULL, NULL);
    """)
    connection.close()
  }

  def checkSqliteSubscribers(table: String) = {
    run(
      "Import vertices",
      Map(
        "table" -> table,
        "id-attr" -> "new_id"))
    assert(vattr[String]("n") == Seq("A", "B", "C", "D"))
    assert(vattr[Long]("id") == Seq(1, 2, 3, 4, 5))
    assert(vattr[String]("name") == Seq("Beata", "Daniel", "Felix", "Oliver"))
    assert(vattr[String]("race condition") == Seq("Dwarf", "Gnome", "Halfling", "Troll"))
    assert(vattr[Double]("level") == Seq(10.0, 20.0))
    assert(subProject.viewer.vertexAttributes.keySet ==
      Set("new_id", "n", "id", "name", "race condition", "level"))
  }

  test("import from SQLite (no partitioning)") {
    createSqliteSubscribers()
    val response = sqlController.importJdbc(
      user,
      JdbcImportRequest(
        table = "jdbc-import-test",
        privacy = "public-read",
        jdbcUrl = sqliteURL,
        jdbcTable = "subscribers",
        overwrite = false,
        columnsToImport = List("n", "id", "name", "race condition", "level"),
        limit = None))
    checkSqliteSubscribers(response.id)
  }

  test("import from SQLite (INTEGER partitioning)") {
    createSqliteSubscribers()
    val response = sqlController.importJdbc(
      user,
      JdbcImportRequest(
        table = "jdbc-import-test",
        privacy = "public-read",
        jdbcUrl = sqliteURL,
        jdbcTable = "subscribers",
        keyColumn = Some("id"),
        overwrite = false,
        columnsToImport = List("n", "id", "name", "race condition", "level"),
        limit = None))
    checkSqliteSubscribers(response.id)
  }

  test("import from SQLite (DOUBLE partitioning)") {
    createSqliteSubscribers()
    val response = sqlController.importJdbc(
      user,
      JdbcImportRequest(
        table = "jdbc-import-test",
        privacy = "public-read",
        jdbcUrl = sqliteURL,
        jdbcTable = "subscribers",
        keyColumn = Some("level"),
        overwrite = false,
        columnsToImport = List("n", "id", "name", "race condition", "level"),
        limit = None))
    checkSqliteSubscribers(response.id)
  }

  test("import from SQLite (TEXT partitioning)") {
    createSqliteSubscribers()
    val response = sqlController.importJdbc(
      user,
      JdbcImportRequest(
        table = "jdbc-import-test",
        privacy = "public-read",
        jdbcUrl = sqliteURL,
        jdbcTable = "subscribers",
        keyColumn = Some("name"),
        overwrite = false,
        columnsToImport = List("n", "id", "name", "race condition", "level"),
        limit = None))
    checkSqliteSubscribers(response.id)
  }

  test("import from SQLite (INTEGER partitioning - custom number of partitions)") {
    createSqliteSubscribers()
    val response = sqlController.importJdbc(
      user,
      JdbcImportRequest(
        table = "jdbc-import-test",
        privacy = "public-read",
        jdbcUrl = sqliteURL,
        jdbcTable = "subscribers",
        keyColumn = Some("id"),
        numPartitions = Some(2),
        overwrite = false,
        columnsToImport = List("n", "id", "name", "race condition", "level"),
        limit = None))
    checkSqliteSubscribers(response.id)
  }

  test("import from SQLite (predicates)") {
    createSqliteSubscribers()
    val response = sqlController.importJdbc(
      user,
      JdbcImportRequest(
        table = "jdbc-import-test",
        privacy = "public-read",
        jdbcUrl = sqliteURL,
        jdbcTable = "subscribers",
        predicates = Some(List("id <= 2", "id >= 3")),
        overwrite = false,
        columnsToImport = List("n", "id", "name", "race condition", "level"),
        limit = None))
    checkSqliteSubscribers(response.id)
  }

  def createSqliteNonConventionalTable() = {
    val connection = java.sql.DriverManager.getConnection(sqliteURL)
    val statement = connection.createStatement()
    statement.executeUpdate(s"""
      DROP TABLE IF EXISTS 'name with space';
      CREATE TABLE 'name with space' (id INTEGER, 'colname with space' INTEGER, a TEXT);
      INSERT INTO 'name with space' VALUES(1, 1, 'x');""")
    connection.close()
  }

  def checkSqliteNonConventionalTable(table: String) = {
    run(
      "Import vertices",
      Map(
        "table" -> table,
        "id-attr" -> "new_id"))
    assert(vattr[Long]("id") == Seq(1L))
    assert(vattr[Long]("colname with space") == Seq(1L))
    assert(vattr[String]("a") == Seq("x"))
  }

  test("import from SQLite (non conventional table name - double quote)") {
    createSqliteNonConventionalTable()
    val response = sqlController.importJdbc(
      user,
      JdbcImportRequest(
        table = "jdbc-import-test",
        privacy = "public-read",
        jdbcUrl = sqliteURL,
        jdbcTable = "\"name with space\"",
        keyColumn = Some("id"),
        overwrite = false,
        columnsToImport = List("id", "colname with space", "a"),
        limit = None))
    checkSqliteNonConventionalTable(response.id)
  }

  test("import from SQLite (non conventional key column name - double quote)") {
    createSqliteNonConventionalTable()
    val response = sqlController.importJdbc(
      user,
      JdbcImportRequest(
        table = "jdbc-import-test",
        privacy = "public-read",
        jdbcUrl = sqliteURL,
        jdbcTable = "\"name with space\"",
        keyColumn = Some("\"colname with space\""),
        overwrite = false,
        columnsToImport = List("id", "colname with space", "a"),
        limit = None))
    checkSqliteNonConventionalTable(response.id)
  }

  test("import from SQLite (non conventional table name - single quote)") {
    createSqliteNonConventionalTable()
    val response = sqlController.importJdbc(
      user,
      JdbcImportRequest(
        table = "jdbc-import-test",
        privacy = "public-read",
        jdbcUrl = sqliteURL,
        jdbcTable = "'name with space'",
        keyColumn = Some("id"),
        overwrite = false,
        columnsToImport = List("id", "colname with space", "a"),
        limit = None))
    checkSqliteNonConventionalTable(response.id)
  }

  test("import from SQLite (non conventional key column name - single quote)") {
    createSqliteNonConventionalTable()
    val response = sqlController.importJdbc(
      user,
      JdbcImportRequest(
        table = "jdbc-import-test",
        privacy = "public-read",
        jdbcUrl = sqliteURL,
        jdbcTable = "'name with space' t",
        keyColumn = Some("t.'colname with space'"),
        overwrite = false,
        columnsToImport = List("id", "colname with space", "a"),
        limit = None))
    checkSqliteNonConventionalTable(response.id)
  }

  test("import from SQLite (aliased native sql - no keyColumn)") {
    createSqliteNonConventionalTable()
    val response = sqlController.importJdbc(
      user,
      JdbcImportRequest(
        table = "jdbc-import-test",
        privacy = "public-read",
        jdbcUrl = sqliteURL,
        jdbcTable = "(SELECT * FROM 'name with space') t",
        overwrite = false,
        columnsToImport = List("id", "colname with space", "a"),
        limit = None))
    checkSqliteNonConventionalTable(response.id)
  }

  test("import from SQLite (aliased native sql - with keyColumn)") {
    createSqliteNonConventionalTable()
    val response = sqlController.importJdbc(
      user,
      JdbcImportRequest(
        table = "jdbc-import-test",
        privacy = "public-read",
        jdbcUrl = sqliteURL,
        jdbcTable = "(SELECT * FROM 'name with space') t",
        keyColumn = Some("t.'colname with space'"),
        overwrite = false,
        columnsToImport = List("id", "colname with space", "a"),
        limit = None))
    checkSqliteNonConventionalTable(response.id)
  }

  test("import from SQLite (non aliased native sql - no keyColumn)") {
    createSqliteNonConventionalTable()
    val response = sqlController.importJdbc(
      user,
      JdbcImportRequest(
        table = "jdbc-import-test",
        privacy = "public-read",
        jdbcUrl = sqliteURL,
        jdbcTable = "(SELECT * FROM 'name with space')",
        overwrite = false,
        columnsToImport = List("id", "colname with space", "a"),
        limit = None))
    checkSqliteNonConventionalTable(response.id)
  }

  test("sql export to parquet + import back (including tuple columns)") {
    val exportPath = "IMPORTGRAPHTEST$/example.parquet"
    graph_util.HadoopFile(exportPath).deleteIfExists

    run("Example Graph")
    val result = await(
      sqlController.exportSQLQueryToParquet(
        user,
        SQLExportToParquetRequest(
          DataFrameSpec.local(
            project = projectName,
            sql = "select name, age, location from vertices"),
          path = exportPath)))
    val response = sqlController.importParquet(
      user,
      ParquetImportRequest(
        table = "csv-import-test",
        privacy = "public-read",
        files = exportPath + "/part*",
        overwrite = false,
        columnsToImport = List("name", "location"),
        limit = None))
    val tablePath = response.id
    run(
      "Import vertices",
      Map(
        "table" -> tablePath,
        "id-attr" -> "new_id"))

    assert(vattr[String]("name") == Seq("Adam", "Bob", "Eve", "Isolated Joe"))
    assert(vattr[(Double, Double)]("location") == Seq(
      (-33.8674869, 151.2069902),
      (1.352083, 103.819836),
      (40.71448, -74.00598),
      (47.5269674, 19.0323968)))
    graph_util.HadoopFile(exportPath).delete
  }

  test("export global sql to view + query it again") {
    val colNames = List("vertexId", "name", "age")
    createViewCSV("testgraph/vertex-data", colNames)
    sqlController.createViewDFSpec(user,
      SQLCreateViewRequest(name = "sql-view-test", privacy = "public-read",
        DataFrameSpec(
          directory = Some(""),
          project = None,
          sql = "select * from `csv-view-test`"
        ), overwrite = false))
    val res = Await.result(sqlController.runSQLQuery(user,
      SQLQueryRequest(
        DataFrameSpec(
          directory = Some(""),
          project = None,
          sql = "select vertexId, name, age from `sql-view-test` order by vertexId"
        ), maxRows = 120)),
      Duration.Inf)
    assert(res.header == colNames)
    assert(res.data == List(
      List("0", "Adam", "20.3"), List("1", "Eve", "18.2"), List("2", "Bob", "50.3")))
  }

  test("export global sql to view with limit + query it again") {
    val colNames = List("vertexId", "name", "age")
    // vertex-data has 3 rows, but we limit the import to 2.
    createViewCSV("testgraph/vertex-data", colNames, limit = Some(2))
    sqlController.createViewDFSpec(user,
      SQLCreateViewRequest(name = "sql-view-test", privacy = "public-read",
        DataFrameSpec(
          directory = Some(""),
          project = None,
          sql = "select * from `csv-view-test`"
        ), overwrite = false))
    val res = Await.result(sqlController.runSQLQuery(user,
      SQLQueryRequest(
        DataFrameSpec(
          directory = Some(""),
          project = None,
          sql = "select vertexId, name, age from `sql-view-test` order by vertexId"
        ), maxRows = 120)),
      Duration.Inf)
    assert(res.data.length == 2)
  }
*/

  test("list project tables") {
    createProject(name = "example1")
    createDirectory(name = "dir")
    createProject(name = "dir/example2")
    run("Example Graph", on = "example1")
    run("Example Graph", on = "dir/example2")
    run(
      "Segment by double attribute",
      params = Map(
        "name" -> "bucketing",
        "attr" -> "age",
        "interval-size" -> "0.1",
        "overlap" -> "no"),
      on = "dir/example2")
    // List tables from root directory.
    val res1 = await(
      sqlController.getAllTables(
        user, GetAllTablesRequest(path = "")))
    assert(List(
      TableDesc("dir/example2", "|vertices", "dir/example2|vertices"),
      TableDesc("dir/example2", "|edges", "dir/example2|edges"),
      TableDesc("dir/example2", "|edge_attributes", "dir/example2|edge_attributes"),
      TableDesc("dir/example2", "|bucketing|vertices", "dir/example2|bucketing|vertices"),
      TableDesc("dir/example2", "|bucketing|belongs_to", "dir/example2|bucketing|belongs_to"),
      TableDesc("example1", "|vertices", "example1|vertices"),
      TableDesc("example1", "|edges", "example1|edges"),
      TableDesc("example1", "|edge_attributes", "example1|edge_attributes")) == res1.list)

    // List tables from the directory named "dir". Verify that
    // the word "dir" is now omitted from table names.
    val res2 = await(
      sqlController.getAllTables(
        user, GetAllTablesRequest(path = "dir")))
    assert(List(
      TableDesc("dir/example2", "|vertices", "example2|vertices"),
      TableDesc("dir/example2", "|edges", "example2|edges"),
      TableDesc("dir/example2", "|edge_attributes", "example2|edge_attributes"),
      TableDesc("dir/example2", "|bucketing|vertices", "example2|bucketing|vertices"),
      TableDesc("dir/example2", "|bucketing|belongs_to", "example2|bucketing|belongs_to")) == res2.list)

    // List tables from the project "dir/example2". Verify that
    // the word "dir/example2" is now omitted from table names.
    val res3 = await(
      sqlController.getAllTables(
        user, GetAllTablesRequest(path = "dir/example2")))
    assert(List(
      TableDesc("dir/example2", "|vertices", "vertices"),
      TableDesc("dir/example2", "|edges", "edges"),
      TableDesc("dir/example2", "|edge_attributes", "edge_attributes"),
      TableDesc("dir/example2", "|bucketing|vertices", "bucketing|vertices"),
      TableDesc("dir/example2", "|bucketing|belongs_to", "bucketing|belongs_to")) == res3.list)
  }

  test("list table columns") {
    run("Example Graph")
    val x = await(sqlController.getColumns(
      user,
      GetColumnsRequest(
        framePath = "Test_Project",
        subTablePath = "|vertices")))
    assert(List(
      ColumnDesc("age"),
      ColumnDesc("income"),
      ColumnDesc("id"),
      ColumnDesc("location"),
      ColumnDesc("name"),
      ColumnDesc("gender")) == x.columns)
  }

  test("list views") {
    run("Example Graph")
    val x = sqlController.createViewDFSpec(
      user,
      SQLCreateViewRequest(
        name = "view1",
        privacy = "public-write",
        overwrite = false,
        dfSpec = DataFrameSpec(
          directory = Some(""),
          project = None,
          sql = "SELECT * FROM `Test_Project|vertices`")))
    println(x)
    val res = await(
      sqlController.getAllTables(
        user, GetAllTablesRequest(path = "")))
    assert(List(
      TableDesc("Test_Project", "|vertices", "Test_Project|vertices"),
      TableDesc("Test_Project", "|edges", "Test_Project|edges"),
      TableDesc("Test_Project", "|edge_attributes", "Test_Project|edge_attributes")) == res.list)
    println(res.list.toList)

  }

}
