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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;

import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;

import org.apache.ibatis.session.SqlSession;
import org.mybatis.cdi.Transactional;

/**
 * @see JtaTransactionInterceptor
 * @see LocalTransactionInterceptor
 *
 * @author Eduardo Macarron
 */
@Transactional
@Interceptor
public class AbstractTransactionInterceptor {

  protected boolean needsRollback(Transactional transactional, Throwable throwable) {
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

  protected void commit(Transactional transactional) {
    for (SqlSession manager : TransactionRegistry.getManagers()) {
      manager.commit(transactional.force());
    }
  }

  protected void rollback(Transactional transactional) {
    for (SqlSession manager : TransactionRegistry.getManagers()) {
      manager.rollback(transactional.force());
    }
  }
  
  protected void close() {
    for (SqlSession manager : TransactionRegistry.getManagers()) {
      manager.close();
    }
  }
  
  protected Exception unwrapException(Exception wrapped) {
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
