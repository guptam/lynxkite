package com.lynxanalytics.biggraph.graph_api

import org.scalatest.FunSuite

class SQLiteKeyValueStoreTest extends KeyValueStoreTest(new SQLiteKeyValueStore("/tmp/key-value-store-test"))

abstract class KeyValueStoreTest(store: KeyValueStore) extends FunSuite {
  test("get and put") {
    store.clear
    assert(store.get("alma").isEmpty)
    store.put("alma", "korte")
    assert(store.get("alma").get == "korte")
  }

  test("delete") {
    store.clear
    store.put("alma", "korte")
    assert(store.get("alma").get == "korte")
    store.delete("alma")
    assert(store.get("alma").isEmpty)
  }

  test("scan") {
    store.clear
    assert(store.scan("alma").isEmpty)
    store.put("alma1", "korte1")
    store.put("alma2", "korte2")
    assert(store.scan("alma").toSeq ==
      Seq("alma1" -> "korte1", "alma2" -> "korte2"))
  }

  test("deletePrefix") {
    store.clear
    store.put("alma1", "korte1")
    store.put("alma2", "korte2")
    assert(store.scan("alma").toSeq ==
      Seq("alma1" -> "korte1", "alma2" -> "korte2"))
    store.deletePrefix("alma")
    assert(store.scan("alma").isEmpty)
  }

  test("successful transaction") {
    store.clear
    store.transaction {
      store.put("alma", "korte")
      assert(store.get("alma").get == "korte")
    }
    assert(store.get("alma").get == "korte")
  }

  test("failed transaction") {
    store.clear
    store.put("alma", "barack")
    class MyException extends Exception
    intercept[MyException] {
      store.transaction {
        store.put("alma", "korte")
        throw new MyException
      }
    }
    assert(store.get("alma").get == "barack")
  }

  test("nested transactions") {
    store.clear
    store.transaction {
      store.put("alma", "barack")
      store.transaction {
        store.put("alma", "szilva")
      }
      class MyException extends Exception
      intercept[MyException] {
        store.transaction {
          store.put("alma", "korte")
          throw new MyException
        }
      }
    }
    assert(store.get("alma").get == "szilva")
  }
}
