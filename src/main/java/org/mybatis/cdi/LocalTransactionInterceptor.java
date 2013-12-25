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
package org.mybatis.cdi;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Collection;

import javax.inject.Inject;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;

import org.apache.ibatis.session.SqlSessionManager;

/**
 * Best-effort interceptor for local transactions. It locates all the instances
 * of {@code SqlSssionManager} and starts transactions on all them. It cannot
 * guarantee atomiticy if there is more than one {@code SqlSssionManager}. Use
 * XA drivers, a JTA container and the {@link JtaTransactionInterceptor} in that
 * case.
 *
 * @see JtaTransactionInterceptor
 *
 * @author Frank David Martínez
 */
@Transactional
@Interceptor
public class LocalTransactionInterceptor {

  @Inject
  private SqlSessionManagerRegistry registry;

  @AroundInvoke
  public Object invoke(InvocationContext ctx) throws Exception {
    Transactional transactional = getTransactionalAnnotation(ctx);
    Collection<SqlSessionManager> managers = registry.getManagers();
    boolean started = start(managers, transactional);
    if (started) {
      beginJta();
    }
    boolean needsRollback = false;
    Object result;
    try {
      result = ctx.proceed();
    }
    catch (Exception ex) {
      Exception unwrapped = unwrapException(ex); 
      needsRollback = needsRollback(transactional, unwrapped);
      throw unwrapped;
    }
    finally {
      if (started) {
        if (needsRollback) {
          rollback(managers, transactional);
        } 
        else {
          commit(managers, transactional);
        }
        close(managers);
        endJta(needsRollback);
      }
    }
    return result;
  }

  protected void beginJta() throws Exception {
    // nothing to do
  }

  protected void endJta(boolean commit) throws Exception {
    // nothing to do
  }

  private boolean needsRollback(Transactional transactional, Throwable throwable) {
    if (transactional.rollbackOnly()) {
      return true;
    }
    if (RuntimeException.class.isAssignableFrom(throwable.getClass())) {
      return true;
    }
    for (Class<?> exceptionClass : transactional.rollbackFor()) {
      if (exceptionClass.isAssignableFrom(throwable.getClass())) {
        return true;
      }
    }
    return false;
  }

  protected Transactional getTransactionalAnnotation(InvocationContext ctx) {
    Transactional t = ctx.getMethod().getAnnotation(Transactional.class);
    if (t == null) {
      t = ctx.getMethod().getDeclaringClass().getAnnotation(Transactional.class);
    }
    return t;
  }

  private boolean start(Collection<SqlSessionManager> managers, Transactional transactional) {
    boolean started = false;
    for (SqlSessionManager manager : managers) {
      if (!manager.isManagedSessionStarted()) {
        manager.startManagedSession(transactional.executorType(), transactional.isolation().getTransactionIsolationLevel());
        started = true;
      }
    }
    return started;
  }

  private void commit(Collection<SqlSessionManager> managers, Transactional transactional) {
    for (SqlSessionManager manager : managers) {
      manager.commit(transactional.force());
    }
  }

  private void rollback(Collection<SqlSessionManager> managers, Transactional transactional) {
    for (SqlSessionManager manager : managers) {
      manager.rollback(transactional.force());
    }
  }
  
  private void close(Collection<SqlSessionManager> managers) {
    for (SqlSessionManager manager : managers) {
      manager.close();
    }
  }
  
  private Exception unwrapException(Exception wrapped) {
    Throwable unwrapped = wrapped;
    while (true) {
      if (unwrapped instanceof InvocationTargetException) {
        unwrapped = ((InvocationTargetException) unwrapped).getTargetException();
      } 
      else if (unwrapped instanceof UndeclaredThrowableException) {
        unwrapped = ((UndeclaredThrowableException) unwrapped).getUndeclaredThrowable();
      } 
      else if (!(unwrapped instanceof Exception)) {
        return new RuntimeException(unwrapped);
      }
      else {
       return (Exception) unwrapped; 
      }      
    }
  }
  

}
