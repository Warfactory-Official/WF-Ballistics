package com.wf.wflib.ui.expr;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * {@link Node} => hidden class implementing {@link CompiledExpr}; one class per non-constant
 * expression, unloadable once unreferenced. Booleans travel as int 0/1 on the operand stack and
 * become 0.0/1.0 only at the boundary.
 */
public final class ExprCompiler implements Opcodes {
    public static final boolean INTERPRET = Boolean.getBoolean("wflib.ui.interpret");

    private static final String NAME = "com/wf/wflib/ui/expr/Compiled";
    private static final String ITF = "com/wf/wflib/ui/expr/CompiledExpr";
    private static final int VALUES = 1;
    private static final int STATE = 2;

    private ExprCompiler() {
    }

    public static CompiledExpr compile(Node node) {
        if (node instanceof Node.Const c) {
            double value = c.value();
            return (values, state) -> value;
        }
        if (node instanceof Node.Slot s) {
            int index = s.index();
            return (values, state) -> values[index];
        }
        if (INTERPRET) {
            return node::eval;
        }
        byte[] bytes = generate(node);
        try {
            MethodHandles.Lookup hidden = MethodHandles.lookup().defineHiddenClass(bytes, true);
            return (CompiledExpr) hidden.findConstructor(hidden.lookupClass(), MethodType.methodType(void.class))
                    .invoke();
        } catch (Throwable t) {
            throw new IllegalStateException("Could not define compiled expression", t);
        }
    }

    static byte[] generate(Node node) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cw.visit(V17, ACC_PUBLIC | ACC_FINAL | ACC_SUPER, NAME, null, "java/lang/Object", new String[]{ITF});

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_FINAL, "eval", "([D[D)D", null, null);
        mv.visitCode();
        if (node.type() == ExprType.BOOL) {
            bool(mv, node);
            mv.visitInsn(I2D);
        } else {
            num(mv, node);
        }
        mv.visitInsn(DRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Leaves a double. */
    private static void num(MethodVisitor mv, Node node) {
        switch (node) {
            case Node.Const c -> mv.visitLdcInsn(c.value());
            case Node.Slot s -> {
                mv.visitVarInsn(ALOAD, VALUES);
                pushInt(mv, s.index());
                mv.visitInsn(DALOAD);
            }
            case Node.Unary u -> {
                num(mv, u.operand());
                mv.visitInsn(DNEG);
            }
            case Node.Binary b -> {
                switch (b.op()) {
                    case ADD, SUB, MUL, DIV -> {
                        num(mv, b.left());
                        num(mv, b.right());
                        mv.visitInsn(switch (b.op()) {
                            case ADD -> DADD;
                            case SUB -> DSUB;
                            case MUL -> DMUL;
                            default -> DDIV;
                        });
                    }
                    case MOD -> {
                        num(mv, b.left());
                        num(mv, b.right());
                        mv.visitMethodInsn(INVOKESTATIC, "com/wf/wflib/ui/expr/ExprRuntime", "mod", "(DD)D", false);
                    }
                    case POW -> {
                        num(mv, b.left());
                        num(mv, b.right());
                        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "pow", "(DD)D", false);
                    }
                    default -> throw new IllegalStateException("not numeric: " + b.op());
                }
            }
            case Node.Ternary t -> {
                Label otherwise = new Label();
                Label end = new Label();
                bool(mv, t.condition());
                mv.visitJumpInsn(IFEQ, otherwise);
                num(mv, t.then());
                mv.visitJumpInsn(GOTO, end);
                mv.visitLabel(otherwise);
                num(mv, t.otherwise());
                mv.visitLabel(end);
            }
            case Node.Call c -> call(mv, c);
        }
    }

    /** Leaves an int 0/1. */
    private static void bool(MethodVisitor mv, Node node) {
        switch (node) {
            case Node.Const c -> pushInt(mv, c.value() != 0 ? 1 : 0);
            case Node.Unary u -> {
                bool(mv, u.operand());
                mv.visitInsn(ICONST_1);
                mv.visitInsn(IXOR);
            }
            case Node.Binary b when b.op() == Node.Op.AND || b.op() == Node.Op.OR -> {
                Label shortCircuit = new Label();
                Label end = new Label();
                bool(mv, b.left());
                mv.visitJumpInsn(b.op() == Node.Op.AND ? IFEQ : IFNE, shortCircuit);
                bool(mv, b.right());
                mv.visitJumpInsn(GOTO, end);
                mv.visitLabel(shortCircuit);
                pushInt(mv, b.op() == Node.Op.AND ? 0 : 1);
                mv.visitLabel(end);
            }
            case Node.Binary b -> {
                boolean boolOperands = b.left().type() == ExprType.BOOL;
                if (boolOperands) {
                    bool(mv, b.left());
                    bool(mv, b.right());
                    mv.visitInsn(IXOR);
                    if (b.op() == Node.Op.EQ) {
                        mv.visitInsn(ICONST_1);
                        mv.visitInsn(IXOR);
                    }
                    return;
                }
                num(mv, b.left());
                num(mv, b.right());
                // DCMPG: NaN => 1, DCMPL: NaN => -1; pick the one that makes every NaN compare false.
                Label yes = new Label();
                Label end = new Label();
                switch (b.op()) {
                    case LT -> {
                        mv.visitInsn(DCMPG);
                        mv.visitJumpInsn(IFLT, yes);
                    }
                    case LE -> {
                        mv.visitInsn(DCMPG);
                        mv.visitJumpInsn(IFLE, yes);
                    }
                    case GT -> {
                        mv.visitInsn(DCMPL);
                        mv.visitJumpInsn(IFGT, yes);
                    }
                    case GE -> {
                        mv.visitInsn(DCMPL);
                        mv.visitJumpInsn(IFGE, yes);
                    }
                    case EQ -> {
                        mv.visitInsn(DCMPL);
                        mv.visitJumpInsn(IFEQ, yes);
                    }
                    case NE -> {
                        mv.visitInsn(DCMPL);
                        mv.visitJumpInsn(IFNE, yes);
                    }
                    default -> throw new IllegalStateException("not boolean: " + b.op());
                }
                mv.visitInsn(ICONST_0);
                mv.visitJumpInsn(GOTO, end);
                mv.visitLabel(yes);
                mv.visitInsn(ICONST_1);
                mv.visitLabel(end);
            }
            case Node.Ternary t -> {
                Label otherwise = new Label();
                Label end = new Label();
                bool(mv, t.condition());
                mv.visitJumpInsn(IFEQ, otherwise);
                bool(mv, t.then());
                mv.visitJumpInsn(GOTO, end);
                mv.visitLabel(otherwise);
                bool(mv, t.otherwise());
                mv.visitLabel(end);
            }
            case Node.Slot s -> {
                numToBool(mv, s);
            }
            case Node.Call c -> {
                numToBool(mv, c);
            }
        }
    }

    private static void numToBool(MethodVisitor mv, Node node) {
        Label zero = new Label();
        Label end = new Label();
        num(mv, node);
        mv.visitInsn(DCONST_0);
        mv.visitInsn(DCMPL);
        mv.visitJumpInsn(IFEQ, zero);
        mv.visitInsn(ICONST_1);
        mv.visitJumpInsn(GOTO, end);
        mv.visitLabel(zero);
        mv.visitInsn(ICONST_0);
        mv.visitLabel(end);
    }

    private static void call(MethodVisitor mv, Node.Call c) {
        Fn fn = c.fn;
        if (fn.kind == Fn.Kind.STATE) {
            mv.visitVarInsn(ALOAD, STATE);
            pushInt(mv, c.cell);
        }
        for (Node arg : c.args) {
            if (arg.type() == ExprType.BOOL) {
                bool(mv, arg);
                mv.visitInsn(I2D);
            } else {
                num(mv, arg);
            }
        }
        if (fn.kind == Fn.Kind.TIME || fn.kind == Fn.Kind.STATE) {
            mv.visitVarInsn(ALOAD, VALUES);
            pushInt(mv, fn.kind == Fn.Kind.TIME ? 0 : 1);
            mv.visitInsn(DALOAD);
        }
        mv.visitMethodInsn(INVOKESTATIC, fn.owner, fn.method, fn.descriptor(), false);
    }

    private static void pushInt(MethodVisitor mv, int value) {
        if (value >= -1 && value <= 5) {
            mv.visitInsn(ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            mv.visitIntInsn(BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            mv.visitIntInsn(SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }
}
