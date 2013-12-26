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

import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;
import javax.transaction.Status;
import javax.transaction.Synchronization;
import javax.transaction.SystemException;
import javax.transaction.TransactionSynchronizationRegistry;
import javax.transaction.UserTransaction;

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

  @Inject
  private UserTransaction userTransaction;

  @Inject
  private TransactionSynchronizationRegistry synchronizationRegistry;

  @AroundInvoke
  public Object invoke(InvocationContext ctx) throws Exception {

    // Case MB active Jta active Description
    // 1    no        no         Start JTA and MB TX and end them
    // 2    yes       no         Not possible
    // 3    no        yes        JTA was externally initiated. Register a sync.
    // 4    yes       yes        Do nothing, we are an inner method call

    Transactional transactional = getTransactionalAnnotation(ctx);

    boolean wasMyBatisTXActive = !start(transactional);
    boolean wasJtaTxActive = isTransactionActive();
    boolean needsRollback = false;

    if (!wasJtaTxActive) { // case 1
      userTransaction.begin(); 
    } else if (!wasMyBatisTXActive) { // case 3
      registerSyncronization(transactional); 
    }
    Object result;
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
    return result;
  }

  private boolean isTransactionActive() throws SystemException {
    switch (userTransaction.getStatus()) {
    case Status.STATUS_ACTIVE:
    case Status.STATUS_MARKED_ROLLBACK:
    case Status.STATUS_ROLLEDBACK:
      return true;
    default:
      return false;
    }
  }

  private void registerSyncronization(Transactional transactional) {
    SqlSessionSynchronization sync = new SqlSessionSynchronization(transactional);
    synchronizationRegistry.registerInterposedSynchronization(sync);
  }

  public class SqlSessionSynchronization implements Synchronization {

    private Transactional transactional;

    public SqlSessionSynchronization(Transactional transactional) {
      this.transactional = transactional;
    }

    /**
     * It is not called in a rollback!
     */
    public void beforeCompletion() {
      flush();
    }

    /**
     * TODO Can be called from a different thread
     * SqlSessionManager won't be able to find the SqlSession in the current thread
     */
    public void afterCompletion(int status) {
      try {
        if (status == Status.STATUS_COMMITTED) {
          commit(transactional);
        }
      } finally {
        close();
      }
    }
  }
  
}
