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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyValues;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.InjectionMetadata;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.config.InstantiationAwareBeanPostProcessorAdapter;
import org.springframework.beans.factory.support.MergedBeanDefinitionPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.Environment;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.apache.dubbo.config.spring.util.ClassUtils.resolveGenericType;
import static org.springframework.core.BridgeMethodResolver.findBridgedMethod;
import static org.springframework.core.BridgeMethodResolver.isVisibilityBridgeMethodPair;
import static org.springframework.core.annotation.AnnotationUtils.findAnnotation;
import static org.springframework.core.annotation.AnnotationUtils.getAnnotation;

/**
 * Abstract generic {@link BeanPostProcessor} implementation for customized annotation that annotated injected-object.
 *
 * The source code is cloned from https://github.com/alibaba/spring-context-support/blob/1.0.2/src/main/java/com/alibaba/spring/beans/factory/annotation/AnnotationInjectedBeanPostProcessor.java
 *
 * @since 2.6.6
 */
public abstract class AnnotationInjectedBeanPostProcessor<A extends Annotation> extends
        InstantiationAwareBeanPostProcessorAdapter implements MergedBeanDefinitionPostProcessor, PriorityOrdered,
        BeanFactoryAware, BeanClassLoaderAware, EnvironmentAware, DisposableBean {

    private final static int CACHE_SIZE = Integer.getInteger("", 32);

    private final Log logger = LogFactory.getLog(getClass());

    private final Class<A> annotationType;

    private final ConcurrentMap<String, AnnotationInjectedBeanPostProcessor.AnnotatedInjectionMetadata> injectionMetadataCache =
            new ConcurrentHashMap<String, AnnotationInjectedBeanPostProcessor.AnnotatedInjectionMetadata>(CACHE_SIZE);

    private final ConcurrentMap<String, Object> injectedObjectsCache = new ConcurrentHashMap<String, Object>(CACHE_SIZE);

    private ConfigurableListableBeanFactory beanFactory;

    private Environment environment;

    private ClassLoader classLoader;

    private int order = Ordered.LOWEST_PRECEDENCE;

    public AnnotationInjectedBeanPostProcessor() {
        this.annotationType = resolveGenericType(getClass());
    }

    private static <T> Collection<T> combine(Collection<? extends T>... elements) {
        List<T> allElements = new ArrayList<T>();
        for (Collection<? extends T> e : elements) {
            allElements.addAll(e);
        }
        return allElements;
    }

    /**
     * Annotation type
     *
     * @return non-null
     */
    public final Class<A> getAnnotationType() {
        return annotationType;
    }

    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        Assert.isInstanceOf(ConfigurableListableBeanFactory.class, beanFactory,
                "AnnotationInjectedBeanPostProcessor requires a ConfigurableListableBeanFactory");
        this.beanFactory = (ConfigurableListableBeanFactory) beanFactory;
    }
    /**
     * 这个方法是InstantiationAwareBeanPostProcessor接口中定义的，作用是对实例化之后属性设置之前Bean 的属性值进行修改。
     * 调用这个方法的前提是postProcessAfterInstantiation方法返回true
     * postProcessPropertyValues方法主要是对Bean中的属性的获取，其中InjectionMetadata对象是spring中用于管理注入元数据的内部类
     */
    @Override
    public PropertyValues postProcessPropertyValues(
            PropertyValues pvs, PropertyDescriptor[] pds, Object bean, String beanName) throws BeanCreationException {
        //获取注入用的元数据
        InjectionMetadata metadata = findInjectionMetadata(beanName, bean.getClass(), pvs);
        try {
            metadata.inject(bean, beanName, pvs);//进行注入
        } catch (BeanCreationException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new BeanCreationException(beanName, "Injection of @" + getAnnotationType().getName()
                    + " dependencies is failed", ex);
        }
        return pvs;
    }


    /**
     * Finds {@link InjectionMetadata.InjectedElement} Metadata from annotated {@link A} fields
     *
     * @param beanClass The {@link Class} of Bean
     * @return non-null {@link List}
     */
    private List<AnnotationInjectedBeanPostProcessor.AnnotatedFieldElement> findFieldAnnotationMetadata(final Class<?> beanClass) {

        final List<AnnotationInjectedBeanPostProcessor.AnnotatedFieldElement> elements = new LinkedList<AnnotationInjectedBeanPostProcessor.AnnotatedFieldElement>();
        // 通过反射的工具类，获取当前beanClass中的所有Field，然后通过实现ReflectionUtils类的内部类FieldCallback的实现，来对字段进行处理
        ReflectionUtils.doWithFields(beanClass, new ReflectionUtils.FieldCallback() {
            @Override
            public void doWith(Field field) throws IllegalArgumentException, IllegalAccessException {
                //获取field里面的Reference标签
                A annotation = getAnnotation(field, getAnnotationType());
                //检查标签是不是null
                if (annotation != null) {
                    //field不是static类型的
                    if (Modifier.isStatic(field.getModifiers())) {
                        if (logger.isWarnEnabled()) {
                            logger.warn("@" + getAnnotationType().getName() + " is not supported on static fields: " + field);
                        }
                        return;
                    }
                    //创建AnnotatedFieldElement并放到集合中
                    elements.add(new AnnotationInjectedBeanPostProcessor.AnnotatedFieldElement(field, annotation));
                }

            }
        });

        return elements;

    }

    /**
     * Finds {@link InjectionMetadata.InjectedElement} Metadata from annotated {@link A @A} methods
     *
     * @param beanClass The {@link Class} of Bean
     * @return non-null {@link List}
     */
    private List<AnnotationInjectedBeanPostProcessor.AnnotatedMethodElement> findAnnotatedMethodMetadata(final Class<?> beanClass) {

        final List<AnnotationInjectedBeanPostProcessor.AnnotatedMethodElement> elements = new LinkedList<AnnotationInjectedBeanPostProcessor.AnnotatedMethodElement>();
        //获取到beanClass中所有的Method，然后调用实现的ReflectionUtils.MethodCallback类的doWith方法
        ReflectionUtils.doWithMethods(beanClass, new ReflectionUtils.MethodCallback() {
            @Override
            public void doWith(Method method) throws IllegalArgumentException, IllegalAccessException {
                //寻找method的桥接方法，关于桥接方法这是在Java1.5引入泛型之后，编译器为了向前兼容，生成这个的
                Method bridgedMethod = findBridgedMethod(method);
                //检查是不是可见的桥接方法
                if (!isVisibilityBridgeMethodPair(method, bridgedMethod)) {
                    return;
                }
                //找到方法上的Reference标签
                A annotation = findAnnotation(bridgedMethod, getAnnotationType());
                //如果标签不是bull并且找到beanClass中的具体的方法
                if (annotation != null && method.equals(ClassUtils.getMostSpecificMethod(method, beanClass))) {
                    if (Modifier.isStatic(method.getModifiers())) {//方法不能是静态的
                        if (logger.isWarnEnabled()) {
                            logger.warn("@" + getAnnotationType().getSimpleName() + " annotation is not supported on static methods: " + method);
                        }
                        return;
                    }
                    if (method.getParameterTypes().length == 0) {//需要有参数
                        if (logger.isWarnEnabled()) {
                            logger.warn("@" + getAnnotationType().getSimpleName() + " annotation should only be used on methods with parameters: " +
                                    method);
                        }
                    }
                    PropertyDescriptor pd = BeanUtils.findPropertyForMethod(bridgedMethod, beanClass);//获取beanClass中指定方法的属性
                    elements.add(new AnnotationInjectedBeanPostProcessor.AnnotatedMethodElement(method, pd, annotation));
                }
            }
        });

        return elements;

    }
    //获取AnnotationInjectedBeanPostProcessor.AnnotatedFieldElement
    //获取AnnotationInjectedBeanPostProcessor.AnnotatedMethodElement
    //上面的两个集合中的对象都是spring中InjectionMetadata类的内部类InjectedElement的继承类用来保存单个元素
    private AnnotationInjectedBeanPostProcessor.AnnotatedInjectionMetadata buildAnnotatedMetadata(final Class<?> beanClass) {
        Collection<AnnotationInjectedBeanPostProcessor.AnnotatedFieldElement> fieldElements = findFieldAnnotationMetadata(beanClass);
        Collection<AnnotationInjectedBeanPostProcessor.AnnotatedMethodElement> methodElements = findAnnotatedMethodMetadata(beanClass);
        return new AnnotationInjectedBeanPostProcessor.AnnotatedInjectionMetadata(beanClass, fieldElements, methodElements);

    }
    //先检查缓存和比较Class对象判断是不是同一个，如果缓存为空或者缓存的Class对象不是传入的Class对象，就需要先进行缓存的清空，然后创建InjectionMetadata进行保存
    private InjectionMetadata findInjectionMetadata(String beanName, Class<?> clazz, PropertyValues pvs) {
        // Fall back to class name as cache key, for backwards compatibility with custom callers.
        String cacheKey = (StringUtils.hasLength(beanName) ? beanName : clazz.getName());//生成缓存的cacheKey
        // Quick check on the concurrent map first, with minimal locking.//检查对应的用于诸如用的元数据是否存在缓存
        AnnotationInjectedBeanPostProcessor.AnnotatedInjectionMetadata metadata = this.injectionMetadataCache.get(cacheKey);
        if (InjectionMetadata.needsRefresh(metadata, clazz)) {//双重检查锁
            synchronized (this.injectionMetadataCache) {
                metadata = this.injectionMetadataCache.get(cacheKey);
                if (InjectionMetadata.needsRefresh(metadata, clazz)) {
                    if (metadata != null) {//如果元数据不是null就对其中的属性进行清空
                        metadata.clear(pvs);
                    }
                    try {//创建元数据
                        metadata = buildAnnotatedMetadata(clazz);
                        this.injectionMetadataCache.put(cacheKey, metadata);//放到缓存中进行保存
                    } catch (NoClassDefFoundError err) {
                        throw new IllegalStateException("Failed to introspect object class [" + clazz.getName() +
                                "] for annotation metadata: could not find class that it depends on", err);
                    }
                }
            }
        }
        return metadata;
    }

    @Override
    public void postProcessMergedBeanDefinition(RootBeanDefinition beanDefinition, Class<?> beanType, String beanName) {
        if (beanType != null) {
            InjectionMetadata metadata = findInjectionMetadata(beanName, beanType, null);
            metadata.checkConfigMembers(beanDefinition);
        }
    }

    @Override
    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    @Override
    public void destroy() throws Exception {

        for (Object object : injectedObjectsCache.values()) {
            if (logger.isInfoEnabled()) {
                logger.info(object + " was destroying!");
            }

            if (object instanceof DisposableBean) {
                ((DisposableBean) object).destroy();
            }
        }

        injectionMetadataCache.clear();
        injectedObjectsCache.clear();

        if (logger.isInfoEnabled()) {
            logger.info(getClass() + " was destroying!");
        }

    }

    @Override
    public void setBeanClassLoader(ClassLoader classLoader) {
        this.classLoader = classLoader;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    protected Environment getEnvironment() {
        return environment;
    }

    protected ClassLoader getClassLoader() {
        return classLoader;
    }

    protected ConfigurableListableBeanFactory getBeanFactory() {
        return beanFactory;
    }

    /**
     * Gets all injected-objects.
     *
     * @return non-null {@link Collection}
     */
    protected Collection<Object> getInjectedObjects() {
        return this.injectedObjectsCache.values();
    }

    /**
     * Get injected-object from specified {@link A annotation} and Bean Class
     *
     * @param annotation      {@link A annotation}
     * @param bean            Current bean that will be injected
     * @param beanName        Current bean name that will be injected
     * @param injectedType    the type of injected-object
     * @param injectedElement {@link InjectionMetadata.InjectedElement}
     * @return An injected object
     * @throws Exception If getting is failed
     */
    protected Object getInjectedObject(A annotation, Object bean, String beanName, Class<?> injectedType,
                                       InjectionMetadata.InjectedElement injectedElement) throws Exception {
        //构建缓存对的key
        String cacheKey = buildInjectedObjectCacheKey(annotation, bean, beanName, injectedType, injectedElement);
        //先查询缓存
        Object injectedObject = injectedObjectsCache.get(cacheKey);
        //缓存为null的处理
        if (injectedObject == null) {
            injectedObject = doGetInjectedBean(annotation, bean, beanName, injectedType, injectedElement);//创建注入之后的对象
            // Customized inject-object if necessary
            injectedObjectsCache.putIfAbsent(cacheKey, injectedObject);
        }

        return injectedObject;

    }

    /**
     * Subclass must implement this method to get injected-object. The context objects could help this method if
     * necessary :
     * <ul>
     * <li>{@link #getBeanFactory() BeanFactory}</li>
     * <li>{@link #getClassLoader() ClassLoader}</li>
     * <li>{@link #getEnvironment() Environment}</li>
     * </ul>
     *
     * @param annotation      {@link A annotation}
     * @param bean            Current bean that will be injected
     * @param beanName        Current bean name that will be injected
     * @param injectedType    the type of injected-object
     * @param injectedElement {@link InjectionMetadata.InjectedElement}
     * @return The injected object
     * @throws Exception If resolving an injected object is failed.
     */
    protected abstract Object doGetInjectedBean(A annotation, Object bean, String beanName, Class<?> injectedType,
                                                InjectionMetadata.InjectedElement injectedElement) throws Exception;

    /**
     * Build a cache key for injected-object. The context objects could help this method if
     * necessary :
     * <ul>
     * <li>{@link #getBeanFactory() BeanFactory}</li>
     * <li>{@link #getClassLoader() ClassLoader}</li>
     * <li>{@link #getEnvironment() Environment}</li>
     * </ul>
     *
     * @param annotation      {@link A annotation}
     * @param bean            Current bean that will be injected
     * @param beanName        Current bean name that will be injected
     * @param injectedType    the type of injected-object
     * @param injectedElement {@link InjectionMetadata.InjectedElement}
     * @return Bean cache key
     */
    protected abstract String buildInjectedObjectCacheKey(A annotation, Object bean, String beanName,
                                                          Class<?> injectedType,
                                                          InjectionMetadata.InjectedElement injectedElement);

    /**
     * Get {@link Map} in injected field.
     *
     * @return non-null ready-only {@link Map}
     */
    protected Map<InjectionMetadata.InjectedElement, Object> getInjectedFieldObjectsMap() {

        Map<InjectionMetadata.InjectedElement, Object> injectedElementBeanMap =
                new LinkedHashMap<InjectionMetadata.InjectedElement, Object>();

        for (AnnotationInjectedBeanPostProcessor.AnnotatedInjectionMetadata metadata : injectionMetadataCache.values()) {

            Collection<AnnotationInjectedBeanPostProcessor.AnnotatedFieldElement> fieldElements = metadata.getFieldElements();

            for (AnnotationInjectedBeanPostProcessor.AnnotatedFieldElement fieldElement : fieldElements) {

                injectedElementBeanMap.put(fieldElement, fieldElement.bean);

            }

        }

        return Collections.unmodifiableMap(injectedElementBeanMap);

    }

    /**
     * Get {@link Map} in injected method.
     *
     * @return non-null {@link Map}
     */
    protected Map<InjectionMetadata.InjectedElement, Object> getInjectedMethodObjectsMap() {

        Map<InjectionMetadata.InjectedElement, Object> injectedElementBeanMap =
                new LinkedHashMap<InjectionMetadata.InjectedElement, Object>();

        for (AnnotationInjectedBeanPostProcessor.AnnotatedInjectionMetadata metadata : injectionMetadataCache.values()) {

            Collection<AnnotationInjectedBeanPostProcessor.AnnotatedMethodElement> methodElements = metadata.getMethodElements();

            for (AnnotationInjectedBeanPostProcessor.AnnotatedMethodElement methodElement : methodElements) {

                injectedElementBeanMap.put(methodElement, methodElement.object);

            }

        }

        return Collections.unmodifiableMap(injectedElementBeanMap);

    }

    /**
     * {@link A} {@link InjectionMetadata} implementation
     */
    private class AnnotatedInjectionMetadata extends InjectionMetadata {

        private final Collection<AnnotationInjectedBeanPostProcessor.AnnotatedFieldElement> fieldElements;

        private final Collection<AnnotationInjectedBeanPostProcessor.AnnotatedMethodElement> methodElements;

        public AnnotatedInjectionMetadata(Class<?> targetClass, Collection<AnnotationInjectedBeanPostProcessor.AnnotatedFieldElement> fieldElements,
                                          Collection<AnnotationInjectedBeanPostProcessor.AnnotatedMethodElement> methodElements) {
            super(targetClass, combine(fieldElements, methodElements));
            this.fieldElements = fieldElements;
            this.methodElements = methodElements;
        }

        public Collection<AnnotationInjectedBeanPostProcessor.AnnotatedFieldElement> getFieldElements() {
            return fieldElements;
        }

        public Collection<AnnotationInjectedBeanPostProcessor.AnnotatedMethodElement> getMethodElements() {
            return methodElements;
        }
    }

    /**
     * {@link A} {@link Method} {@link InjectionMetadata.InjectedElement}
     */
    private class AnnotatedMethodElement extends InjectionMetadata.InjectedElement {

        private final Method method;

        private final A annotation;

        private volatile Object object;

        protected AnnotatedMethodElement(Method method, PropertyDescriptor pd, A annotation) {
            super(method, pd);
            this.method = method;
            this.annotation = annotation;
        }

        @Override
        protected void inject(Object bean, String beanName, PropertyValues pvs) throws Throwable {

            Class<?> injectedType = pd.getPropertyType();

            Object injectedObject = getInjectedObject(annotation, bean, beanName, injectedType, this);

            ReflectionUtils.makeAccessible(method);

            method.invoke(bean, injectedObject);

        }

    }

    /**
     * {@link A} {@link Field} {@link InjectionMetadata.InjectedElement}
     */
    public class AnnotatedFieldElement extends InjectionMetadata.InjectedElement {

        private final Field field;

        private final A annotation;

        private volatile Object bean;

        protected AnnotatedFieldElement(Field field, A annotation) {
            super(field, null);
            this.field = field;
            this.annotation = annotation;
        }

        @Override
        protected void inject(Object bean, String beanName, PropertyValues pvs) throws Throwable {
            //获取类型
            Class<?> injectedType = field.getType();
            //获取注入之后的对象
            Object injectedObject = getInjectedObject(annotation, bean, beanName, injectedType, this);
            //如果是私有字段先设置makeAccessible属性
            ReflectionUtils.makeAccessible(field);
            //给Bean对象设置诸如之后的对象
            field.set(bean, injectedObject);

        }

    }
}
