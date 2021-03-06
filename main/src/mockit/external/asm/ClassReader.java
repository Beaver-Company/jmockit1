/*
 * ASM: a very small and fast Java bytecode manipulation framework
 * Copyright (c) 2000-2011 INRIA, France Telecom
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holders nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
 * THE POSSIBILITY OF SUCH DAMAGE.
 */
package mockit.external.asm;

import java.io.*;
import javax.annotation.*;

/**
 * A Java class parser to make a {@link ClassVisitor} visit an existing class.
 * This class parses a byte array conforming to the Java class file format and calls the appropriate visit methods of a
 * given class visitor for each field, method and bytecode instruction encountered.
 */
public final class ClassReader extends AnnotatedReader
{
   public interface Flags
   {
      /**
       * Flag to skip method code. If this class is set <code>CODE</code> attribute won't be visited.
       * This can be used, for example, to retrieve annotations for methods and method parameters.
       */
      int SKIP_CODE = 1;

      /**
       * Flag to skip the debug information in the class. If this flag is set the debug information of the class is not
       * visited, i.e. the {@link MethodVisitor#visitLocalVariable} and {@link MethodVisitor#visitLineNumber} methods
       * will not be called.
       */
      int SKIP_DEBUG = 2;

      int SKIP_CODE_DEBUG = SKIP_CODE + SKIP_DEBUG;

      int SKIP_INNER_CLASSES = 4;
   }

   private static final String[] NO_INTERFACES = {};

   /**
    * Start index of the class header information (access, name...) in {@link #code}.
    */
   @Nonnegative final int header;

   ClassVisitor cv;
   @Nonnegative int flags;

   private String name;
   @Nullable private String superClass;
   @Nonnull private String[] interfaces = NO_INTERFACES;
   @Nullable private String signature;
   @Nullable private String sourceFileName;
   @Nullable private EnclosingMethod enclosingMethod;
   @Nonnegative private int innerClassesCodeIndex;
   @Nonnegative private int attributesCodeIndex;

   /**
    * The start index of each bootstrap method.
    */
   @Nullable int[] bootstrapMethods;

   /**
    * Initializes a new class reader with the given bytecode array for a classfile.
    */
   public ClassReader(@Nonnull byte[] code) {
      super(code);
      header = codeIndex; // the class header information starts just after the constant pool
   }

   /**
    * Initializes a new class reader whose classfile bytecode array is read from the given input stream.
    */
   public ClassReader(@Nonnull InputStream is) throws IOException {
      this(readClass(is));
   }

   @Nonnull
   private static byte[] readClass(@Nonnull InputStream is) throws IOException {
      try {
         byte[] b = new byte[is.available()];
         int len = 0;

         while (true) {
            int n = is.read(b, len, b.length - len);

            if (n == -1) {
               if (len < b.length) {
                  byte[] c = new byte[len];
                  System.arraycopy(b, 0, c, 0, len);
                  b = c;
               }

               return b;
            }

            len += n;

            if (len == b.length) {
               int last = is.read();

               if (last < 0) {
                  return b;
               }

               byte[] c = new byte[b.length + 1000];
               System.arraycopy(b, 0, c, 0, len);
               c[len++] = (byte) last;
               b = c;
            }
         }
      }
      finally {
         is.close();
      }
   }

   /**
    * Returns the classfile version of the class being read (see {@link ClassVersion}).
    */
   public int getVersion() {
      codeIndex = 6;
      return readShort();
   }

   /**
    * Returns the class's access flags (see {@link Access}).
    */
   public int getAccess() {
      codeIndex = header;
      return readUnsignedShort();
   }

   /**
    * Returns the internal of name of the super class. For interfaces, the super class is {@link Object}.
    */
   @Nonnull
   public String getSuperName() {
      codeIndex = header + 4;
      return readNonnullClass();
   }

   /**
    * Returns the bytecode array of the Java classfile that was read.
    */
   @Nonnull
   public byte[] getBytecode() { return code; }

   /**
    * Makes the given visitor visit the Java class of this Class Reader, all attributes included.
    */
   public void accept(ClassVisitor cv) {
      accept(cv, 0);
   }

   /**
    * Makes the given visitor visit the Java class of this Class Reader.
    *
    * @param cv    the visitor that must visit this class.
    * @param flags option flags that can be used to modify the default behavior of this class. See {@link Flags}.
    */
   public void accept(@Nonnull ClassVisitor cv, @Nonnegative int flags) {
      this.cv = cv;
      this.flags = flags;
      codeIndex = header;
      readClassDeclaration();
      readInterfaces();
      readClassAttributes();
      visitClassDeclaration();
      visitSourceFileName();
      visitOuterClass();
      readAnnotations(cv);
      readInnerClasses();
      readFieldsAndMethods();
      cv.visitEnd();
   }

   private void readClassDeclaration() {
      access = readUnsignedShort();
      name = readNonnullClass();
      superClass = readClass();
   }

   private void readInterfaces() {
      int interfaceCount = readUnsignedShort();

      if (interfaceCount > 0) {
         interfaces = new String[interfaceCount];

         for (int i = 0; i < interfaceCount; i++) {
            interfaces[i] = readNonnullClass();
         }
      }
   }

   private void readClassAttributes() {
      signature = null;
      sourceFileName = null;
      enclosingMethod = null;
      annotationsCodeIndex = 0;
      innerClassesCodeIndex = 0;
      codeIndex = getAttributesStartIndex();

      for (int attributeCount = readUnsignedShort(); attributeCount > 0; attributeCount--) {
         String attrName = readNonnullUTF8();
         int codeOffsetToNextAttribute = readInt();

         if ("Signature".equals(attrName)) {
            signature = readNonnullUTF8();
            continue;
         }

         if ("SourceFile".equals(attrName)) {
            sourceFileName = readNonnullUTF8();
            continue;
         }

         if ("EnclosingMethod".equals(attrName)) {
            enclosingMethod = new EnclosingMethod(this);
         }
         else if ("RuntimeVisibleAnnotations".equals(attrName)) {
            annotationsCodeIndex = codeIndex;
         }
         else if ("InnerClasses".equals(attrName)) {
            if ((flags & Flags.SKIP_INNER_CLASSES) == 0) {
               innerClassesCodeIndex = codeIndex;
            }
         }
         else if ("BootstrapMethods".equals(attrName)) {
            readBootstrapMethods();
            continue;
         }
         else {
            readAccessAttribute(attrName);
         }

         codeIndex += codeOffsetToNextAttribute;
      }
   }

   private void readBootstrapMethods() {
      int bsmCount = readUnsignedShort();
      bootstrapMethods = new int[bsmCount];

      for (int i = 0; i < bsmCount; i++) {
         bootstrapMethods[i] = codeIndex;
         codeIndex += 2;
         int codeOffset = readUnsignedShort();
         codeIndex += codeOffset << 1;
      }
   }

   private void visitClassDeclaration() {
      int version = readInt(items[1] - 7);
      cv.visit(version, access, name, signature, superClass, interfaces);
   }

   private void visitSourceFileName() {
      if ((flags & Flags.SKIP_DEBUG) == 0 && sourceFileName != null) {
         cv.visitSource(sourceFileName);
      }
   }

   private void visitOuterClass() {
      if (enclosingMethod != null) {
         cv.visitOuterClass(enclosingMethod.owner, enclosingMethod.name, enclosingMethod.desc);
      }
   }

   private void readInnerClasses() {
      int startIndex = innerClassesCodeIndex;

      if (startIndex != 0) {
         codeIndex = startIndex;

         for (int innerClassCount = readUnsignedShort(); innerClassCount > 0; innerClassCount--) {
            String name = readNonnullClass();
            String outerName = readClass();
            String innerName = readUTF8();
            int access = readUnsignedShort();

            cv.visitInnerClass(name, outerName, innerName, access);
         }
      }
   }

   private void readFieldsAndMethods() {
      codeIndex = getCodeIndexAfterInterfaces(interfaces.length);

      FieldReader fieldReader = new FieldReader(this);
      codeIndex = fieldReader.readFields();

      MethodReader methodReader = new MethodReader(this);
      codeIndex = methodReader.readMethods();
   }

   @Nonnegative
   private int getCodeIndexAfterInterfaces(@Nonnegative int interfaceCount) { return header + 8 + 2 * interfaceCount; }

   /**
    * Returns the start index of the attribute_info structure of this class.
    */
   @Nonnegative
   int getAttributesStartIndex() {
      if (attributesCodeIndex > 0) {
         return attributesCodeIndex;
      }

      // Skips the header.
      int interfaceCount = readUnsignedShort(header + 6);
      int codeIndex = getCodeIndexAfterInterfaces(interfaceCount);

      codeIndex = skipClassMembers(codeIndex); // fields
      codeIndex = skipClassMembers(codeIndex); // methods

      // The attribute_info structure starts just after the methods.
      attributesCodeIndex = codeIndex;
      return codeIndex;
   }

   @Nonnegative
   private int skipClassMembers(@Nonnegative int codeIndex) {
      for (int memberCount = readUnsignedShort(codeIndex); memberCount > 0; memberCount--) {
         codeIndex = skipMemberAttributes(codeIndex) + 8;
      }

      return codeIndex + 2;
   }

   @Nonnegative
   private int skipMemberAttributes(@Nonnegative int codeIndex) {
      for (int attributeCount = readUnsignedShort(codeIndex + 8); attributeCount > 0; attributeCount--) {
         codeIndex += 6 + readInt(codeIndex + 12);
      }

      return codeIndex;
   }
}
