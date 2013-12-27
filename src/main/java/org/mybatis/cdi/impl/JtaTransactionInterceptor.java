/*
 * Copyright 2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.mybatis.cdi.impl;

import java.sql.SQLException;
import java.util.Collection;

import javax.annotation.PostConstruct;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.RollbackException;
import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.TransactionManager;
import javax.transaction.TransactionSynchronizationRegistry;
import javax.transaction.UserTransaction;

import org.apache.ibatis.session.SqlSession;
import org.mybatis.cdi.Transactional;

/**
 * Interceptor for JTA transactions. Requires a JTA 1.1 container. 
 * MyBatis should be configured to use the {@code MANAGED} transaction manager.
 * 
 * Supports two scenarios:
 * <ul>
 * <li>MyBatis starts a JTA TX and ends it</li>
 * <li>MyBatis TX joins to an externally started JTA TX and ends with it</li>
 * </ul>
 * 
 * @author Eduardo Macarron
 */
@Transactional
@Interceptor
public class JtaTransactionInterceptor extends AbstractTransactionInterceptor {

  @Inject private UserTransaction userTransaction;
  
  @Inject private Instance<TransactionSynchronizationRegistry> registrySource;
  
  private TransactionSynchronizationRegistry registry;
  
  private boolean registryInitiated;
  
  @PostConstruct
  public void init() {
    registryInitiated = true;
    if (registrySource.isUnsatisfied()) {
      if (TransactionSynchronizationRegistry.class.isAssignableFrom(userTransaction.getClass())) {
        registry = (TransactionSynchronizationRegistry) userTransaction;
      } else {
        // look it up
        try {
          InitialContext ic = new InitialContext();
          registry = (TransactionSynchronizationRegistry) ic.lookup("java:comp/TransactionSynchronizationRegistry");
        } catch (NamingException e) {
          // bad luck, no registry...
        }
      }
    } else {
      registry = registrySource.get();
    }
  }

  @AroundInvoke
  public Object invoke(InvocationContext ctx) throws Exception {

    if (!registryInitiated) {
      init(); // TODO why postconstruct is not getting called??
    }
    
    Object result;
	  
    // Case MB initiator Jta initiator Description
    // 1    yes          yes           New TX: start JTA and MB TX and end them
    // 2    no           yes           Not possible
    // 3    yes          no            JTA was externally initiated. Register a sync.
    // 4    no           no            Do nothing, we are an inner method call

    TransactionInfo info = buildTransactionInfo(ctx);
    TransactionRegistry.setCurrentTransactionActive(true);
    
    startJtaTransactionIfNeeded(info);
    try {
      result = ctx.proceed();
      commit(info);
    } catch (Exception ex) {
      Exception unwrapped = unwrapException(ex);      
      handleException(info, unwrapped);
      throw unwrapped;
    } finally {
    	cleanUp();    	
    }
    return result;
  }
  
  private void commit(TransactionInfo info) throws SecurityException, IllegalStateException, RollbackException, HeuristicMixedException, HeuristicRollbackException, SystemException {
    if (!info.isMBTxInitiator()) {
      // inner method, do nothing
      return;
    }
    if (!info.isJtaTxInitiator()) {
      // cannot commit the session because the tx may be rolledback later
      // flush statements and close the JDBC connection before tx ends
      // the syncronization will commit or rollback depending on how tx ends
      if (info.isCanJoinJta()) {
        flush();
        disconnect();
      } else {
        // could not register a sync so lets commit
        // if afterwards, the Jta tx rolls back 2nd level caches may have inconsistent data
        commitSqlSession(info.getTransactional());
        closeSqlSession();
      }
    } else {
      commitSqlSession(info.getTransactional());
      closeSqlSession();
      userTransaction.commit();
    }
  }
  
  private void cleanUp() {
    TransactionRegistry.clear();
  }
  
  private TransactionInfo buildTransactionInfo(InvocationContext ctx) throws SystemException {
    TransactionInfo info = new TransactionInfo();
    info.setTransactional(getTransactionalAnnotation(ctx));
    info.setMBTxInitiator(!TransactionRegistry.isCurrentTransactionActive());
    info.setJtaTxInitiator(!isTransactionActive());
    return info;
  }
  
  private void handleException(TransactionInfo info, Exception ex) throws IllegalStateException, SecurityException, SystemException, RollbackException, HeuristicMixedException, HeuristicRollbackException {
    if (!info.isMBTxInitiator()) {      
      return;
    }    
    if (needsRollback(info.getTransactional(), ex)) {
      try {
        rollbackSqlSession(info.getTransactional());
        if (info.isJtaTxInitiator) {
          userTransaction.rollback();
        } else {
          userTransaction.setRollbackOnly();
        }
      } finally {
        closeSqlSession();
      }
    } else {
      commit(info);
    }
  }
  
  private void startJtaTransactionIfNeeded(TransactionInfo info) throws Exception, Exception {
    if (!info.isMBTxInitiator()) {
      return;
    }    
    if (info.isJtaTxInitiator()) {
      userTransaction.begin();
    } else {
      joinExistingJtaTransaction(info);
    }   
  }
  
  private void joinExistingJtaTransaction(TransactionInfo info) throws Exception {
    registerSyncronization(info);
  }

  private void flush() {
    for (SqlSession session : TransactionRegistry.getManagers()) {
      session.flushStatements();
    }
  }

  private void disconnect() {
    for (SqlSession session : TransactionRegistry.getManagers()) {
      try {
        session.getConnection().close();
      } catch (SQLException ignored) {
        // ignored
      }
    }
  }

  private boolean isTransactionActive() throws SystemException {
	  return userTransaction.getStatus() != Status.STATUS_NO_TRANSACTION;
  }

  private void registerSyncronization(TransactionInfo info) throws Exception {
    SqlSessionSynchronization sync = new SqlSessionSynchronization(TransactionRegistry.getManagers());
    if (registry != null) {
      registry.registerInterposedSynchronization(sync);
      info.setCanJoinJta(true);
    } else if (TransactionManager.class.isAssignableFrom(userTransaction.getClass())) {
      ((TransactionManager) userTransaction).getTransaction().registerSynchronization(sync);
      info.setCanJoinJta(true);
    } 
  }
  
  private static class TransactionInfo {

    private Transactional transactional;
    private boolean isMBTxInitiator;    
    private boolean isJtaTxInitiator;
    private boolean canJoinJta;
    
    public Transactional getTransactional() {
      return transactional;
    }

    public void setTransactional(Transactional transactional) {
      this.transactional = transactional;
    }

    public boolean isMBTxInitiator() {
      return isMBTxInitiator;
    }

    public void setMBTxInitiator(boolean isMBTxInitiator) {
      this.isMBTxInitiator = isMBTxInitiator;
    }

    public boolean isJtaTxInitiator() {
      return isJtaTxInitiator;
    }

    public void setJtaTxInitiator(boolean isJtaTxInitiator) {
      this.isJtaTxInitiator = isJtaTxInitiator;
    }

    public boolean isCanJoinJta() {
      return canJoinJta;
    }

    public void setCanJoinJta(boolean canJoinJta) {
      this.canJoinJta = canJoinJta;
    }
    
  }

  public static class SqlSessionSynchronization implements Synchronization {

    private Collection<SqlSession> resources;

    public SqlSessionSynchronization(Collection<SqlSession> resources) {
      this.resources = resources;
    }

    /**
     * It is not called in a rollback!
     */
    public void beforeCompletion() {
      for (SqlSession session : resources) {
        session.flushStatements();
      }
    }

    /**
     * Can be called from a different thread
     * Note that if inside an external Jta transaction, more than one @Transactional is found
     * each one creates new SqlSessions and registers it own synchronizations
     */
    public void afterCompletion(int status) {
      for (SqlSession session : resources) {
        try {
          if (status == Status.STATUS_COMMITTED) {
            // cannot force commit because connection is closed
            session.commit(false);
          }
        } finally {
          session.close();
        }
      }      
    }
  }
  
}
