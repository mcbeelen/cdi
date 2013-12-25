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

import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;

import org.mybatis.cdi.Transactional;

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
public class LocalTransactionInterceptor extends AbstractTransactionInterceptor {

  @AroundInvoke
  public Object invoke(InvocationContext ctx) throws Exception {
    Transactional transactional = getTransactionalAnnotation(ctx);
    boolean started = start(transactional);
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
          rollback(transactional);
        } 
        else {
          commit(transactional);
        }
        close();
      }
    }
    return result;
  }

}
