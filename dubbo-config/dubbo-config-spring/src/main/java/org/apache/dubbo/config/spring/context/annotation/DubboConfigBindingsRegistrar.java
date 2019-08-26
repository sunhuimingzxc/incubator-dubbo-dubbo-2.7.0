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
package org.apache.dubbo.config.spring.context.annotation;

import org.apache.dubbo.config.AbstractConfig;

import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.util.Assert;

/**
 * {@link AbstractConfig Dubbo Config} binding Bean registrar for {@link EnableDubboConfigBindings}
 *
 * @see EnableDubboConfigBindings
 * @see DubboConfigBindingRegistrar
 * @since 2.5.8
 */
public class DubboConfigBindingsRegistrar implements ImportBeanDefinitionRegistrar, EnvironmentAware {

    private ConfigurableEnvironment environment;

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        //从importingClassMetadata对象中获取 DubboConfigConfiguration.Multiple类上注解EnableDubboConfigBindings标签的内容
        AnnotationAttributes attributes = AnnotationAttributes.fromMap(
                importingClassMetadata.getAnnotationAttributes(EnableDubboConfigBindings.class.getName()));
        //从获取的map集合中找到value属性，此时value是一个EnableDubboConfigBinding标签的集合
        AnnotationAttributes[] annotationAttributes = attributes.getAnnotationArray("value");
        //创建DubboConfigBindingRegistrar类，这个类在后面会讲到，作用是注册EnableDubboConfigBinding标签中的Bean
        DubboConfigBindingRegistrar registrar = new DubboConfigBindingRegistrar();
        registrar.setEnvironment(environment);
        //遍历DubboConfigBindingRegistrar标签，并进行注册。
        for (AnnotationAttributes element : annotationAttributes) {

            registrar.registerBeanDefinitions(element, registry);

        }
    }

    @Override
    public void setEnvironment(Environment environment) {

        Assert.isInstanceOf(ConfigurableEnvironment.class, environment);

        this.environment = (ConfigurableEnvironment) environment;

    }

}
