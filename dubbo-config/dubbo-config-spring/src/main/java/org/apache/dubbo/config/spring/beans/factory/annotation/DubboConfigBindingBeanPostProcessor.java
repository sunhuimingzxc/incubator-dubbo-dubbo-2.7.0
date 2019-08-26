/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.config.spring.beans.factory.annotation;

import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.config.AbstractConfig;
import org.apache.dubbo.config.spring.context.annotation.DubboConfigBindingRegistrar;
import org.apache.dubbo.config.spring.context.annotation.EnableDubboConfigBinding;
import org.apache.dubbo.config.spring.context.properties.DefaultDubboConfigBinder;
import org.apache.dubbo.config.spring.context.properties.DubboConfigBinder;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.core.env.Environment;

/**
 * Dubbo Config Binding {@link BeanPostProcessor}
 * 主要是对配置属性的绑定到不同的DubboConfigBindingBeanPostProcessor类型的Bean中（在dubbo的配置类初始化完成后会执行响应的方法。用来将属性值设置到对应的配置里面去）
 * @see EnableDubboConfigBinding
 * @see DubboConfigBindingRegistrar
 * @since 2.5.8
 */

public class DubboConfigBindingBeanPostProcessor implements BeanPostProcessor, ApplicationContextAware, InitializingBean {

    private final Log log = LogFactory.getLog(getClass());

    /**
     * The prefix of Configuration Properties
     */
    private final String prefix;

    /**
     * Binding Bean Name
     */
    private final String beanName;

    private DubboConfigBinder dubboConfigBinder;

    private ApplicationContext applicationContext;

    private boolean ignoreUnknownFields = true;

    private boolean ignoreInvalidFields = true;

    /**
     * @param prefix   the prefix of Configuration Properties
     * @param beanName the binding Bean Name
     */
    public DubboConfigBindingBeanPostProcessor(String prefix, String beanName) {
        Assert.notNull(prefix, "The prefix of Configuration Properties must not be null");
        Assert.notNull(beanName, "The name of bean must not be null");
        this.prefix = prefix;
        this.beanName = beanName;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        //如果传入的beanName是构造创建时候传入的beanName(前面讲到过这个Bean注册时候会传入BeanName)，并且Bean是AbstractConfig类型
        if (beanName.equals(this.beanName) && bean instanceof AbstractConfig) {
            //转化为AbstractConfig
            AbstractConfig dubboConfig = (AbstractConfig) bean;
            //进行绑定
            dubboConfigBinder.bind(prefix, dubboConfig);

            if (log.isInfoEnabled()) {
                log.info("The properties of bean [name : " + beanName + "] have been binding by prefix of " +
                        "configuration properties : " + prefix);
            }
        }

        return bean;

    }

    public boolean isIgnoreUnknownFields() {
        return ignoreUnknownFields;
    }

    public void setIgnoreUnknownFields(boolean ignoreUnknownFields) {
        this.ignoreUnknownFields = ignoreUnknownFields;
    }

    public boolean isIgnoreInvalidFields() {
        return ignoreInvalidFields;
    }

    public void setIgnoreInvalidFields(boolean ignoreInvalidFields) {
        this.ignoreInvalidFields = ignoreInvalidFields;
    }

    public DubboConfigBinder getDubboConfigBinder() {
        return dubboConfigBinder;
    }

    public void setDubboConfigBinder(DubboConfigBinder dubboConfigBinder) {
        this.dubboConfigBinder = dubboConfigBinder;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        return bean;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    @Override
    public void afterPropertiesSet() throws Exception {

        if (dubboConfigBinder == null) {
            try {//创建DubboConfigBinder类型的bean，每次调用都会创建一个，这个保存在上面的DubboConfigBindingBeanPostProcessor这个Bean中
                dubboConfigBinder = applicationContext.getBean(DubboConfigBinder.class);
            } catch (BeansException ignored) {
                if (log.isDebugEnabled()) {
                    log.debug("DubboConfigBinder Bean can't be found in ApplicationContext.");
                }
                // Use Default implementation
                dubboConfigBinder = createDubboConfigBinder(applicationContext.getEnvironment());
            }
        }
        //再次设置忽略无效跟非法字段
        dubboConfigBinder.setIgnoreUnknownFields(ignoreUnknownFields);
        dubboConfigBinder.setIgnoreInvalidFields(ignoreInvalidFields);

    }

    /**
     * Create {@link DubboConfigBinder} instance.
     *
     * @param environment
     * @return {@link DefaultDubboConfigBinder}
     */
    protected DubboConfigBinder createDubboConfigBinder(Environment environment) {
        DefaultDubboConfigBinder defaultDubboConfigBinder = new DefaultDubboConfigBinder();
        defaultDubboConfigBinder.setEnvironment(environment);
        return defaultDubboConfigBinder;
    }

}
