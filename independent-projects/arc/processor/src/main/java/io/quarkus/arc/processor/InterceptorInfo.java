package io.quarkus.arc.processor;

import static io.quarkus.arc.processor.IndexClassLookupUtils.getClassByName;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import jakarta.enterprise.inject.spi.DefinitionException;
import jakarta.enterprise.inject.spi.InterceptionType;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.jandex.Type.Kind;
import org.jboss.logging.Logger;

import io.quarkus.arc.impl.Sets;

/**
 *
 * @author Martin Kouba
 */
public class InterceptorInfo extends BeanInfo implements Comparable<InterceptorInfo> {

    private static final Logger LOGGER = Logger.getLogger(InterceptorInfo.class);

    private final Set<AnnotationInstance> bindings;
    private final List<MethodInfo> aroundInvokes;
    private final List<MethodInfo> aroundConstructs;
    private final List<MethodInfo> postConstructs;
    private final List<MethodInfo> preDestroys;

    InterceptorInfo(AnnotationTarget target, BeanDeployment beanDeployment, Set<AnnotationInstance> bindings,
            List<Injection> injections, int priority) {
        super(target, beanDeployment, BuiltinScope.DEPENDENT.getInfo(),
                Sets.singletonHashSet(Type.create(target.asClass().name(), Kind.CLASS)), new HashSet<>(), injections,
                null, null, false, Collections.emptyList(), null, false, null, priority);
        this.bindings = bindings;
        AnnotationStore store = beanDeployment.getAnnotationStore();
        List<MethodInfo> aroundInvokes = new ArrayList<>();
        List<MethodInfo> aroundConstructs = new ArrayList<>();
        List<MethodInfo> postConstructs = new ArrayList<>();
        List<MethodInfo> preDestroys = new ArrayList<>();

        List<MethodInfo> allMethods = new ArrayList<>();
        ClassInfo aClass = target.asClass();
        while (aClass != null) {
            // Only one interceptor method of a given type may be declared on a given class
            int aroundInvokesFound = 0, aroundConstructsFound = 0, postConstructsFound = 0, preDestroysFound = 0;

            for (MethodInfo method : aClass.methods()) {
                if (Modifier.isStatic(method.flags())) {
                    continue;
                }
                if (store.hasAnnotation(method, DotNames.PRODUCES) || store.hasAnnotation(method, DotNames.DISPOSES)) {
                    // according to spec, finding @Produces or @Disposes on a method is a DefinitionException
                    throw new DefinitionException(
                            "An interceptor method cannot be marked @Produces or @Disposes - " + method + " in class: "
                                    + aClass);
                }
                if (store.hasAnnotation(method, DotNames.AROUND_INVOKE)) {
                    addInterceptorMethod(allMethods, aroundInvokes, method);
                    if (++aroundInvokesFound > 1) {
                        throw new DefinitionException(
                                "Multiple @AroundInvoke interceptor methods declared on class: " + aClass);
                    }
                }
                if (store.hasAnnotation(method, DotNames.AROUND_CONSTRUCT)) {
                    addInterceptorMethod(allMethods, aroundConstructs, method);
                    if (++aroundConstructsFound > 1) {
                        throw new DefinitionException(
                                "Multiple @AroundConstruct interceptor methods declared on class: " + aClass);
                    }
                }
                if (store.hasAnnotation(method, DotNames.POST_CONSTRUCT)) {
                    addInterceptorMethod(allMethods, postConstructs, method);
                    if (++postConstructsFound > 1) {
                        throw new DefinitionException(
                                "Multiple @PostConstruct interceptor methods declared on class: " + aClass);
                    }
                }
                if (store.hasAnnotation(method, DotNames.PRE_DESTROY)) {
                    addInterceptorMethod(allMethods, preDestroys, method);
                    if (++preDestroysFound > 1) {
                        throw new DefinitionException(
                                "Multiple @PreDestroy interceptor methods declared on class: " + aClass);
                    }
                }
                allMethods.add(method);
            }

            for (FieldInfo field : aClass.fields()) {
                if (store.hasAnnotation(field, DotNames.PRODUCES)) {
                    // according to spec, finding @Produces on a field is a DefinitionException
                    throw new DefinitionException(
                            "An interceptor field cannot be marked @Produces - " + field + " in class: " + aClass);
                }
            }

            DotName superTypeName = aClass.superName();
            aClass = superTypeName == null || DotNames.OBJECT.equals(superTypeName) ? null
                    : getClassByName(beanDeployment.getBeanArchiveIndex(), superTypeName);
        }

        // The interceptor methods defined by the superclasses are invoked before the interceptor method defined by the interceptor class, most general superclass first.
        Collections.reverse(aroundInvokes);
        Collections.reverse(postConstructs);
        Collections.reverse(preDestroys);
        Collections.reverse(aroundConstructs);

        this.aroundInvokes = List.copyOf(aroundInvokes);
        this.aroundConstructs = List.copyOf(aroundConstructs);
        this.postConstructs = List.copyOf(postConstructs);
        this.preDestroys = List.copyOf(preDestroys);

        if (aroundConstructs.isEmpty() && aroundInvokes.isEmpty() && preDestroys.isEmpty() && postConstructs.isEmpty()) {
            LOGGER.warnf("%s declares no around-invoke method nor a lifecycle callback!", this);
        }
    }

    public Set<AnnotationInstance> getBindings() {
        return bindings;
    }

    /**
     * Returns all methods annotated with {@link jakarta.interceptor.AroundInvoke} found in the hierarchy of the interceptor
     * class.
     * <p>
     * The returned list is sorted. The method declared on the most general superclass is first. The method declared on the
     * interceptor class is last.
     *
     * @return the interceptor methods
     */
    public List<MethodInfo> getAroundInvokes() {
        return aroundInvokes;
    }

    /**
     * Returns all methods annotated with {@link jakarta.interceptor.AroundConstruct} found in the hierarchy of the interceptor
     * class.
     * <p>
     * The returned list is sorted. The method declared on the most general superclass is first. The method declared on the
     * interceptor class is last.
     *
     * @return the interceptor methods
     */
    public List<MethodInfo> getAroundConstructs() {
        return aroundConstructs;
    }

    /**
     * Returns all methods annotated with {@link jakarta.annotation.PostConstruct} found in the hierarchy of the interceptor
     * class.
     * <p>
     * The returned list is sorted. The method declared on the most general superclass is first. The method declared on the
     * interceptor class is last.
     *
     * @return the interceptor methods
     */
    public List<MethodInfo> getPostConstructs() {
        return postConstructs;
    }

    /**
     * Returns all methods annotated with {@link jakarta.annotation.PreDestroy} found in the hierarchy of the interceptor class.
     * <p>
     * The returned list is sorted. The method declared on the most general superclass is first. The method declared on the
     * interceptor class is last.
     *
     * @return the interceptor methods
     */
    public List<MethodInfo> getPreDestroys() {
        return preDestroys;
    }

    /**
     *
     * @deprecated Use {@link #getAroundInvokes()} instead
     */
    @Deprecated(since = "3.1", forRemoval = true)
    public MethodInfo getAroundInvoke() {
        return aroundInvokes.get(aroundInvokes.size() - 1);
    }

    /**
     *
     * @deprecated Use {@link #getAroundConstructs()} instead
     */
    @Deprecated(since = "3.1", forRemoval = true)
    public MethodInfo getAroundConstruct() {
        return aroundConstructs.get(aroundConstructs.size() - 1);
    }

    /**
     *
     * @deprecated Use {@link #getPostConstructs()} instead
     */
    @Deprecated(since = "3.1", forRemoval = true)
    public MethodInfo getPostConstruct() {
        return postConstructs.get(postConstructs.size() - 1);
    }

    /**
     *
     * @deprecated Use {@link #getPreDestroys()} instead
     */
    @Deprecated(since = "3.1", forRemoval = true)
    public MethodInfo getPreDestroy() {
        return preDestroys.get(preDestroys.size() - 1);
    }

    public boolean intercepts(InterceptionType interceptionType) {
        switch (interceptionType) {
            case AROUND_INVOKE:
                return !aroundInvokes.isEmpty();
            case AROUND_CONSTRUCT:
                return !aroundConstructs.isEmpty();
            case POST_CONSTRUCT:
                return !postConstructs.isEmpty();
            case PRE_DESTROY:
                return !preDestroys.isEmpty();
            default:
                return false;
        }
    }

    @Override
    public boolean isInterceptor() {
        return true;
    }

    @Override
    public String toString() {
        return "INTERCEPTOR bean [bindings=" + bindings + ", target=" + getTarget() + "]";
    }

    @Override
    public int compareTo(InterceptorInfo other) {
        return getTarget().toString().compareTo(other.getTarget().toString());
    }

    private void addInterceptorMethod(List<MethodInfo> allMethods, List<MethodInfo> interceptorMethods, MethodInfo method) {
        validateSignature(method);
        if (!isInterceptorMethodOverriden(allMethods, method)) {
            interceptorMethods.add(method);
        }
    }

    private boolean isInterceptorMethodOverriden(Iterable<MethodInfo> allMethods, MethodInfo method) {
        for (MethodInfo m : allMethods) {
            if (m.name().equals(method.name()) && m.parametersCount() == 1
                    && (m.parameterType(0).name().equals(DotNames.INVOCATION_CONTEXT)
                            || m.parameterType(0).name().equals(DotNames.ARC_INVOCATION_CONTEXT))) {
                return true;
            }
        }
        return false;
    }

    private MethodInfo validateSignature(MethodInfo method) {
        List<Type> parameters = method.parameterTypes();
        if (parameters.size() != 1 || !(parameters.get(0).name().equals(DotNames.INVOCATION_CONTEXT)
                || parameters.get(0).name().equals(DotNames.ARC_INVOCATION_CONTEXT))) {
            throw new IllegalStateException(
                    "An interceptor method must accept exactly one parameter of type jakarta.interceptor.InvocationContext: "
                            + method + " declared on " + method.declaringClass());
        }
        if (!method.returnType().kind().equals(Type.Kind.VOID) &&
                !method.returnType().name().equals(DotNames.OBJECT)) {
            throw new IllegalStateException(
                    "The return type of an interceptor method must be java.lang.Object or void: "
                            + method + " declared on " + method.declaringClass());
        }
        return method;
    }

}
