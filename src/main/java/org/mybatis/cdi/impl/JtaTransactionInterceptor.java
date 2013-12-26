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

import java.util.Collection;

import javax.annotation.PostConstruct;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.TransactionManager;
import javax.transaction.TransactionSynchronizationRegistry;
import javax.transaction.UserTransaction;

import org.apache.ibatis.session.SqlSession;
import org.mybatis.cdi.MybatisCdiConfigurationException;
import org.mybatis.cdi.Transactional;

/**
 * Interceptor for JTA transactions. Requires a JTA 1.1 container. 
 * MyBatis should be configured to use the {@code MANAGED} transaction manager.
 * 
 * Supports these scenarios:
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
	  
    // Case MB active Jta active Description
    // 1    no        no         Start JTA and MB TX and end them
    // 2    yes       no         Not possible
    // 3    no        yes        JTA was externally initiated. Register a sync.
    // 4    yes       yes        Do nothing, we are an inner method call

    Transactional transactional = getTransactionalAnnotation(ctx);

    // start mybatis tx
    boolean wasMyBatisTXActive = TransactionRegistry.isCurrentTransactionActive();
    TransactionRegistry.setCurrentTransactionActive(true);
    
    try {
	    // start jta tx if needed
	    boolean wasJtaTxActive = isTransactionActive();
	    if (!wasJtaTxActive) { // case 1
	      userTransaction.begin(); 
	    } else if (!wasMyBatisTXActive) { // case 3
	      registerSyncronization(transactional); 
	    }
	    
	    boolean needsRollback = false;    
	    try {
	      result = ctx.proceed();
	    } catch (Exception ex) {
	      Exception unwrapped = unwrapException(ex);
	      needsRollback = needsRollback(transactional, unwrapped);
	      throw unwrapped;
	    } finally {
	      if (needsRollback) {
	        if (wasJtaTxActive) {
	          userTransaction.setRollbackOnly();
	        } else {
	          rollback(transactional);
	          userTransaction.rollback();
	        }
	      } else {
	        if (!wasJtaTxActive) {
	          commit(transactional);
	          userTransaction.commit();
	        }
	      }
	      if (!wasJtaTxActive) {
	        close(); // should this be done before?
	      }
	    }
    } finally {
    	TransactionRegistry.clear();    	
    }
    return result;
  }

  private boolean isTransactionActive() throws SystemException {
	  return userTransaction.getStatus() != Status.STATUS_NO_TRANSACTION;
  }

  private void registerSyncronization(Transactional transactional) throws Exception {
    SqlSessionSynchronization sync = new SqlSessionSynchronization(TransactionRegistry.getManagers(), transactional);
    if (registry != null) {
      registry.registerInterposedSynchronization(sync);
    } else if (TransactionManager.class.isAssignableFrom(userTransaction.getClass())) {
      ((TransactionManager) userTransaction).getTransaction().registerSynchronization(sync);
    } else {
      throw new MybatisCdiConfigurationException("Cannot join existing transaction because could not find a TransactionSynchronizationRegistry");
    }
  }

  public static class SqlSessionSynchronization implements Synchronization {

    private Transactional transactional;
    private Collection<SqlSession> resources;

    public SqlSessionSynchronization(Collection<SqlSession> resources, Transactional transactional) {
      this.transactional = transactional;
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
            session.commit(transactional.force());
          }
        } finally {
          session.close();
        }
      }      
    }
  }
  
}
