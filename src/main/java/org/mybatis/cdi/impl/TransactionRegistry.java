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
import java.util.HashMap;
import java.util.Map;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.cdi.MybatisCdiConfigurationException;

/**
 * 
 * @author Frank D. Martinez [mnesarco]
 */
@ApplicationScoped
public class TransactionRegistry {

	private static ThreadLocal<Map<SqlSessionFactory, SqlSession>> sessions = new ThreadLocal<Map<SqlSessionFactory, SqlSession>>();

	private static ThreadLocal<Boolean> actualTransactionActive = new ThreadLocal<Boolean>();

	@Inject	@Any private Instance<SqlSessionFactory> factories;

	@PostConstruct
	public void init() {
		if (factories.isUnsatisfied()) {
			throw new MybatisCdiConfigurationException(
					"There are no SqlSessionFactory producers properly configured.");
		}
	}

	public static SqlSession getSession(SqlSessionFactory factory) {
	  createMapIfNull();
		SqlSession session = sessions.get().get(factory);
		if (session == null) {
			session = factory.openSession();
			sessions.get().put(factory, session);
		}
		return session;
	}

	public static Collection<SqlSession> getManagers() {
	  createMapIfNull();
	  Map<SqlSessionFactory, SqlSession> managers = sessions.get(); 
		return managers.values();
	}
	
	private static void createMapIfNull() {
    if (sessions.get() == null) {
      sessions.set(new HashMap<SqlSessionFactory, SqlSession>());
    }	  
	}
	
	public static boolean isCurrentTransactionActive() {
    return (actualTransactionActive.get() != null);
	}
	
	public static void setCurrentTransactionActive(boolean active) {
		actualTransactionActive.set(active ? Boolean.TRUE : null);
	}
	
	public static void clear() {
	  setCurrentTransactionActive(false);
	  sessions.remove();
	}

}
