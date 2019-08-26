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
package org.apache.dubbo.config.spring.context.properties;

import org.apache.dubbo.config.AbstractConfig;

import org.springframework.beans.MutablePropertyValues;
import org.springframework.validation.DataBinder;

import java.util.Map;

import static org.apache.dubbo.config.spring.util.PropertySourcesUtils.getSubProperties;

/**
 * Default {@link DubboConfigBinder} implementation based on Spring {@link DataBinder}
 */
public class DefaultDubboConfigBinder extends AbstractDubboConfigBinder {

    @Override
    public <C extends AbstractConfig> void bind(String prefix, C dubboConfig) {
        //创建DataBinder对象，这个对象可以对指定字段进行校验
        DataBinder dataBinder = new DataBinder(dubboConfig);
        // Set ignored* 设置是否忽略的无效和非法字段
        dataBinder.setIgnoreInvalidFields(isIgnoreInvalidFields());
        dataBinder.setIgnoreUnknownFields(isIgnoreUnknownFields());
        // Get properties under specified prefix from PropertySources 从 PropertySources也就是配置中获取执行前缀的属性字段
        Map<String, Object> properties = getSubProperties(getPropertySources(), prefix);
        // Convert Map to MutablePropertyValues 将Map转换为MutablePropertyValues
        MutablePropertyValues propertyValues = new MutablePropertyValues(properties);
        // Bind 将配置参数进行绑定
        dataBinder.bind(propertyValues);
    }

}

