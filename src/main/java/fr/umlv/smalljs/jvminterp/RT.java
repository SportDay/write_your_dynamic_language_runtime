package fr.umlv.smalljs.jvminterp;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodType.methodType;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

public final class RT {
  private static final MethodHandle LOOKUP_OR_DEFAULT, LOOKUP_OR_FAIL, REGISTER, INVOKE, TRUTH, LOOKUP_MH;
  static {
    var lookup = MethodHandles.lookup();
    try {
      LOOKUP_OR_DEFAULT = lookup.findVirtual(JSObject.class, "lookupOrDefault", methodType(Object.class, String.class, Object.class));
      LOOKUP_OR_FAIL = lookup.findStatic(RT.class, "lookupOrFail", methodType(Object.class, JSObject.class, String.class));
      REGISTER = lookup.findVirtual(JSObject.class, "register", methodType(void.class, String.class, Object.class));

      INVOKE = lookup.findVirtual(JSObject.class, "invoke", methodType(Object.class, Object.class, Object[].class));

      TRUTH = lookup.findStatic(RT.class, "truth", methodType(boolean.class, Object.class));

      LOOKUP_MH = lookup.findStatic(RT.class, "lookupMethodHandle", methodType(MethodHandle.class, JSObject.class, String.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  public static Object bsm_undefined(Lookup lookup, String name, Class<?> type) {
    return UNDEFINED;
  }

  public static Object bsm_const(Lookup lookup, String name, Class<?> type, int constant) {
    return constant;
  }

  @SuppressWarnings("unused")  // used by a method handle
  private static Object lookupOrFail(JSObject jsObject, String key) {
    var value = jsObject.lookupOrDefault(key, null);
    if (value == null) {
      throw new Failure(key + " is not defined");
    }
    return value;
  }

  public static CallSite bsm_lookup(Lookup lookup, String name, MethodType type, String variableName) {
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.global();
    // get the LOOKUP_OR_FAIL method handle
    var lookupOrFail = LOOKUP_OR_FAIL;
    // use the global environment as first argument and the variableName as second argument

    //var target = lookupOrFail.bindTo(globalEnv).bindTo(variableName); => same as below
    var target = MethodHandles.insertArguments(lookupOrFail, 0, globalEnv, variableName);
    // create a constant callsite
    return new ConstantCallSite(target);
  }

/*  public static CallSite bsm_funcall(Lookup lookup, String name, MethodType type) {
    // get INVOKE method handle
    var invoke = INVOKE;
    // make it accept an Object (not a JSObject) and objects as other parameters
    var target = invoke.asType(type);
    // create a constant callsite
    return new ConstantCallSite(target);
  }*/

  public static CallSite bsm_funcall(Lookup lookup, String name, MethodType type) {
    return new InliningCache(type, 0, null);
  }

  private static class InliningCache extends MutableCallSite {
    private static final MethodHandle SLOW_PATH, TEST;
    private static final int MAX_DEPTH = 3;

    static {
      var lookup = MethodHandles.lookup();
      try {
        SLOW_PATH = lookup.findVirtual(InliningCache.class, "slowPath", methodType(MethodHandle.class, Object.class, Object.class));
        TEST = lookup.findStatic(InliningCache.class, "test", methodType(boolean.class, Object.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private final int depth;
    private final InliningCache root;

    public InliningCache(MethodType type, int depth, InliningCache root) {
      this.depth = depth;
      super(type);
      var target = foldArguments(MethodHandles.exactInvoker(type), SLOW_PATH.bindTo(this));
      setTarget(target);
      if (root == null) {
        this.root = this;
      } else {
        this.root = root;
      }
    }

    private MethodHandle slowPath(Object qualifier, Object receiver) {
      var jsObject = (JSObject) qualifier;
      var mh = jsObject.methodHandle();

//      IO.println("type: " + type());
//      IO.println("mh: " + mh);
//      IO.println("mh isVarargsCollector: " + mh.isVarargsCollector());

      if (!mh.isVarargsCollector() && type().parameterCount() != mh.type().parameterCount() + 1) {
        throw new Failure("wrong number of arguments for " + (type().parameterCount() - 1) + " expected " + (mh.type().parameterCount() - 2));
      }

      var target = MethodHandles.dropArguments(mh, 0, Object.class);
      target = target.withVarargs(mh.isVarargsCollector());
      target = target.asType(type());

      if (this.depth == MAX_DEPTH) {
        System.err.println("bla ".repeat(MAX_DEPTH));
        root.setTarget(INVOKE.asType(type()));
        return target;
      }

      var test = MethodHandles.insertArguments(TEST, 1, jsObject);
      var fallBack = new InliningCache(type(), depth + 1, root).dynamicInvoker();
      var guard = MethodHandles.guardWithTest(test, target, fallBack);

      setTarget(guard);
      return target;
    }

    private static boolean test(Object qualifier, Object expected) {
      return qualifier == expected;
    }

  }

  public static Object bsm_fun(Lookup lookup, String name, Class<?> type, int funId) {
//    throw new UnsupportedOperationException("TODO bsm_fun");
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    // get the dictionary and get the Fun object corresponding to the id
    var dict = classLoader.dictionary();
    // create the function using ByteCodeRewriter.createFunction(...)
    var fun = dict.lookupAndClear(funId);
    return ByteCodeRewriter.createFunction(fun.name(), fun.parameters(), fun.body(), classLoader.global());
  }

  public static CallSite bsm_register(Lookup lookup, String name, MethodType type, String functionName) {
//    throw new UnsupportedOperationException("TODO bsm_register");
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.global();
    //get the REGISTER method handle
    var register = REGISTER;
    // use the global environment as first argument and the functionName as second argument
    var target = MethodHandles.insertArguments(register, 0, globalEnv, functionName);
    // create a constant callsite
    return new ConstantCallSite(target);
  }

  @SuppressWarnings("unused")  // used by a method handle
  private static boolean truth(Object o) {
    return o != null && o != UNDEFINED && o != Boolean.FALSE;
  }
  public static CallSite bsm_truth(Lookup lookup, String name, MethodType type) {
//    throw new UnsupportedOperationException("TODO bsm_truth");
    // get the TRUTH method handle
    var target = TRUTH;
    // create a constant callsite
    return new ConstantCallSite(target);
  }

/*  public static CallSite bsm_get(Lookup lookup, String name, MethodType type, String fieldName) {
//    throw new UnsupportedOperationException("TODO bsm_get");
    // get the LOOKUP_OR_DEFAULT method handle
    var lookup_or_default = LOOKUP_OR_DEFAULT;
    // use the fieldName and UNDEFINED as second argument and third argument
    var target = MethodHandles.insertArguments(lookup_or_default, 1, fieldName, UNDEFINED);
    // make it accept an Object (not a JSObject) as first parameter
    target = target.asType(type);
    // create a constant callsite
    return new ConstantCallSite(target);
  }*/

  public static CallSite bsm_get(Lookup lookup, String name, MethodType type, String fieldName) {
    //return new ConstantCallSite(insertArguments(LOOKUP, 1, fieldName).asType(type));
    return new InliningFieldCache(type, fieldName);
  }

  private static final class InliningFieldCache extends MutableCallSite {
    private static final MethodHandle SLOW_PATH, LAYOUT_CHECK, FAST_ACCESS;
    static {
      var lookup = MethodHandles.lookup();
      try {
        SLOW_PATH = lookup.findVirtual(InliningFieldCache.class, "slowPath", methodType(Object.class, Object.class));
        FAST_ACCESS = lookup.findVirtual(JSObject.class, "fastAccess", methodType(Object.class, int.class));
        LAYOUT_CHECK = lookup.findStatic(InliningFieldCache.class, "test",
                methodType(boolean.class, JSObject.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private final String fieldName;

    public InliningFieldCache(MethodType type, String fieldName) {
      this.fieldName = fieldName;
      super(type);
      setTarget(SLOW_PATH.bindTo(this));
    }

    @SuppressWarnings("unused")  // called by a MH
    private Object slowPath(Object receiver) {
      var jsObject = (JSObject) receiver;

      var layout = jsObject.layout();
      var slot = jsObject.layoutSlot(fieldName);   // may be -1 !

      MethodHandle target;
      Object value;

      if (slot == -1){
        value = UNDEFINED;
        var constant = MethodHandles.constant(Object.class, UNDEFINED);
        target = MethodHandles.dropArguments(constant, 0, Object.class);
      } else {
        value = jsObject.fastAccess(slot);
        target =  MethodHandles.insertArguments(FAST_ACCESS, 1, slot).asType(type());
      }

      var methodType = MethodType.methodType(boolean.class, Object.class);

      var test = MethodHandles.insertArguments(LAYOUT_CHECK, 1, layout).asType(methodType);
      var fallBack = new InliningFieldCache(type(), jsObject.name()).dynamicInvoker();

      var guard = MethodHandles.guardWithTest(test, target, fallBack);

      setTarget(guard);
      return value;
    }

    private static boolean test(JSObject receiver, Object expected) {
      return receiver.layout() == expected;
    }
  }

  public static CallSite bsm_set(Lookup lookup, String name, MethodType type, String fieldName) {
//    throw new UnsupportedOperationException("TODO bsm_set");
    // get the REGISTER method handle
    var register = REGISTER;
    // use the fieldName as second argument
    var target = MethodHandles.insertArguments(register, 1, fieldName);
    // make it accept an Object (not a JSObject) as first parameter
    target = target.asType(type);
    // create a constant callsite
    return new ConstantCallSite(target);
  }

  @SuppressWarnings("unused")  // used by a method handle
  private static MethodHandle lookupMethodHandle(JSObject receiver, String fieldName) {
    var function = (JSObject) receiver.lookupOrDefault(fieldName, null);
    if (function == null) {
      throw new Failure("no method " + fieldName);
    }
    return function.methodHandle();
  }

  public static CallSite bsm_methodcall(Lookup lookup, String name, MethodType type) {
//    throw new UnsupportedOperationException("TODO bsm_methodcall");
    var lookup_mh = LOOKUP_MH;
    var target = MethodHandles.insertArguments(lookup_mh, 1, name);
    target = target.asType(MethodType.methodType(MethodHandle.class, Object.class));
    var invoker = MethodHandles.invoker(type);
    var newTarget = MethodHandles.foldArguments(invoker, target);
    return new ConstantCallSite(newTarget);
  }

/*  public static CallSite bsm_globalcall(Lookup lookup, String name, MethodType type, String variableName) {
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.global();

    var function = globalEnv.lookupOrDefault(variableName, null);
    var mh = ((JSObject) function).methodHandle();

    return new ConstantCallSite(mh.asType(type));
  }*/

  public static CallSite bsm_globalcall(Lookup lookup, String name, MethodType type, String variableName) {
    var classLoader = (FunClassLoader) lookup.lookupClass().getClassLoader();
    var globalEnv = classLoader.global();
    return new GlobalEnvInliningCache(type, globalEnv, variableName);
  }

  private static final class GlobalEnvInliningCache extends MutableCallSite {
    private static final MethodHandle SLOW_PATH;

    static {
      var lookup = MethodHandles.lookup();
      try {
        SLOW_PATH = lookup.findVirtual(GlobalEnvInliningCache.class, "slowPath", methodType(MethodHandle.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private final JSObject globalEnv;
    private final String indentifierName;

    private final MethodHandle fallBack;

    private GlobalEnvInliningCache(MethodType type, JSObject globalEnv, String indentifierName) {
      this.globalEnv = globalEnv;
      this.indentifierName = indentifierName;
      super(type);
      fallBack = MethodHandles.foldArguments(MethodHandles.exactInvoker(type), SLOW_PATH.bindTo(this));
      setTarget(fallBack);
    }

    @SuppressWarnings("unused")  // called by a MH
    private MethodHandle slowPath() {
      var function = globalEnv.lookupOrDefault(indentifierName, null);
      if (function == null) {
        throw new Failure(indentifierName + " is not found");
      }
      var mh = ((JSObject) function).methodHandle();

      if (!mh.isVarargsCollector() && type().parameterCount() != mh.type().parameterCount()) {
        throw new Failure("wrong number of arguments for " + (type().parameterCount() - 1) + " expected " + (mh.type().parameterCount() - 2));
      }

      var target = mh.asType(type());
      var switchPoint = globalEnv.switchPoint();
      var guard = switchPoint.guardWithTest(target, fallBack);
      setTarget(guard);

      return target;
    }
  }
}
