package com.example.grpc.simple;
import java.lang.reflect.*;
import java.util.Objects;
public final class GrpcApiProxyFactory {
 private GrpcApiProxyFactory(){}
 public static <A,C> A create(Class<A> apiType,String serviceName,GrpcClientManager manager,GrpcClientFactory<C> factory){Objects.requireNonNull(apiType);Objects.requireNonNull(serviceName);Objects.requireNonNull(manager);Objects.requireNonNull(factory);InvocationHandler h=(proxy,method,args)->{if(method.getDeclaringClass()==Object.class)return method.invoke(proxy,args);return manager.execute(serviceName,factory,client->invoke(client,method,args));};return apiType.cast(Proxy.newProxyInstance(apiType.getClassLoader(),new Class<?>[]{apiType},h));}
 private static Object invoke(Object client,Method method,Object[] args){try{return method.invoke(client,args);}catch(InvocationTargetException e){Throwable c=e.getCause();if(c instanceof RuntimeException r)throw r;if(c instanceof Error er)throw er;throw new IllegalStateException("gRPC client method failed",c);}catch(IllegalAccessException e){throw new IllegalStateException("Cannot invoke gRPC client method",e);}}
}