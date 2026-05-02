import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.eclipse.emf.common.util.BasicEList;
import org.eclipse.emf.common.util.ECollections;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.InternalEObject;
import org.omg.sysml.lang.sysml.Feature;
import org.omg.sysml.lang.sysml.FeatureChaining;

public class SysMLVizServerFeatureChainSanitizerHarness {

    public static void main(String[] args) {
        BasicEList<FeatureChaining> chainings = new BasicEList<>();
        chainings.add(makeFeatureChaining(null));
        Feature validFeature = makeFeature(new BasicEList<>());
        FeatureChaining validChaining = makeFeatureChaining(validFeature);
        chainings.add(validChaining);

        Feature owner = makeFeature(chainings);
        int removed = SysMLVizServer.sanitizeBrokenFeatureChainings(owner);

        if (removed != 1) {
            throw new AssertionError("Expected one broken feature chaining to be removed, got " + removed);
        }
        if (chainings.size() != 1) {
            throw new AssertionError("Expected one remaining feature chaining, got " + chainings.size());
        }
        if (chainings.get(0) != validChaining) {
            throw new AssertionError("Sanitizer removed the wrong feature chaining");
        }
    }

    private static Feature makeFeature(BasicEList<FeatureChaining> chainings) {
        InvocationHandler handler = new DefaultHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("getOwnedFeatureChaining".equals(name)) {
                    return chainings;
                }
                if ("eAllContents".equals(name)) {
                    return emptyTreeIterator();
                }
                if ("eContents".equals(name)) {
                    return ECollections.emptyEList();
                }
                return super.invoke(proxy, method, args);
            }
        };
        return (Feature) Proxy.newProxyInstance(
            SysMLVizServerFeatureChainSanitizerHarness.class.getClassLoader(),
            new Class<?>[] { Feature.class },
            handler
        );
    }

    private static FeatureChaining makeFeatureChaining(Feature chainingFeature) {
        InvocationHandler handler = new DefaultHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("getChainingFeature".equals(method.getName())) {
                    return chainingFeature;
                }
                return super.invoke(proxy, method, args);
            }
        };
        return (FeatureChaining) Proxy.newProxyInstance(
            SysMLVizServerFeatureChainSanitizerHarness.class.getClassLoader(),
            new Class<?>[] { FeatureChaining.class, InternalEObject.class },
            handler
        );
    }

    private static TreeIterator<EObject> emptyTreeIterator() {
        return new EmptyTreeIterator();
    }

    private abstract static class AbstractEmptyTreeIterator<T> implements TreeIterator<T> {
        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public T next() {
            throw new java.util.NoSuchElementException();
        }

        @Override
        public void remove() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void prune() {
        }
    }

    private static final class EmptyTreeIterator extends AbstractEmptyTreeIterator<EObject> {
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
