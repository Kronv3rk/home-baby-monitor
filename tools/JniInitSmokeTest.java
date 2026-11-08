import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/** Regression for the class and signature required by WebRTC M150 JNI_OnLoad. */
public final class JniInitSmokeTest {
    public static void main(String[] args) throws Exception {
        Class<?> bridge = Class.forName("org.jni_zero.JniInit");
        Method init = bridge.getDeclaredMethod("init");
        init.setAccessible(true);
        Object[] values = (Object[]) init.invoke(null);
        if (values.length != 2 || !(values[0] instanceof List) || !(values[1] instanceof Map)) {
            throw new AssertionError("JNI initialization must supply a List and a Map");
        }
        if (!((List<?>) values[0]).isEmpty() || !((Map<?, ?>) values[1]).isEmpty()) {
            throw new AssertionError("JNI initialization collections must be empty");
        }
        System.out.println("WebRTC JniInit contract OK");
    }
}
