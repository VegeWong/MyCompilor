package com.vegw.compiler.FrontEnd;

import com.vegw.compiler.AST.ASTNode;
import com.vegw.compiler.AST.Expr.CreatorNode;
import com.vegw.compiler.AST.Expr.Literal.IntegerLiteralNode;
import com.vegw.compiler.AST.Expr.Literal.StringLiteralNode;
import com.vegw.compiler.AST.Expr.VariableNode;
import com.vegw.compiler.AST.Stmt.BlockNode;
import com.vegw.compiler.AST.Stmt.Def.ClassDefNode;
import com.vegw.compiler.AST.Stmt.Def.FunctionDefNode;
import com.vegw.compiler.AST.Stmt.Def.VariableDefNode;
import com.vegw.compiler.AST.Stmt.ReturnNode;
import com.vegw.compiler.AST.Stmt.StmtNode;
import com.vegw.compiler.Entity.*;
import com.vegw.compiler.Exception.SemanticException;
import com.vegw.compiler.Type.*;
import com.vegw.compiler.Utils.ErrorHandler;
import com.vegw.compiler.Utils.Location;

import java.util.LinkedList;
import java.util.List;
import java.util.Stack;


public class LocalResolver extends Visitor {
    private final Stack<Scope> stack;
    private final ConstantTable constantTable;
    private final ErrorHandler errorHandler;
    private Scope currentScope;
    private TopLevelScope topScope;
    private boolean isBody;


    public LocalResolver(ErrorHandler h) {
        this.errorHandler = h;
        this.stack = new Stack<Scope>();
        this.constantTable = new ConstantTable();
    }

    private boolean resolveType(Type type) {
        if (type instanceof ArrayType) return resolveType(((ArrayType) type).baseType());
        if (type == Type.INT || type ==  Type.BOOL ||
            type ==  Type.NULL ||  type ==  Type.VOID)
            return true;
        else if (type instanceof ClassType && ((ClassType) type).entity() == null) {
            try {
                Entity entity = topScope.get(((ClassType) type).name());
                if (entity instanceof ClassEntity)
                    ((ClassType) type).setEntity((ClassEntity) entity);
                else
                    return false;
            }
            catch (SemanticException ex) {
                return false;
            }
        }
        else if (type instanceof FunctionType && ((FunctionType) type).entity() == null){
            try {
                Entity entity = currentScope.get(((FunctionType) type).name());
                if (entity instanceof FunctionEntity) {
                    ((FunctionType) type).setEntity((FunctionEntity) entity);
                    return true;
                }
                else
                    return false;
            }
            catch (SemanticException ex) {
                return false;
            }
        }
        return true;
    }

    public void resolve(ASTNode ast) throws SemanticException {
        TopLevelScope toplevel = new TopLevelScope();
        topScope = toplevel;
        stack.add(toplevel);
        currentScope = stack.peek();

        addGlobalBuiltinFunction();
        for (FunctionDefNode node : ast.funcs) {
            String name = node.entity().name();
            if (toplevel.entities.get(name) != null) {
                toplevel.entities.put(name, node.entity());
            }
            else {
                throw new SemanticException("Duplicated Declaration: " + name);
            }
        }
        for (ClassDefNode node : ast.classes) {
            String name = node.entity().name();
            if (toplevel.entities.get(name) != null) {
                toplevel.entities.put(name, node.entity());
            }
            else
                throw new SemanticException("Duplicated Declaration: " + name);
        }
        for (VariableDefNode node : ast.vars) {
            String name = node.entity().name();
            if (toplevel.entities.get(name) != null) {
                toplevel.entities.put(name, node.entity());
            }
            else
                throw new SemanticException("Duplicated Declaration: " + name);
        }

        for (ClassDefNode node : ast.classes) {
            visit(node);
        }
        for (FunctionDefNode node : ast.funcs) {
            visit(node);
        }
    }

    public void pushScope() {
        LocalScope scope = new LocalScope(currentScope);
        stack.add(scope);
        currentScope = stack.peek();
    }

    public LocalScope popScope() {
        LocalScope returnScope = (LocalScope) stack.pop();
        currentScope = stack.peek();
        return returnScope;
    }

    @Override
    public Void visit(VariableNode node) {
        try {
            Entity ent = currentScope.get(node.name());
                if (!resolveType(node.entity().type()))
                    errorHandler.error(node, "Cannot resolve type" + node.entity().type().toString());
                node.setEntity((VariableEntity) ent);
        }
        catch (SemanticException ex) {
            errorHandler.error(node, ex.getMessage());
        }
        return null;
    }

    @Override
    public Void visit(CreatorNode node) {
        if (!resolveType(node.type()))
            errorHandler.error(node, "Cannot resolve type" + node.type().toString());
        super.visit(node);
        return null;
    }

    @Override
    public Void visit(BlockNode node) {
        if (isBody) {
            isBody = false;
            super.visit(node);
            node.setScope(currentScope);
        }
        else {
            pushScope();
            super.visit(node);
            node.setScope(popScope());
        }
        return null;
    }

    @Override
    public Void visit(ClassDefNode node) {
        ClassEntity entity = node.entity();
        pushScope();
        ClassType thisType = new ClassType(entity.name());
        thisType.setEntity(entity);
        ((LocalScope)currentScope).entities().put("this", new VariableEntity(node.location(),"this", thisType));
        ((LocalScope)currentScope).entities().put(entity.constructor().entity().name(),
                entity.constructor().entity());
        visit(entity.constructor());
        for (FunctionDefNode func : entity.funcs()) {
            ((LocalScope)currentScope).entities().put(func.entity().name(), func.entity());
            visit(func);
        }
        for (VariableDefNode var : entity.vars()) {
            visit(var);
        }
        entity.setScope(popScope());
        return null;
    }

    @Override
    public Void visit(FunctionDefNode node) {
        pushScope();
        if (!resolveType(node.entity().returnType()));
            errorHandler.error(node, "Cannot resolve type" + node.entity().returnType().toString());

        for (ParameterEntity entity : node.entity().params()) {
            String name = entity.name();
            Type type = entity.type();
            if (!resolveType(type))
                errorHandler.error(node, "Cannot resolve type" + type.toString());
            Location location = entity.location();
            VariableEntity nentity = new VariableEntity(location, name, type);
            currentScope.entities().put(name, nentity);
        }
        isBody = true;
        visit(node.entity().body());
        node.entity().setScope(popScope());
        return null;
    }

    @Override
    public Void visit(VariableDefNode node) {
        if (!resolveType(node.entity().type()))
            errorHandler.error(node, "Cannot resolve type" + node.entity().type().toString());
        currentScope.entities().put(node.entity().name(),node.entity());
        pushScope();
        addBuiltinFunction(node.location(), node.entity());
        node.entity().setScope(popScope());
        return null;
    }

    public void addBuiltinFunction(Location loc, VariableEntity entity) {
        if (entity.type() instanceof ArrayType) {
            addArrayBuiltinFunction(loc);
        }
        else if (entity.type() instanceof StringType) {
            addStringBuiltinFunction(loc);
        }
    }

    public void addArrayBuiltinFunction(Location loc) {
        // Add "array.size()" function
        List<StmtNode> stmts = new LinkedList<>() {{
           add(new ReturnNode(loc, new IntegerLiteralNode(loc, 0)));
        }};
        BlockNode body = new BlockNode(loc, stmts);
        FunctionEntity arraySize = new FunctionEntity(loc, "size", Type.INT, null, body);
        currentScope.entities().put("size", arraySize);
    }

    public void addStringBuiltinFunction(Location loc) {
        // Add "string.length()" function
        List<StmtNode> lengthStmts = new LinkedList<>() {{
            add(new ReturnNode(loc, new IntegerLiteralNode(loc, 0)));
        }};
        BlockNode lengthBody = new BlockNode(loc, lengthStmts);
        FunctionEntity arraySize = new FunctionEntity(loc, "length", Type.INT, new LinkedList<>(), lengthBody);
        currentScope.entities().put("length", arraySize);

        // Add "string.substring()" function
        List<StmtNode> substringStmts = new LinkedList<>() {{
            add(new ReturnNode(loc, new StringLiteralNode(loc, " ")));
        }};
        BlockNode substringBody = new BlockNode(loc, substringStmts);
        List<ParameterEntity> substringParams = new LinkedList<>() {{
            add(new ParameterEntity(loc, "left",Type.INT));
            add(new ParameterEntity(loc, "right",Type.INT));
        }};
        FunctionEntity substring = new FunctionEntity(loc, "substring", Type.INT, substringParams, substringBody);
        currentScope.entities().put("substring", substring);

        // Add "string.parseInt()" function
        List<StmtNode> parseIntStmts = new LinkedList<>() {{
            add(new ReturnNode(loc, new IntegerLiteralNode(loc, 0)));
        }};
        BlockNode parseIntBody = new BlockNode(loc, parseIntStmts);
        FunctionEntity parseInt = new FunctionEntity(loc, "parseInt", Type.INT, new LinkedList<>(), parseIntBody);
        currentScope.entities().put("parseInt", parseInt);

        // Add "string.ord()" function
        List<StmtNode> ordStmts = new LinkedList<>() {{
            add(new ReturnNode(loc, new IntegerLiteralNode(loc, 0)));
        }};
        BlockNode ordBody = new BlockNode(loc, ordStmts);
        List<ParameterEntity> ordParams = new LinkedList<>() {{
            add(new ParameterEntity(loc, "pos",Type.INT));
        }};
        FunctionEntity ord = new FunctionEntity(loc, "ord", Type.INT, ordParams, ordBody);
        currentScope.entities().put("ord", ord);
    }
    
    public void addGlobalBuiltinFunction() {
        Location loc = new Location(0,0);
        // Add "print()" function
        List<StmtNode> printStmts = new LinkedList<>();
        BlockNode printBody = new BlockNode(loc, printStmts);
        List<ParameterEntity> printParams = new LinkedList<>() {{
            add(new ParameterEntity(loc, "str", Type.STRING));
        }};
        FunctionEntity print = new FunctionEntity(loc, "print", Type.VOID, printParams, printBody);
        currentScope.entities().put("print", print);

        // Add "println()" function
        List<StmtNode> printlnStmts = new LinkedList<>();
        BlockNode printlnBody = new BlockNode(loc, printlnStmts);
        List<ParameterEntity> printlnParams = new LinkedList<>() {{
            add(new ParameterEntity(loc, "str", Type.STRING));
        }};
        FunctionEntity println = new FunctionEntity(loc, "println", Type.VOID, printlnParams, printlnBody);
        currentScope.entities().put("println", println);

        // Add "getString()" function
        List<StmtNode> getStringStmts = new LinkedList<>(){{
            add(new ReturnNode(loc, new StringLiteralNode(loc, " ")));
        }};
        BlockNode getStringBody = new BlockNode(loc, getStringStmts);
        List<ParameterEntity> getStringParams = new LinkedList<>() {{
        }};
        FunctionEntity getString = new FunctionEntity(loc, "getString", Type.STRING, getStringParams, getStringBody);
        currentScope.entities().put("getString", getString);

        // Add "getInt()" function
        List<StmtNode> getIntStmts = new LinkedList<>(){{
            add(new ReturnNode(loc, new IntegerLiteralNode(loc, 0)));
        }};
        BlockNode getIntBody = new BlockNode(loc, getIntStmts);
        List<ParameterEntity> getIntParams = new LinkedList<>() {{
        }};
        FunctionEntity getInt = new FunctionEntity(loc, "getInt", Type.INT, getIntParams, getIntBody);
        currentScope.entities().put("getInt", getInt);

        // Add "toString()" function
        List<StmtNode> toStringStmts = new LinkedList<>(){{
            add(new ReturnNode(loc, new StringLiteralNode(loc, " ")));
        }};
        BlockNode toStringBody = new BlockNode(loc, toStringStmts);
        List<ParameterEntity> toStringParams = new LinkedList<>() {{
        }};
        FunctionEntity toString = new FunctionEntity(loc, "toString", Type.STRING, toStringParams, toStringBody);
        currentScope.entities().put("toString", toString);
    }
}
