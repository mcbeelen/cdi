/*
 *    Copyright 2013 the original author or authors.
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
package org.mybatis.cdi;

import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Named;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

public class ManagerProducers {

  private SqlSessionFactory createSessionManager(int n) throws IOException {
    Reader reader = Resources.getResourceAsReader("org/mybatis/cdi/mybatis-config_" + n + ".xml");
    SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
    reader.close();

    SqlSession session = sqlSessionFactory.openSession();
    Connection conn = session.getConnection();
    reader = Resources.getResourceAsReader("org/mybatis/cdi/CreateDB_" + n + ".sql");
    ScriptRunner runner = new ScriptRunner(conn);
    runner.setLogWriter(null);
    runner.runScript(reader);
    reader.close();
    session.close();

    return sqlSessionFactory;
  }


  private SqlSessionFactory createSessionManagerJTA() throws IOException {
    Reader reader = Resources.getResourceAsReader("org/mybatis/cdi/mybatis-config_jta.xml");
    SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(reader);
    reader.close();

    SqlSession session = sqlSessionFactory.openSession();
    Connection conn = session.getConnection();
    reader = Resources.getResourceAsReader("org/mybatis/cdi/CreateDB_JTA.sql");
    ScriptRunner runner = new ScriptRunner(conn);
    runner.setLogWriter(null);
    runner.runScript(reader);
    reader.close();
    session.close();

    return sqlSessionFactory;
  }
 
    
  @Named("manager1")
  @Produces
  @ApplicationScoped
  public SqlSessionFactory createManager1() throws IOException {
    return createSessionManager(1);
  }

  @Named("manager2")
  @Produces
  @ApplicationScoped
  public SqlSessionFactory createManager2() throws IOException {
    return createSessionManager(2);
  }

  @Produces
  @ApplicationScoped
  @MySpecialManager
  @OtherQualifier
  public SqlSessionFactory createManager3() throws IOException {
    return createSessionManager(3);
  }

  @Produces
  @ApplicationScoped
  @JtaManager
  public SqlSessionFactory createManagerJTA() throws IOException {
    return createSessionManagerJTA();
  }  
  
}
