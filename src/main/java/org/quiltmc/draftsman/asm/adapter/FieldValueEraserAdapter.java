package org.quiltmc.draftsman.asm.adapter;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.quiltmc.draftsman.Draftsman;
import org.quiltmc.draftsman.Util;
import org.quiltmc.draftsman.asm.Insn;
import org.quiltmc.draftsman.asm.visitor.InsnCollectorMethodVisitor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FieldValueEraserAdapter extends ClassVisitor implements Opcodes {
    private final List<FieldData> noValueStaticFields = new ArrayList<>();
    private final List<FieldData> enumFields = new ArrayList<>();
    private final List<FieldData> instanceFields = new ArrayList<>();
    private final List<MethodData> instanceInitializers = new ArrayList<>();
    private final Map<MethodData, List<Insn>> instanceInitializerInvokeSpecials = new HashMap<>();
    private final List<RecordComponent> recordComponents = new ArrayList<>();
    private String className;
    private boolean isRecord;
    private String recordCanonicalConstructorDescriptor = "";

    public FieldValueEraserAdapter(ClassVisitor classVisitor) {
        super(Draftsman.ASM_VERSION, classVisitor);
    }

    @Override
    public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        className = name;
        if ((access & ACC_RECORD) != 0) {
            isRecord = true;
        }

        super.visit(version, access, name, signature, superName, interfaces);
    }

    @Override
    public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
        FieldData fieldData = new FieldData(access, name, descriptor, signature, value);

        if ((access & ACC_STATIC) != 0) {
            if ((access & ACC_ENUM) != 0) {
                // Enum fields neeed to be initialized in <clinit>
                enumFields.add(fieldData);
            } else if (value == null) {
                // Static fields with a value don't need to be initialized in <clinit>
                noValueStaticFields.add(fieldData);
            }
        } else {
            // All instance fields need to be initialized in <init>
            instanceFields.add(fieldData);

            // Fix record component access flags (for a proper decompilation)
            if (isRecord && (access & ACC_PRIVATE) == 0) {
                access |= ACC_PRIVATE;
            }
        }


        return super.visitField(access, name, descriptor, signature, value);
    }

    @Override
    public RecordComponentVisitor visitRecordComponent(String name, String descriptor, String signature) {
        this.recordComponents.add(new RecordComponent(name, descriptor, signature));
        recordCanonicalConstructorDescriptor += descriptor;

        return super.visitRecordComponent(name, descriptor, signature);
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        if (name.equals("<clinit>")) {
            return null; // Remove clinit
        } else if (name.equals("<init>")) {
            MethodData init = new MethodData(access, name, descriptor, signature, exceptions);
            instanceInitializers.add(init);

            // Save invokespecial instructions for later (we need the super/this constructor)
            InsnCollectorMethodVisitor visitor = new InsnCollectorMethodVisitor(null, INVOKESPECIAL);
            instanceInitializerInvokeSpecials.put(init, visitor.getInsns());

            return visitor;
        }

        return super.visitMethod(access, name, descriptor, signature, exceptions);
    }

    @Override
    public void visitEnd() {
        generateClInit();
        generateInits();

        super.visitEnd();
    }

    private void generateClInit() {
        MethodVisitor visitor = super.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        visitor.visitCode();

        // Add enum field initializations
        int enumIndex = 0;
        for (FieldData field : enumFields) {
            /* Code:
             * NAME("value");
             * ====
             * Bytecode:
             * NEW com/example/TestEnum
             * DUP
             * LDC "NAME"
             * ICONST_0
             * LDC "value"
             * INVOKESPECIAL com/example/TestEnum.<init> (Ljava/lang/String;ILjava/lang/String;)V
             * PUTSTATIC com/example/TestEnum.NAME : Lcom/example/TestEnum;
             */
            visitor.visitTypeInsn(NEW, className);
            visitor.visitInsn(DUP);
            visitor.visitLdcInsn(field.name);
            Util.makeSimplestIPush(enumIndex++, visitor);
            MethodData initializer = instanceInitializers.get(0);
            List<String> initializerParams = Util.splitDescriptorParameters(initializer.descriptor);

            for (int i = 2; i < initializerParams.size(); i++) {
                Util.addTypeDefaultToStack(initializerParams.get(i), visitor);
            }

            visitor.visitMethodInsn(INVOKESPECIAL, className, "<init>", initializer.descriptor, false);
            visitor.visitFieldInsn(PUTSTATIC, className, field.name, field.descriptor);
        }

        // Add static field initializations for the ones without an initial value
        for (FieldData field : noValueStaticFields) {
            /* Code:
             * static int staticField1 = 256;
             * ====
             * Bytecode:
             * SIPUSH 256
             * PUTSTATIC com/example/TestClass.staticField1 : I
             */
            addFieldValueToStack(field, visitor);
            visitor.visitFieldInsn(PUTSTATIC, className, field.name, field.descriptor);
        }

        // We can't throw an exception here, so we just return.
        visitor.visitInsn(RETURN);
        visitor.visitMaxs(0, 0);
        visitor.visitEnd();
    }

    private void generateInits() {
        for (MethodData init : instanceInitializers) {
            MethodVisitor visitor = super.visitMethod(init.access, init.name, init.descriptor, init.signature, init.exceptions);
            visitor.visitCode();
            // Handle super/this constructor call
            /*
             * Code:
             * this(arg, arg2, i);
             * ====
             * Bytecode:
             * ALOAD 0
             * ALOAD 1
             * ALOAD 2
             * ILOAD 3
             * INVOKESPECIAL com/example/TestClass.<init> (Ljava/lang/String;Ljava/lang/Object;I)V
             */
            visitor.visitVarInsn(ALOAD, 0);

            Insn superInvokeSpecial = instanceInitializerInvokeSpecials.get(init).get(0); // Always the first one
            List<Object> superInvokeSpecialArgs = superInvokeSpecial.args();
            String descriptor = (String) superInvokeSpecialArgs.get(2);
            List<String> params = Util.splitDescriptorParameters(descriptor);
            for (String param : params) {
                Util.addTypeDefaultToStack(param, visitor);
            }

            visitor.visitMethodInsn(INVOKESPECIAL, (String) superInvokeSpecialArgs.get(0), (String) superInvokeSpecialArgs.get(1), (String) superInvokeSpecialArgs.get(2), (Boolean) superInvokeSpecialArgs.get(3));

            if (isRecord
                    // && isCanonicalConstructor
                    && init.descriptor.substring(1, init.descriptor.indexOf(')')).equals(recordCanonicalConstructorDescriptor)) {
                // Create default record canonical constructor
                /*
                 * Code: (implicit)
                 * this.field1 = field1;
                 * this.field2 = field2;
                 * ====
                 * Bytecode:
                 * ALOAD 0
                 * ALOAD 1
                 * PUTFIELD com/example/TestClass.field1 : Ljava/lang/Object;
                 * ALOAD 0
                 * ILOAD 2
                 * PUTFIELD com/example/TestClass.field2 : I
                 */
                int i = 1;
                for (RecordComponent component : recordComponents) {
                    visitor.visitVarInsn(ALOAD, 0);

                    switch (component.descriptor) {
                        case "B", "C", "I", "S", "Z" -> visitor.visitVarInsn(ILOAD, i);
                        case "D" -> visitor.visitVarInsn(DLOAD, i++);
                        case "F" -> visitor.visitVarInsn(FLOAD, i);
                        case "J" -> visitor.visitVarInsn(LLOAD, i++);
                        default -> visitor.visitVarInsn(ALOAD, i);
                    }
                    i++;

                    visitor.visitFieldInsn(PUTFIELD, className, component.name, component.descriptor);
                }

                visitor.visitInsn(RETURN);
                visitor.visitMaxs(0, 0);
                visitor.visitEnd();
                continue;
            } else if (superInvokeSpecialArgs.get(0) != className) {
                // Add field initializations if the called constructor is a super constructor
                // (otherwise, we already did it in the called method)
                for (FieldData field : instanceFields) {
                    visitor.visitVarInsn(ALOAD, 0);

                    addFieldValueToStack(field, visitor);

                    visitor.visitFieldInsn(PUTFIELD, className, field.name, field.descriptor);
                }
            }

            visitor.visitTypeInsn(NEW, "java/lang/AbstractMethodError");
            visitor.visitInsn(DUP);
            visitor.visitMethodInsn(INVOKESPECIAL, "java/lang/AbstractMethodError", "<init>", "()V", false);
            visitor.visitInsn(ATHROW);
            visitor.visitMaxs(0, 0);
            visitor.visitEnd();
        }
    }

    private static void addFieldValueToStack(FieldData field, MethodVisitor visitor) {
        Object value = field.value;
        if (value == null) {
            Util.addTypeDefaultToStack(field.descriptor, visitor);

            return;
        }

        if (value instanceof Integer val) {
            Util.makeSimplestIPush(val, visitor);
            return;
        }

        visitor.visitLdcInsn(value);
    }

    public record FieldData(int access, String name, String descriptor, String signature, Object value) {
    }

    public record MethodData(int access, String name, String descriptor, String signature, String[] exceptions) {
    }

    public record RecordComponent(String name, String descriptor, String signature) {
    }
}
