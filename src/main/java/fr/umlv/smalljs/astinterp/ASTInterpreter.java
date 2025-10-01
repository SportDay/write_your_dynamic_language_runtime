package fr.umlv.smalljs.astinterp;

import fr.umlv.smalljs.ast.Expr;
import fr.umlv.smalljs.ast.Expr.Block;
import fr.umlv.smalljs.ast.Expr.Call;
import fr.umlv.smalljs.ast.Expr.FieldAccess;
import fr.umlv.smalljs.ast.Expr.FieldAssignment;
import fr.umlv.smalljs.ast.Expr.Fun;
import fr.umlv.smalljs.ast.Expr.Identifier;
import fr.umlv.smalljs.ast.Expr.If;
import fr.umlv.smalljs.ast.Expr.Literal;
import fr.umlv.smalljs.ast.Expr.MethodCall;
import fr.umlv.smalljs.ast.Expr.ObjectLiteral;
import fr.umlv.smalljs.ast.Expr.Return;
import fr.umlv.smalljs.ast.Expr.VarAssignment;
import fr.umlv.smalljs.ast.Script;
import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static fr.umlv.smalljs.rt.JSObject.newEnv;
import static fr.umlv.smalljs.rt.JSObject.newFunction;

public final class ASTInterpreter {
  private static JSObject asJSObject(Object value, int lineNumber) {
    if (!(value instanceof JSObject jsObject)) {
      throw new Failure("at line " + lineNumber + ", type error " + value + " is not a JSObject");
    }
    return jsObject;
  }

  private static Object execute(Expr.Block body, JSObject env) {
    // initialize declared variables to UNDEFINED
    visitVariable(body, env);
    // interpret the AST
    return visit(body, env);
  }

  private static void visitVariable(Expr expression, JSObject env) {
    switch (expression) {
      case Block(List<Expr> exprs, _) -> {
        for (var expr : exprs) {
          visitVariable(expr, env);
        }
      }
      case VarAssignment(String name, _, boolean declaration, _) -> {
        if (declaration) {
          env.register(name, UNDEFINED);
        }
      }
      case If(_, Block trueBlock, Block falseBlock, _) -> {
        visitVariable(trueBlock, env);
        visitVariable(falseBlock, env);
      }
      case Literal _, Call _, Identifier _, Fun _, Return _, ObjectLiteral _, FieldAccess _,
           FieldAssignment _, MethodCall _ -> {
        // do nothing
      }
    };
  }

  static Object visit(Expr expression, JSObject env) {
    return switch (expression) {
      case Block(List<Expr> exprs, int lineNumber) -> {
        // TODO loop over all instructions
        for (var expr : exprs) {
          visit(expr, env);
        }
        yield UNDEFINED;
      }
      case Literal(Object value, int lineNumber) -> value;
      case Call(Expr qualifier, List<Expr> args, int lineNumber) -> {
        var mayBeFunction = visit(qualifier, env);
//        if (!(mayBeFunction instanceof JSObject function)) {
//          throw new Failure("at line " + lineNumber + ", type error " + mayBeFunction + " is not a function");
//        }
        var function = asJSObject(mayBeFunction, lineNumber);
        var arguments = args.stream().map(arg -> visit(arg, env)).toArray();
        yield function.invoke(UNDEFINED,arguments);
      }
      case Identifier(String name, int lineNumber) -> { // +- Function Name
        var value = env.lookupOrDefault(name, null);

        if (value == null) {
          throw new Failure("at line " + lineNumber + ", reference error " + name + " is not defined");
        }

        yield value;
      }
      case VarAssignment(String name, Expr expr, _, int lineNumber) -> {
        var newValue = visit(expr,env);
        var oldValue = env.lookupOrDefault(name, null);
        if (oldValue == null) {
          throw new Failure("at line " + lineNumber + ", reference error " + name + " is not defined");
        }
        env.register(name, newValue);
        yield newValue;
      }
      case Fun(String name, List<String> parameters, boolean toplevel, Block body, int lineNumber) -> {
        var invoker = new JSObject.Invoker() {
          @Override
          public Object invoke(Object receiver, Object... args) {
            // check the arguments length
            if (args.length != parameters.size()) {
              throw new Failure("at line " + lineNumber + ", function " + name + " arguments length mismatch");
            }
            // create a new environment
            var tmpEnv = newEnv(env);
            // add this and all the parameters
            tmpEnv.register("this", receiver);

            for (var i = 0; i < parameters.size(); i++) {
              tmpEnv.register(parameters.get(i), args[i]);
            }
            // execute the body
            try {
              execute(body, tmpEnv);
            } catch (ReturnError error) {
              return error.getValue();
            }
            return UNDEFINED;
          }
        };
        // create the JS function with the invoker
        var function = newFunction(name, invoker);
        // register it into the global env if it's a toplevel
        if (toplevel) {
          env.register(name, function);
        }
         yield function;
      }
      case Return(Expr expr, int lineNumber) -> {
        var value = visit(expr, env);
        throw new ReturnError(value);
      }
      case If(Expr condition, Block trueBlock, Block falseBlock, int lineNumber) -> {
        var value = visit(condition, env);
        if (value instanceof Integer integer && integer == 0) {
          visit(falseBlock, env);
        } else {
          visit(trueBlock, env);
        }
        yield UNDEFINED;
      }
      case ObjectLiteral(Map<String, Expr> initMap, int lineNumber) -> {
        var newObj = JSObject.newObject(null);

        initMap.forEach((key, value1) -> {
          var value = visit(value1, env);
          newObj.register(key, value);
        });

        yield newObj;
      }
      case FieldAccess(Expr receiver, String name, int lineNumber) -> {
        var value = visit(receiver, env);

        var jsonObject = asJSObject(value, lineNumber);

        var finalValue = jsonObject.lookupOrDefault(name, null);

        if (finalValue == null) {
          yield UNDEFINED;
        }

        yield finalValue;
      }
      case FieldAssignment(Expr receiver, String name, Expr expr, int lineNumber) -> {
        var value = visit(receiver, env);

        var jsonObject = asJSObject(value, lineNumber);

        var newValue = visit(expr, env);

        jsonObject.register(name, newValue);

        yield jsonObject;
      }
      case MethodCall(Expr receiver, String name, List<Expr> args, int lineNumber) -> {
        var value = visit(receiver, env);
        var jsonObject = asJSObject(value, lineNumber);

        var finalValue = jsonObject.lookupOrDefault(name, null);

        if (finalValue == null) {
          throw new Failure("at line " + lineNumber + ", type error " + name + " is not a method");
        }

        var valueAsObject = asJSObject(finalValue, lineNumber);

        var arguments = args.stream().map(arg -> visit(arg, env)).toArray();

        yield valueAsObject.invoke(jsonObject,arguments);
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static JSObject createGlobalEnv(PrintStream outStream) {
    var globalEnv = JSObject.newEnv(null);
    globalEnv.register("globalThis", globalEnv);
    globalEnv.register("print", JSObject.newFunction("print", (_, args) -> {
      System.err.println("print called with " + Arrays.toString(args));
      outStream.println(Arrays.stream(args).map(Object::toString).collect(Collectors.joining(" ")));
      return UNDEFINED;
    }));
    globalEnv.register("+", JSObject.newFunction("+", (_, args) -> (Integer) args[0] + (Integer) args[1]));
    globalEnv.register("-", JSObject.newFunction("-", (_, args) -> (Integer) args[0] - (Integer) args[1]));
    globalEnv.register("/", JSObject.newFunction("/", (_, args) -> (Integer) args[0] / (Integer) args[1]));
    globalEnv.register("*", JSObject.newFunction("*", (_, args) -> (Integer) args[0] * (Integer) args[1]));
    globalEnv.register("%", JSObject.newFunction("%", (_, args) -> (Integer) args[0] % (Integer) args[1]));
    globalEnv.register("==", JSObject.newFunction("==", (_, args) -> args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("!=", JSObject.newFunction("!=", (_, args) -> !args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("<", JSObject.newFunction("<", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) < 0) ? 1 : 0));
    globalEnv.register("<=", JSObject.newFunction("<=", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) <= 0) ? 1 : 0));
    globalEnv.register(">", JSObject.newFunction(">", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) > 0) ? 1 : 0));
    globalEnv.register(">=", JSObject.newFunction(">=", (_, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) >= 0) ? 1 : 0));
    return globalEnv;
  }

  public static void interpret(Script script, PrintStream outStream) {
    var globalEnv =createGlobalEnv(outStream);
    var body = script.body();
    execute(body, globalEnv);
  }
}

