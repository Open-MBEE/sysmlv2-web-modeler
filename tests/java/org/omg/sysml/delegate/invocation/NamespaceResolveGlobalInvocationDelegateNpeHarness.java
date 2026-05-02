package org.omg.sysml.delegate.invocation;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.ECollections;
import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EOperation;
import org.eclipse.emf.ecore.InternalEObject;
import org.omg.sysml.lang.sysml.Namespace;

public class NamespaceResolveGlobalInvocationDelegateNpeHarness {

    public static void main(String[] args) throws Exception {
        Namespace_resolveGlobal_InvocationDelegate delegate =
            new Namespace_resolveGlobal_InvocationDelegate((EOperation) null);
        EList<Object> arguments = new BasicEList<>();
        arguments.add("Definitely::Missing::Library::Element");

        Object result = delegate.dynamicInvoke(makeNamespaceProxy(), arguments);
        if (result != null) {
            throw new AssertionError("Expected null for missing library element, got " + result);
        }
    }

    private static InternalEObject makeNamespaceProxy() {
        InvocationHandler handler = new DefaultHandler();
        return (InternalEObject) Proxy.newProxyInstance(
            NamespaceResolveGlobalInvocationDelegateNpeHarness.class.getClassLoader(),
            new Class<?>[] { Namespace.class, InternalEObject.class },
            handler
        );
    }

    private static final class DefaultHandler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if ("equals".equals(name)) {
                return proxy == args[0];
            }
            if ("hashCode".equals(name)) {
                return System.identityHashCode(proxy);
            }
            if ("toString".equals(name)) {
                return "NamespaceProxy";
            }
            Class<?> returnType = method.getReturnType();
            if (EList.class.isAssignableFrom(returnType)) {
                return ECollections.emptyEList();
            }
            if (returnType == boolean.class) {
                return false;
            }
            if (returnType == int.class) {
                return 0;
            }
            return null;
        }
    }
}
