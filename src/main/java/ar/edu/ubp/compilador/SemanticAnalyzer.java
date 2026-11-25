package ar.edu.ubp.compilador;

import ar.edu.ubp.compilador.ast.*;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SemanticAnalyzer extends CPPSubsetParserBaseListener {
    private static final String UNKNOWN = "<unknown>";

    private final Map<String, FunctionInfo> functions = new LinkedHashMap<>();
    private final Deque<Scope> scopes = new ArrayDeque<>();
    private final Deque<FunctionState> activeFunctions = new ArrayDeque<>();
    private final Map<String, String> symbolTable = new LinkedHashMap<>();
    private boolean hasErrors;
    private int scopeCounter;

    public SemanticAnalyzer() {
        scopes.push(new Scope("", null));
    }

    public void analyze(ParseTree tree) {
        if (tree instanceof CPPSubsetParser.ProgramContext program) {
            registerFunctions(program);
        }
        ParseTreeWalker.DEFAULT.walk(this, tree);
    }

    private void registerFunctions(CPPSubsetParser.ProgramContext program) {
        for (CPPSubsetParser.FunctionDeclContext declaration : program.functionDecl()) {
            String name = declaration.ID().getText();
            List<String> parameterTypes = new ArrayList<>();
            if (declaration.paramList() != null) {
                for (CPPSubsetParser.ParamContext parameter : declaration.paramList().param()) {
                    parameterTypes.add(parameter.type().getText());
                }
            }

            if (functions.containsKey(name)) {
                error(declaration, "Función '" + name + "' ya declarada");
            } else {
                functions.put(name, new FunctionInfo(
                    declaration.type().getText(), parameterTypes
                ));
            }
        }
    }

    @Override
    public void enterFunctionDecl(CPPSubsetParser.FunctionDeclContext ctx) {
        String name = ctx.ID().getText();
        Scope global = scopes.peekLast();
        if (global.variables.containsKey(name)) {
            error(ctx, "La función '" + name + "' ya fue declarada como variable");
        }

        FunctionInfo function = functions.get(name);
        String returnType = function == null ? ctx.type().getText() : function.returnType;
        Scope functionScope = new Scope(name, scopes.peek());
        scopes.push(functionScope);
        activeFunctions.push(new FunctionState(ctx, returnType));

        if (ctx.paramList() != null) {
            for (CPPSubsetParser.ParamContext parameter : ctx.paramList().param()) {
                String type = parameter.type().getText();
                if ("void".equals(type)) {
                    error(parameter, "Un parámetro no puede tener tipo void");
                }
                define(parameter.ID().getText(), type, parameter);
            }
        }
    }

    @Override
    public void exitFunctionDecl(CPPSubsetParser.FunctionDeclContext ctx) {
        activeFunctions.pop();
        scopes.pop();
    }

    @Override
    public void enterBlock(CPPSubsetParser.BlockContext ctx) {
        FunctionState function = activeFunctions.peek();
        if (function == null || ctx != function.declaration.block()) {
            scopes.push(new Scope("block" + (++scopeCounter), scopes.peek()));
        }
    }

    @Override
    public void exitBlock(CPPSubsetParser.BlockContext ctx) {
        FunctionState function = activeFunctions.peek();
        if (function == null || ctx != function.declaration.block()) {
            scopes.pop();
        }
    }

    @Override
    public void exitForLoop(CPPSubsetParser.ForLoopContext ctx) {
        checkCondition(ctx.expr(), ctx, "for");
        scopes.pop();
    }

    @Override
    public void enterVariableDecl(CPPSubsetParser.VariableDeclContext ctx) {
        String name = ctx.ID().getText();
        String type = ctx.type().getText();
        if (scopes.size() == 1 && functions.containsKey(name)) {
            error(ctx, "La variable '" + name + "' ya fue declarada como función");
        }
        if ("void".equals(type)) {
            error(ctx, "La variable '" + name + "' no puede tener tipo void");
        }
        define(name, type, ctx);

        if (ctx.expr() != null) {
            String valueType = typeOf(ctx.expr());
            checkAssignment(type, valueType, ctx, "inicialización de '" + name + "'");
        }
    }

    @Override
    public void enterAssignment(CPPSubsetParser.AssignmentContext ctx) {
        String name = ctx.ID().getText();
        String targetType = lookup(name);
        if (targetType == null) {
            error(ctx, "Variable '" + name + "' no declarada");
        }
        String valueType = typeOf(ctx.expr());
        if (targetType != null) {
            checkAssignment(targetType, valueType, ctx, "asignación a '" + name + "'");
        }
    }

    @Override
    public void enterIfStatement(CPPSubsetParser.IfStatementContext ctx) {
        checkCondition(ctx.expr(), ctx, "if");
    }

    @Override
    public void enterWhileLoop(CPPSubsetParser.WhileLoopContext ctx) {
        checkCondition(ctx.expr(), ctx, "while");
    }

    @Override
    public void enterForLoop(CPPSubsetParser.ForLoopContext ctx) {
        scopes.push(new Scope("for" + (++scopeCounter), scopes.peek()));
    }

    @Override
    public void enterReturnStatement(CPPSubsetParser.ReturnStatementContext ctx) {
        FunctionState function = activeFunctions.peek();
        if (function == null) {
            return;
        }

        if (ctx.expr() == null) {
            if (!"void".equals(function.returnType)) {
                error(ctx, "La función que retorna '" + function.returnType
                    + "' debe devolver un valor");
            }
            return;
        }

        String valueType = typeOf(ctx.expr());
        if ("void".equals(function.returnType)) {
            error(ctx, "Una función void no puede devolver un valor");
        } else {
            checkAssignment(function.returnType, valueType, ctx, "valor de retorno");
        }
    }

    private void define(String name, String type, ParserRuleContext context) {
        Scope current = scopes.peek();
        if (current.variables.containsKey(name)) {
            error(context, "Variable '" + name + "' ya declarada en este ámbito");
            return;
        }

        current.variables.put(name, type);
        String qualifiedName = current.qualifiedName.isEmpty()
            ? name
            : current.qualifiedName + "::" + name;
        symbolTable.put(qualifiedName, type);
    }

    private String lookup(String name) {
        for (Scope scope : scopes) {
            String type = scope.variables.get(name);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    private String typeOf(CPPSubsetParser.ExprContext expression) {
        if (expression instanceof CPPSubsetParser.IntLiteralExprContext) {
            return "int";
        }
        if (expression instanceof CPPSubsetParser.DoubleLiteralExprContext) {
            return "double";
        }
        if (expression instanceof CPPSubsetParser.CharLiteralExprContext) {
            return "char";
        }
        if (expression instanceof CPPSubsetParser.IdExprContext id) {
            String name = id.ID().getText();
            String type = lookup(name);
            if (type == null) {
                error(id, "Variable '" + name + "' no declarada");
                return UNKNOWN;
            }
            return type;
        }
        if (expression instanceof CPPSubsetParser.ParenExprContext paren) {
            return typeOf(paren.expr());
        }
        if (expression instanceof CPPSubsetParser.FunctionCallExprContext call) {
            return typeOfCall(call);
        }
        if (expression instanceof CPPSubsetParser.MulDivExprContext binary) {
            return typeOfArithmetic(binary, binary.left, binary.right, binary.op.getText());
        }
        if (expression instanceof CPPSubsetParser.AddSubExprContext binary) {
            return typeOfArithmetic(binary, binary.left, binary.right, binary.op.getText());
        }
        if (expression instanceof CPPSubsetParser.CompExprContext binary) {
            String left = typeOf(binary.left);
            String right = typeOf(binary.right);
            if (!UNKNOWN.equals(left) && !UNKNOWN.equals(right)
                && !(isNumeric(left) && isNumeric(right))
                && !(left.equals("char") && right.equals("char"))) {
                error(binary, "Tipos incompatibles en comparación: " + left + " y " + right);
            }
            return "int";
        }
        if (expression instanceof CPPSubsetParser.AndExprContext binary) {
            return typeOfLogical(binary, binary.left, binary.right, "&&");
        }
        if (expression instanceof CPPSubsetParser.OrExprContext binary) {
            return typeOfLogical(binary, binary.left, binary.right, "||");
        }
        return UNKNOWN;
    }

    private String typeOfCall(CPPSubsetParser.FunctionCallExprContext call) {
        String name = call.ID().getText();
        List<String> argumentTypes = new ArrayList<>();
        for (CPPSubsetParser.ExprContext argument : call.expr()) {
            argumentTypes.add(typeOf(argument));
        }

        FunctionInfo function = functions.get(name);
        if (function == null) {
            error(call, "Función '" + name + "' no declarada");
            return UNKNOWN;
        }
        if (function.parameterTypes.size() != argumentTypes.size()) {
            error(call, "Llamada a '" + name + "' con " + argumentTypes.size()
                + " argumentos; se esperaban " + function.parameterTypes.size());
        } else {
            for (int i = 0; i < argumentTypes.size(); i++) {
                checkAssignment(
                    function.parameterTypes.get(i),
                    argumentTypes.get(i),
                    call,
                    "argumento " + (i + 1) + " de '" + name + "'"
                );
            }
        }
        return function.returnType;
    }

    private String typeOfArithmetic(
        ParserRuleContext context,
        CPPSubsetParser.ExprContext leftExpression,
        CPPSubsetParser.ExprContext rightExpression,
        String operator
    ) {
        String left = typeOf(leftExpression);
        String right = typeOf(rightExpression);
        if (!UNKNOWN.equals(left) && !UNKNOWN.equals(right)
            && (!isNumeric(left) || !isNumeric(right))) {
            error(context, "El operador '" + operator + "' requiere operandos numéricos; recibió "
                + left + " y " + right);
            return UNKNOWN;
        }
        return "double".equals(left) || "double".equals(right) ? "double" : "int";
    }

    private String typeOfLogical(
        ParserRuleContext context,
        CPPSubsetParser.ExprContext leftExpression,
        CPPSubsetParser.ExprContext rightExpression,
        String operator
    ) {
        String left = typeOf(leftExpression);
        String right = typeOf(rightExpression);
        if (!UNKNOWN.equals(left) && !UNKNOWN.equals(right)
            && (!isNumeric(left) || !isNumeric(right))) {
            error(context, "El operador '" + operator + "' requiere operandos numéricos");
        }
        return "int";
    }

    private void checkCondition(
        CPPSubsetParser.ExprContext expression,
        ParserRuleContext context,
        String statement
    ) {
        String type = typeOf(expression);
        if (!UNKNOWN.equals(type) && !isNumeric(type)) {
            error(context, "La condición de '" + statement + "' debe ser numérica; recibió " + type);
        }
    }

    private void checkAssignment(
        String targetType,
        String valueType,
        ParserRuleContext context,
        String description
    ) {
        if (UNKNOWN.equals(valueType) || UNKNOWN.equals(targetType)) {
            return;
        }
        if (!isAssignable(targetType, valueType)) {
            error(context, "Tipo incompatible en " + description + ": no se puede convertir "
                + valueType + " a " + targetType);
        }
    }

    private boolean isAssignable(String target, String value) {
        return target.equals(value)
            || ("double".equals(target) && isNumeric(value))
            || ("int".equals(target) && "char".equals(value));
    }

    private boolean isNumeric(String type) {
        return "int".equals(type) || "double".equals(type) || "char".equals(type);
    }

    private void error(ParserRuleContext context, String message) {
        Logger.error("[Línea " + context.getStart().getLine() + "] " + message);
        hasErrors = true;
    }

    public boolean hasErrors() {
        return hasErrors;
    }

    public Map<String, String> getSymbolTable() {
        return Collections.unmodifiableMap(symbolTable);
    }

    private static final class Scope {
        private final String qualifiedName;
        private final Map<String, String> variables = new HashMap<>();

        private Scope(String name, Scope parent) {
            this.qualifiedName = parent == null || parent.qualifiedName.isEmpty()
                ? name
                : parent.qualifiedName + "::" + name;
        }
    }

    private static final class FunctionInfo {
        private final String returnType;
        private final List<String> parameterTypes;

        private FunctionInfo(String returnType, List<String> parameterTypes) {
            this.returnType = returnType;
            this.parameterTypes = List.copyOf(parameterTypes);
        }
    }

    private static final class FunctionState {
        private final CPPSubsetParser.FunctionDeclContext declaration;
        private final String returnType;

        private FunctionState(
            CPPSubsetParser.FunctionDeclContext declaration,
            String returnType
        ) {
            this.declaration = declaration;
            this.returnType = returnType;
        }
    }
}
