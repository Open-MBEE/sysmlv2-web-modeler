import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.xtext.serializer.ISerializer;
import org.omg.sysml.interactive.SysMLInteractive;
import org.omg.sysml.interactive.SysMLInteractiveResult;
import org.omg.sysml.lang.sysml.Element;

public class SysMLVizServerSerializeFallbackHarness {

    public static void main(String[] args) throws Exception {
        SysMLInteractive sysml = SysMLInteractive.createInstance();
        SysMLInteractiveResult result = sysml.process(
            """
            package pkgA {
              package pkgB;
            }
            """,
            false
        );

        if (result.getException() != null || result.hasErrors()) {
            throw new AssertionError("Expected parser success for serializer fallback harness");
        }

        Element pkgA = SysMLVizServer.resolveTopLevelLoadedElement(sysml);
        if (pkgA == null) {
            throw new AssertionError("Expected to resolve pkgA");
        }

        EObject detached = EcoreUtil.copy(pkgA);
        Field serializerField = SysMLVizServer.class.getDeclaredField("SYSML_SERIALIZER");
        serializerField.setAccessible(true);
        Object previous = serializerField.get(null);
        serializerField.set(null, stackOverflowSerializer());
        try {
            String text = SysMLVizServer.serializeElementText(detached);
            if (!text.contains("Fallback SysML text generated from repository-loaded model because the Xtext serializer failed on the repository-loaded object")) {
                throw new AssertionError("Expected serializer fallback banner, got: " + text);
            }
            if (!text.contains("package pkgA")) {
                throw new AssertionError("Expected fallback output to contain package pkgA, got: " + text);
            }
        } finally {
            serializerField.set(null, previous);
        }
    }

    private static ISerializer stackOverflowSerializer() {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                if ("serialize".equals(method.getName())) {
                    throw new StackOverflowError("forced by test");
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
        };
        return (ISerializer) Proxy.newProxyInstance(
            SysMLVizServerSerializeFallbackHarness.class.getClassLoader(),
            new Class<?>[] { ISerializer.class },
            handler
        );
    }
}
