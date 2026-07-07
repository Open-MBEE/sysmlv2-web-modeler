import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import org.eclipse.emf.ecore.EClass;
import org.omg.sysml.lang.sysml.Element;

public class SysMLVizServerDerivedNameNpeHarness {

    public static void main(String[] args) {
        EClass eClass = (EClass) Proxy.newProxyInstance(
            SysMLVizServerDerivedNameNpeHarness.class.getClassLoader(),
            new Class<?>[] {EClass.class},
            (proxy, method, methodArgs) -> {
                if ("getName".equals(method.getName())) {
                    return "Package";
                }
                if (method.getReturnType().isPrimitive()) {
                    return primitiveDefault(method.getReturnType());
                }
                return null;
            }
        );

        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] methodArgs) {
                String name = method.getName();
                if ("getQualifiedName".equals(name) || "getName".equals(name)) {
                    throw new NullPointerException("synthetic instantiatedType failure");
                }
                if ("getDeclaredName".equals(name)) {
                    return "";
                }
                if ("getElementId".equals(name)) {
                    return "el-1";
                }
                if ("eClass".equals(name)) {
                    return eClass;
                }
                if ("toString".equals(name)) {
                    return "SyntheticElement";
                }
                if (method.getReturnType().isPrimitive()) {
                    return primitiveDefault(method.getReturnType());
                }
                return null;
            }
        };

        Element element = (Element) Proxy.newProxyInstance(
            SysMLVizServerDerivedNameNpeHarness.class.getClassLoader(),
            new Class<?>[] {Element.class},
            handler
        );

        String qualified = SysMLVizServer.safeQualifiedName(element);
        String simpleName = SysMLVizServer.safeName(element);
        String display = SysMLVizServer.safeDisplayName(element);

        if (!qualified.isBlank()) {
            throw new AssertionError("Expected blank qualified name fallback");
        }
        if (!simpleName.isBlank()) {
            throw new AssertionError("Expected blank name fallback");
        }
        if (!"el-1".equals(display)) {
            throw new AssertionError("Expected element id fallback, got: " + display);
        }
    }

    private static Object primitiveDefault(Class<?> type) {
        if (boolean.class.equals(type)) return false;
        if (char.class.equals(type)) return '\0';
        if (byte.class.equals(type)) return (byte) 0;
        if (short.class.equals(type)) return (short) 0;
        if (int.class.equals(type)) return 0;
        if (long.class.equals(type)) return 0L;
        if (float.class.equals(type)) return 0f;
        if (double.class.equals(type)) return 0d;
        return null;
    }
}
