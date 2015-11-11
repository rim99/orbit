/*
Copyright (C) 2015 Electronic Arts Inc.  All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions
are met:

1.  Redistributions of source code must retain the above copyright
    notice, this list of conditions and the following disclaimer.
2.  Redistributions in binary form must reproduce the above copyright
    notice, this list of conditions and the following disclaimer in the
    documentation and/or other materials provided with the distribution.
3.  Neither the name of Electronic Arts, Inc. ("EA") nor the names of
    its contributors may be used to endorse or promote products derived
    from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY ELECTRONIC ARTS AND ITS CONTRIBUTORS "AS IS" AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL ELECTRONIC ARTS OR ITS CONTRIBUTORS BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

package com.ea.orbit.actors.runtime;

import com.ea.orbit.actors.Actor;
import com.ea.orbit.actors.ActorObserver;
import com.ea.orbit.actors.Addressable;
import com.ea.orbit.actors.annotation.NoIdentity;
import com.ea.orbit.actors.annotation.OneWay;
import com.ea.orbit.actors.cluster.NodeAddressImpl;
import com.ea.orbit.actors.extensions.ActorClassFinder;
import com.ea.orbit.exception.UncheckedException;
import com.ea.orbit.util.ClassPath;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DefaultReferenceFactory implements ReferenceFactory
{
    private static final Logger logger = LoggerFactory.getLogger(ReferenceFactory.class);
    private static DefaultReferenceFactory instance = new DefaultReferenceFactory();

    private ConcurrentMap<Class<?>, ActorFactory<?>> factories = new ConcurrentHashMap<>();
    private ActorFactoryGenerator dynamicReferenceFactory = new ActorFactoryGenerator();

    private ActorClassFinder finder;
    private ConcurrentMap<Class<?>, InterfaceDescriptor> descriptorMapByInterface = new ConcurrentHashMap<>();
    private ConcurrentMap<Integer, InterfaceDescriptor> descriptorMapByInterfaceId = new ConcurrentHashMap<>();

    static class InterfaceDescriptor
    {
        ActorFactory<?> factory;
        ActorInvoker<Object> invoker;
        boolean isObserver;
        boolean isActor;
    }

    private DefaultReferenceFactory()
    {

    }

    public static ReferenceFactory get()
    {
        return instance;
    }

    @SuppressWarnings("unchecked")
    private <T> Class<T> classForName(final String className, boolean ignoreException)
    {
        try
        {
            return (Class<T>) Class.forName(className);
        }
        catch (Error | Exception ex)
        {
            if (!ignoreException)
            {
                throw new Error("Error loading class: " + className, ex);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private InterfaceDescriptor getDescriptor(final Class<?> aInterface)
    {
        InterfaceDescriptor interfaceDescriptor = descriptorMapByInterface.get(aInterface);
        if (interfaceDescriptor == null)
        {
            if (aInterface == Actor.class || aInterface == ActorObserver.class || !aInterface.isInterface())
            {
                return null;
            }

            interfaceDescriptor = new InterfaceDescriptor();
            interfaceDescriptor.isObserver = ActorObserver.class.isAssignableFrom(aInterface);
            interfaceDescriptor.factory = dynamicReferenceFactory.getFactoryFor(aInterface);
            interfaceDescriptor.invoker = (ActorInvoker<Object>) interfaceDescriptor.factory.getInvoker();

            InterfaceDescriptor concurrentInterfaceDescriptor = descriptorMapByInterface.putIfAbsent(aInterface, interfaceDescriptor);
            if (concurrentInterfaceDescriptor != null)
            {
                descriptorMapByInterfaceId.put(interfaceDescriptor.factory.getInterfaceId(), concurrentInterfaceDescriptor);
                return concurrentInterfaceDescriptor;
            }


            descriptorMapByInterfaceId.put(interfaceDescriptor.factory.getInterfaceId(), interfaceDescriptor);
        }
        return interfaceDescriptor;
    }

    private InterfaceDescriptor getDescriptor(final int interfaceId)
    {
        final InterfaceDescriptor interfaceDescriptor = descriptorMapByInterfaceId.get(interfaceId);
        if (interfaceDescriptor != null)
        {
            return interfaceDescriptor;
        }
        Class clazz = findClassById(interfaceId);
        return clazz != null ? getDescriptor(clazz) : null;
    }

    private Class findClassById(final int interfaceId)
    {
        return null;
    }

    public ActorInvoker<?> getInvoker(Class clazz)
    {
        final InterfaceDescriptor descriptor = getDescriptor(clazz);
        if (descriptor == null)
        {
            return null;
        }
        if (descriptor.invoker == null)
        {
            descriptor.invoker = dynamicReferenceFactory.getInvokerFor(descriptor.factory.getInterface());
        }
        return descriptor.invoker;
    }

    public ActorInvoker<?> getInvoker(final int interfaceId)
    {
        final InterfaceDescriptor descriptor = getDescriptor(interfaceId);
        if (descriptor == null)
        {
            return null;
        }
        if (descriptor.invoker == null)
        {
            descriptor.invoker = dynamicReferenceFactory.getInvokerFor(descriptor.factory.getInterface());
        }
        return descriptor.invoker;
    }


    @Override
    public <T extends Actor> T getReference(final Class<T> iClass, final Object id)
    {
        ActorFactory<T> factory = getFactory(iClass);
        return factory.createReference(String.valueOf(id));
    }

    @Override
    public <T extends ActorObserver> T getObserverReference(final UUID nodeId, final Class<T> iClass, final Object id)
    {
        ActorFactory<T> factory = getFactory(iClass);
        final T reference = factory.createReference(String.valueOf(id));
        ActorReference.setAddress((ActorReference<?>) reference, new NodeAddressImpl(nodeId));
        return reference;
    }

    @SuppressWarnings("unchecked")
    private <T> ActorFactory<T> getFactory(final Class<T> iClass)
    {
        ActorFactory<T> factory = (ActorFactory<T>) factories.get(iClass);
        if (factory == null)
        {
            if (!iClass.isInterface())
            {
                throw new IllegalArgumentException("Expecting an interface, but got: " + iClass.getName());
            }
            try
            {
                String factoryClazz = iClass.getSimpleName() + "Factory";
                if (factoryClazz.charAt(0) == 'I')
                {
                    factoryClazz = factoryClazz.substring(1); // remove leading 'I'
                }
                factory = (ActorFactory<T>) Class.forName(ClassPath.getNullSafePackageName(iClass) + "." + factoryClazz).newInstance();
            }
            catch (Exception e)
            {
                if (dynamicReferenceFactory == null)
                {
                    dynamicReferenceFactory = new ActorFactoryGenerator();
                }
                factory = dynamicReferenceFactory.getFactoryFor(iClass);
            }

            factories.put(iClass, factory);
        }
        return factory;
    }

    public static <T extends Actor> T ref(int interfaceId, String id)
    {
        final Class classById = instance.findClassById(interfaceId);
        if (classById == null)
        {
            throw new UncheckedException("Class not found, id: " + interfaceId);
        }
        return (T) ref(classById, id);
    }

    public static <T extends Actor> T ref(Class<T> actorInterface, String id)
    {
        if (id != null)
        {
            if (actorInterface.isAnnotationPresent(NoIdentity.class))
            {
                throw new IllegalArgumentException("Shouldn't supply ids for Actors annotated with " + NoIdentity.class);
            }
        }
        else if (!actorInterface.isAnnotationPresent(NoIdentity.class))
        {
            throw new IllegalArgumentException("Not annotated with " + NoIdentity.class);
        }
        return instance.getReference(actorInterface, id);
    }

    public static <T extends ActorObserver> T observerRef(UUID nodeId, Class<T> actorObserverInterface, String id)
    {
        return instance.getObserverReference(nodeId, actorObserverInterface, id);
    }


    @SuppressWarnings("unchecked")
    public static <T> T cast(Class<T> remoteInterface, Actor actor)
    {
        return (T) Proxy.newProxyInstance(DefaultReferenceFactory.class.getClassLoader(), new Class[]{ remoteInterface },
                (proxy, method, args) -> {
                    // TODO: throw proper exceptions for the expected error scenarios (non task return),
                    final int methodId = instance.dynamicReferenceFactory.getMethodId(method);
                    return ActorRuntime.getRuntime()
                            .invoke((Addressable) actor, method, method.isAnnotationPresent(OneWay.class), methodId, args);
                });

    }
}
