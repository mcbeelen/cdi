/*
 *    Copyright 2009-2012 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.mybatis.cdi.impl;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.TransactionIsolationLevel;

public class SqlSessionProxy implements SqlSessionFactory, SqlSession {

  private final SqlSessionFactory sqlSessionFactory;
  private final SqlSession sqlSessionProxy;
  
  public SqlSessionProxy(SqlSessionFactory sqlSessionFactory) {
    this.sqlSessionFactory = sqlSessionFactory;
    this.sqlSessionProxy = (SqlSession) Proxy.newProxyInstance(
        SqlSessionFactory.class.getClassLoader(),
        new Class[]{SqlSession.class},
        new SqlSessionInterceptor());
  }

  public SqlSession openSession() {
    return sqlSessionFactory.openSession();
  }

  public SqlSession openSession(boolean autoCommit) {
    return sqlSessionFactory.openSession(autoCommit);
  }

  public SqlSession openSession(Connection connection) {
    return sqlSessionFactory.openSession(connection);
  }

  public SqlSession openSession(TransactionIsolationLevel level) {
    return sqlSessionFactory.openSession(level);
  }

  public SqlSession openSession(ExecutorType execType) {
    return sqlSessionFactory.openSession(execType);
  }

  public SqlSession openSession(ExecutorType execType, boolean autoCommit) {
    return sqlSessionFactory.openSession(execType, autoCommit);
  }

  public SqlSession openSession(ExecutorType execType, TransactionIsolationLevel level) {
    return sqlSessionFactory.openSession(execType, level);
  }

  public SqlSession openSession(ExecutorType execType, Connection connection) {
    return sqlSessionFactory.openSession(execType, connection);
  }

  public Configuration getConfiguration() {
    return sqlSessionFactory.getConfiguration();
  }

  public <T> T selectOne(String statement) {
    return sqlSessionProxy.<T> selectOne(statement);
  }

  public <T> T selectOne(String statement, Object parameter) {
    return sqlSessionProxy.<T> selectOne(statement, parameter);
  }

  public <K, V> Map<K, V> selectMap(String statement, String mapKey) {
    return sqlSessionProxy.<K, V> selectMap(statement, mapKey);
  }

  public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey) {
    return sqlSessionProxy.<K, V> selectMap(statement, parameter, mapKey);
  }

  public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey, RowBounds rowBounds) {
    return sqlSessionProxy.<K, V> selectMap(statement, parameter, mapKey, rowBounds);
  }

  public <E> List<E> selectList(String statement) {
    return sqlSessionProxy.<E> selectList(statement);
  }

  public <E> List<E> selectList(String statement, Object parameter) {
    return sqlSessionProxy.<E> selectList(statement, parameter);
  }

  public <E> List<E> selectList(String statement, Object parameter, RowBounds rowBounds) {
    return sqlSessionProxy.<E> selectList(statement, parameter, rowBounds);
  }

  public void select(String statement, ResultHandler handler) {
    sqlSessionProxy.select(statement, handler);
  }

  public void select(String statement, Object parameter, ResultHandler handler) {
    sqlSessionProxy.select(statement, parameter, handler);
  }

  public void select(String statement, Object parameter, RowBounds rowBounds, ResultHandler handler) {
    sqlSessionProxy.select(statement, parameter, rowBounds, handler);
  }

  public int insert(String statement) {
    return sqlSessionProxy.insert(statement);
  }

  public int insert(String statement, Object parameter) {
    return sqlSessionProxy.insert(statement, parameter);
  }

  public int update(String statement) {
    return sqlSessionProxy.update(statement);
  }

  public int update(String statement, Object parameter) {
    return sqlSessionProxy.update(statement, parameter);
  }

  public int delete(String statement) {
    return sqlSessionProxy.delete(statement);
  }

  public int delete(String statement, Object parameter) {
    return sqlSessionProxy.delete(statement, parameter);
  }

  public <T> T getMapper(Class<T> type) {
    return getConfiguration().getMapper(type, this);
  }

  public Connection getConnection() {
	  return sqlSessionProxy.getConnection();
  }

  public void clearCache() {
	  sqlSessionProxy.clearCache();
  }

  public void commit() {
	throw new UnsupportedOperationException();
  }

  public void commit(boolean force) {
	throw new UnsupportedOperationException();
  }

  public void rollback() {
		throw new UnsupportedOperationException();
  }

  public void rollback(boolean force) {
		throw new UnsupportedOperationException();
  }

  public List<BatchResult> flushStatements() {
		return sqlSessionProxy.flushStatements();
  }

  public void close() {
	  throw new UnsupportedOperationException();
  }

  private class SqlSessionInterceptor implements InvocationHandler {
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
      if (TransactionRegistry.isCurrentTransactionActive()) {
    	final SqlSession sqlSession = TransactionRegistry.getSession(sqlSessionFactory);
        try {
          return method.invoke(sqlSession, args);
        } catch (Throwable t) {
          throw ExceptionUtil.unwrapThrowable(t);
        }
      } else {
        final SqlSession autoSqlSession = openSession();
        try {
          final Object result = method.invoke(autoSqlSession, args);
          autoSqlSession.commit();
          return result;
        } catch (Throwable t) {
          autoSqlSession.rollback();
          throw ExceptionUtil.unwrapThrowable(t);
        } finally {
          autoSqlSession.close();
        }
      }
    }
  }

}
