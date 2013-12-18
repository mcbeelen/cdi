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

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.enterprise.context.Dependent;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.util.AnnotationLiteral;
import javax.inject.Named;
import javax.inject.Qualifier;

import org.apache.ibatis.session.SqlSessionFactory;

/**
 * Internal CDI metadata for a sesssion manager bean.
 *
 * @author Eduardo Macarron
 */
public class ManagerBean implements Bean {

  final BeanManager beanManager;
 
  final Set<Annotation> qualifiers;

  final String sqlSessionFactoryName;
  
  public ManagerBean(Set<Annotation> annotations, BeanManager beanManager) {  
    this.sqlSessionFactoryName = getBeanName(annotations);
    this.beanManager = beanManager;
    if (annotations == null || annotations.isEmpty()) {
      this.qualifiers = new HashSet<Annotation>();
      this.qualifiers.add(new AnnotationLiteral<Default>() {});
      this.qualifiers.add(new AnnotationLiteral<Any>() {});
    }
    else {
      this.qualifiers = filterQualifiers(annotations);
    }
  }
  
  private String getBeanName(Set<Annotation> annotations) {
    for (Annotation a : annotations) {
      if (a.annotationType().equals(Named.class)) {
        return ((Named) a).value();
      }
    }
    return null;    
  }
  
  private Set<Annotation> filterQualifiers(Set<Annotation> annotations) {
    final Set<Annotation> set = new HashSet<Annotation>();
    for (Annotation a : annotations) {
      if (a.annotationType().isAnnotationPresent(Qualifier.class) && !a.annotationType().equals(Named.class)) {
        set.add(a);
      }
    }
    return set;
  }  

  public Set getTypes() {
    Set<Type> types = new HashSet<Type>();
    types.add(SqlSessionManager.class);
    return types;
  }

  public Set getQualifiers() {
    return qualifiers;
  }

  public Class getScope() {
    return Dependent.class;
  }

  public String getName() {
    return sqlSessionFactoryName == null ? null : "$$mybatis$$_" + sqlSessionFactoryName;
  }

  public Set getStereotypes() {
    return Collections.emptySet();
  }

  public Class getBeanClass() {
    return SqlSessionManager.class;
  }

  public boolean isAlternative() {
    return false;
  }

  public boolean isNullable() {
    return false;
  }

  public Set getInjectionPoints() {
    return Collections.emptySet();
  }

  public Object create(CreationalContext creationalContext) {
    Bean managerBean = findSqlSessionFactoryBean();
    SqlSessionFactory factory = (SqlSessionFactory) beanManager.getReference(managerBean, SqlSessionFactory.class, creationalContext);
    SqlSessionManager manager = new SqlSessionManager(factory);
    return manager;
  }

  public void destroy(Object instance, CreationalContext creationalContext) {
    creationalContext.release();
  }

  private Bean findSqlSessionFactoryBean() {
    Set<Bean<?>> beans;
    if (sqlSessionFactoryName != null) {
      beans = beanManager.getBeans(sqlSessionFactoryName);
    }
    else {
      beans = beanManager.getBeans(SqlSessionFactory.class, qualifiers.toArray(new Annotation[] {}));
    }
    Bean bean = beanManager.resolve(beans);
    if (bean == null) {
      throw new MybatisCdiConfigurationException("There are no SqlSessionFactory producers properly configured.");
    }
    return bean;
  }

}
