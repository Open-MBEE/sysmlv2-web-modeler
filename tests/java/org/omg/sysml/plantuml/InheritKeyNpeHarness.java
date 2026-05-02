package org.omg.sysml.plantuml;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.eclipse.emf.common.util.ECollections;
import org.eclipse.emf.common.util.EList;
import org.omg.sysml.lang.sysml.Feature;
import org.omg.sysml.lang.sysml.Membership;
import org.omg.sysml.lang.sysml.Type;

public class InheritKeyNpeHarness {

    public static void main(String[] args) throws Exception {
        Method featureBelonging = InheritKey.class.getDeclaredMethod("isBelonging", Type.class, Feature.class);
        featureBelonging.setAccessible(true);
        Method membershipBelonging = InheritKey.class.getDeclaredMethod("isBelonging", Type.class, Membership.class);
        membershipBelonging.setAccessible(true);

        Type type = makeTypeProxy();
        Feature feature = makeProxy(Feature.class);
        Membership membership = makeProxy(Membership.class);

        Object featureResult = featureBelonging.invoke(null, type, feature);
        if (!Boolean.FALSE.equals(featureResult)) {
            throw new AssertionError("Expected false when inherited feature lookup throws NPE");
        }

        Object membershipResult = membershipBelonging.invoke(null, type, membership);
        if (!Boolean.FALSE.equals(membershipResult)) {
            throw new AssertionError("Expected false when inherited membership lookup throws NPE");
        }
    }

    private static Type makeTypeProxy() {
        InvocationHandler handler = new DefaultHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("getInheritedFeature".equals(name) || "getInheritedMembership".equals(name)) {
                    throw new NullPointerException("synthetic failure");
                }
                if ("getOwnedRelationship".equals(name)
                    || "getOwnedMembership".equals(name)
                    || "getOwnedSpecialization".equals(name)) {
                    return ECollections.emptyEList();
                }
                return super.invoke(proxy, method, args);
            }
        };
        return (Type) Proxy.newProxyInstance(
            InheritKeyNpeHarness.class.getClassLoader(),
            new Class<?>[] { Type.class },
            handler
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> T makeProxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
            InheritKeyNpeHarness.class.getClassLoader(),
            new Class<?>[] { type },
            new DefaultHandler()
        );
    }

    private static class DefaultHandler implements InvocationHandler {

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
                return method.getDeclaringClass().getSimpleName() + "Proxy";
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
