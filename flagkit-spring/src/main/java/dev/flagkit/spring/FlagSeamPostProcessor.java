package dev.flagkit.spring;

import dev.flagkit.Evaluator;
import dev.flagkit.FlagDeclaration;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.framework.autoproxy.AutoProxyUtils;
import org.springframework.aop.framework.AbstractAdvisingBeanPostProcessor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Proxies beans that have {@link Flagged} methods and checks every such method at startup.
 * A bean is validated as it is created, so a bad seam stops the application before it serves
 * traffic. The advice evaluates the flag for the context from the {@link FlagContextProvider} and
 * calls either the seam or its fallback on the target object.
 */
final class FlagSeamPostProcessor extends AbstractAdvisingBeanPostProcessor {

    private final ObjectProvider<FlagDeclaration> declarations;
    private final ObjectProvider<Evaluator<Object>> evaluator;
    private final ObjectProvider<FlagContextProvider<?>> contexts;
    private final Map<Method, Seam> seams = new ConcurrentHashMap<>();
    private volatile Map<String, FlagDeclaration> declarationsByKey;

    FlagSeamPostProcessor(ObjectProvider<FlagDeclaration> declarations,
                          ObjectProvider<Evaluator<Object>> evaluator,
                          ObjectProvider<FlagContextProvider<?>> contexts) {
        this.declarations = declarations;
        this.evaluator = evaluator;
        this.contexts = contexts;
        setProxyTargetClass(true);
        this.advisor = new DefaultPointcutAdvisor(
                new AnnotationMatchingPointcut(null, Flagged.class, true), new SeamInterceptor());
    }

    private record Seam(FlagDeclaration declaration, Method fallback) {}

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        var type = AopUtils.getTargetClass(bean);
        ReflectionUtils.doWithMethods(type, method -> seams.computeIfAbsent(method, this::resolve),
                method -> AnnotatedElementUtils.hasAnnotation(method, Flagged.class)
                        && !method.isBridge() && !method.isSynthetic());
        return super.postProcessAfterInitialization(bean, beanName);
    }

    /** Checks one seam method and finds its fallback. Throws {@link FlagSeamException} on any problem. */
    private Seam resolve(Method seam) {
        var flagged = AnnotatedElementUtils.findMergedAnnotation(seam, Flagged.class);
        var where = ClassUtils.getQualifiedMethodName(seam);
        var modifiers = seam.getModifiers();
        if (Modifier.isPrivate(modifiers) || Modifier.isFinal(modifiers) || Modifier.isStatic(modifiers)) {
            throw new FlagSeamException(where + " is @Flagged but is private, final or static. "
                    + "Spring proxies cannot intercept it.");
        }
        if (Modifier.isFinal(seam.getDeclaringClass().getModifiers())) {
            throw new FlagSeamException(where + " is @Flagged but its class is final. "
                    + "Spring proxies cannot subclass it.");
        }
        var declaration = declarationsByKey().get(flagged.key());
        if (declaration == null) {
            throw new FlagSeamException(where + " names flag '" + flagged.key()
                    + "' but no FlagDeclaration bean has that key. Declared keys: "
                    + declarationsByKey().keySet());
        }
        if (contexts.getIfAvailable() == null) {
            throw new FlagSeamException(where + " needs a FlagContextProvider bean, and there is none.");
        }
        var fallback = ReflectionUtils.findMethod(seam.getDeclaringClass(), flagged.fallback(), seam.getParameterTypes());
        if (fallback == null) {
            throw new FlagSeamException(where + " names fallback '" + flagged.fallback()
                    + "' but " + seam.getDeclaringClass().getSimpleName() + " has no method "
                    + flagged.fallback() + signature(seam));
        }
        if (fallback.equals(seam)) {
            throw new FlagSeamException(where + " names itself as its fallback.");
        }
        if (!returnTypeFits(seam.getReturnType(), fallback.getReturnType())) {
            throw new FlagSeamException(where + " returns " + seam.getReturnType().getSimpleName()
                    + " but fallback '" + flagged.fallback() + "' returns " + fallback.getReturnType().getSimpleName());
        }
        ReflectionUtils.makeAccessible(fallback);
        return new Seam(declaration, fallback);
    }

    private static boolean returnTypeFits(Class<?> seamReturn, Class<?> fallbackReturn) {
        if (seamReturn.isPrimitive() || fallbackReturn.isPrimitive()) {
            return seamReturn.equals(fallbackReturn);
        }
        return seamReturn.isAssignableFrom(fallbackReturn);
    }

    private static String signature(Method method) {
        var parameters = new StringBuilder("(");
        var types = method.getParameterTypes();
        for (var i = 0; i < types.length; i++) {
            parameters.append(i == 0 ? "" : ", ").append(types[i].getSimpleName());
        }
        return parameters.append(")").toString();
    }

    private Map<String, FlagDeclaration> declarationsByKey() {
        var current = declarationsByKey;
        if (current == null) {
            var byKey = new HashMap<String, FlagDeclaration>();
            declarations.orderedStream().forEach(declaration -> {
                if (byKey.putIfAbsent(declaration.key(), declaration) != null) {
                    throw new FlagSeamException("Flag '" + declaration.key() + "' is declared more than once");
                }
            });
            declarationsByKey = current = Map.copyOf(byKey);
        }
        return current;
    }

    private final class SeamInterceptor implements MethodInterceptor {
        @Override
        public Object invoke(MethodInvocation invocation) throws Throwable {
            var target = invocation.getThis();
            var method = AopUtils.getMostSpecificMethod(invocation.getMethod(), AopUtils.getTargetClass(target));
            var seam = seams.computeIfAbsent(method, FlagSeamPostProcessor.this::resolve);
            var context = contexts.getObject().current();
            if (evaluator.getObject().evaluate(seam.declaration(), context).enabled()) {
                return invocation.proceed();
            }
            try {
                return seam.fallback().invoke(target, invocation.getArguments());
            } catch (InvocationTargetException e) {
                throw e.getTargetException();
            }
        }
    }
}
